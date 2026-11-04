//! HTTP/WS routes. Every handler is token-gated; the WS stream polls
//! `pane.read` and emits wire-v0-shaped events with the slice-A
//! reconciliation semantics.

use crate::{
    agents, conversation, herdr, mutation, prompt, questions, reconcile, stats, sync, transcript,
};
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Json, Path as AxumPath, Query, State};
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::Arc;

pub struct BridgeState {
    pub machine_id: String,
    pub machine_name: Option<String>,
    pub telegram: crate::telegram::Shared,
    /// Pi agent directory from `[pi] agent_dir`; `None` uses the default stores.
    pub pi_agent_dir: Option<std::path::PathBuf>,
}

impl BridgeState {
    pub fn new(machine_id: String) -> Self {
        BridgeState {
            machine_id,
            machine_name: host_name(),
            telegram: Arc::new(std::sync::Mutex::new(crate::telegram::Status::default())),
            pi_agent_dir: None,
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

/// Maps a rejected JSON body: an over-limit body is 413, anything else 400.
pub fn body_rejection(rejection: axum::extract::rejection::JsonRejection) -> Response {
    if rejection.into_response().status() == StatusCode::PAYLOAD_TOO_LARGE {
        return error_response(
            StatusCode::PAYLOAD_TOO_LARGE,
            "payload_too_large",
            "Request body exceeds the bridge limit",
        );
    }
    error_response(
        StatusCode::BAD_REQUEST,
        "bad_request",
        "Invalid mutation body",
    )
}

pub fn error_response(status: StatusCode, code: &str, message: &str) -> Response {
    (
        status,
        [(header::CONTENT_TYPE, "application/json")],
        json!({"event": "error", "code": code, "message": message}).to_string(),
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
            "upstream_unavailable",
            &error.to_string(),
        ),
    }
}

/// WS /api/stream?session=<locator>
pub async fn stream(
    State(state): State<Arc<BridgeState>>,
    AxumPath(session_id): AxumPath<String>,
    upgrade: WebSocketUpgrade,
) -> Response {
    if session_id.is_empty() {
        return error_response(
            StatusCode::BAD_REQUEST,
            "bad_request",
            "sessionId is required",
        );
    }
    upgrade.on_upgrade(move |socket| stream_socket(state, session_id, socket))
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
    let initial_branch = match herdr::agent_get(&session).await {
        Ok(info) => info["agent"]["cwd"]
            .as_str()
            .map(|cwd| agents::own_branch(cwd).branch)
            .unwrap_or(None),
        Err(_) => {
            send_stream_unavailable(&mut socket).await;
            return;
        }
    };
    let mut ring = reconcile::StreamRing::new(initial_branch);
    let mut generation = ring.generation;
    let mut last_status: Option<(String, Option<String>)> = None;
    let mut last_prompt_id: Option<String> = None;

    if socket
        .send(Message::Text(
            json!({"event": "stream_open", "session_id": session, "stream_generation": generation})
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
    let stream = &crate::tuning::active().stream;
    let mut poll = tokio::time::interval(std::time::Duration::from_millis(stream.poll_interval_ms));
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
        if last_ping.elapsed() >= std::time::Duration::from_secs(stream.ping_interval_secs) {
            last_ping = tokio::time::Instant::now();
            if socket.send(Message::Ping(Vec::new().into())).await.is_err() {
                return;
            }
        }
        let text = match herdr::pane_read_recent(&pane_id, stream.pane_lines).await {
            Ok(text) => text,
            Err(_) => {
                send_stream_unavailable(&mut socket).await;
                return;
            }
        };
        let mut lines: Vec<String> = text.lines().map(|line| line.to_string()).collect();
        while lines.last().is_some_and(|last| last.is_empty()) {
            lines.pop();
        }
        let agent = match herdr::agent_get(&session).await {
            Ok(agent) => agent,
            Err(_) => {
                send_stream_unavailable(&mut socket).await;
                return;
            }
        };
        if let Some(cwd) = agent["agent"]["cwd"].as_str() {
            let branch = agents::own_branch(cwd).branch;
            ring.set_branch(branch);
        }
        let Some(current_status_str) = agent["agent"]["agent_status"].as_str() else {
            send_stream_unavailable(&mut socket).await;
            return;
        };
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
                    json!({"event": "stream_reset", "stream_generation": ring.generation})
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
            let event = json!({"event": "entries", "stream_generation": ring.generation, "entries": new_entries});
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
/// A stale prompt answer. `on_screen_prompt_id` names the card on screen, or is null.
fn stale_prompt(message: &str, on_screen_prompt_id: Option<&str>) -> Response {
    crate::protocol::error(
        StatusCode::CONFLICT,
        "stale_prompt",
        message,
        json!({"on_screen_prompt_id": on_screen_prompt_id}),
    )
}

fn claimed(pane_id: &str, prompt_id: &str) -> Option<Response> {
    (!prompt::claim(pane_id, prompt_id))
        .then(|| stale_prompt("this prompt was already answered", None))
}

async fn send_stream_unavailable(socket: &mut WebSocket) {
    let _ = socket
        .send(Message::Text(
            json!({
                "event": "error",
                "code": "upstream_unavailable",
                "message": "The agent stream source is temporarily unavailable.",
            })
            .to_string()
            .into(),
        ))
        .await;
    let _ = socket.send(Message::Close(None)).await;
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
    let stale = |message: &str| stale_prompt(message, None);
    let screen = herdr::pane_read_recent(pane_id, crate::tuning::active().stream.screen_lines)
        .await
        .map_err(|err| {
            error_response(
                StatusCode::BAD_GATEWAY,
                "upstream_unavailable",
                &err.to_string(),
            )
        })?;
    let Some(form) = questions::form(&screen) else {
        return Err(stale("the question is no longer shown"));
    };
    let Some(shown) = prompt::shown(pane_id, Some(questions::card(&form))).map(|c| c.prompt_id)
    else {
        return Err(stale("this question was already answered"));
    };
    if shown != prompt_id {
        return Err(stale_prompt(
            "the question on screen has changed",
            Some(&shown),
        ));
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
/// `GET /api/v2/agents/{sessionId}/conversation/sync` (contract section 7). With no token the
/// newest page comes back with both tokens. `after` moves the phone's forward position, and
/// `before` pages back. A token that no longer matches the file gets the newest page as a resync.
/// A session without a canonical log answers with its pane scrollback as one snapshot.
pub async fn conversation_sync(
    State(state): State<Arc<BridgeState>>,
    AxumPath(session_id): AxumPath<String>,
    Query(query): Query<HashMap<String, String>>,
) -> Response {
    if session_id.is_empty() {
        return error_response(
            StatusCode::BAD_REQUEST,
            "bad_request",
            "sessionId is required",
        );
    }
    let after = query.get("after");
    let before = query.get("before");
    if after.is_some() && before.is_some() {
        return error_response(
            StatusCode::BAD_REQUEST,
            "bad_request",
            "send either after or before, not both",
        );
    }

    let resolved =
        match transcript::resolve_session_transcript(&session_id, state.pi_agent_dir.as_deref())
            .await
        {
            Ok(resolved) => resolved,
            Err(err) => {
                return error_response(StatusCode::BAD_GATEWAY, "upstream_unavailable", &err)
            }
        };
    let Some(resolved) = resolved else {
        return scrollback_snapshot(&state, &session_id).await;
    };

    let kind = sync::Kind::of(&resolved);
    let file = sync::file_id(&resolved);
    let request = match (after, before) {
        (Some(text), _) => match sync::decode(text) {
            Some(token) => sync::Request::After(token),
            None => sync::Request::Unreadable,
        },
        (None, Some(text)) => match sync::decode(text) {
            Some(token) => sync::Request::Before(token),
            None => sync::Request::Unreadable,
        },
        (None, None) => sync::Request::Initial,
    };
    let budget = crate::tuning::active().sync.page_bytes as u64;
    let served = match sync::serve(resolved.path(), &file, kind, request.clone(), budget) {
        Ok(served) => served,
        Err(err) => {
            return error_response(
                StatusCode::INTERNAL_SERVER_ERROR,
                "internal_error",
                &format!("cannot read transcript: {err}"),
            )
        }
    };
    let (mode, page) = match &served {
        sync::Served::Page(page) => ("append", page),
        sync::Served::Resync(page) => ("resync", page),
    };
    let mut body = sync::body(mode, page, &request);
    if matches!(served, sync::Served::Resync(_)) || matches!(request, sync::Request::Initial) {
        // The newest page also carries the canonical conversation id and the composer's stats.
        match conversation::read_canonical_tail(&resolved, conversation::TRANSCRIPT_TAIL_BYTES) {
            Ok((content, conversation_id)) => {
                let stats = canonical_stats(&state, &session_id, &resolved, &content).await;
                if let Some(id) = conversation_id {
                    body["conversation_id"] = json!(id);
                }
                body["stats"] = serde_json::to_value(stats).unwrap_or(Value::Null);
            }
            Err(err) => {
                return error_response(
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "internal_error",
                    &format!("cannot read transcript: {err}"),
                )
            }
        }
    }
    (
        StatusCode::OK,
        [(header::CONTENT_TYPE, "application/json")],
        body.to_string(),
    )
        .into_response()
}

/// The pane's recent scrollback as one snapshot, for a session with no canonical log.
async fn scrollback_snapshot(state: &Arc<BridgeState>, session_id: &str) -> Response {
    let Some(pane_id) = resolve_pane(state, session_id).await else {
        return error_response(
            StatusCode::NOT_FOUND,
            "not_found",
            &format!("no agent with session_id {session_id}"),
        );
    };
    match herdr::pane_read_recent(&pane_id, crate::tuning::active().stream.scrollback_lines).await {
        Ok(text) => {
            let lines: Vec<String> = text.lines().map(str::to_string).collect();
            let turns = conversation::parse_scrollback_turns(&lines);
            let body = json!({
                "mode": "scrollback",
                "upserts": turns,
                "caught_up": true,
                "after_token": null,
                "before_token": null,
            });
            (
                StatusCode::OK,
                [(header::CONTENT_TYPE, "application/json")],
                body.to_string(),
            )
                .into_response()
        }
        Err(err) => error_response(
            StatusCode::BAD_GATEWAY,
            "upstream_unavailable",
            &err.to_string(),
        ),
    }
}

/// The composer's stats for a canonical log: model, context window, thinking level and rate limits.
async fn canonical_stats(
    state: &Arc<BridgeState>,
    session_id: &str,
    resolved: &transcript::ResolvedTranscript,
    content: &str,
) -> Option<stats::SessionStats> {
    let kind = match resolved {
        transcript::ResolvedTranscript::Pi(_) => stats::Kind::Pi,
        transcript::ResolvedTranscript::Claude(_) => stats::Kind::Claude,
        transcript::ResolvedTranscript::Agy(_) => stats::Kind::Agy,
    };
    let agent = match kind {
        stats::Kind::Pi => Some("pi"),
        stats::Kind::Claude => Some("claude"),
        stats::Kind::Agy => Some("agy"),
    };
    let mut session_stats = stats::session_stats(kind, content);
    let pane_text = match resolve_pane(state, session_id).await {
        Some(pane_id) => {
            herdr::pane_read_recent(&pane_id, crate::tuning::active().stream.stats_pane_lines)
                .await
                .ok()
        }
        None => None,
    };
    let (rate_limits, inferred_model) =
        stats::parse_rate_limits(kind, content, pane_text.as_deref());

    if let Some(found) = session_stats.as_mut() {
        if found.model.is_none() {
            found.model = inferred_model;
        }
        found.rate_limits = rate_limits;
        if kind == stats::Kind::Pi {
            if let (Some(provider), Some(model)) = (&found.provider, &found.model) {
                found.context_window = stats::pi_context_window(resolved.path(), provider, model);
            }
            if found.thinking_level.is_none() {
                found.thinking_level = stats::pi_thinking_level(resolved.path());
            }
        }
        if found.context_window.is_none() {
            found.context_window =
                stats::default_context_window(found.model.as_deref(), agent, found.context_tokens);
        }
    } else if rate_limits.is_some() || inferred_model.is_some() {
        let mut found = stats::SessionStats {
            model: inferred_model,
            rate_limits,
            ..Default::default()
        };
        found.context_window =
            stats::default_context_window(found.model.as_deref(), agent, found.context_tokens);
        session_stats = Some(found);
    }
    session_stats
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
    body: Result<Json<serde_json::Value>, axum::extract::rejection::JsonRejection>,
) -> Response {
    let Json(body) = match body {
        Ok(body) => body,
        Err(rejection) => return body_rejection(rejection),
    };
    let binding = match mutation::Binding::from_body(&body) {
        Ok(binding) => binding,
        Err(response) => return response,
    };
    let Ok(request) = serde_json::from_value::<ControlRequest>(body) else {
        return error_response(
            StatusCode::BAD_REQUEST,
            "bad_request",
            "Invalid control body",
        );
    };
    if !matches!(request.action.as_str(), "interrupt" | "new_session") {
        return error_response(
            StatusCode::BAD_REQUEST,
            "bad_request",
            "unknown control action",
        );
    }
    // This observation is a preflight, not an atomic check-and-write at Herdr.
    let row = match binding.check(&state.machine_id, &session_id, false).await {
        Ok(row) => row,
        Err(response) => return response,
    };
    let sent = match request.action.as_str() {
        "interrupt" => herdr::pane_send_keys(&binding.pane_id, &["esc"]).await,
        _ => {
            match row["agent_status"].as_str() {
                Some("idle") => {}
                Some("working" | "blocked") => {
                    return error_response(
                        StatusCode::CONFLICT,
                        "agent_busy",
                        "Stop the agent before starting a new session",
                    )
                }
                _ => {
                    return error_response(
                        StatusCode::SERVICE_UNAVAILABLE,
                        "upstream_unavailable",
                        "Agent idle state is unavailable",
                    )
                }
            }
            let Some(command) = new_session_command(row["agent"].as_str().unwrap_or_default())
            else {
                return error_response(
                    StatusCode::BAD_REQUEST,
                    "unsupported",
                    "No new-session command known for this agent",
                );
            };
            herdr::agent_prompt(&binding.pane_id, command).await
        }
    };
    match sent {
        Ok(_) => (
            StatusCode::OK,
            [(header::CONTENT_TYPE, "application/json")],
            json!({"event": "control_typed", "action": request.action, "session_id": session_id})
                .to_string(),
        )
            .into_response(),
        Err(_) => mutation::unknown(),
    }
}

/// POST /api/agents/{sessionId}/prompt
pub async fn submit_agent_prompt(
    State(state): State<Arc<BridgeState>>,
    AxumPath(session_id): AxumPath<String>,
    body: Result<Json<serde_json::Value>, axum::extract::rejection::JsonRejection>,
) -> Response {
    let Json(body) = match body {
        Ok(body) => body,
        Err(rejection) => return body_rejection(rejection),
    };
    let binding = match mutation::Binding::from_body(&body) {
        Ok(binding) => binding,
        Err(response) => return response,
    };
    let Ok(submission) = serde_json::from_value::<prompt::PromptSubmission>(body) else {
        return error_response(
            StatusCode::BAD_REQUEST,
            "bad_request",
            "Invalid prompt body",
        );
    };
    if let Err(response) = binding.check(&state.machine_id, &session_id, false).await {
        return response;
    }
    let pane_id = binding.pane_id.clone();

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
            if let Err(response) = binding.check(&state.machine_id, &session_id, false).await {
                return response;
            }
            match herdr::agent_prompt(&pane_id, &text).await {
                Ok(_) => (
                    StatusCode::OK,
                    [(header::CONTENT_TYPE, "application/json")],
                    json!({"event": "prompt_typed", "session_id": session_id}).to_string(),
                )
                    .into_response(),
                Err(_) => mutation::unknown(),
            }
        }
        prompt::PromptSubmission::AnswerPrompt {
            prompt_id,
            option_id,
            action,
        } => {
            let active_screen = match herdr::pane_read_recent(
                &pane_id,
                crate::tuning::active().stream.screen_lines,
            )
            .await
            {
                Ok(text) => text,
                Err(err) => {
                    return error_response(
                        StatusCode::BAD_GATEWAY,
                        "upstream_unavailable",
                        &err.to_string(),
                    );
                }
            };

            let Some(card) = prompt::shown(&pane_id, prompt::screen_card(&active_screen)) else {
                return stale_prompt("interactive prompt is no longer visible on screen", None);
            };

            if card.prompt_id != prompt_id {
                return stale_prompt(
                    &format!(
                        "prompt id mismatch: expected {}, on screen {}",
                        prompt_id, card.prompt_id
                    ),
                    Some(&card.prompt_id),
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
                if let Err(response) = binding.check(&state.machine_id, &session_id, false).await {
                    return response;
                }
                if let Some(stale) = claimed(&pane_id, &prompt_id) {
                    return stale;
                }
                if herdr::pane_send_keys(&pane_id, &[key]).await.is_err() {
                    prompt::forget(&pane_id);
                    return mutation::unknown();
                }
                return (
                    StatusCode::OK,
                    [(header::CONTENT_TYPE, "application/json")],
                    json!({"event": "prompt_answered", "prompt_id": prompt_id, "outcome": "cancelled"}).to_string(),
                )
                    .into_response();
            }

            // Fail closed: an approval's first option allows the command, so a missing or
            // unknown choice must never fall back to a position.
            if action.is_some() {
                return error_response(
                    StatusCode::BAD_REQUEST,
                    "bad_answer",
                    "action must be cancel when option_id is absent",
                );
            }
            let Some(option_id) = option_id else {
                return error_response(
                    StatusCode::BAD_REQUEST,
                    "bad_answer",
                    "option_id is required unless action is cancel",
                );
            };
            let Some(index) = card
                .options
                .iter()
                .position(|option| option.id == option_id)
            else {
                return error_response(
                    StatusCode::BAD_REQUEST,
                    "bad_answer",
                    "option_id must name one option on the card",
                );
            };
            let Some(row) = prompt::screen_row(&card.card_type, &option_id, index) else {
                return error_response(
                    StatusCode::BAD_REQUEST,
                    "bad_answer",
                    "option_id must name one option on the card",
                );
            };

            // If numbered choice (1, 2, 3), press digit + enter. The review acts on the digit
            // alone; a following enter would land in the agent's input.
            let key_digit = row.to_string();
            let keys: &[&str] = if card.card_type == questions::REVIEW {
                &[&key_digit]
            } else {
                &[&key_digit, "enter"]
            };
            if let Err(response) = binding.check(&state.machine_id, &session_id, false).await {
                return response;
            }
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
                        "outcome": "option",
                        "option_id": option_id,
                    })
                    .to_string(),
                )
                    .into_response(),
                Err(_) => {
                    prompt::forget(&pane_id);
                    mutation::unknown()
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
            for (index, step) in steps.into_iter().enumerate() {
                if let Err(response) = binding
                    .check(&state.machine_id, &session_id, index > 0)
                    .await
                {
                    return response;
                }
                if index == 0 {
                    if let Some(stale) = claimed(&pane_id, &prompt_id) {
                        return stale;
                    }
                }
                let sent = match &step {
                    questions::Step::Keys(keys) => {
                        let keys: Vec<&str> = keys.iter().map(String::as_str).collect();
                        herdr::pane_send_keys(&pane_id, &keys).await
                    }
                    questions::Step::Text(text) => herdr::pane_send_text(&pane_id, text).await,
                };
                if sent.is_err() {
                    prompt::forget(&pane_id);
                    return mutation::unknown();
                }
                // The form redraws between keys; faster input lands on the previous step.
                tokio::time::sleep(std::time::Duration::from_millis(
                    crate::tuning::active().stream.answer_key_gap_ms,
                ))
                .await;
            }
            (
                StatusCode::OK,
                [(header::CONTENT_TYPE, "application/json")],
                json!({"event": "prompt_answered", "prompt_id": prompt_id, "outcome": "answered"})
                    .to_string(),
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
}
