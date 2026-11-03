//! Operational limits and timeouts. Every value has a built-in default and can
//! be overridden in the `[tuning]` table of the bridge config. Unknown keys and
//! zero values fail startup. Wire formats, the pairing-code rotation window and
//! QR rendering are not here: clients depend on them.

use serde::{Deserialize, Serialize};
use std::sync::{LazyLock, OnceLock};

#[derive(Debug, Clone, PartialEq, Deserialize, Serialize)]
#[serde(default, deny_unknown_fields)]
pub struct Tuning {
    /// Largest request body the HTTP listener reads. The phone apps read
    /// responses up to 2 MiB too (`MAX_HTTP_BODY_BYTES`, `maxHTTPBodyBytes`)
    /// and do not learn a changed value, so raise it only together with them.
    pub max_request_body_bytes: usize,
    pub herdr: Herdr,
    pub stream: Stream,
    pub iroh: Iroh,
    pub lifecycle: Lifecycle,
    pub telegram: Telegram,
}

/// Herdr socket client.
#[derive(Debug, Clone, PartialEq, Deserialize, Serialize)]
#[serde(default, deny_unknown_fields)]
pub struct Herdr {
    /// Shared bound for connect, write and read on one request. A safety
    /// ceiling for local IPC, not a latency target.
    pub request_timeout_ms: u64,
    /// Largest response payload, excluding the trailing LF. Oversized responses
    /// fail explicitly.
    pub max_response_line_bytes: usize,
}

/// Phone-facing stream and pane reads.
#[derive(Debug, Clone, PartialEq, Deserialize, Serialize)]
#[serde(default, deny_unknown_fields)]
pub struct Stream {
    /// How often an idle stream pings the phone.
    pub ping_interval_secs: u64,
    /// How often a stream re-reads the pane.
    pub poll_interval_ms: u64,
    /// Pane lines read for each stream poll.
    pub pane_lines: u32,
    /// Pane lines read to find a question form or approval prompt on screen.
    pub screen_lines: u32,
    /// Pane lines read for a session's rate-limit and model hints.
    pub stats_pane_lines: u32,
    /// Pane lines read when a conversation has no canonical transcript.
    pub scrollback_lines: u32,
    /// Pause between keys when answering a multi-step question, because the
    /// form redraws between steps.
    pub answer_key_gap_ms: u64,
}

/// iroh transport and pairing limits.
#[derive(Debug, Clone, PartialEq, Deserialize, Serialize)]
#[serde(default, deny_unknown_fields)]
pub struct Iroh {
    pub max_tunnel_streams: u32,
    pub max_tunnel_connections: usize,
    pub max_tunnel_connections_per_id: usize,
    pub max_pair_connections: usize,
    pub pair_attempts_per_window: usize,
    pub pair_attempt_window_secs: u64,
    pub max_pending_handshakes: usize,
    pub pair_exchange_timeout_secs: u64,
    pub stopped_timeout_secs: u64,
    pub online_wait_secs: u64,
}

/// Bridge process lifecycle commands (`start`, `stop`, `status`).
#[derive(Debug, Clone, PartialEq, Deserialize, Serialize)]
#[serde(default, deny_unknown_fields)]
pub struct Lifecycle {
    pub start_timeout_secs: u64,
    pub stop_timeout_secs: u64,
    pub control_timeout_ms: u64,
    pub poll_interval_ms: u64,
    /// Connect and read bound for the health probe of a running bridge.
    pub probe_timeout_ms: u64,
}

/// Telegram alert delivery. Enabling alerts still needs `[telegram]`.
#[derive(Debug, Clone, PartialEq, Deserialize, Serialize)]
#[serde(default, deny_unknown_fields)]
pub struct Telegram {
    pub request_timeout_secs: u64,
    /// Longest `retry_after` honoured from Telegram's 429 response.
    pub max_retry_after_secs: u64,
    pub max_rate_limit_tries: u32,
    pub server_retry_delay_secs: u64,
    pub poll_interval_secs: u64,
    /// Alerts queued while Telegram is unreachable; the oldest is dropped beyond this.
    pub max_pending: usize,
    /// How long a missing agent counts as still present before it is treated as gone.
    pub absence_grace_secs: u64,
    pub max_text_chars: usize,
    pub max_label_chars: usize,
    pub max_name_chars: usize,
}

impl Default for Tuning {
    fn default() -> Self {
        Tuning {
            max_request_body_bytes: 2 * 1024 * 1024,
            herdr: Herdr::default(),
            stream: Stream::default(),
            iroh: Iroh::default(),
            lifecycle: Lifecycle::default(),
            telegram: Telegram::default(),
        }
    }
}

impl Default for Herdr {
    fn default() -> Self {
        Herdr {
            request_timeout_ms: 10_000,
            max_response_line_bytes: 4 * 1024 * 1024,
        }
    }
}

impl Default for Stream {
    fn default() -> Self {
        Stream {
            ping_interval_secs: 30,
            poll_interval_ms: 400,
            pane_lines: 2000,
            screen_lines: 80,
            stats_pane_lines: 30,
            scrollback_lines: 200,
            answer_key_gap_ms: 250,
        }
    }
}

impl Default for Iroh {
    fn default() -> Self {
        Iroh {
            max_tunnel_streams: 32,
            max_tunnel_connections: 64,
            max_tunnel_connections_per_id: 16,
            max_pair_connections: 4,
            pair_attempts_per_window: 5,
            pair_attempt_window_secs: 60,
            max_pending_handshakes: 32,
            pair_exchange_timeout_secs: 10,
            stopped_timeout_secs: 5,
            online_wait_secs: 10,
        }
    }
}

impl Default for Lifecycle {
    fn default() -> Self {
        Lifecycle {
            start_timeout_secs: 5,
            stop_timeout_secs: 3,
            control_timeout_ms: 500,
            poll_interval_ms: 50,
            probe_timeout_ms: 250,
        }
    }
}

impl Default for Telegram {
    fn default() -> Self {
        Telegram {
            request_timeout_secs: 10,
            max_retry_after_secs: 60,
            max_rate_limit_tries: 3,
            server_retry_delay_secs: 30,
            poll_interval_secs: 5,
            max_pending: 50,
            absence_grace_secs: 120,
            max_text_chars: 200,
            max_label_chars: 40,
            max_name_chars: 40,
        }
    }
}

impl Tuning {
    /// Rejects zero values: a zero timeout fails every call, a zero interval
    /// panics the timer, and a zero capacity blocks forever.
    pub fn validate(&self) -> Result<(), String> {
        let fields = [
            ("max_request_body_bytes", self.max_request_body_bytes as u64),
            ("herdr.request_timeout_ms", self.herdr.request_timeout_ms),
            (
                "herdr.max_response_line_bytes",
                self.herdr.max_response_line_bytes as u64,
            ),
            ("stream.ping_interval_secs", self.stream.ping_interval_secs),
            ("stream.poll_interval_ms", self.stream.poll_interval_ms),
            ("stream.pane_lines", self.stream.pane_lines as u64),
            ("stream.screen_lines", self.stream.screen_lines as u64),
            (
                "stream.stats_pane_lines",
                self.stream.stats_pane_lines as u64,
            ),
            (
                "stream.scrollback_lines",
                self.stream.scrollback_lines as u64,
            ),
            ("stream.answer_key_gap_ms", self.stream.answer_key_gap_ms),
            (
                "iroh.max_tunnel_streams",
                self.iroh.max_tunnel_streams as u64,
            ),
            (
                "iroh.max_tunnel_connections",
                self.iroh.max_tunnel_connections as u64,
            ),
            (
                "iroh.max_tunnel_connections_per_id",
                self.iroh.max_tunnel_connections_per_id as u64,
            ),
            (
                "iroh.max_pair_connections",
                self.iroh.max_pair_connections as u64,
            ),
            (
                "iroh.pair_attempts_per_window",
                self.iroh.pair_attempts_per_window as u64,
            ),
            (
                "iroh.pair_attempt_window_secs",
                self.iroh.pair_attempt_window_secs,
            ),
            (
                "iroh.max_pending_handshakes",
                self.iroh.max_pending_handshakes as u64,
            ),
            (
                "iroh.pair_exchange_timeout_secs",
                self.iroh.pair_exchange_timeout_secs,
            ),
            ("iroh.stopped_timeout_secs", self.iroh.stopped_timeout_secs),
            ("iroh.online_wait_secs", self.iroh.online_wait_secs),
            (
                "lifecycle.start_timeout_secs",
                self.lifecycle.start_timeout_secs,
            ),
            (
                "lifecycle.stop_timeout_secs",
                self.lifecycle.stop_timeout_secs,
            ),
            (
                "lifecycle.control_timeout_ms",
                self.lifecycle.control_timeout_ms,
            ),
            (
                "lifecycle.poll_interval_ms",
                self.lifecycle.poll_interval_ms,
            ),
            (
                "lifecycle.probe_timeout_ms",
                self.lifecycle.probe_timeout_ms,
            ),
            (
                "telegram.request_timeout_secs",
                self.telegram.request_timeout_secs,
            ),
            (
                "telegram.max_retry_after_secs",
                self.telegram.max_retry_after_secs,
            ),
            (
                "telegram.max_rate_limit_tries",
                self.telegram.max_rate_limit_tries as u64,
            ),
            (
                "telegram.server_retry_delay_secs",
                self.telegram.server_retry_delay_secs,
            ),
            (
                "telegram.poll_interval_secs",
                self.telegram.poll_interval_secs,
            ),
            ("telegram.max_pending", self.telegram.max_pending as u64),
            (
                "telegram.absence_grace_secs",
                self.telegram.absence_grace_secs,
            ),
            (
                "telegram.max_text_chars",
                self.telegram.max_text_chars as u64,
            ),
            (
                "telegram.max_label_chars",
                self.telegram.max_label_chars as u64,
            ),
            (
                "telegram.max_name_chars",
                self.telegram.max_name_chars as u64,
            ),
        ];
        match fields.iter().find(|(_, value)| *value == 0) {
            Some((name, _)) => Err(format!("tuning.{name} must be greater than 0")),
            None => Ok(()),
        }
    }
}

static INSTALLED: OnceLock<Tuning> = OnceLock::new();
static DEFAULTS: LazyLock<Tuning> = LazyLock::new(Tuning::default);

/// The tuning in effect: the installed config, or the defaults before one is
/// installed (tests and CLI paths that never start the server).
pub fn active() -> &'static Tuning {
    INSTALLED.get().unwrap_or(&DEFAULTS)
}

/// Installs the loaded config once, before the server starts.
pub fn install(tuning: Tuning) -> Result<(), String> {
    INSTALLED
        .set(tuning)
        .map_err(|_| "tuning was already installed".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    /// Every key as (section, key). The top-level key has an empty section.
    const KEYS: &[(&str, &str)] = &[
        ("", "max_request_body_bytes"),
        ("herdr", "request_timeout_ms"),
        ("herdr", "max_response_line_bytes"),
        ("stream", "ping_interval_secs"),
        ("stream", "poll_interval_ms"),
        ("stream", "pane_lines"),
        ("stream", "screen_lines"),
        ("stream", "stats_pane_lines"),
        ("stream", "scrollback_lines"),
        ("stream", "answer_key_gap_ms"),
        ("iroh", "max_tunnel_streams"),
        ("iroh", "max_tunnel_connections"),
        ("iroh", "max_tunnel_connections_per_id"),
        ("iroh", "max_pair_connections"),
        ("iroh", "pair_attempts_per_window"),
        ("iroh", "pair_attempt_window_secs"),
        ("iroh", "max_pending_handshakes"),
        ("iroh", "pair_exchange_timeout_secs"),
        ("iroh", "stopped_timeout_secs"),
        ("iroh", "online_wait_secs"),
        ("lifecycle", "start_timeout_secs"),
        ("lifecycle", "stop_timeout_secs"),
        ("lifecycle", "control_timeout_ms"),
        ("lifecycle", "poll_interval_ms"),
        ("lifecycle", "probe_timeout_ms"),
        ("telegram", "request_timeout_secs"),
        ("telegram", "max_retry_after_secs"),
        ("telegram", "max_rate_limit_tries"),
        ("telegram", "server_retry_delay_secs"),
        ("telegram", "poll_interval_secs"),
        ("telegram", "max_pending"),
        ("telegram", "absence_grace_secs"),
        ("telegram", "max_text_chars"),
        ("telegram", "max_label_chars"),
        ("telegram", "max_name_chars"),
    ];

    #[derive(Deserialize)]
    struct Wrapped {
        tuning: Tuning,
    }

    fn path(section: &str, key: &str) -> String {
        if section.is_empty() {
            key.to_string()
        } else {
            format!("{section}.{key}")
        }
    }

    /// The `[tuning]` document with one key set to `value`.
    fn document(section: &str, key: &str, value: &str) -> String {
        if section.is_empty() {
            format!("[tuning]\n{key} = {value}\n")
        } else {
            format!("[tuning.{section}]\n{key} = {value}\n")
        }
    }

    /// Every leaf value of a tuning, keyed by its dotted path.
    fn leaves(tuning: &Tuning) -> BTreeMap<String, toml::Value> {
        fn walk(prefix: &str, value: &toml::Value, out: &mut BTreeMap<String, toml::Value>) {
            match value {
                toml::Value::Table(table) => {
                    for (key, child) in table {
                        let child_path = if prefix.is_empty() {
                            key.clone()
                        } else {
                            format!("{prefix}.{key}")
                        };
                        walk(&child_path, child, out);
                    }
                }
                leaf => {
                    out.insert(prefix.to_string(), leaf.clone());
                }
            }
        }
        let mut out = BTreeMap::new();
        walk("", &toml::Value::try_from(tuning).unwrap(), &mut out);
        out
    }

    #[test]
    fn defaults_are_valid() {
        assert_eq!(Tuning::default().validate(), Ok(()));
    }

    #[test]
    fn key_list_covers_every_field() {
        let listed: Vec<String> = KEYS
            .iter()
            .map(|(section, key)| path(section, key))
            .collect();
        let fields: Vec<String> = leaves(&Tuning::default()).into_keys().collect();
        assert_eq!(
            listed.len(),
            fields.len(),
            "a field is missing from KEYS: {fields:?}"
        );
        for field in &fields {
            assert!(listed.contains(field), "{field} is not in KEYS");
        }
    }

    #[test]
    fn every_key_parses_and_changes_only_itself() {
        let defaults = leaves(&Tuning::default());
        for (section, key) in KEYS {
            let text = document(section, key, "7");
            let parsed: Wrapped =
                toml::from_str(&text).unwrap_or_else(|error| panic!("{text}: {error}"));
            let changed = leaves(&parsed.tuning);
            let expected = path(section, key);
            let differing: Vec<&String> = changed
                .iter()
                .filter(|(name, value)| defaults.get(*name) != Some(value))
                .map(|(name, _)| name)
                .collect();
            assert_eq!(differing, vec![&expected], "setting {expected}");
            assert_eq!(
                changed[&expected],
                toml::Value::Integer(7),
                "setting {expected}"
            );
            assert_eq!(parsed.tuning.validate(), Ok(()), "setting {expected}");
        }
    }

    #[test]
    fn every_key_rejects_zero_by_name() {
        for (section, key) in KEYS {
            let text = document(section, key, "0");
            let parsed: Wrapped = toml::from_str(&text).unwrap();
            assert_eq!(
                parsed.tuning.validate(),
                Err(format!(
                    "tuning.{} must be greater than 0",
                    path(section, key)
                )),
            );
        }
    }

    #[test]
    fn unknown_keys_are_rejected_in_every_section() {
        for (section, _) in KEYS {
            let text = document(section, "not_a_real_key", "7");
            assert!(
                toml::from_str::<Wrapped>(&text).is_err(),
                "[{section}] accepted an unknown key"
            );
        }
    }

    #[test]
    fn partial_table_keeps_other_defaults() {
        let partial: Tuning = toml::from_str("[herdr]\nrequest_timeout_ms = 500\n").unwrap();
        assert_eq!(partial.herdr.request_timeout_ms, 500);
        assert_eq!(partial.herdr.max_response_line_bytes, 4 * 1024 * 1024);
    }
}
