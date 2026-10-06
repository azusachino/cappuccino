//! Conformance against the committed protocol contract
//! (`fixtures/herdr-api.schema.json`, captured from the installed herdr
//! 0.9.3 via `herdr api schema --json`, protocol 22).
//!
//! Pure serde/structural checks — hermetic, no live socket. The bridge must
//! speak exactly these request shapes; if the fixture and our client drift,
//! these tests fail before a live machine can.

use serde_json::{json, Value};
use std::path::PathBuf;

fn schema() -> Value {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures/herdr-api.schema.json");
    let contents = std::fs::read_to_string(&path)
        .unwrap_or_else(|error| panic!("schema fixture missing ({}): {error}", path.display()));
    serde_json::from_str(&contents).expect("schema fixture is valid JSON")
}

/// Finds the request oneOf variant for a method const.
fn request_variant<'a>(schema: &'a Value, method: &str) -> Option<&'a Value> {
    schema["schemas"]["request"]["oneOf"]
        .as_array()?
        .iter()
        .find(|variant| variant["properties"]["method"]["const"] == json!(method))
}

fn params_ref(variant: &Value) -> Option<&str> {
    variant["properties"]["params"]["$ref"].as_str()
}

fn resolve_def<'a>(schema: &'a Value, reference: &str) -> Option<&'a Value> {
    let name = reference.strip_prefix("#/schemas/request/$defs/")?;
    schema["schemas"]["request"]["$defs"].get(name)
}

/// A params object satisfies a schema definition when every required property
/// is present and no unexpected property appears (the herdr server is strict
/// about both: unknown fields and missing required fields are rejected).
fn params_match(schema: &Value, definition: &Value, params: &Value) -> Result<(), String> {
    let properties = definition["properties"].as_object();
    let Some(properties) = properties else {
        // e.g. EmptyParams: an object with no properties at all — only an
        // empty params object can satisfy it.
        return if params.as_object().is_some_and(|object| object.is_empty()) {
            Ok(())
        } else {
            Err("definition declares no properties; params must be empty".into())
        };
    };
    let required: Vec<&str> = definition["required"]
        .as_array()
        .map(|list| list.iter().filter_map(|v| v.as_str()).collect())
        .unwrap_or_default();
    let object = params
        .as_object()
        .ok_or_else(|| "params is not an object".to_string())?;
    for name in &required {
        if !object.contains_key(*name) {
            return Err(format!("missing required property '{name}'"));
        }
    }
    for name in object.keys() {
        if !properties.contains_key(name) {
            return Err(format!("unknown property '{name}' is not in the protocol"));
        }
    }
    // Enum-valued properties must use an allowed variant (e.g. ReadSource).
    for (name, property) in properties {
        let Some(value) = object.get(name) else { continue };
        let enum_values = property["enum"]
            .as_array()
            .or_else(|| {
                // $ref into the same request $defs table (e.g. ReadSource).
                let reference = property["$ref"].as_str()?;
                resolve_def(schema, reference).and_then(|definition| definition["enum"].as_array())
            });
        if let Some(enum_values) = enum_values {
            let text = value.as_str().unwrap_or_default();
            if !enum_values.contains(value) {
                return Err(format!(
                    "property '{name}' value '{text}' is not one of the protocol variants"
                ));
            }
        }
    }
    Ok(())
}

#[test]
fn fixture_pins_protocol_22() {
    let schema = schema();
    assert_eq!(schema["protocol"], 22, "schema fixture must be the 0.9.3 contract");
}

#[test]
fn agent_list_request_matches_schema() {
    let schema = schema();
    let variant = request_variant(&schema, "agent.list").expect("agent.list in protocol");
    let reference = params_ref(variant).expect("agent.list params are a $ref");
    let definition = resolve_def(&schema, reference).expect("params definition resolves");
    // Our client sends exactly an empty params object.
    let params = json!({});
    params_match(&schema, definition, &params)
        .unwrap_or_else(|error| panic!("agent.list params: {error}"));
}

#[test]
fn agent_get_request_matches_schema() {
    let schema = schema();
    let variant = request_variant(&schema, "agent.get").expect("agent.get in protocol");
    let reference = params_ref(variant).expect("agent.get params are a $ref");
    let definition = resolve_def(&schema, reference).expect("params definition resolves");
    let params = json!({ "target": "cap-spike-agent" });
    params_match(&schema, definition, &params)
        .unwrap_or_else(|error| panic!("agent.get params: {error}"));
}

#[test]
fn pane_read_request_matches_schema() {
    let schema = schema();
    let variant = request_variant(&schema, "pane.read").expect("pane.read in protocol");
    let reference = params_ref(variant).expect("pane.read params are a $ref");
    let definition = resolve_def(&schema, reference).expect("params definition resolves");
    let params = json!({
        "pane_id": "w1:p5Y",
        "source": "recent_unwrapped",
        "lines": 2000,
    });
    params_match(&schema, definition, &params)
        .unwrap_or_else(|error| panic!("pane.read params: {error}"));
}

#[test]
fn pane_read_rejects_an_unknown_source_variant() {
    let schema = schema();
    let variant = request_variant(&schema, "pane.read").expect("pane.read in protocol");
    let reference = params_ref(variant).expect("pane.read params are a $ref");
    let definition = resolve_def(&schema, reference).expect("params definition resolves");
    let params = json!({
        "pane_id": "w1:p5Y",
        "source": "not_a_real_source",
    });
    assert!(
        params_match(&schema, definition, &params).is_err(),
        "an unknown ReadSource variant must fail conformance"
    );
}

#[test]
fn success_envelope_carries_id_and_result() {
    let schema = schema();
    let properties = &schema["schemas"]["success_response"]["properties"];
    assert_eq!(properties["id"]["type"], "string");
    assert!(
        properties["result"]["$ref"]
            .as_str()
            .unwrap_or_default()
            .contains("ResponseResult"),
        "success envelope is {{id, result}}"
    );
}

#[test]
fn agent_info_schema_has_the_fields_we_map() {
    let schema = schema();
    let properties = &schema["schemas"]["success_response"]["$defs"]["AgentInfo"]["properties"];
    for field in ["agent", "agent_status", "cwd", "name", "pane_id", "terminal_title_stripped"] {
        assert!(
            properties.get(field).is_some(),
            "AgentInfo must expose '{field}' for catalog mapping"
        );
    }
    // `name` is optional on this build: unnamed panes are part of the parity
    // contract, not an error.
    let required: Vec<&str> = schema["schemas"]["success_response"]["$defs"]["AgentInfo"]
        ["required"]
        .as_array()
        .map(|list| list.iter().filter_map(|v| v.as_str()).collect())
        .unwrap_or_default();
    assert!(
        !required.contains(&"name"),
        "name must stay optional so unnamed-pane fallback remains correct"
    );
}
