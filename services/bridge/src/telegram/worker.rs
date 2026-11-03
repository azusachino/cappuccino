use super::config::{Event, Settings};
use super::message::{message, route_failure_message};
use super::send::{
    Outcome, Sender, API_BASE, MAX_RATE_LIMIT_TRIES, REQUEST_TIMEOUT, SERVER_RETRY_DELAY,
};
use super::status::{Route, Shared, Status};
use super::token::{read_token, Token};
use super::tracker::Tracker;
use crate::agents::AgentRow;
use serde_json::Value;
use std::collections::VecDeque;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

pub(super) const POLL_INTERVAL: Duration = Duration::from_secs(5);
pub(super) const MAX_PENDING: usize = 50;

pub(super) fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|elapsed| elapsed.as_secs())
        .unwrap_or(0)
}

pub(super) struct Pending {
    text: String,
    attempts: u32,
    not_before: Instant,
}

/// The owner's route at runtime: a tracker, a bounded queue and the sender.
pub struct Telegram {
    settings: Settings,
    token: Option<Token>,
    sender: Sender,
    shared: Shared,
    status: Status,
    tracker: Tracker,
    pending: VecDeque<Pending>,
}

impl Telegram {
    pub fn new(settings: Settings, token: Option<Token>, sender: Sender, shared: Shared) -> Self {
        let mut telegram = Telegram {
            settings,
            token,
            sender,
            shared,
            status: Status::default(),
            tracker: Tracker::default(),
            pending: VecDeque::new(),
        };
        telegram.status.watching_since = Some(unix_now());
        telegram.status.route = if telegram.token.is_some() {
            Route::Enabled
        } else {
            Route::Misconfigured
        };
        telegram.publish();
        telegram
    }

    fn publish(&self) {
        if let Ok(mut shared) = self.shared.lock() {
            *shared = self.status.clone();
        }
    }

    pub fn is_misconfigured(&self) -> bool {
        self.token.is_none()
    }

    /// Queues the notices this snapshot produces, filtered by the owner's events.
    #[cfg(test)]
    pub fn observe(&mut self, rows: &[AgentRow]) {
        self.observe_at(rows, Instant::now());
    }

    fn observe_at(&mut self, rows: &[AgentRow], at: Instant) {
        if self.is_misconfigured() {
            return;
        }
        for notice in self.tracker.observe_at(rows, at) {
            if self.settings.events.contains(&notice.kind.event()) {
                let text = message(
                    &self.settings.machine_label,
                    &notice,
                    self.settings.show_pane_names,
                );
                self.enqueue(text, at);
            }
        }
    }

    /// Applies one poll result. `None` (herdr unreachable) and a snapshot that does
    /// not parse change nothing, so an outage never re-baselines an agent.
    pub fn ingest(&mut self, snapshot: Option<&Value>, machine_id: &str, at: Instant) {
        let Some(result) = snapshot else {
            return;
        };
        if let Ok(rows) = crate::agents::map_agents(result, machine_id) {
            self.observe_at(&rows, at);
        }
    }

    fn enqueue(&mut self, text: String, now: Instant) {
        if self.pending.len() >= MAX_PENDING {
            self.pending.pop_front();
            self.status.dropped_count += 1;
        }
        self.pending.push_back(Pending {
            text,
            attempts: 0,
            not_before: now,
        });
    }

    /// Sends what is due. Stops at a rate limit, a server error or a rejection.
    /// A timeout is counted as uncertain and never sent again.
    pub async fn flush(&mut self, now: Instant) {
        let Some(token) = self.token.clone() else {
            return;
        };
        if self.status.route == Route::Rejected {
            return;
        }
        while let Some(front) = self.pending.front() {
            if front.not_before > now {
                break;
            }
            let outcome = self
                .sender
                .send(&token, &self.settings.chat_id, &front.text)
                .await;
            let stop = self.apply(outcome, now);
            if stop {
                break;
            }
        }
        self.publish();
    }

    /// Applies one outcome to the head of the queue. Returns true to stop flushing.
    fn apply(&mut self, outcome: Outcome, now: Instant) -> bool {
        match outcome {
            Outcome::Delivered => {
                self.pending.pop_front();
                self.status.route = Route::Enabled;
                self.status.last_delivered_at = Some(unix_now());
                self.status.last_http_status = None;
                false
            }
            Outcome::RateLimited { retry_after } => {
                self.status.route = Route::RateLimited;
                let Some(front) = self.pending.front_mut() else {
                    return true;
                };
                front.attempts += 1;
                if front.attempts >= MAX_RATE_LIMIT_TRIES {
                    self.pending.pop_front();
                    self.status.dropped_count += 1;
                } else {
                    front.not_before = now + Duration::from_secs(retry_after);
                }
                true
            }
            Outcome::Failing(code) => {
                let entering = self.status.route != Route::Failing;
                self.status.route = Route::Failing;
                self.status.last_http_status = Some(code);
                let Some(front) = self.pending.front_mut() else {
                    return true;
                };
                if front.attempts == 0 {
                    front.attempts = 1;
                    front.not_before = now + SERVER_RETRY_DELAY;
                } else {
                    self.pending.pop_front();
                    self.status.dropped_count += 1;
                }
                if entering && self.settings.events.contains(&Event::RouteFailure) {
                    let text = route_failure_message(&self.settings.machine_label, code);
                    self.enqueue(text, now);
                }
                true
            }
            Outcome::Rejected(code) => {
                // The owner must fix the configuration and reload; nothing is retried.
                self.pending.pop_front();
                self.status.dropped_count += 1;
                self.status.route = Route::Rejected;
                self.status.last_http_status = Some(code);
                true
            }
            Outcome::Uncertain => {
                self.pending.pop_front();
                self.status.uncertain_count += 1;
                false
            }
        }
    }
}

/// Runs the route for the life of the bridge: poll the agent list, queue notices,
/// send what is due. A herdr error skips the snapshot, so it never re-baselines.
pub async fn watch(machine_id: String, settings: Settings, shared: Shared) {
    let token = read_token(&settings.token_file).ok();
    let sender = match Sender::new(API_BASE, REQUEST_TIMEOUT) {
        Ok(sender) => sender,
        Err(_) => return,
    };
    let mut telegram = Telegram::new(settings, token, sender, shared);
    if telegram.is_misconfigured() {
        return;
    }
    let mut ticker = tokio::time::interval(POLL_INTERVAL);
    loop {
        ticker.tick().await;
        let snapshot = crate::herdr::agent_list().await.ok();
        let now = Instant::now();
        telegram.ingest(snapshot.as_ref(), &machine_id, now);
        telegram.flush(now).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::telegram::test_support::*;
    use serde_json::json;
    use std::path::PathBuf;
    use std::sync::{atomic::Ordering, Arc, Mutex};

    fn telegram_for(base: &str, events: Vec<Event>) -> Telegram {
        let settings = Settings {
            chat_id: "-100".into(),
            token_file: PathBuf::from("/unused"),
            machine_label: "mini".into(),
            events,
            show_pane_names: false,
        };
        Telegram::new(
            settings,
            Some(token()),
            sender(base, Duration::from_secs(5)),
            Arc::new(Mutex::new(Status::default())),
        )
    }

    #[tokio::test]
    async fn delivered_message_marks_the_route_and_is_not_resent() {
        let (base, hits) = serve(200, Duration::ZERO).await;
        let mut telegram = telegram_for(&base, vec![Event::Completion]);
        telegram.enqueue("one".into(), Instant::now());
        telegram.flush(Instant::now()).await;
        assert_eq!(hits.load(Ordering::SeqCst), 1);
        assert!(telegram.status.last_delivered_at.is_some());
        assert_eq!(telegram.status.route, Route::Enabled);
        telegram.flush(Instant::now()).await;
        assert_eq!(hits.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn rejected_route_stops_and_never_sends_again() {
        let (base, hits) = serve(403, Duration::ZERO).await;
        let mut telegram = telegram_for(&base, vec![Event::Completion]);
        telegram.enqueue("one".into(), Instant::now());
        telegram.enqueue("two".into(), Instant::now());
        telegram.flush(Instant::now()).await;
        telegram.flush(Instant::now()).await;
        assert_eq!(hits.load(Ordering::SeqCst), 1);
        assert_eq!(telegram.status.route, Route::Rejected);
        assert_eq!(telegram.status.last_http_status, Some(403));
    }

    #[tokio::test]
    async fn server_error_retries_once_after_the_delay() {
        let (base, hits) = serve(500, Duration::ZERO).await;
        let mut telegram = telegram_for(&base, vec![Event::Completion]);
        let start = Instant::now();
        telegram.enqueue("one".into(), start);
        telegram.flush(start).await;
        assert_eq!(hits.load(Ordering::SeqCst), 1);
        assert_eq!(telegram.status.route, Route::Failing);
        telegram.flush(start).await;
        assert_eq!(
            hits.load(Ordering::SeqCst),
            1,
            "not due before the retry delay"
        );
        telegram.flush(start + SERVER_RETRY_DELAY).await;
        assert_eq!(hits.load(Ordering::SeqCst), 2);
        assert_eq!(telegram.status.dropped_count, 1);
    }

    #[tokio::test]
    async fn route_failure_is_queued_once_when_the_route_starts_failing() {
        let (base, _) = serve(500, Duration::ZERO).await;
        let mut telegram = telegram_for(&base, vec![Event::RouteFailure]);
        telegram.enqueue("one".into(), Instant::now());
        telegram.flush(Instant::now()).await;
        assert_eq!(
            telegram.pending.len(),
            2,
            "original plus one route failure notice"
        );
        telegram.flush(Instant::now()).await;
        assert_eq!(telegram.pending.len(), 2, "still failing: no second notice");
    }

    #[test]
    fn queue_is_bounded_and_counts_drops() {
        let mut telegram = telegram_for("http://127.0.0.1:1", vec![Event::Completion]);
        for index in 0..(MAX_PENDING + 5) {
            telegram.enqueue(format!("m{index}"), Instant::now());
        }
        assert_eq!(telegram.pending.len(), MAX_PENDING);
        assert_eq!(telegram.status.dropped_count, 5);
    }

    #[test]
    fn misconfigured_route_queues_nothing() {
        let settings = Settings {
            chat_id: "-100".into(),
            token_file: PathBuf::from("/does/not/exist"),
            machine_label: "mini".into(),
            events: vec![Event::Completion],
            show_pane_names: false,
        };
        let mut telegram = Telegram::new(
            settings,
            None,
            sender("http://127.0.0.1:1", Duration::from_secs(1)),
            Arc::new(Mutex::new(Status::default())),
        );
        assert_eq!(telegram.status.route, Route::Misconfigured);
        telegram.observe(&[row("p1", Some("n1"), "idle", Some(1))]);
        telegram.observe(&[row("p1", Some("n1"), "idle", Some(2))]);
        assert!(telegram.pending.is_empty());
    }

    #[test]
    fn a_herdr_outage_changes_nothing_so_the_next_completion_still_counts() {
        let mut telegram = telegram_for("http://127.0.0.1:1", vec![Event::Completion]);
        let at = Instant::now();
        telegram.observe_at(&[row("p1", Some("n1"), "idle", Some(1))], at);
        telegram.ingest(None, "m", at);
        telegram.ingest(Some(&json!({})), "m", at);
        telegram.observe_at(&[row("p1", Some("n1"), "idle", Some(2))], at);
        assert_eq!(telegram.pending.len(), 1);
    }
}
