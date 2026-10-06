//! Cappuccino bridge: a herdr plugin binary serving the loopback API native
//! clients consume. No auth by owner decision — the tailnet/loopback boundary
//! is the security model, and a middleware hook position is reserved for a
//! future authenticator. Transport choice per the 2026-10-06 plugin pivot
//! (issue #16); reconciliation semantics follow the slice-A reference
//! contract. Loopback only — tailnet exposure is tailscale-serve's job, not
//! this process's.

mod agents;
mod config;
mod herdr;
mod modules;
mod reconcile;
mod routes;
mod transcript;

use axum::routing::get;
use axum::Router;
use std::sync::Arc;

#[tokio::main]
async fn main() {
    let config = match config::BridgeConfig::load() {
        Ok(config) => config,
        Err(reason) => {
            eprintln!("cappuccino-bridge: {reason}");
            std::process::exit(1);
        }
    };
    let bind = config.bind.clone();
    let port = config.port;
    if bind != "127.0.0.1" && bind != "localhost" && bind != "::1" {
        eprintln!(
            "cappuccino-bridge: refusing non-loopback bind {bind}; tailnet exposure belongs to tailscale serve"
        );
        std::process::exit(1);
    }
    let machine_id = agents::machine_id();
    let state = Arc::new(routes::BridgeState::new(machine_id));

    // Identity endpoint: cross-module, so it stays in the bootstrap.
    let mut app = Router::new().route("/api/session", get(routes::session));
    // Composable endpoint modules (agents/transcript/stream now; approvals
    // and push later bolt on as new modules).
    for module in modules::all() {
        app = app.merge((module.router)());
    }
    // Middleware/hook position: an authenticator slots in here as another
    // layer without touching any endpoint handler. The MVP ships auth-free
    // (the reserved config section errors instead of pretending).
    let app = app
        .layer(axum::middleware::from_fn(auth_middleware))
        .with_state(state);

    let listener = tokio::net::TcpListener::bind((bind.as_str(), port))
        .await
        .unwrap_or_else(|error| {
            eprintln!("cappuccino-bridge: cannot bind {bind}:{port}: {error}");
            std::process::exit(1);
        });
    println!(
        "cappuccino-bridge: listening on {bind}:{port} (no auth: tailnet/loopback is the boundary; data dir {})",
        config.data_dir.display()
    );
    axum::serve(listener, app)
        .await
        .expect("bridge server failed");
}

/// Reserved middleware position for a future authenticator. The MVP is
/// auth-free by owner decision: the tailnet/loopback boundary is the security
/// model, so this pass-through is the whole implementation until an
/// authenticator replaces it — endpoint handlers never change.
async fn auth_middleware(
    request: axum::extract::Request,
    next: axum::middleware::Next,
) -> axum::response::Response {
    next.run(request).await
}
