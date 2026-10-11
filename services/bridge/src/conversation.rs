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
    #[serde(rename = "response_status")]
    ResponseStatus {
        status: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        detail: Option<String>,
    },
    #[serde(rename = "tool_call")]
    ToolCall {
        name: String,
        input: Value,
        #[serde(skip_serializing_if = "Option::is_none")]
        output: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        is_error: Option<bool>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        started_at: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        finished_at: Option<String>,
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
                            is_error: None,
                            started_at: timestamp.clone(),
                            finished_at: None,
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
    let mut tool_calls = std::collections::HashMap::new();

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
                                if let Some(id) = block.get("id").and_then(Value::as_str) {
                                    tool_calls.insert(id.to_string(), (turns.len(), parts.len()));
                                }
                                parts.push(ConversationPart::ToolCall {
                                    name,
                                    input,
                                    output: None,
                                    is_error: None,
                                    started_at: timestamp.clone(),
                                    finished_at: None,
                                });
                            }
                            _ => {}
                        }
                    }
                }

                let status = match msg.get("stopReason").and_then(Value::as_str) {
                    Some("aborted") => Some("aborted"),
                    Some("error") => Some("failed"),
                    _ => None,
                };
                if let Some(status) = status {
                    parts.push(ConversationPart::ResponseStatus {
                        status: status.to_string(),
                        detail: msg
                            .get("errorMessage")
                            .and_then(Value::as_str)
                            .map(str::to_string),
                    });
                }
                if parts.is_empty() {
                    continue;
                }

                // Preserve actual response boundaries and their timestamps.
                turns.push(ConversationTurn {
                    id: format!("t-{turn_counter:03}"),
                    role: "agent".to_string(),
                    timestamp,
                    text: None,
                    parts,
                });
                turn_counter += 1;
            }
            "toolResult" | "tool_result" => {
                let content_str = if let Some(s) = msg.get("content").and_then(|c| c.as_str()) {
                    s.to_string()
                } else if let Some(blocks) = msg.get("content").and_then(Value::as_array) {
                    blocks
                        .iter()
                        .filter(|block| block.get("type").and_then(Value::as_str) == Some("text"))
                        .filter_map(|block| block.get("text").and_then(Value::as_str))
                        .collect::<Vec<_>>()
                        .join("\n")
                } else if let Some(output) = msg.get("output").and_then(|c| c.as_str()) {
                    output.to_string()
                } else {
                    String::new()
                };

                if let Some(id) = msg.get("toolCallId").and_then(Value::as_str) {
                    if let Some((turn_index, part_index)) = tool_calls.remove(id) {
                        if let Some(ConversationPart::ToolCall {
                            output,
                            is_error,
                            finished_at,
                            ..
                        }) = turns
                            .get_mut(turn_index)
                            .and_then(|turn| turn.parts.get_mut(part_index))
                        {
                            *output = Some(content_str);
                            *is_error = msg.get("isError").and_then(Value::as_bool);
                            *finished_at = timestamp.clone();
                        }
                    }
                    // An unmatched typed result must never attach to another tool.
                } else if let Some(last) = turns.last_mut() {
                    // Legacy logs without IDs retain their sequential association.
                    if last.role == "agent" {
                        for part in last.parts.iter_mut().rev() {
                            if let ConversationPart::ToolCall {
                                output,
                                is_error,
                                finished_at,
                                ..
                            } = part
                            {
                                if output.is_none() {
                                    *output = Some(content_str);
                                    *is_error = msg.get("isError").and_then(Value::as_bool);
                                    *finished_at = timestamp.clone();
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
        assert_eq!(turns.len(), 3);
        assert_eq!(turns[0].role, "user");
        assert_eq!(turns[0].text.as_deref(), Some("Run tests"));

        assert_eq!(turns[1].role, "agent");
        assert_eq!(turns[1].parts.len(), 1);
        assert_eq!(turns[1].timestamp.as_deref(), Some("2026-10-07T12:00:01Z"));
        assert_eq!(turns[2].timestamp.as_deref(), Some("2026-10-07T12:00:06Z"));
        match &turns[1].parts[0] {
            ConversationPart::ToolCall {
                name,
                output,
                started_at,
                finished_at,
                ..
            } => {
                assert_eq!(name, "cargo_test");
                assert_eq!(output.as_deref(), Some("test result: ok"));
                assert_eq!(started_at.as_deref(), Some("2026-10-07T12:00:01Z"));
                assert_eq!(finished_at.as_deref(), Some("2026-10-07T12:00:05Z"));
            }
            _ => panic!("expected tool_call"),
        }
        match &turns[2].parts[0] {
            ConversationPart::Text { text } => assert_eq!(text, "Tests passed successfully"),
            _ => panic!("expected text"),
        }
    }

    #[test]
    fn pi_text_blocks_match_parallel_tool_ids_not_result_order() {
        let jsonl = r#"{"type":"message","message":{"role":"assistant","content":[{"type":"toolCall","id":"a","name":"read","arguments":{"path":"a.md"}},{"type":"toolCall","id":"b","name":"bash","arguments":{"command":"pwd"}}]}}
{"type":"message","message":{"role":"toolResult","toolCallId":"missing","content":[{"type":"text","text":"must not attach"}]}}
{"type":"message","message":{"role":"toolResult","toolCallId":"a","content":[{"type":"text","text":"first block"},{"type":"text","text":"second block"}]}}
{"type":"message","message":{"role":"toolResult","toolCallId":"b","content":[{"type":"text","text":"/project"}]}}"#;
        let turns = parse_pi_transcript(jsonl);
        assert_eq!(turns.len(), 1);
        assert_eq!(turns[0].parts.len(), 2);
        assert!(
            matches!(&turns[0].parts[0], ConversationPart::ToolCall { name, input, output, .. }
            if name == "read" && input["path"] == "a.md" && output.as_deref() == Some("first block\nsecond block"))
        );
        assert!(
            matches!(&turns[0].parts[1], ConversationPart::ToolCall { name, output, .. }
            if name == "bash" && output.as_deref() == Some("/project"))
        );
    }

    #[test]
    fn pi_error_flag_follows_the_matching_tool_result() {
        let log = r#"{"type":"message","message":{"role":"assistant","content":[{"type":"toolCall","id":"a","name":"read","arguments":{}},{"type":"toolCall","id":"b","name":"bash","arguments":{}}]}}
{"type":"message","message":{"role":"toolResult","toolCallId":"b","isError":true,"content":[{"type":"text","text":"command failed"}]}}
{"type":"message","message":{"role":"toolResult","toolCallId":"a","isError":false,"content":[{"type":"text","text":"contains the word error but succeeded"}]}}"#;
        let turns = parse_pi_transcript(log);
        assert!(matches!(
            &turns[0].parts[0],
            ConversationPart::ToolCall {
                is_error: Some(false),
                ..
            }
        ));
        assert!(
            matches!(&turns[0].parts[1], ConversationPart::ToolCall { is_error: Some(true), output: Some(output), .. } if output == "command failed")
        );
    }

    #[test]
    fn pi_explicit_abort_keeps_partial_and_empty_responses_without_guessing() {
        let turns = parse_pi_transcript(
            r#"{"type":"message","message":{"role":"assistant","content":[{"type":"text","text":"Partial reply"}],"stopReason":"aborted","errorMessage":"Operation cancelled"}}
{"type":"message","message":{"role":"user","content":"next"}}
{"type":"message","message":{"role":"assistant","content":[],"stopReason":"aborted"}}"#,
        );
        assert!(
            matches!(&turns[0].parts[0], ConversationPart::Text { text } if text == "Partial reply")
        );
        assert!(
            matches!(&turns[0].parts[1], ConversationPart::ResponseStatus { status, detail: Some(detail) } if status == "aborted" && detail == "Operation cancelled")
        );
        assert!(
            matches!(&turns[2].parts[0], ConversationPart::ResponseStatus { status, detail: None } if status == "aborted")
        );
        let ordinary = parse_pi_transcript(
            r#"{"type":"message","message":{"role":"assistant","content":[{"type":"text","text":"The operation aborted yesterday"}],"stopReason":"stop"}}"#,
        );
        assert_eq!(ordinary[0].parts.len(), 1);
        let failed = parse_pi_transcript(
            r#"{"type":"message","message":{"role":"assistant","content":[],"stopReason":"error","errorMessage":"Provider failed"}}"#,
        );
        assert!(
            matches!(&failed[0].parts[0], ConversationPart::ResponseStatus { status, .. } if status == "failed")
        );
    }

    #[test]
    fn pi_tool_times_remain_id_bound_across_separate_responses() {
        let log = r#"{"type":"message","timestamp":"2026-10-10T01:00:00Z","message":{"role":"assistant","content":[{"type":"toolCall","id":"a","name":"read","arguments":{}}]}}
{"type":"message","timestamp":"2026-10-10T01:00:01Z","message":{"role":"assistant","content":[{"type":"toolCall","id":"b","name":"bash","arguments":{}}]}}
{"type":"message","timestamp":"2026-10-10T01:00:02Z","message":{"role":"toolResult","toolCallId":"b","content":"b"}}
{"type":"message","timestamp":"2026-10-10T01:00:03Z","message":{"role":"toolResult","toolCallId":"a","content":"a"}}"#;
        let turns = parse_pi_transcript(log);
        assert_eq!(turns.len(), 2);
        assert!(
            matches!(&turns[0].parts[0], ConversationPart::ToolCall { started_at: Some(start), finished_at: Some(end), output: Some(output), .. } if start == "2026-10-10T01:00:00Z" && end == "2026-10-10T01:00:03Z" && output == "a")
        );
        assert!(
            matches!(&turns[1].parts[0], ConversationPart::ToolCall { finished_at: Some(end), output: Some(output), .. } if end == "2026-10-10T01:00:02Z" && output == "b")
        );
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
