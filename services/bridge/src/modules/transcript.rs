//! Transcript module: pi sessions-store validation over HTTP.

use crate::routes;
use axum::routing::get;
use axum::Router;
use std::sync::Arc;

pub fn router() -> Router<Arc<routes::BridgeState>> {
    Router::new().route("/api/transcript", get(routes::transcript))
}
