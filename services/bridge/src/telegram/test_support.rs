use super::config::File;
use super::send::Sender;
use super::token::Token;
use crate::agents::AgentRow;
use axum::http::StatusCode;
use axum::{
    extract::State,
    http::header,
    response::{IntoResponse, Response},
    routing::any,
    Router,
};
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc,
};
use std::time::Duration;

pub(super) fn row(
    pane: &str,
    native: Option<&str>,
    status: &str,
    completion: Option<u64>,
) -> AgentRow {
    AgentRow {
        machine_id: "m".into(),
        session_id: pane.into(),
        pane_id: pane.into(),
        label: "label".into(),
        agent: "pi".into(),
        status: status.into(),
        working: status == "working",
        cwd: String::new(),
        active_branch: None,
        branch_source: "none".into(),
        state_change_seq: None,
        completion_seq: completion,
        native_session_ref: native.map(str::to_string),
    }
}

pub(super) fn file(text: &str) -> File {
    toml::from_str(text).unwrap()
}

#[derive(Clone)]
pub(super) struct Fake {
    status: u16,
    hits: Arc<AtomicUsize>,
    delay: Duration,
}

pub(super) async fn fake(State(fake): State<Fake>) -> Response {
    fake.hits.fetch_add(1, Ordering::SeqCst);
    tokio::time::sleep(fake.delay).await;
    let mut response = (
        StatusCode::from_u16(fake.status).unwrap(),
        r#"{"ok":false,"parameters":{"retry_after":7}}"#,
    )
        .into_response();
    if (300..400).contains(&fake.status) {
        response.headers_mut().insert(
            header::LOCATION,
            "https://example.invalid/".parse().unwrap(),
        );
    }
    response
}

pub(super) async fn serve(status: u16, delay: Duration) -> (String, Arc<AtomicUsize>) {
    let hits = Arc::new(AtomicUsize::new(0));
    let app = Router::new().route("/{*path}", any(fake)).with_state(Fake {
        status,
        hits: hits.clone(),
        delay,
    });
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    (base, hits)
}

pub(super) fn token() -> Token {
    Token("123:secret".into())
}

pub(super) fn sender(base: &str, timeout: Duration) -> Sender {
    Sender::new(base, timeout).unwrap()
}
