//! Expected native binding checks. These are not atomic PTY fencing or authentication.
use crate::{agents, herdr, routes::error_response};
use axum::{http::StatusCode, response::Response};
use serde::Deserialize;
use serde_json::Value;

#[derive(Debug, Deserialize)]
pub struct Binding {
    machine_id: String,
    pub pane_id: String,
    native_session_ref: String,
}

fn uuid(value: &str) -> bool {
    value.len() == 36
        && value.bytes().enumerate().all(|(i, b)| {
            if matches!(i, 8 | 13 | 18 | 23) {
                b == b'-'
            } else {
                b.is_ascii_hexdigit()
            }
        })
}

fn bounded(value: &str) -> bool {
    !value.trim().is_empty() && value.len() <= 256
}

pub fn unknown() -> Response {
    error_response(
        StatusCode::BAD_GATEWAY,
        "mutation_outcome_unknown",
        "Input may have been applied; inspect current state before another action",
    )
}

fn reject(started: bool, status: StatusCode, code: &str, message: &str) -> Response {
    if started {
        unknown()
    } else {
        error_response(status, code, message)
    }
}

impl Binding {
    pub fn from_body(body: &Value) -> Result<Self, Response> {
        let Some(value) = body
            .get("expected_session")
            .filter(|value| !value.is_null())
        else {
            return Err(error_response(
                StatusCode::PRECONDITION_REQUIRED,
                "session_binding_required",
                "Expected session binding is required",
            ));
        };
        let binding: Self = serde_json::from_value(value.clone()).map_err(|_| {
            error_response(
                StatusCode::BAD_REQUEST,
                "bad_session_binding",
                "Invalid expected session binding",
            )
        })?;
        if !uuid(&binding.machine_id)
            || !bounded(&binding.pane_id)
            || !bounded(&binding.native_session_ref)
        {
            return Err(error_response(
                StatusCode::BAD_REQUEST,
                "bad_session_binding",
                "Invalid expected session binding",
            ));
        }
        Ok(binding)
    }

    /// Recheck immediately before each write. A replacement after this observation remains
    /// possible because Herdr's mutation RPCs do not enforce an expected native reference.
    pub async fn check(
        &self,
        machine: &str,
        locator: &str,
        started: bool,
    ) -> Result<Value, Response> {
        if !self.machine_id.eq_ignore_ascii_case(machine) {
            return Err(reject(
                started,
                StatusCode::CONFLICT,
                "stale_session",
                "Selected session changed; reselect it",
            ));
        }
        let result = herdr::agent_list().await.map_err(|_| {
            reject(
                started,
                StatusCode::SERVICE_UNAVAILABLE,
                "upstream_unavailable",
                "Session metadata is unavailable",
            )
        })?;
        let rows = result
            .get("agents")
            .and_then(Value::as_array)
            .ok_or_else(|| {
                reject(
                    started,
                    StatusCode::SERVICE_UNAVAILABLE,
                    "upstream_unavailable",
                    "Session metadata is unavailable",
                )
            })?;
        if rows.iter().any(|row| {
            !row.is_object() || row["pane_id"].as_str().is_none() || row["agent"].as_str().is_none()
        }) {
            return Err(reject(
                started,
                StatusCode::SERVICE_UNAVAILABLE,
                "upstream_unavailable",
                "Session metadata is unavailable",
            ));
        }
        let candidates: Vec<_> = rows
            .iter()
            .filter(|row| row["pane_id"].as_str() == Some(&self.pane_id))
            .collect();
        // A name shadowing the pane target could make agent.prompt resolve another occupant.
        if candidates.len() != 1
            || rows.iter().any(|row| {
                row["pane_id"].as_str() != Some(&self.pane_id)
                    && (row["name"].as_str() == Some(locator)
                        || row["name"].as_str() == Some(&self.pane_id)
                        || row["pane_id"].as_str() == Some(locator))
            })
        {
            return Err(reject(
                started,
                StatusCode::CONFLICT,
                "stale_session",
                "Selected session changed; reselect it",
            ));
        }
        let agent = candidates[0];
        let pane = agent.get("pane_id").and_then(Value::as_str);
        let kind = agent.get("agent").and_then(Value::as_str);
        let Some((pane, kind)) = pane
            .zip(kind)
            .filter(|(pane, kind)| bounded(pane) && !kind.is_empty())
        else {
            return Err(reject(
                started,
                StatusCode::SERVICE_UNAVAILABLE,
                "upstream_unavailable",
                "Session metadata is unavailable",
            ));
        };
        let reference = &agent["agent_session"];
        let observed = if reference.is_null() {
            None
        } else {
            Some(
                agents::native_session_reference(reference, kind).ok_or_else(|| {
                    reject(
                        started,
                        StatusCode::SERVICE_UNAVAILABLE,
                        "upstream_unavailable",
                        "Session metadata is unavailable",
                    )
                })?,
            )
        };
        if pane != self.pane_id
            || (locator != pane && agent["name"].as_str() != Some(locator))
            || observed.as_deref() != Some(self.native_session_ref.as_str())
        {
            return Err(reject(
                started,
                StatusCode::CONFLICT,
                "stale_session",
                "Selected session changed; reselect it",
            ));
        }
        Ok(agent.clone())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn body() -> Value {
        json!({"expected_session": {
            "machine_id": "ABCDEFAB-1111-4111-8111-111111111111",
            "pane_id": "w1:a", "native_session_ref": "opaque"
        }})
    }

    #[tokio::test]
    async fn shared_wire_fixture_preserves_binding_and_error_shapes() {
        let fixture: Value = serde_json::from_str(include_str!(
            "../../../fixtures/mutation-session-binding.json"
        ))
        .unwrap();
        assert!(Binding::from_body(&fixture["valid"]).is_ok());
        for case in fixture["invalid"].as_array().unwrap() {
            let response = Binding::from_body(&case["body"]).unwrap_err();
            assert_eq!(
                response.status().as_u16(),
                case["status"].as_u64().unwrap() as u16
            );
            let bytes = axum::body::to_bytes(response.into_body(), 4096)
                .await
                .unwrap();
            let error: Value = serde_json::from_slice(&bytes).unwrap();
            assert_eq!(error["event"], "error");
            assert_eq!(error["code"], case["code"]);
        }
    }

    #[test]
    fn missing_binding_and_invalid_shapes_have_distinct_errors() {
        assert_eq!(
            Binding::from_body(&json!({})).unwrap_err().status(),
            StatusCode::PRECONDITION_REQUIRED
        );
        assert_eq!(
            Binding::from_body(&json!({"expected_session": null}))
                .unwrap_err()
                .status(),
            StatusCode::PRECONDITION_REQUIRED
        );
        for value in [json!(false), json!([]), json!({}), json!({"machine_id": 1})] {
            assert_eq!(
                Binding::from_body(&json!({"expected_session": value}))
                    .unwrap_err()
                    .status(),
                StatusCode::BAD_REQUEST
            );
        }
    }

    #[test]
    fn bounds_are_utf8_bytes_and_unknown_fields_remain_additive() {
        let mut value = body();
        value["expected_session"]["future"] = json!(true);
        assert!(Binding::from_body(&value).is_ok());
        for field in ["pane_id", "native_session_ref"] {
            for invalid in [" ".to_string(), "a".repeat(257), "界".repeat(86)] {
                let mut value = body();
                value["expected_session"][field] = json!(invalid);
                assert!(Binding::from_body(&value).is_err());
            }
        }
    }

    #[tokio::test]
    async fn foreign_machine_never_contacts_herdr_and_partial_input_is_unknown() {
        let binding = Binding::from_body(&body()).unwrap();
        assert_eq!(
            binding
                .check("different", "alias", false)
                .await
                .unwrap_err()
                .status(),
            StatusCode::CONFLICT
        );
        assert_eq!(
            binding
                .check("different", "alias", true)
                .await
                .unwrap_err()
                .status(),
            StatusCode::BAD_GATEWAY
        );
    }
}
