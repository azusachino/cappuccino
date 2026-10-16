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
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rate_limits: Option<RateLimitStats>,
    #[serde(skip)]
    pub provider: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct RateLimitWindow {
    pub window: String,
    pub used_percent: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resets_at: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resets_in: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct RateLimitStats {
    pub provider: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub plan_tier: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub windows: Vec<RateLimitWindow>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub status: Option<String>,
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
        stats.context_window = claude_window(stats.model.as_deref(), stats.context_tokens);
    }
    Some(stats)
}

/// Models before Opus/Sonnet 4.6, and Haiku 4.5, have 200K of context; every later Claude model
/// has 1M. Older models could opt into 1M, which the transcript does not record, so a count
/// beyond 200K proves the larger window.
const CLAUDE_200K_MODELS: &[&str] = &[
    "claude-3",
    "claude-haiku-4-5",
    "claude-sonnet-4-5",
    "claude-opus-4-5",
    "claude-opus-4-1",
    "claude-opus-4-0",
    "claude-sonnet-4-0",
    "claude-opus-4-2025",
    "claude-sonnet-4-2025",
];

fn claude_window(model: Option<&str>, tokens: Option<u64>) -> Option<u64> {
    let legacy = model.is_some_and(|m| CLAUDE_200K_MODELS.iter().any(|p| m.starts_with(p)));
    Some(if legacy && tokens.unwrap_or(0) <= 200_000 {
        200_000
    } else {
        1_000_000
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

/// Extracts rate limits and optionally an inferred model from the transcript tail or terminal pane lines.
pub fn parse_rate_limits(
    kind: Kind,
    transcript: &str,
    pane_text: Option<&str>,
) -> (Option<RateLimitStats>, Option<String>) {
    let mut stats: Option<RateLimitStats> = None;
    let mut model_out: Option<String> = None;

    match kind {
        Kind::Claude => {
            // 1. Check transcript for explicit quota limits / 429 error
            for line in transcript.lines().rev() {
                if let Ok(entry) = serde_json::from_str::<Value>(line) {
                    if let Some(quota) = entry.get("quotaLimits") {
                        if quota.get("status").and_then(Value::as_str) == Some("rejected") {
                            let r_type = quota
                                .get("rateLimitType")
                                .and_then(Value::as_str)
                                .unwrap_or("five_hour");
                            let window_label = match r_type {
                                "five_hour" => "5h",
                                "seven_day" => "7d",
                                other => other,
                            };
                            let resets_at = quota.get("resetsAt").and_then(Value::as_i64);
                            stats = Some(RateLimitStats {
                                provider: "anthropic".to_string(),
                                plan_tier: None,
                                windows: vec![RateLimitWindow {
                                    window: window_label.to_string(),
                                    used_percent: 100,
                                    resets_at,
                                    resets_in: None,
                                }],
                                status: Some("exhausted".to_string()),
                            });
                            break;
                        }
                    }
                }
            }

            // 2. Check pane text for live statusline "5h:X% ... 7d:Y% ~ZhZm"
            if let Some(pane) = pane_text {
                let mut windows = Vec::new();
                let mut max_used = 0u32;
                for line in pane.lines().rev().take(15) {
                    if line.contains("5h:") || line.contains("7d:") {
                        if let Some(idx) = line.find("5h:") {
                            let rest = &line[idx + 3..];
                            let pct_str: String =
                                rest.chars().take_while(|c| c.is_ascii_digit()).collect();
                            if let Ok(pct) = pct_str.parse::<u32>() {
                                max_used = max_used.max(pct);
                                let resets_in = rest.find('~').and_then(|t_idx| {
                                    let reset_str: String = rest[t_idx + 1..]
                                        .chars()
                                        .take_while(|c| !c.is_whitespace() && *c != '·')
                                        .collect();
                                    (!reset_str.is_empty()).then_some(reset_str)
                                });
                                windows.push(RateLimitWindow {
                                    window: "5h".to_string(),
                                    used_percent: pct,
                                    resets_at: None,
                                    resets_in,
                                });
                            }
                        }
                        if let Some(idx) = line.find("7d:") {
                            let rest = &line[idx + 3..];
                            let pct_str: String =
                                rest.chars().take_while(|c| c.is_ascii_digit()).collect();
                            if let Ok(pct) = pct_str.parse::<u32>() {
                                max_used = max_used.max(pct);
                                let resets_in = rest.find('~').and_then(|t_idx| {
                                    let reset_str: String = rest[t_idx + 1..]
                                        .chars()
                                        .take_while(|c| !c.is_whitespace() && *c != '·')
                                        .collect();
                                    (!reset_str.is_empty()).then_some(reset_str)
                                });
                                windows.push(RateLimitWindow {
                                    window: "7d".to_string(),
                                    used_percent: pct,
                                    resets_at: None,
                                    resets_in,
                                });
                            }
                        }
                        if !windows.is_empty() {
                            let status = if max_used >= 95 {
                                "exhausted"
                            } else if max_used >= 70 {
                                "warning"
                            } else {
                                "normal"
                            };
                            stats = Some(RateLimitStats {
                                provider: "anthropic".to_string(),
                                plan_tier: None,
                                windows,
                                status: Some(status.to_string()),
                            });
                            break;
                        }
                    }
                }
            }
        }
        Kind::Agy => {
            if let Some(pane) = pane_text {
                for line in pane.lines().rev().take(15) {
                    if model_out.is_none()
                        && (line.contains("Gemini")
                            || line.contains("Flash")
                            || line.contains("Pro"))
                    {
                        for part in line.split('·') {
                            let trimmed = part.trim();
                            if trimmed.starts_with("Gemini") || trimmed.starts_with("Claude") {
                                model_out = Some(trimmed.to_string());
                                break;
                            }
                        }
                    }
                    if line.contains("% used")
                        || line.contains("Refreshes in")
                        || line.contains("Quota available")
                    {
                        let mut windows = Vec::new();
                        let mut plan_tier = None;
                        for part in line.split('·') {
                            let trimmed = part.trim();
                            if trimmed == "Pro"
                                || trimmed == "Ultra"
                                || trimmed.contains("Google AI")
                            {
                                plan_tier = Some(trimmed.to_string());
                            } else if trimmed.ends_with("% used") {
                                let num_str = trimmed.trim_end_matches("% used").trim();
                                if let Ok(pct) = num_str.parse::<u32>() {
                                    windows.push(RateLimitWindow {
                                        window: "session".to_string(),
                                        used_percent: pct,
                                        resets_at: None,
                                        resets_in: None,
                                    });
                                }
                            }
                        }
                        if !windows.is_empty() || plan_tier.is_some() {
                            stats = Some(RateLimitStats {
                                provider: "google".to_string(),
                                plan_tier,
                                windows,
                                status: Some("normal".to_string()),
                            });
                            break;
                        }
                    }
                }
            }
        }
        Kind::Pi => {
            if let Some(pane) = pane_text {
                for line in pane.lines().rev().take(15) {
                    if let Some(arrow_idx) = line.find('↻') {
                        let before = line[..arrow_idx].trim_end();
                        let after = line[arrow_idx + '↻'.len_utf8()..].trim();
                        let resets_in = after.split_whitespace().next().map(str::to_string);
                        if let Some(pct_idx) = before.rfind('%') {
                            let pct_start = before[..pct_idx]
                                .rfind(|c: char| !c.is_ascii_digit())
                                .map(|i| i + 1)
                                .unwrap_or(0);
                            let pct_str = &before[pct_start..pct_idx];
                            let name_part = before[..pct_start].trim();
                            let provider_name =
                                name_part.split_whitespace().last().unwrap_or("extension");
                            if let Ok(pct) = pct_str.parse::<u32>() {
                                let status = if pct >= 95 {
                                    "exhausted"
                                } else if pct >= 70 {
                                    "warning"
                                } else {
                                    "normal"
                                };
                                stats = Some(RateLimitStats {
                                    provider: provider_name.to_string(),
                                    plan_tier: None,
                                    windows: vec![RateLimitWindow {
                                        window: "quota".to_string(),
                                        used_percent: pct,
                                        resets_at: None,
                                        resets_in,
                                    }],
                                    status: Some(status.to_string()),
                                });
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    (stats, model_out)
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
        assert_eq!(
            stats.context_window,
            Some(1_000_000),
            "current models have 1M even early in a session"
        );

        let window = |model: &str, tokens: u64| {
            let line = format!(
                r#"{{"type":"assistant","message":{{"model":"{model}","usage":{{"input_tokens":1,"cache_read_input_tokens":{tokens},"output_tokens":1}}}}}}"#
            );
            session_stats(Kind::Claude, &line).unwrap().context_window
        };
        assert_eq!(window("claude-sonnet-5-5", 10), Some(1_000_000));
        assert_eq!(window("claude-haiku-5-5", 10), Some(1_000_000));
        assert_eq!(window("claude-haiku-4-5", 10), Some(200_000));
        assert_eq!(window("claude-sonnet-4-20250514", 10), Some(200_000));
        assert_eq!(
            window("claude-sonnet-4-5-20250929", 250_000),
            Some(1_000_000),
            "a count past 200K proves an older model's 1M option"
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

    #[test]
    fn parse_rate_limits_extracts_claude_statusline_and_quota_error() {
        let pane = "claude-haiku-5-5 · ~/Projects/test · main* · 5h:2% · 7d:81% ~17h43m · 89% left · v2.1.300";
        let (stats, _) = parse_rate_limits(Kind::Claude, "", Some(pane));
        let stats = stats.expect("claude rate limits");
        assert_eq!(stats.provider, "anthropic");
        assert_eq!(stats.status.as_deref(), Some("warning"));
        assert_eq!(stats.windows.len(), 2);
        assert_eq!(stats.windows[0].window, "5h");
        assert_eq!(stats.windows[0].used_percent, 2);
        assert_eq!(stats.windows[1].window, "7d");
        assert_eq!(stats.windows[1].used_percent, 81);
        assert_eq!(stats.windows[1].resets_in.as_deref(), Some("17h43m"));

        let rejected = r#"{"quotaLimits":{"status":"rejected","resetsAt":1791813600,"rateLimitType":"five_hour"}}"#;
        let (stats_err, _) = parse_rate_limits(Kind::Claude, rejected, None);
        let stats_err = stats_err.expect("rejected quota");
        assert_eq!(stats_err.status.as_deref(), Some("exhausted"));
        assert_eq!(stats_err.windows[0].used_percent, 100);
        assert_eq!(stats_err.windows[0].resets_at, Some(1791813600));
    }

    #[test]
    fn parse_rate_limits_extracts_agy_and_pi_extension_status() {
        let agy_pane =
            "working · Gemini 3.8 Flash (Medium) · test · main* · 20% used · Pro · v1.3.5";
        let (agy_stats, model) = parse_rate_limits(Kind::Agy, "", Some(agy_pane));
        assert_eq!(model.as_deref(), Some("Gemini 3.8 Flash (Medium)"));
        let agy_stats = agy_stats.expect("agy rate limits");
        assert_eq!(agy_stats.provider, "google");
        assert_eq!(agy_stats.plan_tier.as_deref(), Some("Pro"));
        assert_eq!(agy_stats.windows[0].used_percent, 20);

        let pi_pane = "↑808k ↓142k (openai-codex) gpt-6.1-sol\n bg 2 done · codex 95% ↻ 6d15h";
        let (pi_stats, _) = parse_rate_limits(Kind::Pi, "", Some(pi_pane));
        let pi_stats = pi_stats.expect("pi rate limits");
        assert_eq!(pi_stats.provider, "codex");
        assert_eq!(pi_stats.status.as_deref(), Some("exhausted"));
        assert_eq!(pi_stats.windows[0].used_percent, 95);
        assert_eq!(pi_stats.windows[0].resets_in.as_deref(), Some("6d15h"));
    }
}
