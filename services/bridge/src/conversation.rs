//! Native transcript parsing and conversation turn generation for agy, pi, and shell agents.
//! Supports reading a tail window of large session files to preserve bounded memory.

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

/// Bounded window for reading transcripts without loading multi-megabyte files into memory.
pub const TRANSCRIPT_TAIL_BYTES: u64 = 2 * 1024 * 1024; // 2MB

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ConversationResponse {
    pub session_id: String,
    pub source: String,
    pub turns: Vec<ConversationTurn>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ConversationTurn {
    pub id: String,
    pub role: String, // "user" | "agent"
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timestamp: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub parts: Vec<ConversationPart>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type")]
pub enum ConversationPart {
    #[serde(rename = "text")]
    Text { text: String },
    #[serde(rename = "thinking")]
    Thinking { text: String },
    #[serde(rename = "tool_call")]
    ToolCall {
        name: String,
        input: Value,
        #[serde(skip_serializing_if = "Option::is_none")]
        output: Option<String>,
    },
}

/// Reads the tail window of a file up to `max_bytes`.
/// If the file is sliced, it drops the first partial line up to the first newline.
pub fn read_tail_lines(path: &Path, max_bytes: u64) -> std::io::Result<String> {
    let mut file = File::open(path)?;
    let len = file.metadata()?.len();
    if len == 0 {
        return Ok(String::new());
    }

    let (seek_offset, read_len) = if len > max_bytes {
        (len - max_bytes, max_bytes as usize)
    } else {
        (0, len as usize)
    };

    file.seek(SeekFrom::Start(seek_offset))?;
    let mut buffer = vec![0u8; read_len];
    file.read_exact(&mut buffer)?;

    let s = String::from_utf8_lossy(&buffer);
    if seek_offset > 0 {
        // Discard the first line which might be partial
        if let Some(pos) = s.find('\n') {
            return Ok(s[pos + 1..].to_string());
        }
    }
    Ok(s.into_owned())
}

/// Parse agy transcript lines (from `~/.gemini/antigravity-cli/brain/<uuid>/.system_generated/logs/transcript.jsonl`)
pub fn parse_agy_transcript(content: &str) -> Vec<ConversationTurn> {
    let mut turns: Vec<ConversationTurn> = Vec::new();
    let mut turn_counter = 1usize;

    for line in content.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let Ok(v) = serde_json::from_str::<Value>(trimmed) else {
            continue;
        };

        let source = v.get("source").and_then(|s| s.as_str()).unwrap_or("");
        let typ = v.get("type").and_then(|t| t.as_str()).unwrap_or("");
        let timestamp = v
            .get("created_at")
            .and_then(|t| t.as_str())
            .map(str::to_string);

        match (source, typ) {
            ("USER_EXPLICIT", "USER_INPUT") => {
                let text = v
                    .get("content")
                    .and_then(|c| c.as_str())
                    .unwrap_or("")
                    .to_string();
                let clean_text = clean_agy_user_prompt(&text);
                turns.push(ConversationTurn {
                    id: format!("t-{turn_counter:03}"),
                    role: "user".to_string(),
                    timestamp,
                    text: Some(clean_text),
                    parts: Vec::new(),
                });
                turn_counter += 1;
            }
            ("MODEL", "PLANNER_RESPONSE") => {
                let mut parts = Vec::new();

                if let Some(th) = v.get("thinking").and_then(|t| t.as_str()) {
                    let th_trimmed = th.trim();
                    if !th_trimmed.is_empty() {
                        parts.push(ConversationPart::Thinking {
                            text: th_trimmed.to_string(),
                        });
                    }
                }

                if let Some(calls) = v.get("tool_calls").and_then(|c| c.as_array()) {
                    for call in calls {
                        let name = call
                            .get("name")
                            .and_then(|n| n.as_str())
                            .unwrap_or("tool")
                            .to_string();
                        let input = call.get("args").cloned().unwrap_or(Value::Null);
                        parts.push(ConversationPart::ToolCall {
                            name,
                            input,
                            output: None,
                        });
                    }
                }

                if let Some(c) = v.get("content").and_then(|c| c.as_str()) {
                    let c_trimmed = c.trim();
                    if !c_trimmed.is_empty() {
                        parts.push(ConversationPart::Text {
                            text: c_trimmed.to_string(),
                        });
                    }
                }

                if parts.is_empty() {
                    continue;
                }

                // If the last turn was agent, merge into it; otherwise create a new agent turn
                let merge = turns.last().map(|t| t.role.as_str()) == Some("agent");
                if merge {
                    if let Some(last) = turns.last_mut() {
                        last.parts.extend(parts);
                    }
                } else {
                    turns.push(ConversationTurn {
                        id: format!("t-{turn_counter:03}"),
                        role: "agent".to_string(),
                        timestamp,
                        text: None,
                        parts,
                    });
                    turn_counter += 1;
                }
            }
            ("MODEL", "GENERIC") => {
                // Tool output from previous tool_call
                let output_text = v.get("content").and_then(|c| c.as_str()).unwrap_or("");
                if let Some(last) = turns.last_mut() {
                    if last.role == "agent" {
                        // Find the last tool_call without an output
                        for part in last.parts.iter_mut().rev() {
                            if let ConversationPart::ToolCall { output, .. } = part {
                                if output.is_none() {
                                    *output = Some(output_text.trim().to_string());
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            _ => {}
        }
    }

    turns
}

/// Strip `<USER_REQUEST>` envelope if present
fn clean_agy_user_prompt(raw: &str) -> String {
    let mut text = raw;
    if let Some(start) = text.find("<USER_REQUEST>") {
        let after = &text[start + "<USER_REQUEST>".len()..];
        if let Some(end) = after.find("</USER_REQUEST>") {
            return after[..end].trim().to_string();
        }
    }
    // Also cut out <ADDITIONAL_METADATA> or similar
    if let Some(idx) = text.find("<ADDITIONAL_METADATA>") {
        text = text[..idx].trim();
    }
    text.trim().to_string()
}

/// Parse pi transcript lines (from `~/.pi/agent/sessions/<cwd-slug>/<session>.jsonl`)
pub fn parse_pi_transcript(content: &str) -> Vec<ConversationTurn> {
    let mut turns: Vec<ConversationTurn> = Vec::new();
    let mut turn_counter = 1usize;

    for line in content.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let Ok(v) = serde_json::from_str::<Value>(trimmed) else {
            continue;
        };

        let typ = v.get("type").and_then(|t| t.as_str()).unwrap_or("");
        if typ != "message" {
            continue;
        }

        let Some(msg) = v.get("message") else {
            continue;
        };
        let role = msg.get("role").and_then(|r| r.as_str()).unwrap_or("");
        let timestamp = v
            .get("timestamp")
            .and_then(|t| t.as_str())
            .map(str::to_string);

        match role {
            "user" => {
                let text = if let Some(s) = msg.get("content").and_then(|c| c.as_str()) {
                    s.to_string()
                } else if let Some(arr) = msg.get("content").and_then(|c| c.as_array()) {
                    arr.iter()
                        .filter_map(|p| p.get("text").and_then(|t| t.as_str()))
                        .collect::<Vec<_>>()
                        .join("\n")
                } else {
                    String::new()
                };

                turns.push(ConversationTurn {
                    id: format!("t-{turn_counter:03}"),
                    role: "user".to_string(),
                    timestamp,
                    text: Some(text.trim().to_string()),
                    parts: Vec::new(),
                });
                turn_counter += 1;
            }
            "assistant" => {
                let mut parts = Vec::new();
                if let Some(arr) = msg.get("content").and_then(|c| c.as_array()) {
                    for block in arr {
                        let block_type = block.get("type").and_then(|t| t.as_str()).unwrap_or("");
                        match block_type {
                            "text" => {
                                if let Some(t) = block.get("text").and_then(|s| s.as_str()) {
                                    if !t.trim().is_empty() {
                                        parts.push(ConversationPart::Text {
                                            text: t.trim().to_string(),
                                        });
                                    }
                                }
                            }
                            "thinking" => {
                                if let Some(t) = block.get("thinking").and_then(|s| s.as_str()) {
                                    if !t.trim().is_empty() {
                                        parts.push(ConversationPart::Thinking {
                                            text: t.trim().to_string(),
                                        });
                                    }
                                }
                            }
                            "toolCall" | "tool_call" => {
                                let name = block
                                    .get("name")
                                    .or_else(|| block.get("toolName"))
                                    .and_then(|s| s.as_str())
                                    .unwrap_or("tool")
                                    .to_string();
                                let input = block
                                    .get("input")
                                    .or_else(|| block.get("arguments"))
                                    .or_else(|| block.get("toolInput"))
                                    .cloned()
                                    .unwrap_or(Value::Null);
                                parts.push(ConversationPart::ToolCall {
                                    name,
                                    input,
                                    output: None,
                                });
                            }
                            _ => {}
                        }
                    }
                }

                if parts.is_empty() {
                    continue;
                }

                let merge = turns.last().map(|t| t.role.as_str()) == Some("agent");
                if merge {
                    if let Some(last) = turns.last_mut() {
                        last.parts.extend(parts);
                    }
                } else {
                    turns.push(ConversationTurn {
                        id: format!("t-{turn_counter:03}"),
                        role: "agent".to_string(),
                        timestamp,
                        text: None,
                        parts,
                    });
                    turn_counter += 1;
                }
            }
            "toolResult" | "tool_result" => {
                let content_str = if let Some(s) = msg.get("content").and_then(|c| c.as_str()) {
                    s.to_string()
                } else if let Some(output) = msg.get("output").and_then(|c| c.as_str()) {
                    output.to_string()
                } else {
                    String::new()
                };

                if let Some(last) = turns.last_mut() {
                    if last.role == "agent" {
                        for part in last.parts.iter_mut().rev() {
                            if let ConversationPart::ToolCall { output, .. } = part {
                                if output.is_none() {
                                    *output = Some(content_str.trim().to_string());
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            _ => {}
        }
    }

    turns
}

/// Fallback parser for raw terminal scrollback lines when no canonical log exists.
pub fn parse_scrollback_turns(lines: &[String]) -> Vec<ConversationTurn> {
    let mut turns = Vec::new();
    let mut current_agent_lines: Vec<String> = Vec::new();
    let mut turn_counter = 1usize;

    for line in lines {
        let trimmed = line.trim();
        // Check for user prompt markers like ❯ or >
        if trimmed.starts_with('❯') || trimmed.starts_with('>') || trimmed.starts_with("user:") {
            if !current_agent_lines.is_empty() {
                turns.push(ConversationTurn {
                    id: format!("t-{turn_counter:03}"),
                    role: "agent".to_string(),
                    timestamp: None,
                    text: None,
                    parts: vec![ConversationPart::Text {
                        text: current_agent_lines.join("\n").trim().to_string(),
                    }],
                });
                turn_counter += 1;
                current_agent_lines.clear();
            }

            let prompt_text = trimmed
                .trim_start_matches(|c| c == '❯' || c == '>' || c == ' ')
                .trim();
            if !prompt_text.is_empty() {
                turns.push(ConversationTurn {
                    id: format!("t-{turn_counter:03}"),
                    role: "user".to_string(),
                    timestamp: None,
                    text: Some(prompt_text.to_string()),
                    parts: Vec::new(),
                });
                turn_counter += 1;
            }
        } else if !trimmed.is_empty() {
            current_agent_lines.push(line.clone());
        }
    }

    if !current_agent_lines.is_empty() {
        turns.push(ConversationTurn {
            id: format!("t-{turn_counter:03}"),
            role: "agent".to_string(),
            timestamp: None,
            text: None,
            parts: vec![ConversationPart::Text {
                text: current_agent_lines.join("\n").trim().to_string(),
            }],
        });
    }

    turns
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_agy_transcript_into_turns() {
        let jsonl = r#"{"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","created_at":"2026-10-07T08:00:41Z","content":"<USER_REQUEST>\ncheck caccpucino status\n</USER_REQUEST>"}
{"step_index":1,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"2026-10-07T08:00:41Z","thinking":"Checking repo status","tool_calls":[{"name":"run_command","args":{"CommandLine":"git status"}}]}
{"step_index":2,"source":"MODEL","type":"GENERIC","status":"DONE","created_at":"2026-10-07T08:00:59Z","content":"On branch main\nnothing to commit"}
{"step_index":3,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"2026-10-07T08:01:00Z","content":"Status looks clean."}"#;

        let turns = parse_agy_transcript(jsonl);
        assert_eq!(turns.len(), 2);
        assert_eq!(turns[0].role, "user");
        assert_eq!(turns[0].text.as_deref(), Some("check caccpucino status"));

        assert_eq!(turns[1].role, "agent");
        assert_eq!(turns[1].parts.len(), 3);

        match &turns[1].parts[0] {
            ConversationPart::Thinking { text } => assert_eq!(text, "Checking repo status"),
            _ => panic!("expected thinking"),
        }
        match &turns[1].parts[1] {
            ConversationPart::ToolCall { name, output, .. } => {
                assert_eq!(name, "run_command");
                assert_eq!(output.as_deref(), Some("On branch main\nnothing to commit"));
            }
            _ => panic!("expected tool_call"),
        }
        match &turns[1].parts[2] {
            ConversationPart::Text { text } => assert_eq!(text, "Status looks clean."),
            _ => panic!("expected text"),
        }
    }

    #[test]
    fn parses_pi_transcript_into_turns() {
        let jsonl = r#"{"type":"message","timestamp":"2026-10-07T12:00:00Z","message":{"role":"user","content":"Run tests"}}
{"type":"message","timestamp":"2026-10-07T12:00:01Z","message":{"role":"assistant","content":[{"type":"toolCall","name":"cargo_test","arguments":{"target":"all"}}]}}
{"type":"message","timestamp":"2026-10-07T12:00:05Z","message":{"role":"toolResult","content":"test result: ok"}}
{"type":"message","timestamp":"2026-10-07T12:00:06Z","message":{"role":"assistant","content":[{"type":"text","text":"Tests passed successfully"}]}}"#;

        let turns = parse_pi_transcript(jsonl);
        assert_eq!(turns.len(), 2);
        assert_eq!(turns[0].role, "user");
        assert_eq!(turns[0].text.as_deref(), Some("Run tests"));

        assert_eq!(turns[1].role, "agent");
        assert_eq!(turns[1].parts.len(), 2);
        match &turns[1].parts[0] {
            ConversationPart::ToolCall { name, output, .. } => {
                assert_eq!(name, "cargo_test");
                assert_eq!(output.as_deref(), Some("test result: ok"));
            }
            _ => panic!("expected tool_call"),
        }
        match &turns[1].parts[1] {
            ConversationPart::Text { text } => assert_eq!(text, "Tests passed successfully"),
            _ => panic!("expected text"),
        }
    }

    #[test]
    fn tail_reader_reads_bounded_window() {
        let dir = std::env::temp_dir().join(format!("test-tail-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("test.log");

        let long_content = (0..1000).map(|i| format!("line {i}\n")).collect::<String>();
        std::fs::write(&path, long_content).unwrap();

        let tail = read_tail_lines(&path, 50).unwrap();
        assert!(tail.contains("line 999"));
        assert!(!tail.contains("line 0"));

        let _ = std::fs::remove_dir_all(&dir);
    }
}
