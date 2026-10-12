//! Native transcript parsing and conversation turn generation for agy, pi, and shell agents.
//! Supports reading a tail window of large session files to preserve bounded memory.

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::fs::File;
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom};

/// Bounded window for reading transcripts without loading multi-megabyte files into memory.
pub const TRANSCRIPT_TAIL_BYTES: u64 = 2 * 1024 * 1024; // 2MB

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ConversationResponse {
    pub session_id: String,
    pub source: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub machine_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub conversation_id: Option<String>,
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
        #[serde(default, skip_serializing_if = "Option::is_none")]
        images: Option<u32>,
    },
}

/// If the tail is sliced, discard its first partial line.
fn read_tail_from(file: &mut File, max_bytes: u64) -> std::io::Result<String> {
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

/// Most bytes the compacted Claude reader may read from the end of a transcript.
pub const CLAUDE_TAIL_IO_CAP: u64 = 32 * 1024 * 1024;
const COMPACT_LINE_BYTES: usize = 16 * 1024;
const TAIL_CHUNK_BYTES: u64 = 256 * 1024;

/// Claude Code stores pasted and tool-returned images inline as base64, so a few screenshots
/// can fill a byte window with nothing the phone shows. Read backwards, drop image data from
/// each line, and stop once `budget` bytes of compacted lines or `io_cap` bytes read are reached.
fn read_compacted_tail(file: &mut File, budget: u64, io_cap: u64) -> std::io::Result<String> {
    let len = file.metadata()?.len();
    let mut pos = len;
    let mut carry: Vec<u8> = Vec::new();
    let mut kept: Vec<String> = Vec::new();
    let mut kept_bytes = 0u64;
    while pos > 0 && len - pos < io_cap && kept_bytes < budget {
        let start = pos.saturating_sub(TAIL_CHUNK_BYTES);
        let mut chunk = vec![0u8; (pos - start) as usize];
        file.seek(SeekFrom::Start(start))?;
        file.read_exact(&mut chunk)?;
        chunk.extend_from_slice(&carry);
        pos = start;
        // Before the file's start, the first line of a chunk may continue in the previous one.
        let complete_from = if pos == 0 {
            0
        } else {
            match chunk.iter().position(|byte| *byte == b'\n') {
                Some(newline) => newline + 1,
                None => {
                    carry = chunk;
                    continue;
                }
            }
        };
        carry = chunk[..complete_from].to_vec();
        for line in chunk[complete_from..].rsplit(|byte| *byte == b'\n') {
            if line.is_empty() {
                continue;
            }
            let text = compact_claude_line(line);
            if text.is_empty() {
                continue;
            }
            kept_bytes += text.len() as u64 + 1;
            kept.push(text);
            if kept_bytes >= budget {
                break;
            }
        }
    }
    kept.reverse();
    Ok(kept.join("\n"))
}

fn compact_claude_line(line: &[u8]) -> String {
    if line.len() <= COMPACT_LINE_BYTES {
        return String::from_utf8_lossy(line).into_owned();
    }
    fn strip_images(value: &mut Value) {
        match value {
            Value::Object(map) => {
                if map.get("type").and_then(Value::as_str) == Some("image") {
                    map.remove("source");
                }
                map.values_mut().for_each(strip_images);
            }
            Value::Array(items) => items.iter_mut().for_each(strip_images),
            _ => {}
        }
    }
    let Ok(mut value) = serde_json::from_slice::<Value>(line) else {
        return String::new();
    };
    strip_images(&mut value);
    value.to_string()
}

/// Read identity and content from the same open file. A changed/invalid Pi
/// header still permits display, but cannot establish an offline archive key.
/// Call only with a transcript returned by the owned-session resolver.
pub fn read_canonical_tail(
    resolved: &crate::transcript::ResolvedTranscript,
    max_bytes: u64,
) -> std::io::Result<(String, Option<String>)> {
    let mut file = File::open(resolved.path())?;
    let before = read_conversation_id(resolved, &mut file)?;
    let content = match resolved {
        crate::transcript::ResolvedTranscript::Claude(_) => {
            read_compacted_tail(&mut file, max_bytes, CLAUDE_TAIL_IO_CAP)?
        }
        _ => read_tail_from(&mut file, max_bytes)?,
    };
    let after = read_conversation_id(resolved, &mut file)?;
    Ok((content, if before == after { before } else { None }))
}

fn read_conversation_id(
    resolved: &crate::transcript::ResolvedTranscript,
    file: &mut File,
) -> std::io::Result<Option<String>> {
    if matches!(resolved, crate::transcript::ResolvedTranscript::Agy(_)) {
        return Ok(resolved.conversation_id(None));
    }
    const MAX_HEADER_BYTES: u64 = 16 * 1024;
    file.seek(SeekFrom::Start(0))?;
    let mut header = Vec::new();
    BufReader::new(file.take(MAX_HEADER_BYTES + 1)).read_until(b'\n', &mut header)?;
    if header.len() as u64 > MAX_HEADER_BYTES {
        return Ok(None);
    }
    Ok(std::str::from_utf8(&header)
        .ok()
        .and_then(|line| resolved.conversation_id(Some(line))))
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
                            images: None,
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
                                    images: None,
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

/// Slash-command bookkeeping Claude Code writes as user text; never a person's message.
fn claude_command_text(text: &str) -> Option<String> {
    let trimmed = text.trim();
    if trimmed.starts_with("<local-command-") || trimmed.starts_with("<command-message>") {
        return Some(String::new());
    }
    let name = trimmed
        .strip_prefix("<command-name>")?
        .split("</command-name>")
        .next()?;
    let args = trimmed
        .split("<command-args>")
        .nth(1)
        .and_then(|rest| rest.split("</command-args>").next())
        .unwrap_or("")
        .trim();
    Some(if args.is_empty() {
        name.to_string()
    } else {
        format!("{name} {args}")
    })
}

/// Images are never sent to the phone; the count lets the step say one came back.
fn claude_tool_result_images(content: &Value) -> u32 {
    content.as_array().map_or(0, |blocks| {
        blocks
            .iter()
            .filter(|block| block["type"].as_str() == Some("image"))
            .count() as u32
    })
}

fn claude_tool_result_text(content: &Value) -> String {
    match content {
        Value::String(text) => text.clone(),
        Value::Array(blocks) => blocks
            .iter()
            .filter(|block| block["type"].as_str() == Some("text"))
            .filter_map(|block| block["text"].as_str())
            .collect::<Vec<_>>()
            .join("\n"),
        _ => String::new(),
    }
}

/// Parse a Claude Code session (`<config>/projects/<project>/<session>.jsonl`).
/// One agent turn spans everything between a person's messages; subagent sidechains,
/// meta records and hook bookkeeping are skipped.
pub fn parse_claude_transcript(content: &str) -> Vec<ConversationTurn> {
    let mut turns: Vec<ConversationTurn> = Vec::new();
    let mut tool_calls = std::collections::HashMap::new();

    fn push_user(turns: &mut Vec<ConversationTurn>, text: String, timestamp: Option<String>) {
        if text.trim().is_empty() {
            return;
        }
        let id = format!("t-{:03}", turns.len() + 1);
        turns.push(ConversationTurn {
            id,
            role: "user".to_string(),
            timestamp,
            text: Some(text.trim().to_string()),
            parts: Vec::new(),
        });
    }

    for line in content.lines() {
        let Ok(v) = serde_json::from_str::<Value>(line.trim()) else {
            continue;
        };
        if v["isSidechain"].as_bool() == Some(true) || v["isMeta"].as_bool() == Some(true) {
            continue;
        }
        let timestamp = v["timestamp"].as_str().map(str::to_string);
        match v["type"].as_str().unwrap_or("") {
            // A message typed while the agent was working, delivered into the running turn.
            "attachment" => {
                let attachment = &v["attachment"];
                if attachment["type"].as_str() == Some("queued_command")
                    && attachment["humanTurn"].as_bool() == Some(true)
                {
                    let text = attachment["prompt"].as_str().unwrap_or("").to_string();
                    push_user(&mut turns, text, timestamp);
                }
            }
            "user" => match &v["message"]["content"] {
                Value::String(text) => {
                    let text = claude_command_text(text).unwrap_or_else(|| text.clone());
                    push_user(&mut turns, text, timestamp);
                }
                Value::Array(blocks) => {
                    let text = blocks
                        .iter()
                        .filter(|block| block["type"].as_str() == Some("text"))
                        .filter_map(|block| block["text"].as_str())
                        .collect::<Vec<_>>()
                        .join("\n");
                    push_user(&mut turns, text, timestamp.clone());
                    for block in blocks
                        .iter()
                        .filter(|block| block["type"].as_str() == Some("tool_result"))
                    {
                        let Some(id) = block["tool_use_id"].as_str() else {
                            continue;
                        };
                        // An unmatched result never attaches to another tool.
                        let Some((turn_index, part_index)) = tool_calls.remove(id) else {
                            continue;
                        };
                        if let Some(ConversationPart::ToolCall {
                            output,
                            is_error,
                            finished_at,
                            images,
                            ..
                        }) = turns
                            .get_mut(turn_index)
                            .and_then(|turn: &mut ConversationTurn| turn.parts.get_mut(part_index))
                        {
                            *output = Some(claude_tool_result_text(&block["content"]));
                            *is_error = block["is_error"].as_bool();
                            *finished_at = timestamp.clone();
                            let count = claude_tool_result_images(&block["content"]);
                            *images = (count > 0).then_some(count);
                        }
                    }
                }
                _ => {}
            },
            "assistant" => {
                let mut parts = Vec::new();
                let next_part = turns
                    .last()
                    .filter(|turn| turn.role == "agent")
                    .map_or(0, |turn| turn.parts.len());
                for block in v["message"]["content"].as_array().into_iter().flatten() {
                    match block["type"].as_str().unwrap_or("") {
                        "text" => {
                            if let Some(t) = block["text"].as_str().filter(|t| !t.trim().is_empty())
                            {
                                parts.push(ConversationPart::Text {
                                    text: t.trim().to_string(),
                                });
                            }
                        }
                        "thinking" => {
                            if let Some(t) =
                                block["thinking"].as_str().filter(|t| !t.trim().is_empty())
                            {
                                parts.push(ConversationPart::Thinking {
                                    text: t.trim().to_string(),
                                });
                            }
                        }
                        "tool_use" => {
                            if let Some(id) = block["id"].as_str() {
                                let turn_index = if next_part > 0 {
                                    turns.len() - 1
                                } else {
                                    turns.len()
                                };
                                tool_calls
                                    .insert(id.to_string(), (turn_index, next_part + parts.len()));
                            }
                            parts.push(ConversationPart::ToolCall {
                                name: block["name"].as_str().unwrap_or("tool").to_string(),
                                input: block["input"].clone(),
                                output: None,
                                is_error: None,
                                started_at: timestamp.clone(),
                                finished_at: None,
                                images: None,
                            });
                        }
                        _ => {}
                    }
                }
                if v["isApiErrorMessage"].as_bool() == Some(true) {
                    parts.push(ConversationPart::ResponseStatus {
                        status: "failed".to_string(),
                        detail: None,
                    });
                }
                if parts.is_empty() {
                    continue;
                }
                match turns.last_mut().filter(|turn| turn.role == "agent") {
                    Some(turn) => turn.parts.extend(parts),
                    None => {
                        let id = format!("t-{:03}", turns.len() + 1);
                        turns.push(ConversationTurn {
                            id,
                            role: "agent".to_string(),
                            timestamp,
                            text: None,
                            parts,
                        });
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
    fn claude_transcript_groups_a_turn_and_attaches_results_by_id() {
        let turns = parse_claude_transcript(include_str!("../../../fixtures/claude/session.jsonl"));
        let roles: Vec<_> = turns.iter().map(|t| t.role.as_str()).collect();
        assert_eq!(roles, ["user", "user", "agent", "user", "agent"]);
        assert_eq!(turns[0].text.as_deref(), Some("/effort"));
        assert_eq!(turns[1].text.as_deref(), Some("Fix the failing test"));
        assert_eq!(turns[3].text.as_deref(), Some("also add a regression test"));

        // Thinking and the split tool_use line join one agent turn; the result attaches by ID.
        match turns[2].parts.as_slice() {
            [ConversationPart::Thinking { text }, ConversationPart::ToolCall {
                name,
                output,
                is_error,
                started_at,
                finished_at,
                ..
            }] => {
                assert_eq!(text, "Check the test first.");
                assert_eq!(name, "Bash");
                assert_eq!(output.as_deref(), Some("1 failed"));
                assert_eq!(*is_error, Some(true));
                assert_eq!(started_at.as_deref(), Some("2026-10-12T10:00:05Z"));
                assert_eq!(finished_at.as_deref(), Some("2026-10-12T10:00:08Z"));
            }
            other => panic!("unexpected parts {other:?}"),
        }

        // Sidechains are skipped, array results are joined, an orphan result attaches nowhere.
        // An image-only result has no text and is counted instead.
        match turns[4].parts.as_slice() {
            [ConversationPart::ToolCall {
                output,
                is_error,
                images: edit_images,
                ..
            }, ConversationPart::ToolCall {
                name: read_name,
                output: read_output,
                images,
                ..
            }, ConversationPart::Text { text }, ConversationPart::Text { .. }, ConversationPart::ResponseStatus { status, .. }] =>
            {
                assert_eq!(output.as_deref(), Some("edited"));
                assert_eq!(*is_error, None);
                assert_eq!(*edit_images, None);
                assert_eq!(read_name, "Read");
                assert_eq!(read_output.as_deref(), Some(""));
                assert_eq!(*images, Some(1));
                assert_eq!(text, "Fixed, with a regression test.");
                assert_eq!(status, "failed");
            }
            other => panic!("unexpected parts {other:?}"),
        }
    }

    #[test]
    fn canonical_identity_is_stable_on_append_but_changes_when_session_is_replaced() {
        use crate::transcript::ResolvedTranscript;
        let dir = std::env::temp_dir().join(format!(
            "cappuccino-identity-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("session.jsonl");
        let first = "{\"type\":\"session\",\"id\":\"session-one\"}\n";
        std::fs::write(&path, first).unwrap();
        let resolved = ResolvedTranscript::Pi(path.clone());
        let (_, initial) = read_canonical_tail(&resolved, 64).unwrap();
        let initial = initial.unwrap();
        assert!(initial.starts_with("pi:"));
        assert_eq!(initial.len(), 67);
        assert!(!initial.contains("session-one"));
        assert!(!initial.contains(dir.to_str().unwrap()));
        std::fs::write(&path, format!("{first}{}\n", "x".repeat(512))).unwrap();
        assert_eq!(
            read_canonical_tail(&resolved, 64).unwrap().1,
            Some(initial.clone())
        );
        std::fs::write(&path, "{\"type\":\"session\",\"id\":\"session-two\"}\n").unwrap();
        assert_ne!(read_canonical_tail(&resolved, 64).unwrap().1, Some(initial));
        std::fs::write(
            &path,
            format!(
                "{{\"type\":\"session\",\"id\":\"session-three\",\"cwd\":\"{}\"}}\n",
                "x".repeat(16 * 1024)
            ),
        )
        .unwrap();
        assert_eq!(read_canonical_tail(&resolved, 64).unwrap().1, None);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn canonical_id_requires_real_provider_identity_and_never_a_locator() {
        use crate::transcript::ResolvedTranscript;
        let pi = ResolvedTranscript::Pi("/unused/session.jsonl".into());
        for header in [
            None,
            Some("{}"),
            Some("{\"type\":\"message\",\"id\":\"pane\"}"),
            Some("{\"type\":\"session\",\"id\":\"\"}"),
            Some("{\"type\":\"session\",\"id\":\" bad \"}"),
        ] {
            assert_eq!(pi.conversation_id(header), None);
        }
        let agy = ResolvedTranscript::Agy(
            "/brain/session-one/.system_generated/logs/transcript.jsonl".into(),
        );
        let agy_id = agy.conversation_id(None).unwrap();
        assert!(agy_id.starts_with("agy:"));
        assert_ne!(
            agy_id,
            pi.conversation_id(Some("{\"type\":\"session\",\"id\":\"session-one\"}"))
                .unwrap()
        );
        assert_eq!(
            ResolvedTranscript::Agy("/brain/session-one/guessed.jsonl".into())
                .conversation_id(None),
            None
        );
    }

    #[test]
    fn legacy_conversation_response_has_no_claimed_canonical_id() {
        let legacy: ConversationResponse = serde_json::from_str(
            "{\"session_id\":\"pane\",\"source\":\"scrollback\",\"turns\":[]}",
        )
        .unwrap();
        assert_eq!(legacy.conversation_id, None);
        assert!(!serde_json::to_value(legacy)
            .unwrap()
            .as_object()
            .unwrap()
            .contains_key("conversation_id"));
    }

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

        let tail =
            read_canonical_tail(&crate::transcript::ResolvedTranscript::Pi(path.clone()), 50)
                .unwrap()
                .0;
        assert!(tail.contains("line 999"));
        assert!(!tail.contains("line 0"));

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn claude_tail_spends_its_budget_on_text_not_inline_images() {
        let dir = std::env::temp_dir().join(format!("test-claude-tail-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("session.jsonl");
        let image = serde_json::json!({"type": "user", "message": {"role": "user", "content": [
            {"type": "tool_result", "tool_use_id": "t1", "content": [
                {"type": "image", "source": {"type": "base64", "data": "A".repeat(600_000)}}]}]}});
        let lines = [
            r#"{"type":"user","message":{"role":"user","content":"early question"}}"#.to_string(),
            image.to_string(),
            image.to_string(),
            r#"{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"late answer"}]}}"#.to_string(),
        ];
        std::fs::write(&path, lines.join("\n") + "\n").unwrap();
        let mut file = File::open(&path).unwrap();

        // 1.2 MB of images behind a 4 KB budget: both texts survive, no image data does.
        let tail = read_compacted_tail(&mut file, 4 * 1024, CLAUDE_TAIL_IO_CAP).unwrap();
        assert!(tail.contains("early question") && tail.contains("late answer"));
        assert!(!tail.contains("AAAA"));
        assert!(tail.len() < 4 * 1024);
        assert_eq!(tail.lines().count(), 4);

        // The read cap holds even when the budget is not yet spent.
        let capped = read_compacted_tail(&mut file, 4 * 1024, 700_000).unwrap();
        assert!(capped.contains("late answer") && !capped.contains("early question"));

        let _ = std::fs::remove_dir_all(&dir);
    }
}
