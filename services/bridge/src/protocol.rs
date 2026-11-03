//! Protocol v2: a handshake that negotiates the protocol version and the
//! capabilities before any other v2 endpoint is called. Design:
//! docs/bridge-v2-contract.md §4.
//!
//! Negotiation is stateless. The bridge keeps no record of an agreement; each
//! client gates its own actions on the `agreed` map it received. The bridge
//! enforces only the protocol header on v2 endpoints.

use crate::routes::{self, BridgeState};
use axum::{
    extract::State,
    http::{header, StatusCode},
    middleware::Next,
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{collections::BTreeMap, sync::Arc};

/// The one protocol version this bridge speaks on v2 paths.
pub const VERSION: u64 = 2;
/// Request header that carries the agreed protocol version on every v2 call.
pub const HEADER: &str = "x-cappuccino-protocol";

/// Capabilities this bridge implements, each with the version it speaks.
pub const OFFERED: &[(&str, u64)] = &[
    ("agent_discovery", 1),
    ("live_output", 1),
    ("conversation_history", 1),
    ("prompt_input", 1),
    ("prompt_answers", 1),
    ("agent_control", 1),
    ("mutation_binding", 1),
    ("completion_signals", 1),
];

#[derive(Debug, PartialEq, Eq)]
pub enum Refusal {
    /// The client's protocol range does not include [`VERSION`].
    Protocol,
    /// The client needs capabilities this bridge does not offer.
    MissingRequired(Vec<String>),
}

/// Picks the agreed capabilities. A capability is agreed when both sides speak
/// it (version above zero), at the lower of the two versions.
pub fn negotiate(
    protocol_min: u64,
    protocol_max: u64,
    client: &BTreeMap<String, u64>,
    required: &[String],
) -> Result<BTreeMap<String, u64>, Refusal> {
    if protocol_min > protocol_max || protocol_min > VERSION || protocol_max < VERSION {
        return Err(Refusal::Protocol);
    }
    let mut agreed = BTreeMap::new();
    for (name, offered) in OFFERED {
        if let Some(&version) = client.get(*name) {
            if version > 0 {
                agreed.insert((*name).to_string(), version.min(*offered));
            }
        }
    }
    let missing: Vec<String> = required
        .iter()
        .filter(|name| !agreed.contains_key(name.as_str()))
        .cloned()
        .collect();
    if missing.is_empty() {
        Ok(agreed)
    } else {
        Err(Refusal::MissingRequired(missing))
    }
}

#[derive(Deserialize)]
struct HandshakeRequest {
    protocol_min: u64,
    protocol_max: u64,
    #[serde(default)]
    capabilities: BTreeMap<String, u64>,
    #[serde(default)]
    required: Vec<String>,
}

fn error(status: StatusCode, body: Value) -> Response {
    (
        status,
        [(header::CONTENT_TYPE, "application/json")],
        body.to_string(),
    )
        .into_response()
}

fn protocol_refused() -> Response {
    error(
        StatusCode::UPGRADE_REQUIRED,
        json!({"event": "error", "code": "protocol", "supported": [VERSION, VERSION]}),
    )
}

/// `POST /api/v2/handshake`: negotiates the protocol and capabilities.
async fn handshake(State(state): State<Arc<BridgeState>>, Json(body): Json<Value>) -> Response {
    let request: HandshakeRequest = match serde_json::from_value(body) {
        Ok(request) => request,
        Err(_) => {
            return error(
                StatusCode::BAD_REQUEST,
                json!({"event": "error", "code": "bad_request"}),
            )
        }
    };
    let agreed = match negotiate(
        request.protocol_min,
        request.protocol_max,
        &request.capabilities,
        &request.required,
    ) {
        Ok(agreed) => agreed,
        Err(Refusal::Protocol) => return protocol_refused(),
        Err(Refusal::MissingRequired(missing)) => {
            return error(
                StatusCode::CONFLICT,
                json!({"event": "error", "code": "capability_missing", "capabilities": missing}),
            )
        }
    };
    let telegram = state
        .telegram
        .lock()
        .map(|status| status.clone())
        .unwrap_or_default();
    let offered: BTreeMap<&str, u64> = OFFERED.iter().copied().collect();
    error(
        StatusCode::OK,
        json!({
            "event": "paired",
            "machine_id": state.machine_id,
            "machine_name": state.machine_name,
            "plugin": "cappuccino-bridge",
            "bridge_version": env!("CARGO_PKG_VERSION"),
            "protocol": VERSION,
            "capabilities": offered,
            "agreed": agreed,
            "telegram": telegram.to_json(),
        }),
    )
}

/// Refuses a v2 call whose protocol header is missing or not the agreed version.
async fn require_version(request: axum::extract::Request, next: Next) -> Response {
    let agreed = request
        .headers()
        .get(HEADER)
        .and_then(|value| value.to_str().ok())
        == Some("2");
    if agreed {
        next.run(request).await
    } else {
        protocol_refused()
    }
}

/// The v2 surface: the handshake, then every endpoint behind the header gate.
pub fn routes() -> Router<Arc<BridgeState>> {
    let gated = Router::new()
        .route("/api/v2/agents", get(routes::agents))
        .route(
            "/api/v2/agents/{sessionId}/conversation",
            get(routes::agent_conversation),
        )
        .route(
            "/api/v2/agents/{sessionId}/prompt",
            post(routes::submit_agent_prompt),
        )
        .route(
            "/api/v2/agents/{sessionId}/control",
            post(routes::control_agent),
        )
        .route("/api/v2/transcript", get(routes::transcript))
        .route("/api/v2/stream", get(routes::stream))
        .route_layer(axum::middleware::from_fn(require_version));
    Router::new()
        .route("/api/v2/handshake", post(handshake))
        .merge(gated)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn client(pairs: &[(&str, u64)]) -> BTreeMap<String, u64> {
        pairs
            .iter()
            .map(|(name, v)| ((*name).to_string(), *v))
            .collect()
    }

    #[test]
    fn agreement_is_the_lower_common_version_of_shared_capabilities() {
        let agreed = negotiate(
            1,
            2,
            &client(&[("live_output", 1), ("agent_control", 3)]),
            &[],
        )
        .expect("overlap");
        assert_eq!(agreed.get("live_output"), Some(&1));
        assert_eq!(agreed.get("agent_control"), Some(&1));
        assert_eq!(agreed.len(), 2);
    }

    #[test]
    fn unknown_zero_and_unoffered_keys_are_ignored() {
        let agreed = negotiate(
            2,
            2,
            &client(&[("future_thing", 1), ("live_output", 0)]),
            &[],
        )
        .expect("no refusal");
        assert!(agreed.is_empty());
    }

    #[test]
    fn a_range_without_version_two_is_refused() {
        assert_eq!(negotiate(1, 1, &client(&[]), &[]), Err(Refusal::Protocol));
        assert_eq!(negotiate(3, 4, &client(&[]), &[]), Err(Refusal::Protocol));
        assert_eq!(negotiate(2, 1, &client(&[]), &[]), Err(Refusal::Protocol));
    }

    #[test]
    fn missing_required_capabilities_are_listed() {
        let required = vec!["live_output".to_string(), "future_thing".to_string()];
        assert_eq!(
            negotiate(2, 2, &client(&[("live_output", 1)]), &required),
            Err(Refusal::MissingRequired(vec!["future_thing".to_string()]))
        );
    }
}

#[cfg(test)]
mod http_tests {
    use super::*;
    use axum::{
        body::{to_bytes, Body},
        http::Request,
    };
    use tower::ServiceExt;

    fn app() -> Router {
        routes().with_state(Arc::new(BridgeState::new("test-machine".into())))
    }

    async fn post_handshake(body: Value) -> (StatusCode, Value) {
        let response = app()
            .oneshot(
                Request::post("/api/v2/handshake")
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(body.to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        let status = response.status();
        let bytes = to_bytes(response.into_body(), usize::MAX).await.unwrap();
        (status, serde_json::from_slice(&bytes).unwrap())
    }

    #[tokio::test]
    async fn v2_endpoints_refuse_a_call_without_the_agreed_header() {
        let response = app()
            .oneshot(Request::get("/api/v2/agents").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UPGRADE_REQUIRED);

        let response = app()
            .oneshot(
                Request::get("/api/v2/agents")
                    .header(HEADER, "1")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UPGRADE_REQUIRED);
    }

    #[tokio::test]
    async fn handshake_returns_the_agreed_capabilities() {
        let (status, body) = post_handshake(json!({
            "protocol_min": 1,
            "protocol_max": 2,
            "capabilities": {"live_output": 1, "agent_control": 1},
            "required": ["live_output"]
        }))
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body["protocol"], 2);
        assert_eq!(body["machine_id"], "test-machine");
        assert_eq!(body["agreed"]["live_output"], 1);
        assert_eq!(body["agreed"]["agent_control"], 1);
        assert!(body["capabilities"]["agent_discovery"].is_number());
    }

    #[tokio::test]
    async fn handshake_refuses_a_protocol_range_without_version_two() {
        let (status, body) = post_handshake(json!({"protocol_min": 1, "protocol_max": 1})).await;
        assert_eq!(status, StatusCode::UPGRADE_REQUIRED);
        assert_eq!(body["code"], "protocol");
        assert_eq!(body["supported"], json!([2, 2]));
    }

    #[tokio::test]
    async fn handshake_names_the_required_capabilities_it_lacks() {
        let (status, body) = post_handshake(json!({
            "protocol_min": 2,
            "protocol_max": 2,
            "required": ["future_thing"]
        }))
        .await;
        assert_eq!(status, StatusCode::CONFLICT);
        assert_eq!(body["code"], "capability_missing");
        assert_eq!(body["capabilities"], json!(["future_thing"]));
    }

    #[tokio::test]
    async fn handshake_rejects_a_malformed_body() {
        let (status, body) = post_handshake(json!({"protocol_max": 2})).await;
        assert_eq!(status, StatusCode::BAD_REQUEST);
        assert_eq!(body["code"], "bad_request");
    }
}

#[cfg(test)]
mod identity_tests {
    use super::*;
    use axum::{
        body::{to_bytes, Body},
        http::Request,
    };
    use tower::ServiceExt;

    #[tokio::test]
    async fn handshake_reports_the_machine_name_as_metadata_not_identity() {
        let mut state = BridgeState::new("stable-machine-id".to_string());
        state.machine_name = Some("Owner's Mac".to_string());
        let response = routes()
            .with_state(Arc::new(state))
            .oneshot(
                Request::post("/api/v2/handshake")
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(
                        json!({"protocol_min": 2, "protocol_max": 2}).to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        let bytes = to_bytes(response.into_body(), usize::MAX).await.unwrap();
        let value: Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(value["machine_name"], "Owner's Mac");
        assert_eq!(value["machine_id"], "stable-machine-id");
        assert_eq!(value["protocol"], 2);
        assert_eq!(value["bridge_version"], env!("CARGO_PKG_VERSION"));
    }
}
