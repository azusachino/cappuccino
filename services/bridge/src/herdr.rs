//! Herdr socket RPC client: one newline-delimited JSON request per
//! short-lived Unix connection, exactly as the reference daemon proved the
//! server behaves (it closes after answering).

use serde_json::{json, Value};
use std::path::PathBuf;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixStream;

#[derive(Debug)]
pub struct HerdrError(pub String);

impl std::fmt::Display for HerdrError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "herdr: {}", self.0)
    }
}

/// Socket path: HERDR_SOCKET_PATH (what herdr injects into plugin processes),
/// then HERDR_SOCKET, then the default per-user path.
pub fn socket_path() -> String {
    if let Ok(explicit) = std::env::var("HERDR_SOCKET_PATH") {
        return explicit;
    }
    if let Ok(explicit) = std::env::var("HERDR_SOCKET") {
        return explicit;
    }
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    format!("{home}/.config/herdr/herdr.sock")
}

fn socket_pathbuf() -> PathBuf {
    PathBuf::from(socket_path())
}

/// Sends one request and returns the response envelope's `result` object.
pub async fn request(method: &str, params: Value) -> Result<Value, HerdrError> {
    let id = format!("bridge-{}", std::process::id());
    let stream = UnixStream::connect(socket_pathbuf())
        .await
        .map_err(|error| HerdrError(format!("cannot connect to {}: {error}", socket_path())))?;
    let (reader, mut writer) = stream.into_split();
    let request = json!({"id": id, "method": method, "params": params});
    let mut payload =
        serde_json::to_vec(&request).map_err(|error| HerdrError(error.to_string()))?;
    payload.push(b'\n');
    writer
        .write_all(&payload)
        .await
        .map_err(|error| HerdrError(format!("write failed: {error}")))?;
    writer
        .flush()
        .await
        .map_err(|error| HerdrError(format!("flush failed: {error}")))?;

    let mut lines = BufReader::new(reader);
    let mut line = String::new();
    let read = lines
        .read_line(&mut line)
        .await
        .map_err(|error| HerdrError(format!("read failed: {error}")))?;
    if read == 0 {
        return Err(HerdrError("connection closed before a response".into()));
    }
    let response: Value =
        serde_json::from_str(&line).map_err(|error| HerdrError(format!("bad JSON: {error}")))?;
    if let Some(error) = response.get("error") {
        let code = error["code"].as_str().unwrap_or("unknown");
        let message = error["message"].as_str().unwrap_or("");
        return Err(HerdrError(format!("{code}: {message}")));
    }
    response
        .get("result")
        .cloned()
        .ok_or_else(|| HerdrError("response missing result".into()))
}

/// `agent.list` result envelope.
pub async fn agent_list() -> Result<Value, HerdrError> {
    request("agent.list", json!({})).await
}

/// `agent.get` for a locator (agent name or pane id).
pub async fn agent_get(target: &str) -> Result<Value, HerdrError> {
    request("agent.get", json!({ "target": target })).await
}

/// `pane.read` recent unwrapped text for one pane.
pub async fn pane_read_recent(pane_id: &str, lines: u32) -> Result<String, HerdrError> {
    let result = request(
        "pane.read",
        json!({
            "pane_id": pane_id,
            "source": "recent_unwrapped",
            "lines": lines,
        }),
    )
    .await?;
    Ok(result["read"]["text"]
        .as_str()
        .unwrap_or_default()
        .to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn connect_failure_is_a_typed_error() {
        // A socket path that cannot exist must yield a clean HerdrError, not a panic.
        let saved = std::env::var("HERDR_SOCKET_PATH").ok();
        std::env::set_var("HERDR_SOCKET_PATH", "/nonexistent/cappuccino-test.sock");
        let error = agent_list().await;
        std::env::remove_var("HERDR_SOCKET_PATH");
        if let Some(saved) = saved {
            std::env::set_var("HERDR_SOCKET_PATH", saved);
        }
        assert!(error.is_err());
    }
}
