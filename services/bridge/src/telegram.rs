//! Telegram attention alerts (issue #11). Send-only: the bridge posts plain text
//! to one private group and never reads updates. Design:
//! docs/design/telegram-attention-alerts.md.

use crate::agents::AgentRow;
use crate::config::StrictBool;
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const API_BASE: &str = "https://api.telegram.org";
const POLL_INTERVAL: Duration = Duration::from_secs(5);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_TEXT_CHARS: usize = 200;
const MAX_PENDING: usize = 50;
const MAX_RATE_LIMIT_TRIES: u32 = 3;
const MAX_RETRY_AFTER_SECS: u64 = 60;
const SERVER_RETRY_DELAY: Duration = Duration::from_secs(30);
const MAX_LABEL_CHARS: usize = 40;
const MAX_NAME_CHARS: usize = 40;

/// Events an owner can enable in `[telegram] events`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Event {
    Completion,
    NeedsAnswer,
    RouteFailure,
}

impl Event {
    fn parse(text: &str) -> Result<Event, String> {
        match text {
            "completion" => Ok(Event::Completion),
            "needs_answer" => Ok(Event::NeedsAnswer),
            "route_failure" => Ok(Event::RouteFailure),
            other => Err(format!("telegram.events has unknown event '{other}'")),
        }
    }
}

/// The `[telegram]` table as written. Unknown keys are rejected so a typo never
/// silently disables an alert.
#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct File {
    pub enabled: Option<StrictBool>,
    pub chat_id: Option<String>,
    pub token_file: Option<PathBuf>,
    pub machine_label: Option<String>,
    pub events: Option<Vec<String>>,
}

/// Validated settings for an enabled route. The token itself is read from
/// `token_file` at start and is never part of these settings.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Settings {
    pub chat_id: String,
    pub token_file: PathBuf,
    pub machine_label: String,
    pub events: Vec<Event>,
}

/// `Ok(None)` when the route is disabled. Shape errors fail startup; a missing or
/// unreadable token file does not, it is reported as `misconfigured` instead.
pub fn settings(file: File) -> Result<Option<Settings>, String> {
    if !file.enabled.unwrap_or(StrictBool(false)).0 {
        return Ok(None);
    }
    let chat_id = file.chat_id.unwrap_or_default();
    let digits = chat_id.strip_prefix('-').unwrap_or(&chat_id);
    if digits.is_empty() || !digits.chars().all(|c| c.is_ascii_digit()) {
        return Err("telegram.chat_id must be the numeric id of the group".to_string());
    }
    let token_file = file
        .token_file
        .ok_or_else(|| "telegram.token_file must be set".to_string())?;
    let machine_label = file.machine_label.unwrap_or_default();
    let label = machine_label.trim();
    if label.is_empty()
        || label.chars().count() > MAX_LABEL_CHARS
        || label.chars().any(char::is_control)
    {
        return Err(format!(
            "telegram.machine_label must be 1-{MAX_LABEL_CHARS} printable characters"
        ));
    }
    let mut events = Vec::new();
    for text in file.events.unwrap_or_else(|| {
        vec![
            "completion".to_string(),
            "needs_answer".to_string(),
            "route_failure".to_string(),
        ]
    }) {
        let event = Event::parse(&text)?;
        if !events.contains(&event) {
            events.push(event);
        }
    }
    Ok(Some(Settings {
        chat_id,
        token_file,
        machine_label: label.to_string(),
        events,
    }))
}

/// The bot token. Never printed: its `Debug` is redacted and it has no `Display`.
#[derive(Clone)]
pub struct Token(String);

impl std::fmt::Debug for Token {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Token(<redacted>)")
    }
}

/// Reads the token from its file. A missing, empty or multi-word file is an error;
/// the error carries no detail, so no path or content reaches the status or logs.
pub fn read_token(path: &Path) -> Result<Token, ()> {
    let text = std::fs::read_to_string(path).map_err(|_| ())?;
    let token = text.trim();
    if token.is_empty() || token.contains(char::is_whitespace) {
        return Err(());
    }
    Ok(Token(token.to_string()))
}

/// The route as the owner sees it in status. `Enabled` means configured and
/// working as far as the last send showed.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Route {
    #[default]
    Disabled,
    Misconfigured,
    Enabled,
    RateLimited,
    Failing,
    Rejected,
}

impl Route {
    pub fn name(self) -> &'static str {
        match self {
            Route::Disabled => "disabled",
            Route::Misconfigured => "misconfigured",
            Route::Enabled => "enabled",
            Route::RateLimited => "rate_limited",
            Route::Failing => "failing",
            Route::Rejected => "rejected",
        }
    }
}

/// Status shown on the bridge. Holds counts and bounded codes only.
#[derive(Debug, Clone, Default)]
pub struct Status {
    pub route: Route,
    pub last_delivered_at: Option<u64>,
    pub uncertain_count: u64,
    pub dropped_count: u64,
    pub watching_since: Option<u64>,
    pub last_http_status: Option<u16>,
}

impl Status {
    pub fn to_json(&self) -> Value {
        json!({
            "route": self.route.name(),
            "last_delivered_at": self.last_delivered_at,
            "uncertain_count": self.uncertain_count,
            "dropped_count": self.dropped_count,
            "watching_since": self.watching_since,
            "last_http_status": self.last_http_status,
        })
    }
}

/// Status shared with the HTTP handlers.
pub type Shared = Arc<Mutex<Status>>;

/// The result of one send, as far as the bridge can tell.
#[derive(Debug, PartialEq, Eq)]
pub enum Outcome {
    Delivered,
    RateLimited {
        retry_after: u64,
    },
    Rejected(u16),
    Failing(u16),
    /// Timeout or transport error: the message may or may not have arrived.
    Uncertain,
}

/// Posts to the Bot API. Redirects are refused and no error text is kept, since a
/// reqwest error can name the URL and therefore the token.
pub struct Sender {
    client: reqwest::Client,
    base: String,
}

impl Sender {
    pub fn new(base: &str, timeout: Duration) -> Result<Self, String> {
        // reqwest takes the process default TLS provider; iroh passes its own explicitly, so
        // installing ring here does not change it. An existing default is kept.
        let _ = rustls::crypto::ring::default_provider().install_default();
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(timeout)
            .build()
            .map_err(|_| "telegram client could not start".to_string())?;
        Ok(Sender {
            client,
            base: base.trim_end_matches('/').to_string(),
        })
    }

    pub async fn send(&self, token: &Token, chat_id: &str, text: &str) -> Outcome {
        let url = format!("{}/bot{}/sendMessage", self.base, token.0);
        let body = json!({ "chat_id": chat_id, "text": text });
        let response = match self.client.post(url).json(&body).send().await {
            Ok(response) => response,
            Err(_) => return Outcome::Uncertain,
        };
        let status = response.status().as_u16();
        match status {
            200 => Outcome::Delivered,
            429 => {
                let retry_after = response
                    .json::<Value>()
                    .await
                    .ok()
                    .and_then(|body| body["parameters"]["retry_after"].as_u64())
                    .unwrap_or(1);
                Outcome::RateLimited {
                    retry_after: retry_after.min(MAX_RETRY_AFTER_SECS),
                }
            }
            500..=599 => Outcome::Failing(status),
            _ => Outcome::Rejected(status),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Completion,
    NeedsAnswer,
}

impl Kind {
    fn event(self) -> Event {
        match self {
            Kind::Completion => Event::Completion,
            Kind::NeedsAnswer => Event::NeedsAnswer,
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
pub struct Notice {
    pub kind: Kind,
    pub agent: String,
    /// The owner's herdr pane name, when one is set. Never the pane id.
    pub name: Option<String>,
}

/// The herdr pane name the owner set, or `None`. The bridge uses the pane id as
/// `session_id` when no name is set, so a session id equal to the pane id is not a name.
fn pane_name(row: &AgentRow) -> Option<String> {
    if row.session_id == row.pane_id {
        return None;
    }
    let name: String = row
        .session_id
        .chars()
        .filter(|c| !c.is_control())
        .take(MAX_NAME_CHARS)
        .collect();
    let name = name.trim().to_string();
    (!name.is_empty()).then_some(name)
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct Key {
    pane_id: String,
    native_ref: String,
}

#[derive(Debug, Clone, Copy)]
struct Seen {
    completion_seq: Option<u64>,
    blocked: bool,
    last_seen: Instant,
}

/// How long an agent missing from polls keeps its baseline. A brief herdr gap
/// must not re-baseline an agent and swallow a completion that happened during it.
const ABSENCE_GRACE: Duration = Duration::from_secs(120);

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
                self.seen.insert(
                    key,
                    Seen {
                        completion_seq: row.completion_seq,
                        blocked,
                        last_seen: at,
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

/// The plain text sent for a notice. Never includes a transcript, a path or a
/// native reference, and is capped at 200 characters.
pub fn message(machine: &str, notice: &Notice) -> String {
    let verb = match notice.kind {
        Kind::Completion => "finished a work cycle",
        Kind::NeedsAnswer => "is waiting for an answer",
    };
    let agent = match &notice.name {
        Some(name) => format!("{} ({name})", notice.agent),
        None => notice.agent.clone(),
    };
    cap(format!("[cappuccino] {machine} · {agent} {verb}"))
}

fn route_failure_message(machine: &str, status: u16) -> String {
    cap(format!(
        "[cappuccino] {machine} · telegram route failing (HTTP {status})"
    ))
}

fn cap(text: String) -> String {
    if text.chars().count() <= MAX_TEXT_CHARS {
        return text;
    }
    let mut capped: String = text.chars().take(MAX_TEXT_CHARS - 1).collect();
    capped.push('…');
    capped
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|elapsed| elapsed.as_secs())
        .unwrap_or(0)
}

struct Pending {
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
                let text = message(&self.settings.machine_label, &notice);
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
    use axum::{
        extract::State,
        http::{header, StatusCode},
        response::{IntoResponse, Response},
        routing::any,
        Router,
    };
    use std::sync::atomic::{AtomicUsize, Ordering};

    fn row(pane: &str, native: Option<&str>, status: &str, completion: Option<u64>) -> AgentRow {
        AgentRow {
            machine_id: "m".into(),
            session_id: pane.into(),
            pane_id: pane.into(),
            label: "label".into(),
            agent: "pi".into(),
            status: status.into(),
            working: status == "working",
            cwd: String::new(),
            active_branch: None,
            branch_source: "none".into(),
            state_change_seq: None,
            completion_seq: completion,
            native_session_ref: native.map(str::to_string),
        }
    }

    fn file(text: &str) -> File {
        toml::from_str(text).unwrap()
    }

    #[test]
    fn disabled_route_has_no_settings() {
        assert_eq!(settings(file("enabled = false")).unwrap(), None);
        assert_eq!(settings(File::default()).unwrap(), None);
    }

    #[test]
    fn enabled_route_defaults_to_every_event() {
        let parsed = settings(file(
            "enabled = true\nchat_id = \"-100123\"\ntoken_file = \"/tmp/t\"\nmachine_label = \"mini\"",
        ))
        .unwrap()
        .unwrap();
        assert_eq!(
            parsed.events,
            vec![Event::Completion, Event::NeedsAnswer, Event::RouteFailure]
        );
        assert_eq!(parsed.chat_id, "-100123");
    }

    #[test]
    fn invalid_shape_fails_startup() {
        let base = "enabled = true\ntoken_file = \"/tmp/t\"\nmachine_label = \"mini\"\n";
        for extra in [
            "chat_id = \"@group\"",
            "chat_id = \"12a\"",
            "chat_id = \"-\"",
            "chat_id = \"1\"\nevents = [\"deploy\"]",
        ] {
            assert!(
                settings(file(&format!("{base}{extra}"))).is_err(),
                "{extra}"
            );
        }
        let blank_label =
            "enabled = true\nchat_id = \"1\"\ntoken_file = \"/tmp/t\"\nmachine_label = \"  \"";
        assert!(settings(file(blank_label)).is_err());
        // A typo'd key must fail the parse, not silently fall back to a default.
        assert!(toml::from_str::<File>(&format!("{base}chat_id = \"1\"\nmystery = 1")).is_err());
        assert!(settings(file(&format!("{base}chat_id = \"1\""))).is_ok());
    }

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
    fn pane_name_is_only_an_owner_set_name() {
        let mut named = row("w1:p2", None, "idle", None);
        named.session_id = "telegram".into();
        assert_eq!(pane_name(&named), Some("telegram".into()));
        let unnamed = row("w1:p2", None, "idle", None);
        assert_eq!(pane_name(&unnamed), None, "pane id is not a name");
        named.session_id = "  \u{7}  ".into();
        assert_eq!(pane_name(&named), None, "blank after cleanup");
        named.session_id = "x".repeat(200);
        assert_eq!(pane_name(&named).unwrap().chars().count(), MAX_NAME_CHARS);
    }

    #[test]
    fn messages_are_plain_and_capped() {
        let notice = Notice {
            kind: Kind::Completion,
            agent: "pi".into(),
            name: None,
        };
        assert_eq!(
            message("mini", &notice),
            "[cappuccino] mini · pi finished a work cycle"
        );
        let named = Notice {
            kind: Kind::Completion,
            agent: "claude".into(),
            name: Some("telegram".into()),
        };
        assert_eq!(
            message("MacMini", &named),
            "[cappuccino] MacMini · claude (telegram) finished a work cycle"
        );
        let long = Notice {
            kind: Kind::NeedsAnswer,
            agent: "a".repeat(400),
            name: None,
        };
        let text = message("mini", &long);
        assert_eq!(text.chars().count(), MAX_TEXT_CHARS);
        assert!(text.ends_with('…'));
    }

    #[test]
    fn token_debug_is_redacted() {
        let token = Token("123:secret".into());
        assert_eq!(format!("{token:?}"), "Token(<redacted>)");
    }

    #[derive(Clone)]
    struct Fake {
        status: u16,
        hits: Arc<AtomicUsize>,
        delay: Duration,
    }

    async fn fake(State(fake): State<Fake>) -> Response {
        fake.hits.fetch_add(1, Ordering::SeqCst);
        tokio::time::sleep(fake.delay).await;
        let mut response = (
            StatusCode::from_u16(fake.status).unwrap(),
            r#"{"ok":false,"parameters":{"retry_after":7}}"#,
        )
            .into_response();
        if (300..400).contains(&fake.status) {
            response.headers_mut().insert(
                header::LOCATION,
                "https://example.invalid/".parse().unwrap(),
            );
        }
        response
    }

    async fn serve(status: u16, delay: Duration) -> (String, Arc<AtomicUsize>) {
        let hits = Arc::new(AtomicUsize::new(0));
        let app = Router::new().route("/{*path}", any(fake)).with_state(Fake {
            status,
            hits: hits.clone(),
            delay,
        });
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        (base, hits)
    }

    fn token() -> Token {
        Token("123:secret".into())
    }

    fn sender(base: &str, timeout: Duration) -> Sender {
        Sender::new(base, timeout).unwrap()
    }

    #[tokio::test]
    async fn outcomes_map_from_status_codes() {
        let long = Duration::from_secs(5);
        let (base, _) = serve(200, Duration::ZERO).await;
        assert_eq!(
            sender(&base, long).send(&token(), "-1", "hi").await,
            Outcome::Delivered
        );
        let (base, _) = serve(429, Duration::ZERO).await;
        assert_eq!(
            sender(&base, long).send(&token(), "-1", "hi").await,
            Outcome::RateLimited { retry_after: 7 }
        );
        let (base, _) = serve(400, Duration::ZERO).await;
        assert_eq!(
            sender(&base, long).send(&token(), "-1", "hi").await,
            Outcome::Rejected(400)
        );
        let (base, _) = serve(500, Duration::ZERO).await;
        assert_eq!(
            sender(&base, long).send(&token(), "-1", "hi").await,
            Outcome::Failing(500)
        );
    }

    #[tokio::test]
    async fn redirects_are_refused_and_timeouts_are_uncertain() {
        let (base, _) = serve(302, Duration::ZERO).await;
        assert_eq!(
            sender(&base, Duration::from_secs(5))
                .send(&token(), "-1", "hi")
                .await,
            Outcome::Rejected(302)
        );
        let (base, _) = serve(200, Duration::from_secs(2)).await;
        assert_eq!(
            sender(&base, Duration::from_millis(200))
                .send(&token(), "-1", "hi")
                .await,
            Outcome::Uncertain
        );
    }

    fn telegram_for(base: &str, events: Vec<Event>) -> Telegram {
        let settings = Settings {
            chat_id: "-100".into(),
            token_file: PathBuf::from("/unused"),
            machine_label: "mini".into(),
            events,
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
    fn unreadable_token_file_is_an_error_without_detail() {
        assert!(read_token(Path::new("/does/not/exist")).is_err());
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
