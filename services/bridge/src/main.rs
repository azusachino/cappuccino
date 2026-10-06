//! Cappuccino bridge: a Herdr plugin transport facade for native clients.
//! Herdr owns agent lifetime; this subprocess exposes selected local Herdr
//! APIs over a loopback HTTP/WebSocket API. No auth by owner decision — the
//! tailnet/loopback boundary is the security model.

mod agents;
mod config;
mod herdr;
mod lifecycle;
mod modules;
mod reconcile;
mod routes;
mod transcript;

use axum::routing::get;
use axum::Router;
use std::sync::Arc;

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        None => run_server().await,
        Some("__serve") if args.get(1).is_some_and(|value| valid_nonce(value)) => {
            run_server().await
        }
        Some("start" | "stop" | "status" | "logs") => {
            if let Err(error) = lifecycle::execute(args[0].as_str()) {
                eprintln!("cappuccino-bridge: {error}");
                std::process::exit(1);
            }
        }
        Some("__serve") => {
            eprintln!("cappuccino-bridge: invalid managed server marker");
            std::process::exit(2);
        }
        Some(_) => {
            eprintln!("usage: cappuccino-bridge [start|stop|status|logs]");
            std::process::exit(2);
        }
    }
}

fn valid_nonce(value: &str) -> bool {
    !value.is_empty()
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || byte == b'-')
}

async fn run_server() {
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
    // layer without touching any endpoint handler. The MVP ships auth-free.
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

async fn auth_middleware(
    request: axum::extract::Request,
    next: axum::middleware::Next,
) -> axum::response::Response {
    next.run(request).await
}
