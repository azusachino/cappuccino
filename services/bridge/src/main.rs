//! Cappuccino bridge: a Herdr plugin transport facade for native clients.
//! Herdr owns agent lifetime; this subprocess exposes selected local Herdr
//! APIs over a loopback HTTP/WebSocket API. No auth by owner decision — the
//! tailnet/loopback boundary is the security model.

mod agents;
mod compression;
mod config;
mod conversation;
mod herdr;
mod iroh_transport;
mod lifecycle;
mod modules;
mod mutation;
mod prompt;
mod questions;
mod reconcile;
mod routes;
mod state;
mod stats;
mod transcript;

use axum::routing::get;
use axum::Router;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};
use tower_http::limit::RequestBodyLimitLayer;

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
        Some("pair") => iroh_transport::run_pair_command(&args[1..]),
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
    let machine_id = agents::machine_id(&config.data_dir)?;
    let state = Arc::new(routes::BridgeState::new(machine_id));
    let iroh_hub = if config.iroh_enabled {
        let target = iroh_transport::Target {
            host: bind.clone(),
            port,
        };
        Some(iroh_transport::start(&config.data_dir, target, config.iroh_relay_url.clone()).await?)
    } else {
        None
    };

    let app = build_app(state, iroh_hub);

    let listener = tokio::net::TcpListener::bind((bind.as_str(), port))
        .await
        .map_err(|error| format!("cannot bind {bind}:{port}: {error}"))?;
    println!(
        "cappuccino-bridge: listening on {bind}:{port} (no auth: tailnet/loopback is the boundary; data dir {})",
        config.data_dir.display()
    );

    if let Some(token) = managed_token {
        let state = state::StateDir::open(&lifecycle::state_path()?)?;
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

/// Largest request body the bridge reads. Equal to the apps' own ceiling for
/// any HTTP body (`MAX_HTTP_BODY_BYTES` on Android, `maxHTTPBodyBytes` on
/// Apple), so a client never sends a body the bridge would refuse for a
/// reason the client cannot see. Prompts are the only large request body.
const MAX_REQUEST_BODY_BYTES: usize = 2 * 1024 * 1024;

fn build_app(
    state: Arc<routes::BridgeState>,
    iroh_hub: Option<Arc<iroh_transport::IrohHub>>,
) -> Router {
    let mut app = Router::new().route("/api/session", get(routes::session));
    for module in modules::all() {
        app = app.merge((module.router)());
    }
    let app = iroh_transport::with_routes(app, iroh_hub);
    app.layer(compression::json_layer())
        .layer(axum::middleware::from_fn(auth_middleware))
        .layer(axum::middleware::from_fn(log_body_limit))
        .layer(RequestBodyLimitLayer::new(MAX_REQUEST_BODY_BYTES))
        .with_state(state)
}

async fn auth_middleware(
    request: axum::extract::Request,
    next: axum::middleware::Next,
) -> axum::response::Response {
    next.run(request).await
}

/// The HTTP listener has no transport identity, so this line carries no
/// EndpointId; it records that a body hit the cap.
async fn log_body_limit(
    request: axum::extract::Request,
    next: axum::middleware::Next,
) -> axum::response::Response {
    let response = next.run(request).await;
    if response.status() == axum::http::StatusCode::PAYLOAD_TOO_LARGE {
        println!(
            "cappuccino-bridge: audit limit_hit limit=request_body max_bytes={MAX_REQUEST_BODY_BYTES}"
        );
    }
    response
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use tower::ServiceExt;

    fn app() -> Router {
        let state = Arc::new(routes::BridgeState::new("test-machine".into()));
        build_app(state, None)
    }

    fn prompt_request(body: Body, content_length: Option<usize>) -> Request<Body> {
        let mut builder = Request::post("/api/agents/session-1/prompt")
            .header("content-type", "application/json");
        if let Some(length) = content_length {
            builder = builder.header("content-length", length.to_string());
        }
        builder.body(body).unwrap()
    }

    #[tokio::test]
    async fn declared_oversized_body_is_413_without_being_read() {
        let oversized = MAX_REQUEST_BODY_BYTES + 1;
        let response = app()
            .oneshot(prompt_request(Body::empty(), Some(oversized)))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
    }

    #[tokio::test]
    async fn streamed_oversized_body_is_413_not_400() {
        let chunk = bytes::Bytes::from(vec![b'a'; 64 * 1024]);
        let chunks = (0..=(MAX_REQUEST_BODY_BYTES / chunk.len()))
            .map(|_| Ok::<_, std::io::Error>(chunk.clone()))
            .collect::<Vec<_>>();
        let body = Body::from_stream(futures_util::stream::iter(chunks));
        let response = app().oneshot(prompt_request(body, None)).await.unwrap();
        assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
    }

    #[tokio::test]
    async fn body_at_the_limit_is_not_refused_as_too_large() {
        // Not valid JSON, so the handler answers 400; the point is that the cap
        // does not fire at or below the limit.
        let body = Body::from(vec![b'{'; MAX_REQUEST_BODY_BYTES]);
        let response = app()
            .oneshot(prompt_request(body, Some(MAX_REQUEST_BODY_BYTES)))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    }
}
