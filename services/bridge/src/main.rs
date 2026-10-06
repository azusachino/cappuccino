//! Cappuccino bridge: a herdr plugin binary serving the token-authed loopback
//! API native clients consume. Transport choice per the 2026-10-06 plugin
//! pivot (issue #16); reconciliation semantics follow the slice-A reference
//! contract. Loopback only — tailnet exposure is tailscale-serve's job, not
//! this process's.

mod agents;
mod auth;
mod herdr;
mod reconcile;
mod routes;
mod transcript;

use axum::routing::get;
use axum::Router;
use std::sync::Arc;

#[tokio::main]
async fn main() {
    let bind = std::env::var("CAPP_BRIDGE_HOST").unwrap_or_else(|_| "127.0.0.1".to_string());
    let port: u16 = std::env::var("CAPP_BRIDGE_PORT")
        .ok()
        .and_then(|port| port.parse().ok())
        .unwrap_or(7392);
    let token = match auth::load_token() {
        Ok(token) => token,
        Err(reason) => {
            eprintln!("cappuccino-bridge: {reason}");
            std::process::exit(1);
        }
    };
    if bind != "127.0.0.1" && bind != "localhost" && bind != "::1" {
        eprintln!(
            "cappuccino-bridge: refusing non-loopback bind {bind}; tailnet exposure belongs to tailscale serve"
        );
        std::process::exit(1);
    }
    let machine_id = agents::machine_id();
    let state = Arc::new(routes::BridgeState::new(token, machine_id));
    let app = Router::new()
        .route("/api/session", get(routes::session))
        .route("/api/agents", get(routes::agents))
        .route("/api/transcript", get(routes::transcript))
        .route("/api/stream", get(routes::stream))
        .with_state(state);
    let listener = tokio::net::TcpListener::bind((bind.as_str(), port))
        .await
        .unwrap_or_else(|error| {
            eprintln!("cappuccino-bridge: cannot bind {bind}:{port}: {error}");
            std::process::exit(1);
        });
    println!("cappuccino-bridge: listening on {bind}:{port} (loopback only)");
    axum::serve(listener, app)
        .await
        .expect("bridge server failed");
}
