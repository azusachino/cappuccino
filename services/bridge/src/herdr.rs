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

/// Strips ANSI escape sequences and carriage returns from terminal text.
pub fn strip_ansi(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let mut chars = input.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\x1b' {
            if let Some(&next) = chars.peek() {
                if next == '[' {
                    chars.next();
                    // CSI sequence: params (0x30-0x3F) / intermediates (0x20-0x2F), ending in 0x40-0x7E.
                    while let Some(&p) = chars.peek() {
                        chars.next();
                        if (0x40..=0x7E).contains(&(p as u32)) {
                            break;
                        }
                    }
                } else if next == ']' {
                    chars.next();
                    // OSC sequence: until BEL (\x07) or ST (\x1b\\).
                    while let Some(osc_c) = chars.next() {
                        if osc_c == '\x07' {
                            break;
                        }
                        if osc_c == '\x1b' && chars.peek() == Some(&'\\') {
                            chars.next();
                            break;
                        }
                    }
                } else if (0x40..=0x5F).contains(&(next as u32)) {
                    chars.next();
                }
            }
        } else if c != '\r' {
            out.push(c);
        }
    }
    out
}

/// `pane.read` recent unwrapped text for one pane using passive ANSI format.
///
/// Passive ANSI format only snapshots stored rows without scrolling an idle
/// agent's TUI terminal to harvest history (which text format does, causing
/// the host terminal panel to jitter/frenzy on continuous polling).
pub async fn pane_read_recent(pane_id: &str, lines: u32) -> Result<String, HerdrError> {
    let result = request(
        "pane.read",
        json!({
            "pane_id": pane_id,
            "source": "recent_unwrapped",
            "format": "ansi",
            "lines": lines,
        }),
    )
    .await?;
    let raw = result["read"]["text"].as_str().unwrap_or_default();
    Ok(strip_ansi(raw))
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

    #[test]
    fn test_strip_ansi_colors_and_controls() {
        let input =
            "\x1b[0m\x1b[38;2;138;180;248mRunning command...\x1b[0m\r\n\x1b[38;5;14mworking\x1b[0m";
        let cleaned = strip_ansi(input);
        assert_eq!(cleaned, "Running command...\nworking");
    }

    #[test]
    fn test_strip_ansi_osc_and_plain_text() {
        let input = "hello \x1b]0;terminal title\x07world\r\n";
        let cleaned = strip_ansi(input);
        assert_eq!(cleaned, "hello world\n");
    }
}
