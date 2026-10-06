//! HTTP/WS routes. Every handler is token-gated; the WS stream polls
//! `pane.read` and emits wire-v0-shaped events with the slice-A
//! reconciliation semantics.

use crate::{agents, herdr, reconcile, transcript};
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Query, State};
use axum::http::{header, StatusCode};
use axum::response::{IntoResponse, Response};
use serde_json::json;
use std::collections::HashMap;
use std::sync::Arc;

pub struct BridgeState {
    pub machine_id: String,
}

impl BridgeState {
    pub fn new(machine_id: String) -> Self {
        BridgeState { machine_id }
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
            "protocol": 1,
            "plugin": "azusachino.cappuccino-bridge",
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

/// GET /api/stream?session=<locator>&token=…
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
    let generation = ring.generation;
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
        let working = agent
            .as_ref()
            .and_then(|info| info["agent"]["agent_status"].as_str())
            .map(|status| status == "working")
            .unwrap_or(true);
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
