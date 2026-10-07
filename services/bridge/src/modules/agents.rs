//! Agents module: herdr agent.list parity over HTTP.

use crate::routes;
use axum::routing::{get, post};
use axum::Router;
use std::sync::Arc;

pub fn router() -> Router<Arc<routes::BridgeState>> {
    Router::new()
        .route("/api/agents", get(routes::agents))
        .route(
            "/api/agents/{sessionId}/conversation",
            get(routes::agent_conversation),
        )
        .route(
            "/api/agents/{sessionId}/prompt",
            post(routes::submit_agent_prompt),
        )
}
