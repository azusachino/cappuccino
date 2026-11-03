use super::notice::{pane_name, Kind, Notice};
use crate::agents::AgentRow;
use std::collections::{HashMap, HashSet};
use std::time::{Duration, Instant};

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub(super) struct Key {
    pane_id: String,
    native_ref: String,
}

#[derive(Debug, Clone, Copy)]
pub(super) struct Seen {
    completion_seq: Option<u64>,
    blocked: bool,
    last_seen: Instant,
    number: u32,
}

/// How long an agent missing from polls keeps its baseline. A brief herdr gap
/// must not re-baseline an agent and swallow a completion that happened during it.
pub(super) const ABSENCE_GRACE: Duration = Duration::from_secs(120);

/// Turns successive agent snapshots into notices. Pure apart from the `Instant`
/// it is given; no network.
///
/// The first sighting of an agent (and any new native session reference, pane
/// replacement or sequence regression) sets a baseline and produces nothing. An
/// agent missing from polls for longer than `ABSENCE_GRACE` is forgotten, so it
/// baselines again when it returns.
#[derive(Default)]
pub struct Tracker {
    seen: HashMap<Key, Seen>,
    /// Highest number handed out per agent kind.
    counts: HashMap<String, u32>,
}

impl Tracker {
    #[cfg(test)]
    pub fn observe(&mut self, rows: &[AgentRow]) -> Vec<Notice> {
        self.observe_at(rows, Instant::now())
    }

    pub fn observe_at(&mut self, rows: &[AgentRow], at: Instant) -> Vec<Notice> {
        let mut notices = Vec::new();
        let mut live = HashSet::new();
        for row in rows {
            // Without a native reference there is no identity to dedupe against, so
            // the agent is not tracked rather than guessed at.
            let Some(native_ref) = &row.native_session_ref else {
                continue;
            };
            let key = Key {
                pane_id: row.pane_id.clone(),
                native_ref: native_ref.clone(),
            };
            let blocked = row.status == "blocked";
            live.insert(key.clone());
            let Some(seen) = self.seen.get_mut(&key) else {
                let count = self.counts.entry(row.agent.clone()).or_insert(0);
                *count += 1;
                let number = *count;
                self.seen.insert(
                    key,
                    Seen {
                        completion_seq: row.completion_seq,
                        blocked,
                        last_seen: at,
                        number,
                    },
                );
                continue;
            };
            seen.last_seen = at;
            let current = row.completion_seq;
            let regressed = matches!(
                (seen.completion_seq, current),
                (Some(before), Some(seq)) if seq < before
            );
            if regressed {
                seen.completion_seq = current;
                seen.blocked = blocked;
                continue;
            }
            match (seen.completion_seq, current) {
                (Some(before), Some(seq)) if seq > before => {
                    notices.push(Notice {
                        kind: Kind::Completion,
                        agent: row.agent.clone(),
                        number: seen.number,
                        name: pane_name(row),
                    });
                    seen.completion_seq = current;
                }
                (None, Some(_)) => seen.completion_seq = current,
                _ => {}
            }
            if blocked && !seen.blocked {
                notices.push(Notice {
                    kind: Kind::NeedsAnswer,
                    agent: row.agent.clone(),
                    number: seen.number,
                    name: pane_name(row),
                });
            }
            seen.blocked = blocked;
        }
        self.seen.retain(|key, seen| {
            live.contains(key) || at.duration_since(seen.last_seen) <= ABSENCE_GRACE
        });
        notices
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::telegram::test_support::*;

    #[test]
    fn first_sighting_is_a_baseline_and_repeats_do_not_duplicate() {
        let mut tracker = Tracker::default();
        assert!(tracker
            .observe(&[row("p1", Some("n1"), "idle", Some(4))])
            .is_empty());
        assert!(tracker
            .observe(&[row("p1", Some("n1"), "idle", Some(4))])
            .is_empty());
        let notices = tracker.observe(&[row("p1", Some("n1"), "idle", Some(5))]);
        assert_eq!(
            notices,
            vec![Notice {
                kind: Kind::Completion,
                agent: "pi".into(),
                number: 1,
                name: None,
            }]
        );
        assert!(tracker
            .observe(&[row("p1", Some("n1"), "idle", Some(5))])
            .is_empty());
    }

    #[test]
    fn needs_answer_fires_once_per_entry_into_blocked() {
        let mut tracker = Tracker::default();
        tracker.observe(&[row("p1", Some("n1"), "working", None)]);
        let first = tracker.observe(&[row("p1", Some("n1"), "blocked", None)]);
        assert_eq!(
            first,
            vec![Notice {
                kind: Kind::NeedsAnswer,
                agent: "pi".into(),
                number: 1,
                name: None,
            }]
        );
        assert!(tracker
            .observe(&[row("p1", Some("n1"), "blocked", None)])
            .is_empty());
        assert!(tracker
            .observe(&[row("p1", Some("n1"), "working", None)])
            .is_empty());
        assert_eq!(
            tracker
                .observe(&[row("p1", Some("n1"), "blocked", None)])
                .len(),
            1
        );
    }

    #[test]
    fn replacement_and_sequence_reset_never_inherit_a_completion() {
        let mut tracker = Tracker::default();
        tracker.observe(&[row("p1", Some("old"), "idle", Some(9))]);
        // A new native reference in the same pane is a new agent: baseline only.
        assert!(tracker
            .observe(&[row("p1", Some("new"), "idle", Some(1))])
            .is_empty());
        // A sequence that goes backwards re-baselines without alerting.
        assert!(tracker
            .observe(&[row("p1", Some("new"), "idle", Some(0))])
            .is_empty());
        let notices = tracker.observe(&[row("p1", Some("new"), "idle", Some(1))]);
        assert_eq!(notices.len(), 1);
    }

    #[test]
    fn rows_without_a_native_reference_are_not_tracked() {
        let mut tracker = Tracker::default();
        assert!(tracker
            .observe(&[row("p1", None, "idle", Some(1))])
            .is_empty());
        assert!(tracker
            .observe(&[row("p1", None, "idle", Some(2))])
            .is_empty());
    }

    #[test]
    fn each_new_session_gets_the_next_number() {
        let mut tracker = Tracker::default();
        tracker.observe(&[
            row("p1", Some("n1"), "idle", Some(1)),
            row("p2", Some("n2"), "idle", Some(1)),
        ]);
        let first = tracker.observe(&[row("p1", Some("n1"), "idle", Some(2))]);
        assert_eq!(first[0].number, 1);
        let second = tracker.observe(&[row("p2", Some("n2"), "idle", Some(2))]);
        assert_eq!(second[0].number, 2);
        // A new session in the same pane is a new native reference, so it gets a new number.
        tracker.observe(&[row("p1", Some("n3"), "idle", Some(1))]);
        let third = tracker.observe(&[row("p1", Some("n3"), "idle", Some(2))]);
        assert_eq!(third[0].number, 3);
    }

    #[test]
    fn tracker_fills_the_name_and_numbers_per_kind() {
        let mut tracker = Tracker::default();
        let mut pi = row("p1", Some("n1"), "idle", Some(1));
        pi.session_id = "pi-name".into();
        let mut claude = row("p2", Some("n2"), "idle", Some(1));
        claude.agent = "claude".into();
        tracker.observe(&[pi, claude]);

        let mut pi = row("p1", Some("n1"), "idle", Some(2));
        pi.session_id = "pi-name".into();
        let mut claude = row("p2", Some("n2"), "idle", Some(2));
        claude.agent = "claude".into();
        let notices = tracker.observe(&[pi, claude]);
        assert_eq!(notices.len(), 2);
        let pi_notice = notices.iter().find(|n| n.agent == "pi").unwrap();
        assert_eq!(
            (pi_notice.number, pi_notice.name.as_deref()),
            (1, Some("pi-name"))
        );
        let claude_notice = notices.iter().find(|n| n.agent == "claude").unwrap();
        assert_eq!(
            (claude_notice.number, claude_notice.name.as_deref()),
            (1, None)
        );
    }

    #[test]
    fn rows_without_a_native_reference_do_not_use_a_number() {
        let mut tracker = Tracker::default();
        tracker.observe(&[row("p9", None, "idle", Some(1))]);
        tracker.observe(&[row("p1", Some("n1"), "idle", Some(1))]);
        let notices = tracker.observe(&[row("p1", Some("n1"), "idle", Some(2))]);
        assert_eq!(notices[0].number, 1);
    }

    #[test]
    fn a_sequence_regression_keeps_the_number() {
        let mut tracker = Tracker::default();
        tracker.observe(&[row("p1", Some("n1"), "idle", Some(5))]);
        tracker.observe(&[row("p1", Some("n1"), "idle", Some(3))]);
        let notices = tracker.observe(&[row("p1", Some("n1"), "idle", Some(4))]);
        assert_eq!(notices[0].number, 1);
    }

    #[test]
    fn short_absence_keeps_the_baseline_across_a_missed_poll() {
        let start = Instant::now();
        let at = |secs: u64| start + Duration::from_secs(secs);
        let mut tracker = Tracker::default();
        tracker.observe_at(&[row("p1", Some("n1"), "idle", Some(1))], at(0));
        assert!(tracker.observe_at(&[], at(10)).is_empty(), "missed poll");
        let notices = tracker.observe_at(&[row("p1", Some("n1"), "idle", Some(2))], at(20));
        assert_eq!(
            notices,
            vec![Notice {
                kind: Kind::Completion,
                agent: "pi".into(),
                number: 1,
                name: None,
            }]
        );
    }

    #[test]
    fn absence_longer_than_the_grace_baselines_again() {
        let start = Instant::now();
        let mut tracker = Tracker::default();
        tracker.observe_at(&[row("p1", Some("n1"), "idle", Some(1))], start);
        let gone = start + ABSENCE_GRACE + Duration::from_secs(1);
        tracker.observe_at(&[], gone);
        let back = gone + Duration::from_secs(1);
        assert!(tracker
            .observe_at(&[row("p1", Some("n1"), "idle", Some(2))], back)
            .is_empty());
    }

    #[test]
    fn a_brief_gap_keeps_the_number_and_absence_gives_a_new_one() {
        let start = Instant::now();
        let mut tracker = Tracker::default();
        tracker.observe_at(&[row("p1", Some("n1"), "idle", Some(1))], start);
        tracker.observe_at(&[], start + Duration::from_secs(10));
        let back = start + Duration::from_secs(20);
        let notices = tracker.observe_at(&[row("p1", Some("n1"), "idle", Some(2))], back);
        assert_eq!(notices[0].number, 1, "a brief gap keeps the number");

        let gone = back + ABSENCE_GRACE + Duration::from_secs(1);
        tracker.observe_at(&[], gone);
        let again = gone + Duration::from_secs(1);
        assert!(tracker
            .observe_at(&[row("p1", Some("n1"), "idle", Some(3))], again)
            .is_empty());
        let notices = tracker.observe_at(
            &[row("p1", Some("n1"), "idle", Some(4))],
            again + Duration::from_secs(1),
        );
        assert_eq!(
            notices[0].number, 2,
            "absence past the grace gives a new number"
        );
    }

    #[test]
    fn completion_on_one_agent_does_not_touch_another() {
        let mut tracker = Tracker::default();
        tracker.observe(&[
            row("p1", Some("a"), "idle", Some(1)),
            row("p2", Some("b"), "idle", Some(1)),
        ]);
        let notices = tracker.observe(&[
            row("p1", Some("a"), "idle", Some(2)),
            row("p2", Some("b"), "idle", Some(1)),
        ]);
        assert_eq!(notices.len(), 1);
    }

    #[test]
    fn missing_completion_sequence_never_alerts() {
        let mut tracker = Tracker::default();
        tracker.observe(&[row("p1", Some("n1"), "idle", None)]);
        assert!(tracker
            .observe(&[row("p1", Some("n1"), "working", None)])
            .is_empty());
        assert!(tracker
            .observe(&[row("p1", Some("n1"), "idle", None)])
            .is_empty());
    }
}
