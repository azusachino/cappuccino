//! HTTP/WS routes. Every handler is token-gated; the WS stream polls
//! `pane.read` and emits wire-v0-shaped events with the slice-A
//! reconciliation semantics.

use crate::{agents, conversation, herdr, prompt, questions, reconcile, stats, transcript};
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Json, Path as AxumPath, Query, State};
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use serde_json::json;
use std::collections::HashMap;
use std::sync::Arc;

/// How often an idle stream checks that the phone is still there.
const STREAM_PING_INTERVAL: std::time::Duration = std::time::Duration::from_secs(30);

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
    // confirmed while the agent is idle or already superseded. The socket is
    // read too, and pinged, so a closed or vanished phone ends the loop even
    // when the pane is quiet and nothing else would be sent.
    let mut last_ping = tokio::time::Instant::now();
    let mut poll = tokio::time::interval(std::time::Duration::from_millis(400));
    poll.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    poll.tick().await;
    loop {
        tokio::select! {
            _ = poll.tick() => {}
            incoming = socket.recv() => match incoming {
                Some(Ok(Message::Close(_))) | Some(Err(_)) | None => return,
                Some(Ok(_)) => continue,
            },
        }
        if last_ping.elapsed() >= STREAM_PING_INTERVAL {
            last_ping = tokio::time::Instant::now();
            if socket.send(Message::Ping(Vec::new().into())).await.is_err() {
                return;
            }
        }
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
            if socket
                .send(Message::Text(status_msg.to_string().into()))
                .await
                .is_err()
            {
                return;
            }
        }

        // Check for active interactive prompt (tool approval or a question).
        let active_prompt = prompt::shown(&pane_id, prompt::screen_card(&text));
        if let Some(card) = &active_prompt {
            if last_prompt_id.as_deref() != Some(&card.prompt_id) {
                last_prompt_id = Some(card.prompt_id.clone());
                let prompt_msg = json!({
                    "event": "prompt_request",
                    "session_id": session,
                    "prompt": card,
                });
                if socket
                    .send(Message::Text(prompt_msg.to_string().into()))
                    .await
                    .is_err()
                {
                    return;
                }
            }
        } else if last_prompt_id.is_some() {
            let prompt_id = last_prompt_id.take().unwrap();
            let resolved_msg = json!({
                "event": "prompt_resolved",
                "session_id": session,
                "prompt_id": prompt_id,
            });
            if socket
                .send(Message::Text(resolved_msg.to_string().into()))
                .await
                .is_err()
            {
                return;
            }
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

/// Spends the answered card's id before anything is typed; a racing or repeated answer is stale.
fn claimed(pane_id: &str, prompt_id: &str) -> Option<Response> {
    (!prompt::claim(pane_id, prompt_id)).then(|| {
        error_response(
            StatusCode::CONFLICT,
            "stale_prompt",
            "this prompt was already answered",
        )
    })
}

async fn resolve_pane(state: &Arc<BridgeState>, session: &str) -> Option<String> {
    let result = herdr::agent_list().await.ok()?;
    let rows = agents::map_agents(&result, &state.machine_id).ok()?;
    rows.into_iter()
        .find(|row| row.session_id == session || row.pane_id == session)
        .map(|row| row.pane_id)
}

/// The question form on screen while `prompt_id` still names it, untouched by the terminal.
async fn current_form(pane_id: &str, prompt_id: &str) -> Result<questions::Form, Response> {
    let stale = |message: &str| error_response(StatusCode::CONFLICT, "stale_prompt", message);
    let screen = herdr::pane_read_recent(pane_id, 80)
        .await
        .map_err(|err| error_response(StatusCode::BAD_GATEWAY, "herdr_error", &err.to_string()))?;
    let Some(form) = questions::form(&screen) else {
        return Err(stale("the question is no longer shown"));
    };
    let Some(shown) = prompt::shown(pane_id, Some(questions::card(&form))).map(|c| c.prompt_id)
    else {
        return Err(stale("this question was already answered"));
    };
    if shown != prompt_id {
        return Err(stale(&format!(
            "the question on screen has changed (on screen {shown})"
        )));
    }
    // Toggles are relative: ticks made in the terminal would be undone.
    if form.ticked {
        return Err(stale(
            "this question was started in the terminal; finish it there",
        ));
    }
    Ok(form)
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
                    let (turns, kind) = match resolved {
                        transcript::ResolvedTranscript::Pi(_) => {
                            (conversation::parse_pi_transcript(&content), stats::Kind::Pi)
                        }
                        transcript::ResolvedTranscript::Claude(_) => (
                            conversation::parse_claude_transcript(&content),
                            stats::Kind::Claude,
                        ),
                        transcript::ResolvedTranscript::Agy(_) => (
                            conversation::parse_agy_transcript(&content),
                            stats::Kind::Agy,
                        ),
                    };
                    let mut session_stats = stats::session_stats(kind, &content);
                    if let Some(found) = session_stats.as_mut().filter(|_| kind == stats::Kind::Pi)
                    {
                        if let (Some(provider), Some(model)) = (&found.provider, &found.model) {
                            found.context_window =
                                stats::pi_context_window(resolved.path(), provider, model);
                        }
                        if found.thinking_level.is_none() {
                            found.thinking_level = stats::pi_thinking_level(resolved.path());
                        }
                    }
                    let payload = conversation::ConversationResponse {
                        session_id,
                        source: "canonical_log".to_string(),
                        machine_id: Some(state.machine_id.clone()),
                        conversation_id,
                        turns,
                        stats: session_stats,
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
                            stats: None,
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

/// Pi, Claude Code and Agy all start a fresh conversation in the same process with `/new`.
pub fn new_session_command(agent: &str) -> Option<&'static str> {
    matches!(
        agent,
        "pi" | "claude" | "agy" | "antigravity" | "antigravity-cli"
    )
    .then_some("/new")
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
    // Typed into a running turn or an open question, the command would become its input.
    if request.action == "new_session"
        && (row.working || matches!(row.status.as_str(), "working" | "blocked"))
    {
        return error_response(
            StatusCode::CONFLICT,
            "agent_busy",
            &format!(
                "{} is {}; stop it before starting a new session",
                row.agent, row.status
            ),
        );
    }
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
        prompt::PromptSubmission::Prompt { text, confirmed } => {
            if text.trim().is_empty() {
                return error_response(
                    StatusCode::BAD_REQUEST,
                    "bad_request",
                    "prompt text cannot be empty",
                );
            }
            // A command can cost far more than a message (`/model` drops the prompt cache, `/new`
            // ends the conversation), so the owner must have confirmed it.
            if let Some(command) = prompt::slash_command(&text).filter(|_| !confirmed) {
                return error_response(
                    StatusCode::CONFLICT,
                    "confirm_command",
                    &format!("{command} is an agent command; confirm it to run it"),
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
            let active_screen = match herdr::pane_read_recent(&pane_id, 80).await {
                Ok(text) => text,
                Err(err) => {
                    return error_response(
                        StatusCode::BAD_GATEWAY,
                        "herdr_error",
                        &err.to_string(),
                    );
                }
            };

            let Some(card) = prompt::shown(&pane_id, prompt::screen_card(&active_screen)) else {
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

            if card.card_type == questions::QUESTION && action.as_deref() != Some("cancel") {
                return error_response(
                    StatusCode::BAD_REQUEST,
                    "bad_request",
                    "answer a question card with answer_question",
                );
            }
            // Determine key to send. The review's own Cancel row stops the whole form.
            if action.as_deref() == Some("cancel") {
                let key = if card.card_type == questions::REVIEW {
                    "2"
                } else {
                    "esc"
                };
                if let Some(stale) = claimed(&pane_id, &prompt_id) {
                    return stale;
                }
                if let Err(err) = herdr::pane_send_keys(&pane_id, &[key]).await {
                    prompt::forget(&pane_id);
                    return error_response(
                        StatusCode::BAD_GATEWAY,
                        "herdr_error",
                        &format!("cannot cancel: {err}"),
                    );
                }
                return (
                    StatusCode::OK,
                    [(header::CONTENT_TYPE, "application/json")],
                    json!({"event": "prompt_answered", "action": "canceled", "prompt_id": prompt_id}).to_string(),
                )
                    .into_response();
            }

            // Fail closed: an approval's first option allows the command, so a missing,
            // unknown or contradictory choice must never fall back to an index.
            if action.as_deref() != Some("select_option") {
                return error_response(
                    StatusCode::BAD_REQUEST,
                    "bad_answer",
                    "action must be select_option or cancel",
                );
            }
            let by_id = option_id
                .as_ref()
                .map(|id| card.options.iter().position(|o| o.id == *id));
            let opt_idx = match (option_index, by_id) {
                (_, Some(None)) => None,
                (Some(idx), Some(Some(pos))) if idx != pos => None,
                (Some(idx), _) => Some(idx),
                (None, Some(pos)) => pos,
                (None, None) => None,
            };
            let Some(opt_idx) = opt_idx.filter(|idx| *idx < card.options.len()) else {
                return error_response(
                    StatusCode::BAD_REQUEST,
                    "bad_answer",
                    "option_index or option_id must name one option on the card",
                );
            };

            // If numbered choice (1, 2, 3), press digit + enter. The review acts on the digit
            // alone; a following enter would land in the agent's input.
            let key_digit = format!("{}", opt_idx + 1);
            let keys: &[&str] = if card.card_type == questions::REVIEW {
                &[&key_digit]
            } else {
                &[&key_digit, "enter"]
            };
            if let Some(stale) = claimed(&pane_id, &prompt_id) {
                return stale;
            }
            let send_res = herdr::pane_send_keys(&pane_id, keys).await;
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
                Err(err) => {
                    prompt::forget(&pane_id);
                    error_response(
                        StatusCode::BAD_GATEWAY,
                        "herdr_error",
                        &format!("cannot send keys: {err}"),
                    )
                }
            }
        }
        prompt::PromptSubmission::AnswerQuestion { prompt_id, answers } => {
            let form = match current_form(&pane_id, &prompt_id).await {
                Ok(form) => form,
                Err(response) => return response,
            };
            // One card is one question: the form shows the next once this one is answered.
            let [answer] = answers.as_slice() else {
                return error_response(
                    StatusCode::BAD_REQUEST,
                    "bad_answer",
                    "answer the question on screen",
                );
            };
            let steps = match questions::answer_steps(&form, answer) {
                Ok(steps) => steps,
                Err(message) => {
                    return error_response(StatusCode::BAD_REQUEST, "bad_answer", &message)
                }
            };
            if let Some(stale) = claimed(&pane_id, &prompt_id) {
                return stale;
            }
            for step in steps {
                let sent = match &step {
                    questions::Step::Keys(keys) => {
                        let keys: Vec<&str> = keys.iter().map(String::as_str).collect();
                        herdr::pane_send_keys(&pane_id, &keys).await
                    }
                    questions::Step::Text(text) => herdr::pane_send_text(&pane_id, text).await,
                };
                if let Err(err) = sent {
                    prompt::forget(&pane_id);
                    return error_response(
                        StatusCode::BAD_GATEWAY,
                        "herdr_error",
                        &format!("cannot send answer: {err}"),
                    );
                }
                // The form redraws between keys; faster input lands on the previous step.
                tokio::time::sleep(std::time::Duration::from_millis(250)).await;
            }
            (
                StatusCode::OK,
                [(header::CONTENT_TYPE, "application/json")],
                json!({"event": "question_answered", "prompt_id": prompt_id}).to_string(),
            )
                .into_response()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_session_is_slash_new_for_every_known_agent() {
        assert_eq!(new_session_command("pi"), Some("/new"));
        assert_eq!(new_session_command("claude"), Some("/new"));
        assert_eq!(new_session_command("agy"), Some("/new"));
        assert_eq!(new_session_command("antigravity-cli"), Some("/new"));
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
