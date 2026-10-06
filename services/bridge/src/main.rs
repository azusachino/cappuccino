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
mod state;
mod transcript;

use axum::routing::get;
use axum::Router;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let result = match args.first().map(String::as_str) {
        None => run_server_with_runtime(None),
        Some("__serve")
            if args
                .get(1)
                .is_some_and(|value| lifecycle::valid_token(value)) =>
        {
            run_server_with_runtime(args.get(1).cloned())
        }
        Some("start" | "stop" | "status" | "logs") if args.len() == 1 => {
            lifecycle::execute(args[0].as_str())
        }
        Some("start" | "stop" | "status" | "logs") => {
            Err("lifecycle command takes no additional arguments".into())
        }
        Some("__serve") => Err("invalid managed server token".into()),
        Some(_) => Err("usage: cappuccino-bridge [start|stop|status|logs]".into()),
    };
    if let Err(error) = result {
        eprintln!("cappuccino-bridge: {error}");
        std::process::exit(1);
    }
}

fn run_server_with_runtime(token: Option<String>) -> Result<(), String> {
    if token.is_some() {
        // Set before Tokio creates worker threads. The socket's actual mode is
        // also checked after bind; this is not an assumption about umask.
        unsafe { libc::umask(0o177) };
    }
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .map_err(|error| format!("start async runtime: {error}"))?
        .block_on(run_server(token))
}

async fn run_server(managed_token: Option<String>) -> Result<(), String> {
    let config = config::BridgeConfig::load()?;
    let bind = config.bind.clone();
    let port = config.port;
    if bind != "127.0.0.1" && bind != "localhost" && bind != "::1" {
        return Err(format!(
            "refusing non-loopback bind {bind}; tailnet exposure belongs to tailscale serve"
        ));
    }
    let machine_id = agents::machine_id();
    let state = Arc::new(routes::BridgeState::new(machine_id));

    let mut app = Router::new().route("/api/session", get(routes::session));
    for module in modules::all() {
        app = app.merge((module.router)());
    }
    let app = app
        .layer(axum::middleware::from_fn(auth_middleware))
        .with_state(state);

    let listener = tokio::net::TcpListener::bind((bind.as_str(), port))
        .await
        .map_err(|error| format!("cannot bind {bind}:{port}: {error}"))?;
    println!(
        "cappuccino-bridge: listening on {bind}:{port} (no auth: tailnet/loopback is the boundary; data dir {})",
        config.data_dir.display()
    );

    if let Some(token) = managed_token {
        let state = state::StateDir::open(&lifecycle::state_path())?;
        let control = lifecycle::ControlServer::bind(&state, &token)?;
        let (shutdown_tx, shutdown_rx) = tokio::sync::oneshot::channel();
        let stopping = Arc::new(AtomicBool::new(false));
        let control_thread = control
            .spawn(shutdown_tx, Arc::clone(&stopping))
            .map_err(|error| format!("start private control listener: {error}"))?;
        let result = axum::serve(listener, app)
            .with_graceful_shutdown(async move {
                let _ = shutdown_rx.await;
            })
            .await;
        stopping.store(true, Ordering::Release);
        control_thread.thread().unpark();
        control_thread
            .join()
            .map_err(|_| "private control listener panicked".to_string())?;
        result.map_err(|error| format!("bridge server failed: {error}"))
    } else {
        axum::serve(listener, app)
            .await
            .map_err(|error| format!("bridge server failed: {error}"))
    }
}

async fn auth_middleware(
    request: axum::extract::Request,
    next: axum::middleware::Next,
) -> axum::response::Response {
    next.run(request).await
}
