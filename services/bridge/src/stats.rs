//! The session's model and context use, read from the newest model call in its transcript.
//! Transcripts do not record the context window, so it comes from Pi's own model registry or a
//! known model family; when neither knows it, only the token count is reported.

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::path::Path;

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct SessionStats {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub thinking_level: Option<String>,
    /// Tokens the newest model call carried; absent after a compaction until the next call.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub context_tokens: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub context_window: Option<u64>,
    /// Share of the newest call's input that was read from the prompt cache.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cache_percent: Option<u8>,
    #[serde(skip)]
    pub provider: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Pi,
    Claude,
    Agy,
}

fn number(value: &Value, key: &str) -> u64 {
    value.get(key).and_then(Value::as_u64).unwrap_or(0)
}

fn text(value: &Value, key: &str) -> Option<String> {
    value
        .get(key)
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

fn cache_percent(cached: u64, input: u64) -> Option<u8> {
    (input > 0).then(|| (cached * 100 / input).min(100) as u8)
}

/// Stats for one transcript tail, or `None` when it holds no model call at all.
pub fn session_stats(kind: Kind, content: &str) -> Option<SessionStats> {
    let mut stats = SessionStats::default();
    let mut seen = false;
    for line in content.lines() {
        let Ok(entry) = serde_json::from_str::<Value>(line) else {
            continue;
        };
        match kind {
            Kind::Pi => match entry.get("type").and_then(Value::as_str) {
                Some("thinking_level_change") => {
                    stats.thinking_level = text(&entry, "thinkingLevel");
                }
                Some("compaction") => {
                    stats.context_tokens = None;
                    stats.cache_percent = None;
                }
                Some("message") => {
                    let message = &entry["message"];
                    let usage = &message["usage"];
                    // Aborted calls carry no usage; they say nothing about the context.
                    if message["role"] != "assistant" || number(usage, "totalTokens") == 0 {
                        continue;
                    }
                    let (input, cached) = (
                        number(usage, "input") + number(usage, "cacheWrite"),
                        number(usage, "cacheRead"),
                    );
                    stats.model = text(message, "model");
                    stats.provider = text(message, "provider");
                    stats.context_tokens = Some(number(usage, "totalTokens"));
                    stats.cache_percent = cache_percent(cached, input + cached);
                    seen = true;
                }
                _ => {}
            },
            Kind::Claude => {
                if entry["type"] == "system" && entry["subtype"] == "compact_boundary" {
                    stats.context_tokens = None;
                    stats.cache_percent = None;
                    continue;
                }
                let message = &entry["message"];
                let usage = &message["usage"];
                // `<synthetic>` marks harness-made replies such as API errors, not model calls.
                if entry["type"] != "assistant"
                    || entry["isSidechain"] == true
                    || !usage.is_object()
                    || message["model"] == "<synthetic>"
                {
                    continue;
                }
                let (input, cached) = (
                    number(usage, "input_tokens") + number(usage, "cache_creation_input_tokens"),
                    number(usage, "cache_read_input_tokens"),
                );
                // Claude Code records the effort each call ran at beside the message.
                if let Some(effort) =
                    text(&entry, "perTurnEffort").or_else(|| text(&entry, "effort"))
                {
                    stats.thinking_level = Some(effort);
                }
                stats.model = text(message, "model");
                stats.context_tokens = Some(input + cached + number(usage, "output_tokens"));
                stats.cache_percent = cache_percent(cached, input + cached);
                seen = true;
            }
            Kind::Agy => {
                if entry.get("input_tokens").is_none() {
                    continue;
                }
                let (input, cached) = (
                    number(&entry, "input_tokens"),
                    number(&entry, "cache_read_tokens"),
                );
                stats.context_tokens = Some(input + cached + number(&entry, "output_tokens"));
                stats.cache_percent = cache_percent(cached, input + cached);
                seen = true;
            }
        }
    }
    if !seen {
        return None;
    }
    if kind == Kind::Claude {
        stats.context_window = claude_window(stats.context_tokens);
    }
    Some(stats)
}

/// Claude models run with 200K of context unless started with the 1M option, which the
/// transcript does not record; a count beyond 200K proves the larger window.
fn claude_window(tokens: Option<u64>) -> Option<u64> {
    Some(if tokens.unwrap_or(0) > 200_000 {
        1_000_000
    } else {
        200_000
    })
}

/// The latest thinking level anywhere in a Pi session. A long session may set it well before the
/// tail the conversation reads, so the file is scanned once and afterwards only what it grew by.
pub fn pi_thinking_level(transcript: &Path) -> Option<String> {
    use std::collections::HashMap;
    use std::io::{BufRead, BufReader, Seek, SeekFrom};
    use std::path::PathBuf;
    use std::sync::{Mutex, OnceLock};
    static SCANNED: OnceLock<Mutex<HashMap<PathBuf, (u64, Option<String>)>>> = OnceLock::new();

    let mut scanned = SCANNED.get_or_init(Default::default).lock().ok()?;
    let len = std::fs::metadata(transcript).ok()?.len();
    // A shorter file is a replaced one: start over.
    let (mut offset, mut level) = scanned
        .get(transcript)
        .filter(|(offset, _)| *offset <= len)
        .cloned()
        .unwrap_or((0, None));
    let mut file = std::fs::File::open(transcript).ok()?;
    file.seek(SeekFrom::Start(offset)).ok()?;
    let mut reader = BufReader::new(file);
    let mut line = Vec::new();
    loop {
        line.clear();
        let read = reader.read_until(b'\n', &mut line).ok()?;
        // Stop before a line still being written; it is read whole next time.
        if read == 0 || line.last() != Some(&b'\n') {
            break;
        }
        offset += read as u64;
        if line.windows(21).any(|w| w == b"thinking_level_change") {
            if let Ok(entry) = serde_json::from_slice::<Value>(&line) {
                if entry["type"] == "thinking_level_change" {
                    level = text(&entry, "thinkingLevel").or(level);
                }
            }
        }
    }
    scanned.insert(transcript.to_path_buf(), (offset, level.clone()));
    level
}

/// A Pi model's window from the `models.json` beside its `sessions` store.
pub fn pi_context_window(transcript: &Path, provider: &str, model: &str) -> Option<u64> {
    let agent_dir = transcript
        .ancestors()
        .find(|dir| dir.file_name().is_some_and(|name| name == "sessions"))?
        .parent()?;
    let registry: Value =
        serde_json::from_slice(&std::fs::read(agent_dir.join("models.json")).ok()?).ok()?;
    registry["providers"][provider]["models"]
        .as_array()?
        .iter()
        .find(|entry| entry["id"] == model)?["contextWindow"]
        .as_u64()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pi_uses_the_newest_call_and_forgets_context_on_compaction() {
        let jsonl = r#"{"type":"thinking_level_change","thinkingLevel":"high"}
{"type":"message","message":{"role":"assistant","provider":"zai","model":"glm","usage":{"input":300,"output":100,"cacheRead":600,"cacheWrite":100,"totalTokens":1100}}}
{"type":"message","message":{"role":"assistant","provider":"zai","model":"glm","stopReason":"aborted","usage":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0}}}"#;
        let stats = session_stats(Kind::Pi, jsonl).unwrap();
        assert_eq!(stats.model.as_deref(), Some("glm"));
        assert_eq!(stats.provider.as_deref(), Some("zai"));
        assert_eq!(stats.thinking_level.as_deref(), Some("high"));
        assert_eq!(stats.context_tokens, Some(1100));
        assert_eq!(stats.cache_percent, Some(60));
        assert_eq!(stats.context_window, None);

        let compacted = format!("{jsonl}\n{{\"type\":\"compaction\"}}");
        let stats = session_stats(Kind::Pi, &compacted).unwrap();
        assert_eq!(stats.context_tokens, None);
        assert_eq!(stats.cache_percent, None);
        assert_eq!(stats.model.as_deref(), Some("glm"));
    }

    #[test]
    fn claude_skips_synthetic_and_sidechain_calls_and_infers_the_window() {
        let jsonl = r#"{"type":"assistant","effort":"high","perTurnEffort":"medium","message":{"model":"claude-opus-5-5","usage":{"input_tokens":10,"cache_creation_input_tokens":90,"cache_read_input_tokens":900,"output_tokens":50}}}
{"type":"assistant","isSidechain":true,"message":{"model":"claude-haiku-5-5","usage":{"input_tokens":5,"output_tokens":5}}}
{"type":"assistant","message":{"model":"<synthetic>","usage":{"input_tokens":0,"output_tokens":0}}}"#;
        let stats = session_stats(Kind::Claude, jsonl).unwrap();
        assert_eq!(stats.model.as_deref(), Some("claude-opus-5-5"));
        assert_eq!(stats.thinking_level.as_deref(), Some("medium"));
        assert_eq!(stats.context_tokens, Some(1050));
        assert_eq!(stats.cache_percent, Some(90));
        assert_eq!(stats.context_window, Some(200_000));

        let large = r#"{"type":"assistant","message":{"model":"claude-opus-5-5","usage":{"input_tokens":1,"cache_read_input_tokens":250000,"output_tokens":1}}}"#;
        assert_eq!(
            session_stats(Kind::Claude, large).unwrap().context_window,
            Some(1_000_000)
        );
        assert_eq!(session_stats(Kind::Claude, "{\"type\":\"user\"}"), None);
    }

    #[test]
    fn agy_counts_uncached_input_cache_and_output_without_a_model() {
        let jsonl = r#"{"step_index":1,"source":"USER_EXPLICIT","type":"USER_INPUT"}
{"step_index":2,"source":"MODEL","input_tokens":2000,"cache_read_tokens":8000,"output_tokens":100}"#;
        let stats = session_stats(Kind::Agy, jsonl).unwrap();
        assert_eq!(stats.model, None);
        assert_eq!(stats.context_tokens, Some(10_100));
        assert_eq!(stats.cache_percent, Some(80));
        assert_eq!(stats.context_window, None);
    }

    #[test]
    fn pi_thinking_level_survives_a_long_session_and_follows_growth() {
        let path =
            std::env::temp_dir().join(format!("cappuccino-level-{}.jsonl", std::process::id()));
        let early = "{\"type\":\"thinking_level_change\",\"thinkingLevel\":\"high\"}\n";
        let filler = "{\"type\":\"message\",\"message\":{\"role\":\"user\"}}\n".repeat(50);
        std::fs::write(&path, format!("{early}{filler}")).unwrap();
        assert_eq!(pi_thinking_level(&path).as_deref(), Some("high"));
        // A half-written line is left for the next read.
        let mut grown = format!(
            "{early}{filler}{{\"type\":\"thinking_level_change\",\"thinkingLevel\":\"low\"}}"
        );
        std::fs::write(&path, &grown).unwrap();
        assert_eq!(pi_thinking_level(&path).as_deref(), Some("high"));
        grown.push('\n');
        std::fs::write(&path, &grown).unwrap();
        assert_eq!(pi_thinking_level(&path).as_deref(), Some("low"));
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn pi_window_comes_from_the_registry_beside_the_store() {
        let dir = std::env::temp_dir().join(format!("cappuccino-stats-{}", std::process::id()));
        let session = dir.join("sessions/--slug--/s.jsonl");
        std::fs::create_dir_all(session.parent().unwrap()).unwrap();
        std::fs::write(
            dir.join("models.json"),
            r#"{"providers":{"zai":{"models":[{"id":"glm","contextWindow":1000000}]}}}"#,
        )
        .unwrap();
        assert_eq!(pi_context_window(&session, "zai", "glm"), Some(1_000_000));
        assert_eq!(pi_context_window(&session, "zai", "other"), None);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
