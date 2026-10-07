//! Reconciliation semantics ported from the reference daemon's DaemonCore
//! (services/daemon, slice-A contract): successive pane reads are aligned by
//! longest common subsequence; candidates are lines the LCS leaves unmatched;
//! a candidate becomes a durable entry when it is still present one poll
//! later, or immediately when the agent is idle (quiescence). Volatile footer
//! churn mutates every poll and never stabilizes; ids already emitted are
//! never re-emitted; a pure compaction does not wipe pending candidates; an
//! active-branch change resets the stream (never concatenating an abandoned
//! branch into the visible history).

use serde_json::{json, Value};
use std::collections::HashSet;

/// Maximum window for the quadratic LCS pass; pane reads are capped upstream.
const WINDOW: usize = 512;

/// Checks if a line is transient status or chrome (spinners, status lines, dividers, usage footers)
/// that should not be admitted into historical chat entries.
pub fn is_transient_chrome(line: &str) -> bool {
    let trimmed = line.trim();
    if trimmed.is_empty() {
        return false;
    }

    // 1. Dividers and box drawing borders: ─────, ━━━━━, ╭─, etc.
    let is_divider = trimmed.chars().all(|c| {
        c == '─'
            || c == '━'
            || c == '═'
            || c == '╌'
            || c == '▔'
            || c == '-'
            || c == '='
            || c == '╭'
            || c == '╮'
            || c == '╰'
            || c == '╯'
            || c == '├'
            || c == '┤'
            || c == '┬'
            || c == '┴'
            || c == '┼'
    });
    if is_divider && trimmed.chars().count() >= 3 {
        return true;
    }

    // 2. Working status lines and spinners:
    // e.g. "working · Gemini 3.8 Flash ...", "thinking · ...", "idle · ..."
    if trimmed.starts_with("working ·")
        || trimmed.starts_with("working...")
        || trimmed.starts_with("thinking ·")
        || trimmed.starts_with("thinking...")
        || trimmed.starts_with("idle ·")
    {
        return true;
    }

    // Braille / clock spinners at line start: ⠋, ⠙, ⠹, ⠸, ⠼, ⠴, ⠦, ⠧, ⠇, ⠏, ⢿, etc.
    if let Some(first_char) = trimmed.chars().next() {
        if ('\u{2800}'..='\u{28FF}').contains(&first_char) {
            return true;
        }
    }

    // 3. Status meter / usage / footer lines:
    // e.g. "↑73k ↓5.6k R779k CH99.0% $0.018 (sub)...", "codex 100% ↻ 4h46m..."
    if (trimmed.starts_with('↑') || trimmed.starts_with('↓')) && trimmed.contains('$') {
        return true;
    }
    if trimmed.contains("↻") && (trimmed.contains('%') || trimmed.contains('h')) {
        return true;
    }

    // Single prompt input prefixes from TUI bottom if blank: ">", "❯", etc.
    if trimmed == ">" || trimmed == "❯" || trimmed == "›" {
        return true;
    }

    false
}

#[derive(Debug, Clone)]
pub struct PaneSnapshot {
    pub lines: Vec<String>,
}

/// Indices of `current` matched by the LCS with `previous`, plus the current
/// index of the last match (-1 when none).
pub fn align(previous: &[String], current: &[String]) -> (HashSet<usize>, isize) {
    let take = WINDOW.min(previous.len());
    let prev: Vec<String> = previous[previous.len() - take..].to_vec();
    let take_cur = WINDOW.min(current.len());
    let cur: Vec<String> = current[current.len() - take_cur..].to_vec();
    let offset = current.len() - cur.len();
    let mut matched = HashSet::new();
    let (n, m) = (prev.len(), cur.len());
    if n == 0 || m == 0 {
        return (matched, -1);
    }
    let mut dp = vec![vec![0usize; m + 1]; n + 1];
    for i in (0..n).rev() {
        for j in (0..m).rev() {
            dp[i][j] = if prev[i] == cur[j] {
                dp[i + 1][j + 1] + 1
            } else {
                dp[i + 1][j].max(dp[i][j + 1])
            };
        }
    }
    let (mut i, mut j) = (0usize, 0usize);
    let mut last_match: isize = -1;
    while i < n && j < m {
        if prev[i] == cur[j] {
            matched.insert(j + offset);
            last_match = (j + offset) as isize;
            i += 1;
            j += 1;
        } else if dp[i + 1][j] >= dp[i][j + 1] {
            i += 1;
        } else {
            j += 1;
        }
    }
    (matched, last_match)
}

pub fn entry_id(branch: Option<&str>, text: &str) -> String {
    use sha2::{Digest, Sha256};
    let mut hasher = Sha256::new();
    if let Some(branch) = branch {
        hasher.update(branch.as_bytes());
    }
    hasher.update(b"\n");
    hasher.update(text.as_bytes());
    let digest = hasher.finalize();
    digest[..32]
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

/// Per-session live ring; one ring per open stream, driven by that stream's
/// own poll task (no shared locking needed).
pub struct StreamRing {
    new_entries: Option<Vec<Value>>,
    entries: Vec<Value>,
    seen_ids: HashSet<String>,
    stored_lines: Vec<String>,
    previous_candidate_ids: HashSet<String>,
    next_seq: u64,
    last_branch: Option<String>,
    branch: Option<String>,
    pub generation: u64,
}

impl StreamRing {
    pub fn new(branch: Option<String>) -> Self {
        StreamRing {
            new_entries: None,
            entries: Vec::new(),
            seen_ids: HashSet::new(),
            stored_lines: Vec::new(),
            previous_candidate_ids: HashSet::new(),
            next_seq: 1,
            last_branch: None,
            branch,
            generation: 0,
        }
    }

    /// Updates the tracked branch; a change resets the stream (the caller
    /// learns about it via the generation counter).
    pub fn set_branch(&mut self, branch: Option<String>) {
        self.branch = branch;
    }

    /// Ingests one poll; returns newly confirmed entries in order.
    pub fn ingest(&mut self, snapshot: &PaneSnapshot, confirm_trailing: bool) -> Vec<Value> {
        if let Some(branch) = &self.branch {
            if Some(branch.as_str()) != self.last_branch.as_deref() && self.last_branch.is_some() {
                self.generation += 1;
                self.entries.clear();
                self.seen_ids.clear();
                self.stored_lines.clear();
                self.previous_candidate_ids.clear();
            }
            self.last_branch = Some(branch.clone());
        }

        let (matched, _) = align(&self.stored_lines, &snapshot.lines);
        let mut candidate_ids = HashSet::new();
        for (index, text) in snapshot.lines.iter().enumerate() {
            if matched.contains(&index) {
                continue;
            }
            candidate_ids.insert(entry_id(self.branch.as_deref(), text));
        }

        let current_ids: HashSet<String> = snapshot
            .lines
            .iter()
            .map(|text| entry_id(self.branch.as_deref(), text))
            .collect();
        let mut stable_ids: HashSet<String> = current_ids
            .intersection(&self.previous_candidate_ids)
            .cloned()
            .collect();
        if confirm_trailing {
            stable_ids.extend(candidate_ids.iter().cloned());
        }
        // A pure compaction (everything matched, nothing new) must not wipe
        // pending candidates: lines that arrived just before the compaction
        // still need their confirming poll.
        if !candidate_ids.is_empty() || snapshot.lines.len() >= self.stored_lines.len() {
            self.previous_candidate_ids = candidate_ids;
        }

        let mut new_entries = Vec::new();
        for text in &snapshot.lines {
            if is_transient_chrome(text) {
                continue;
            }
            let id = entry_id(self.branch.as_deref(), text);
            if self.seen_ids.contains(&id) || !stable_ids.contains(&id) {
                continue;
            }
            self.seen_ids.insert(id.clone());
            let entry = json!({
                "seq": self.next_seq,
                "id": id,
                "kind": "output",
                "text": text,
                "branch": self.branch,
                "complete": true,
            });
            self.next_seq += 1;
            new_entries.push(entry);
        }
        self.append_with_gaps(new_entries);
        self.stored_lines = snapshot.lines.clone();
        self.new_entries.take().unwrap_or_default()
    }

    /// Gap placeholders: duplicates are idempotent by seq, gaps stay visible.
    fn append_with_gaps(&mut self, mut incoming: Vec<Value>) {
        let mut out = Vec::new();
        for entry in incoming.drain(..) {
            if let Some(last) = self.entries.last() {
                let last_seq = last["seq"].as_u64().unwrap_or(0);
                let entry_seq = entry["seq"].as_u64().unwrap_or(0);
                if entry_seq > last_seq + 1 {
                    self.entries.push(json!({
                        "seq": null,
                        "id": entry_id(None, "...gap..."),
                        "kind": "gap",
                        "text": format!("gap: missing entries {}-{}", last_seq + 1, entry_seq - 1),
                        "branch": null,
                        "complete": true,
                    }));
                }
            }
            self.entries.push(entry.clone());
            out.push(entry);
        }
        self.new_entries = Some(out);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lines(count: usize) -> Vec<String> {
        (1..=count).map(|n| format!("line{n}")).collect()
    }

    fn churned_footer(body: &[String], stamp: u32) -> Vec<String> {
        let mut out = body.to_vec();
        out.push("────────".to_string());
        out.push("~/work (main)".to_string());
        out.push(format!("↑{stamp} ${stamp} (auto)"));
        out
    }

    #[test]
    fn initial_snapshot_confirmed_on_idle() {
        let mut ring = StreamRing::new(Some("main".into()));
        let entries = ring.ingest(
            &PaneSnapshot { lines: lines(3) },
            true, // idle
        );
        assert_eq!(entries.len(), 3);
        assert_eq!(entries[2]["text"], "line3");
    }

    #[test]
    fn append_detected_exactly_once_under_continuous_footer_churn() {
        let mut ring = StreamRing::new(Some("main".into()));
        ring.ingest(
            &PaneSnapshot {
                lines: churned_footer(&lines(10), 1),
            },
            true,
        );

        // Turn: footer churns, one real line arrives; the first churning poll
        // registers it as pending, the next confirms it.
        let mut body = lines(10);
        body.push("ACK-MARKER".to_string());
        let first = ring.ingest(
            &PaneSnapshot {
                lines: churned_footer(&body, 2),
            },
            false,
        );
        let second = ring.ingest(
            &PaneSnapshot {
                lines: churned_footer(&body, 3),
            },
            false,
        );
        let emitted: Vec<&str> = first
            .iter()
            .chain(second.iter())
            .map(|entry| entry["text"].as_str().unwrap())
            .collect();
        assert!(
            emitted.contains(&"ACK-MARKER"),
            "the append must survive footer churn, got {emitted:?}"
        );

        // Further churn must not re-emit it.
        let third = ring.ingest(
            &PaneSnapshot {
                lines: churned_footer(&body, 4),
            },
            false,
        );
        assert!(!third.iter().any(|entry| entry["text"] == "ACK-MARKER"));
        let total = ring
            .entries
            .iter()
            .filter(|entry| entry["text"] == "ACK-MARKER")
            .count();
        assert_eq!(total, 1, "exactly-once semantics");
    }

    #[test]
    fn trailing_line_held_while_working_confirmed_when_idle() {
        let mut ring = StreamRing::new(Some("main".into()));
        ring.ingest(&PaneSnapshot { lines: lines(3) }, true);
        let grown = ring.ingest(&PaneSnapshot { lines: lines(5) }, false);
        let texts: Vec<&str> = grown
            .iter()
            .map(|entry| entry["text"].as_str().unwrap())
            .collect();
        assert!(
            !texts.contains(&"line5"),
            "trailing line of a working pane is held"
        );
        let idle = ring.ingest(&PaneSnapshot { lines: lines(5) }, true);
        assert!(idle.iter().any(|entry| entry["text"] == "line5"));
    }

    #[test]
    fn compaction_does_not_wipe_pending_candidates() {
        let mut ring = StreamRing::new(Some("main".into()));
        ring.ingest(&PaneSnapshot { lines: lines(20) }, true);
        // Compaction swallows visible history.
        ring.ingest(&PaneSnapshot { lines: lines(8) }, false);
        // New content arrives, then quiescence confirms it.
        let mut grown = lines(10);
        grown.push("fresh-line".to_string());
        ring.ingest(
            &PaneSnapshot {
                lines: grown.clone(),
            },
            false,
        );
        let idle = ring.ingest(&PaneSnapshot { lines: grown }, true);
        assert!(idle.iter().any(|entry| entry["text"] == "fresh-line"));
    }

    #[test]
    fn branch_change_resets_generation() {
        let mut ring = StreamRing::new(Some("main".into()));
        ring.ingest(&PaneSnapshot { lines: lines(4) }, true);
        ring.set_branch(Some("feat/next".into()));
        let entries = ring.ingest(
            &PaneSnapshot {
                lines: ["fresh".to_string()].to_vec(),
            },
            true,
        );
        assert_eq!(ring.generation, 1);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0]["text"], "fresh");
        assert_eq!(ring.entries.len(), 1, "no abandoned-branch concatenation");
    }

    /// The documented gap semantics: a sequence jump inside the entry ring
    /// inserts a visible gap placeholder, never a silent drop.
    #[test]
    fn gap_placeholder_inserted_for_sequence_jump() {
        let mut ring = StreamRing::new(Some("main".into()));
        // Seed the ring's internal entry list directly through the private
        // append path: entries 1 and 2 exist, then an entry with seq 5
        // arrives (as it would if intermediate seqs were skipped elsewhere).
        ring.append_with_gaps(vec![entry_with_seq(1), entry_with_seq(2)]);
        ring.append_with_gaps(vec![entry_with_seq(5)]);
        assert_eq!(ring.entries.len(), 4);
        assert_eq!(ring.entries[2]["kind"], "gap");
        assert!(
            ring.entries[2]["seq"].is_null(),
            "gap placeholder has no seq"
        );
        assert_eq!(ring.entries[2]["text"], "gap: missing entries 3-4");
    }

    fn entry_with_seq(seq: u64) -> Value {
        serde_json::json!({
            "seq": seq,
            "id": format!("id-{seq}"),
            "kind": "output",
            "text": format!("line-{seq}"),
            "branch": "main",
            "complete": true,
        })
    }

    #[test]
    fn alignment_survives_in_place_churn() {
        let previous = vec![
            "head".to_string(),
            "↑10k $1 (auto)".to_string(),
            "mid".to_string(),
            "foot 1".to_string(),
        ];
        let current = vec![
            "head".to_string(),
            "↑11k $2 (auto)".to_string(),
            "mid".to_string(),
            "foot 1".to_string(),
        ];
        let (matched, last_match) = align(&previous, &current);
        assert_eq!(last_match, 3);
        assert!(matched.contains(&0) && matched.contains(&2) && matched.contains(&3));
    }

    #[test]
    fn transient_chrome_is_identified_and_filtered() {
        assert!(is_transient_chrome("────────────────────────"));
        assert!(is_transient_chrome("━━━━━━━━━━━━━━━━━━━━━━━━"));
        assert!(is_transient_chrome(
            "working · Gemini 3.8 Flash · harus-workstation"
        ));
        assert!(is_transient_chrome("thinking · analyzing directory..."));
        assert!(is_transient_chrome("idle · standing by"));
        assert!(is_transient_chrome("⠋ Running cargo test"));
        assert!(is_transient_chrome("⢿ Compiling crate"));
        assert!(is_transient_chrome(
            "↑73k ↓5.6k R779k CH99.0% $0.018 (sub) 20.1%/272k (auto)"
        ));
        assert!(is_transient_chrome("codex 100% ↻ 4h46m 82% ↻ 6d12h"));
        assert!(is_transient_chrome(">"));
        assert!(is_transient_chrome("❯"));

        assert!(!is_transient_chrome("cargo test passed successfully"));
        assert!(!is_transient_chrome("let x = 42;"));
        assert!(!is_transient_chrome("error: failed to compile"));

        let mut ring = StreamRing::new(Some("main".into()));
        let entries = ring.ingest(
            &PaneSnapshot {
                lines: vec![
                    "real line 1".to_string(),
                    "working · Gemini 3.8 Flash".to_string(),
                    "────────────────".to_string(),
                    "real line 2".to_string(),
                ],
            },
            true,
        );
        let texts: Vec<&str> = entries
            .iter()
            .map(|e| e["text"].as_str().unwrap())
            .collect();
        assert_eq!(texts, vec!["real line 1", "real line 2"]);
    }
}
