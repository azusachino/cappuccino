//! HTTP/WS routes. Every handler is token-gated; the WS stream polls
//! `pane.read` and emits wire-v0-shaped events with the slice-A
//! reconciliation semantics.

use crate::{agents, conversation, herdr, prompt, reconcile, transcript};
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Json, Path as AxumPath, Query, State};
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use serde_json::json;
use std::collections::HashMap;
use std::sync::Arc;

pub struct BridgeState {
    pub machine_id: String,
    pub machine_name: Option<String>,
}

impl BridgeState {
    pub fn new(machine_id: String) -> Self {
        BridgeState {
            machine_id,
            machine_name: host_name(),
        }
    }
}

fn host_name() -> Option<String> {
    let mut bytes = [0u8; 256];
    // SAFETY: gethostname receives a writable buffer and its exact capacity.
    if unsafe { libc::gethostname(bytes.as_mut_ptr().cast(), bytes.len()) } != 0 {
        return None;
    }
    let end = bytes.iter().position(|byte| *byte == 0)?;
    let name = std::str::from_utf8(&bytes[..end]).ok()?.trim();
    if name.is_empty() {
        None
    } else {
        Some(name.to_string())
    }
}

pub fn error_response(status: StatusCode, code: &str, message: &str) -> Response {
    (
        status,
        [(header::CONTENT_TYPE, "application/json")],
        json!({"event": "error", "code": code, "message": message}).to_string(),
    )
        .into_response()
}

pub async fn session(State(state): State<Arc<BridgeState>>) -> Response {
    (
        StatusCode::OK,
        [(header::CONTENT_TYPE, "application/json")],
        json!({
            "event": "paired",
            "machine_id": state.machine_id,
            "machine_name": state.machine_name,
            "protocol": 1,
            "plugin": "cappuccino-bridge",
            "bridge_version": env!("CARGO_PKG_VERSION"),
            // Endpoint support, not a promise that every agent can use it or is healthy.
            "capabilities": {
                "agent_discovery": true,
                "live_output": true,
                "conversation_history": true,
                "prompt_input": true,
                "prompt_answers": true,
                "agent_control": true,
                "completion_signals": true,
                "telegram_notifications": false,
                "telegram_notifications_configured": false,
            },
        })
        .to_string(),
    )
        .into_response()
}

pub async fn agents(State(state): State<Arc<BridgeState>>) -> Response {
    match herdr::agent_list().await {
        Ok(result) => match agents::map_agents(&result, &state.machine_id) {
            Ok(rows) => (
                StatusCode::OK,
                [(header::CONTENT_TYPE, "application/json")],
                json!({
                    "event": "agents",
                    "machine_id": state.machine_id,
                    "agents": rows.iter().map(|row| row.to_json()).collect::<Vec<_>>(),
                })
                .to_string(),
            )
                .into_response(),
            Err(message) => error_response(StatusCode::BAD_GATEWAY, "protocol", &message),
        },
        Err(error) => error_response(
            StatusCode::BAD_GATEWAY,
            "herdr_unreachable",
            &error.to_string(),
        ),
    }
}

pub async fn transcript(
    State(_state): State<Arc<BridgeState>>,
    Query(query): Query<HashMap<String, String>>,
) -> Response {
    let Some(session) = query.get("session").filter(|session| !session.is_empty()) else {
        return error_response(
            StatusCode::BAD_REQUEST,
            "bad_request",
            "session is required",
        );
    };
    match transcript::resolve_transcript(session).await {
        Ok(path) => (
            StatusCode::OK,
            [(header::CONTENT_TYPE, "application/json")],
            transcript::transcript_json(session, path.as_deref()).to_string(),
        )
            .into_response(),
        Err(message) => error_response(StatusCode::BAD_GATEWAY, "protocol", &message),
    }
}

/// WS /api/stream?session=<locator>
pub async fn stream(
    State(state): State<Arc<BridgeState>>,
    Query(query): Query<HashMap<String, String>>,
    upgrade: WebSocketUpgrade,
) -> Response {
    let Some(session) = query.get("session").cloned() else {
        return error_response(
            StatusCode::BAD_REQUEST,
            "bad_request",
            "session is required",
        );
    };
    upgrade.on_upgrade(move |socket| stream_socket(state, session, socket))
}

async fn stream_socket(state: Arc<BridgeState>, session: String, mut socket: WebSocket) {
    // Resolve the locator to a pane id: exact `herdr agent list` parity means
    // the locator can be a name or a pane id.
    let pane_id = match resolve_pane(&state, &session).await {
        Some(pane_id) => pane_id,
        None => {
            let _ = socket
                .send(Message::Text(
                    json!({"event": "error", "code": "not_found",
                           "message": format!("no agent with session_id {session}")})
                    .to_string()
                    .into(),
                ))
                .await;
            return;
        }
    };
    let initial_branch = herdr::agent_get(&session)
        .await
        .ok()
        .and_then(|info| info["agent"]["cwd"].as_str().map(str::to_string))
        .and_then(|cwd| agents::own_branch(&cwd).branch);
    let mut ring = reconcile::StreamRing::new(initial_branch);
    let mut generation = ring.generation;
    let mut last_status: Option<(String, Option<String>)> = None;
    let mut last_prompt_id: Option<String> = None;

    if socket
        .send(Message::Text(
            json!({"event": "stream_open", "session_id": session, "generation": generation})
                .to_string()
                .into(),
        ))
        .await
        .is_err()
    {
        return;
    }

    // Poll loop: read-only pane reads every 400 ms. The trailing line is only
    // confirmed while the agent is idle or already superseded.
    loop {
        tokio::time::sleep(std::time::Duration::from_millis(400)).await;
        let text = match herdr::pane_read_recent(&pane_id, 2000).await {
            Ok(text) => text,
            Err(_) => continue,
        };
        let mut lines: Vec<String> = text.lines().map(|line| line.to_string()).collect();
        while lines.last().is_some_and(|last| last.is_empty()) {
            lines.pop();
        }
        let agent = herdr::agent_get(&session).await.ok();
        if let Some(cwd) = agent
            .as_ref()
            .and_then(|info| info["agent"]["cwd"].as_str())
        {
            let branch = agents::own_branch(cwd).branch;
            ring.set_branch(branch);
        }
        let current_status_str = agent
            .as_ref()
            .and_then(|info| info["agent"]["agent_status"].as_str())
            .unwrap_or("idle");
        let working = current_status_str == "working";

        // Extract detail line if available (e.g. running command or thinking detail)
        let status_detail = if working {
            lines.iter().rev().find_map(|line| {
                let trimmed = line.trim();
                if trimmed.starts_with("working ·") || trimmed.starts_with("thinking ·") {
                    Some(trimmed.to_string())
                } else if trimmed.starts_with("●") || trimmed.starts_with("⢿") {
                    Some(trimmed.to_string())
                } else {
                    None
                }
            })
        } else {
            None
        };

        let status_pair = (current_status_str.to_string(), status_detail);
        if last_status.as_ref() != Some(&status_pair) {
            last_status = Some(status_pair.clone());
            let status_msg = json!({
                "event": "agent_status",
                "session_id": session,
                "state": status_pair.0,
                "detail": status_pair.1,
            });
            let _ = socket
                .send(Message::Text(status_msg.to_string().into()))
                .await;
        }

        // Check for active interactive prompt (tool approval or ask_question)
        let active_prompt = prompt::parse_prompt_from_screen(&text);
        if let Some(card) = &active_prompt {
            if last_prompt_id.as_deref() != Some(&card.prompt_id) {
                last_prompt_id = Some(card.prompt_id.clone());
                let prompt_msg = json!({
                    "event": "prompt_request",
                    "session_id": session,
                    "prompt": card,
                });
                let _ = socket
                    .send(Message::Text(prompt_msg.to_string().into()))
                    .await;
            }
        } else if last_prompt_id.is_some() {
            let prompt_id = last_prompt_id.take().unwrap();
            let resolved_msg = json!({
                "event": "prompt_resolved",
                "session_id": session,
                "prompt_id": prompt_id,
            });
            let _ = socket
                .send(Message::Text(resolved_msg.to_string().into()))
                .await;
        }

        let new_entries = ring.ingest(&reconcile::PaneSnapshot { lines }, !working);
        if ring.generation != generation {
            if socket
                .send(Message::Text(
                    json!({"event": "stream_reset", "generation": ring.generation})
                        .to_string()
                        .into(),
                ))
                .await
                .is_err()
            {
                return;
            }
            // Acknowledge the observed reset so a stable generation does not
            // re-emit stream_reset on every subsequent poll.
            generation = ring.generation;
        }
        if !new_entries.is_empty() {
            let event = json!({"event": "entries", "entries": new_entries});
            if socket
                .send(Message::Text(event.to_string().into()))
                .await
                .is_err()
            {
                return;
            }
        }
    }
}

async fn resolve_pane(state: &Arc<BridgeState>, session: &str) -> Option<String> {
    let result = herdr::agent_list().await.ok()?;
    let rows = agents::map_agents(&result, &state.machine_id).ok()?;
    rows.into_iter()
        .find(|row| row.session_id == session || row.pane_id == session)
        .map(|row| row.pane_id)
}

/// GET /api/agents/{sessionId}/conversation
pub async fn agent_conversation(
    State(state): State<Arc<BridgeState>>,
    AxumPath(session_id): AxumPath<String>,
) -> Response {
    if session_id.is_empty() {
        return error_response(
            StatusCode::BAD_REQUEST,
            "bad_request",
            "sessionId is required",
        );
    }

    match transcript::resolve_session_transcript(&session_id).await {
        Ok(Some(resolved)) => {
            match conversation::read_canonical_tail(&resolved, conversation::TRANSCRIPT_TAIL_BYTES)
            {
                Ok((content, conversation_id)) => {
                    let turns = match resolved {
                        transcript::ResolvedTranscript::Pi(_) => {
                            conversation::parse_pi_transcript(&content)
                        }
                        transcript::ResolvedTranscript::Claude(_) => {
                            conversation::parse_claude_transcript(&content)
                        }
                        transcript::ResolvedTranscript::Agy(_) => {
                            conversation::parse_agy_transcript(&content)
                        }
                    };
                    let payload = conversation::ConversationResponse {
                        session_id,
                        source: "canonical_log".to_string(),
                        machine_id: Some(state.machine_id.clone()),
                        conversation_id,
                        turns,
                    };
                    (
                        StatusCode::OK,
                        [(header::CONTENT_TYPE, "application/json")],
                        serde_json::to_string(&payload).unwrap_or_default(),
                    )
                        .into_response()
                }
                Err(err) => error_response(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "io_error",
                    &format!("cannot read transcript: {err}"),
                ),
            }
        }
        Ok(None) => {
            // Fallback: If no canonical session transcript exists, check if pane exists and parse recent scrollback
            if let Some(pane_id) = resolve_pane(&state, &session_id).await {
                match herdr::pane_read_recent(&pane_id, 200).await {
                    Ok(text) => {
                        let lines: Vec<String> = text.lines().map(str::to_string).collect();
                        let turns = conversation::parse_scrollback_turns(&lines);
                        let payload = conversation::ConversationResponse {
                            session_id,
                            source: "scrollback".to_string(),
                            machine_id: Some(state.machine_id.clone()),
                            conversation_id: None,
                            turns,
                        };
                        (
                            StatusCode::OK,
                            [(header::CONTENT_TYPE, "application/json")],
                            serde_json::to_string(&payload).unwrap_or_default(),
                        )
                            .into_response()
                    }
                    Err(err) => {
                        error_response(StatusCode::BAD_GATEWAY, "herdr_error", &err.to_string())
                    }
                }
            } else {
                error_response(
                    StatusCode::NOT_FOUND,
                    "not_found",
                    &format!("no agent with session_id {session_id}"),
                )
            }
        }
        Err(err) => error_response(StatusCode::BAD_GATEWAY, "protocol", &err),
    }
}

/// The command an agent itself uses to start a fresh conversation in the same process.
pub fn new_session_command(agent: &str) -> Option<&'static str> {
    match agent {
        "pi" => Some("/new"),
        "claude" | "agy" | "antigravity" | "antigravity-cli" => Some("/clear"),
        _ => None,
    }
}

#[derive(Debug, serde::Deserialize)]
pub struct ControlRequest {
    pub action: String,
}

/// POST /api/agents/{sessionId}/control: the owner's own controls over a running agent. `interrupt`
/// presses Esc to stop the current run; `new_session` types the agent's own new-conversation
/// command. Neither starts nor ends a process.
pub async fn control_agent(
    State(state): State<Arc<BridgeState>>,
    AxumPath(session_id): AxumPath<String>,
    Json(request): Json<ControlRequest>,
) -> Response {
    if !matches!(request.action.as_str(), "interrupt" | "new_session") {
        return error_response(
            StatusCode::BAD_REQUEST,
            "bad_request",
            "unknown control action",
        );
    }
    let rows = match herdr::agent_list().await {
        Ok(result) => agents::map_agents(&result, &state.machine_id).unwrap_or_default(),
        Err(err) => {
            return error_response(StatusCode::BAD_GATEWAY, "herdr_error", &err.to_string())
        }
    };
    let Some(row) = rows
        .into_iter()
        .find(|row| row.session_id == session_id || row.pane_id == session_id)
    else {
        return error_response(
            StatusCode::NOT_FOUND,
            "not_found",
            &format!("no agent with session_id {session_id}"),
        );
    };
    let sent = match request.action.as_str() {
        "interrupt" => herdr::pane_send_keys(&row.pane_id, &["esc"]).await,
        _ => match new_session_command(&row.agent) {
            Some(command) => herdr::agent_prompt(&row.pane_id, command).await,
            None => {
                return error_response(
                    StatusCode::BAD_REQUEST,
                    "unsupported",
                    &format!("no new-session command known for {}", row.agent),
                )
            }
        },
    };
    match sent {
        Ok(_) => (
            StatusCode::OK,
            [(header::CONTENT_TYPE, "application/json")],
            json!({"event": "control_sent", "action": request.action, "session_id": session_id})
                .to_string(),
        )
            .into_response(),
        Err(err) => error_response(StatusCode::BAD_GATEWAY, "herdr_error", &err.to_string()),
    }
}

/// POST /api/agents/{sessionId}/prompt
pub async fn submit_agent_prompt(
    State(state): State<Arc<BridgeState>>,
    AxumPath(session_id): AxumPath<String>,
    Json(submission): Json<prompt::PromptSubmission>,
) -> Response {
    let pane_id = match resolve_pane(&state, &session_id).await {
        Some(pane_id) => pane_id,
        None => {
            return error_response(
                StatusCode::NOT_FOUND,
                "not_found",
                &format!("no agent with session_id {session_id}"),
            );
        }
    };

    match submission {
        prompt::PromptSubmission::Prompt { text } => {
            if text.trim().is_empty() {
                return error_response(
                    StatusCode::BAD_REQUEST,
                    "bad_request",
                    "prompt text cannot be empty",
                );
            }
            match herdr::agent_prompt(&session_id, &text).await {
                Ok(_) => (
                    StatusCode::OK,
                    [(header::CONTENT_TYPE, "application/json")],
                    json!({"event": "prompt_sent", "session_id": session_id}).to_string(),
                )
                    .into_response(),
                Err(err) => error_response(
                    StatusCode::BAD_GATEWAY,
                    "herdr_error",
                    &format!("cannot deliver prompt: {err}"),
                ),
            }
        }
        prompt::PromptSubmission::AnswerPrompt {
            prompt_id,
            action,
            option_index,
            option_id,
        } => {
            let active_screen = match herdr::pane_read_recent(&pane_id, 30).await {
                Ok(text) => text,
                Err(err) => {
                    return error_response(
                        StatusCode::BAD_GATEWAY,
                        "herdr_error",
                        &err.to_string(),
                    );
                }
            };

            let Some(card) = prompt::parse_prompt_from_screen(&active_screen) else {
                return error_response(
                    StatusCode::CONFLICT,
                    "stale_prompt",
                    "interactive prompt is no longer visible on screen",
                );
            };

            if card.prompt_id != prompt_id {
                return error_response(
                    StatusCode::CONFLICT,
                    "stale_prompt",
                    &format!(
                        "prompt id mismatch: expected {}, on screen {}",
                        prompt_id, card.prompt_id
                    ),
                );
            }

            // Determine key to send
            if action.as_deref() == Some("cancel") {
                let _ = herdr::pane_send_keys(&pane_id, &["esc"]).await;
                return (
                    StatusCode::OK,
                    [(header::CONTENT_TYPE, "application/json")],
                    json!({"event": "prompt_answered", "action": "canceled", "prompt_id": prompt_id}).to_string(),
                )
                    .into_response();
            }

            let opt_idx = if let Some(idx) = option_index {
                idx
            } else if let Some(ref id) = option_id {
                card.options.iter().position(|o| o.id == *id).unwrap_or(0)
            } else {
                0
            };

            // If numbered choice (1, 2, 3), press digit + enter
            let key_digit = format!("{}", opt_idx + 1);
            let send_res = herdr::pane_send_keys(&pane_id, &[&key_digit, "enter"]).await;
            match send_res {
                Ok(_) => (
                    StatusCode::OK,
                    [(header::CONTENT_TYPE, "application/json")],
                    json!({
                        "event": "prompt_answered",
                        "prompt_id": prompt_id,
                        "selected_index": opt_idx,
                    })
                    .to_string(),
                )
                    .into_response(),
                Err(err) => error_response(
                    StatusCode::BAD_GATEWAY,
                    "herdr_error",
                    &format!("cannot send keys: {err}"),
                ),
            }
        }
        prompt::PromptSubmission::AnswerQuestion { prompt_id, answers } => {
            // For ask_question answers submitted as structured JSON or text
            let answer_text = if let Some(s) = answers.as_str() {
                s.to_string()
            } else {
                answers.to_string()
            };
            match herdr::pane_send_text(&pane_id, &format!("{answer_text}\n")).await {
                Ok(_) => (
                    StatusCode::OK,
                    [(header::CONTENT_TYPE, "application/json")],
                    json!({"event": "question_answered", "prompt_id": prompt_id}).to_string(),
                )
                    .into_response(),
                Err(err) => error_response(
                    StatusCode::BAD_GATEWAY,
                    "herdr_error",
                    &format!("cannot send answer: {err}"),
                ),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_session_uses_each_agents_own_command() {
        assert_eq!(new_session_command("pi"), Some("/new"));
        assert_eq!(new_session_command("claude"), Some("/clear"));
        assert_eq!(new_session_command("agy"), Some("/clear"));
        assert_eq!(new_session_command("antigravity-cli"), Some("/clear"));
        assert_eq!(new_session_command("codex"), None);
    }

    #[tokio::test]
    async fn machine_name_is_additive_metadata_not_machine_identity() {
        let mut state = BridgeState::new("stable-machine-id".to_string());
        state.machine_name = Some("Owner's Mac".to_string());
        let response = session(State(Arc::new(state))).await;
        let body = axum::body::to_bytes(response.into_body(), 4096)
            .await
            .unwrap();
        let value: serde_json::Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(value["machine_name"], "Owner's Mac");
        assert_eq!(value["machine_id"], "stable-machine-id");
        assert_eq!(value["protocol"], 1);
        assert_eq!(value["bridge_version"], env!("CARGO_PKG_VERSION"));
        assert_eq!(value["capabilities"]["agent_discovery"], true);
        assert_eq!(value["capabilities"]["live_output"], true);
        assert_eq!(value["capabilities"]["conversation_history"], true);
        assert_eq!(value["capabilities"]["prompt_input"], true);
        assert_eq!(value["capabilities"]["prompt_answers"], true);
        assert_eq!(value["capabilities"]["agent_control"], true);
    }
}
