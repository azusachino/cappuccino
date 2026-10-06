//! Endpoint modules: each owns one capability and contributes a router.
//! Adding approvals or push later means adding a module here (and manifest
//! actions) — no changes to the existing handlers, the middleware stack, or
//! the server bootstrap.

pub mod agents;
pub mod stream;
pub mod transcript;

use crate::routes::BridgeState;
use axum::Router;
use std::sync::Arc;

pub struct EndpointModule {
    #[allow(dead_code)] // names land in logs/diagnostics as the set grows
    pub name: &'static str,
    pub router: fn() -> Router<Arc<BridgeState>>,
}

/// The composable set; order does not matter, routers are merged.
pub fn all() -> Vec<EndpointModule> {
    vec![
        EndpointModule {
            name: "agents",
            router: agents::router,
        },
        EndpointModule {
            name: "transcript",
            router: transcript::router,
        },
        EndpointModule {
            name: "stream",
            router: stream::router,
        },
    ]
}
