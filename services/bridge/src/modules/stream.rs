//! Stream module: WebSocket pane-line appends.

use crate::routes;
use axum::routing::get;
use axum::Router;
use std::sync::Arc;

pub fn router() -> Router<Arc<routes::BridgeState>> {
    Router::new().route("/api/stream", get(routes::stream))
}
