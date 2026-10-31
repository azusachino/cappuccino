//! Herdr socket RPC client: one newline-delimited JSON request per
//! short-lived Unix connection, as the retired slice-A daemon proved the
//! server behaves (it closes after answering).

use serde_json::{json, Value};
use std::path::PathBuf;
use std::time::Duration;
use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncWriteExt};
use tokio::net::UnixStream;

/// Shared timeout for connect/write/read. This is a safety ceiling for local
/// IPC, not a measured latency target or a preemptive CPU-time limit.
const REQUEST_DEADLINE: Duration = Duration::from_secs(10);

/// Maximum response payload bytes, excluding LF. A safety ceiling rather
/// than a measured transcript maximum; oversized responses fail explicitly.
const MAX_RESPONSE_LINE_BYTES: usize = 4 * 1024 * 1024;

/// Injected bounds used by tests: a short deadline and a small line cap.
#[cfg(test)]
const TEST_DEADLINE: Duration = Duration::from_millis(150);
#[cfg(test)]
const TEST_MAX_RESPONSE_LINE_BYTES: usize = 256;

/// Deadline and allocation bounds for one request.
#[derive(Debug, Clone, Copy)]
struct RequestLimits {
    deadline: Duration,
    max_response_line_bytes: usize,
}

impl RequestLimits {
    fn production() -> Self {
        Self {
            deadline: REQUEST_DEADLINE,
            max_response_line_bytes: MAX_RESPONSE_LINE_BYTES,
        }
    }

    #[cfg(test)]
    fn test_bounds() -> Self {
        Self {
            deadline: TEST_DEADLINE,
            max_response_line_bytes: TEST_MAX_RESPONSE_LINE_BYTES,
        }
    }
}

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

/// Sends one request over the discovered socket and returns the response
/// envelope's `result` object.
///
/// A deadline that expires after the write was issued is ambiguous: the Herdr
/// side may already have applied a side effect. The error says so and the
/// caller must not retry blindly.
pub async fn request(method: &str, params: Value) -> Result<Value, HerdrError> {
    request_at(
        socket_pathbuf(),
        method,
        params,
        RequestLimits::production(),
    )
    .await
}

/// Reads one LF-terminated line, failing before the buffer may exceed
/// `max_bytes`. EOF before any byte means the peer closed without answering;
/// EOF after a partial line is a truncated response, never parsed.
async fn read_limited_line<R: AsyncBufRead + Unpin>(
    reader: &mut R,
    max_bytes: usize,
) -> Result<Vec<u8>, HerdrError> {
    let mut line = Vec::new();
    loop {
        let available = reader
            .fill_buf()
            .await
            .map_err(|error| HerdrError(format!("read failed: {error}")))?;
        if available.is_empty() {
            return if line.is_empty() {
                Err(HerdrError("connection closed before a response".into()))
            } else {
                Err(HerdrError(format!(
                    "connection closed mid-response after {} bytes without a newline",
                    line.len()
                )))
            };
        }
        if let Some(newline) = available.iter().position(|&byte| byte == b'\n') {
            // Same ceiling applies when the LF arrives: reject before the
            // final extend, whether the whole line landed in one chunk or was
            // split across earlier fills.
            if line.len() + newline > max_bytes {
                return Err(HerdrError(format!(
                    "response line exceeds {} bytes",
                    max_bytes
                )));
            }
            line.extend_from_slice(&available[..newline]);
            reader.consume(newline + 1);
            return Ok(line);
        }
        if line.len() + available.len() > max_bytes {
            return Err(HerdrError(format!(
                "response line exceeds {} bytes without a newline",
                max_bytes
            )));
        }
        line.extend_from_slice(available);
        let buffered = available.len();
        reader.consume(buffered);
    }
}

/// Sends one request to an injected socket path under injected bounds and
/// returns the response envelope's `result` object.
///
/// All asynchronous I/O shares one timeout; connect does not receive an extra
/// budget. Expiry after a write is ambiguous and must not trigger blind retry.
async fn request_at(
    path: PathBuf,
    method: &str,
    params: Value,
    limits: RequestLimits,
) -> Result<Value, HerdrError> {
    let id = format!("bridge-{}", std::process::id());
    let call = async {
        let stream = UnixStream::connect(path.clone()).await.map_err(|error| {
            HerdrError(format!("cannot connect to {}: {error}", path.display()))
        })?;
        bounded_call(stream, &id, method, params, limits).await
    };
    match tokio::time::timeout(limits.deadline, call).await {
        Ok(outcome) => outcome,
        Err(_) => Err(HerdrError(format!(
            "request deadline exceeded after {:?} (write may have been applied; \
             outcome ambiguous, not retried)",
            limits.deadline
        ))),
    }
}

/// Runs write and read of one response line; the caller owns the deadline.
async fn bounded_call(
    stream: UnixStream,
    id: &str,
    method: &str,
    params: Value,
    limits: RequestLimits,
) -> Result<Value, HerdrError> {
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

    let mut lines = tokio::io::BufReader::new(reader);
    let line = read_limited_line(&mut lines, limits.max_response_line_bytes).await?;
    let line = String::from_utf8(line)
        .map_err(|error| HerdrError(format!("response is not UTF-8: {error}")))?;
    let response: Value =
        serde_json::from_str(&line).map_err(|error| HerdrError(format!("bad JSON: {error}")))?;
    // Correlate the response to our request when the peer reports an id; a
    // mismatched envelope is not our answer, never parse its payload.
    if let Some(response_id) = response.get("id") {
        if response_id != &Value::String(id.to_string()) {
            return Err(HerdrError(format!(
                "response id mismatch: expected {id}, got {response_id}"
            )));
        }
    }
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

/// `agent.prompt`: writes text + Enter to the agent PTY.
pub async fn agent_prompt(target: &str, text: &str) -> Result<Value, HerdrError> {
    request(
        "agent.prompt",
        json!({
            "target": target,
            "text": text,
        }),
    )
    .await
}

/// `pane.send_keys`: sends logical keystrokes (e.g. ["1", "enter"] or ["space", "enter"]).
pub async fn pane_send_keys(pane_id: &str, keys: &[&str]) -> Result<Value, HerdrError> {
    request(
        "pane.send_keys",
        json!({
            "pane_id": pane_id,
            "keys": keys,
        }),
    )
    .await
}

/// `pane.send_text`: sends literal text to a pane PTY.
pub async fn pane_send_text(pane_id: &str, text: &str) -> Result<Value, HerdrError> {
    request(
        "pane.send_text",
        json!({
            "pane_id": pane_id,
            "text": text,
        }),
    )
    .await
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::AsyncReadExt;
    use tokio::task::JoinHandle;

    #[tokio::test]
    async fn connect_failure_is_a_typed_error() {
        // A socket path that cannot exist must yield a clean HerdrError, not a panic.
        let error = request_at(
            PathBuf::from("/nonexistent/cappuccino-test.sock"),
            "agent.list",
            json!({}),
            RequestLimits::test_bounds(),
        )
        .await;
        assert!(error.is_err());
        assert!(error.err().unwrap().0.contains("cannot connect"));
    }

    /// A one-shot test peer on a fresh, short temp socket. Unix socket paths
    /// must stay under SUN_LEN (104), so the name sits directly in the temp
    /// directory; nothing touches the process environment. Dropping the
    /// guard aborts the peer task and removes the socket file.
    struct TestPeer {
        path: PathBuf,
        peer: JoinHandle<()>,
    }

    impl Drop for TestPeer {
        fn drop(&mut self) {
            self.peer.abort();
            let _ = std::fs::remove_file(&self.path);
        }
    }

    /// Binds a listener, spawns the accept+`peer` task, and returns the
    /// guard immediately so the caller can connect without deadlocking.
    fn serve_one<F, Fut>(peer: F) -> TestPeer
    where
        F: FnOnce(tokio::net::UnixStream) -> Fut + Send + 'static,
        Fut: std::future::Future<Output = ()> + Send + 'static,
    {
        // A counter, not the clock: parallel tests can share a timestamp on macOS, and bind fails
        // with AlreadyExists.
        static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        let path = std::env::temp_dir().join(format!(
            "cap-h-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ));
        let listener = tokio::net::UnixListener::bind(&path).unwrap();
        let peer = tokio::spawn(async move {
            if let Ok((stream, _)) = listener.accept().await {
                peer(stream).await;
            }
        });
        TestPeer { path, peer }
    }

    /// Reads one request line and echoes the envelope id back at the
    /// envelope level; the client must validate it before trusting result.
    async fn valid_peer(mut stream: tokio::net::UnixStream) {
        let mut buf = vec![0u8; 4096];
        let read = stream.read(&mut buf).await.unwrap();
        let request: Value = serde_json::from_slice(&buf[..read]).unwrap();
        let response = json!({"id": request["id"], "result": {"agent": "s-probe"}});
        stream
            .write_all(response.to_string().as_bytes())
            .await
            .unwrap();
        stream.write_all(b"\n").await.unwrap();
    }

    #[tokio::test]
    async fn valid_response_returns_result_and_matches_request_id() {
        let guard = serve_one(valid_peer);
        let result = request_at(
            guard.path.clone(),
            "agent.list",
            json!({}),
            RequestLimits::test_bounds(),
        )
        .await
        .unwrap();
        assert_eq!(result["agent"].as_str().unwrap(), "s-probe");
    }

    #[tokio::test]
    async fn mismatched_response_id_is_rejected() {
        let guard = serve_one(|mut stream| async move {
            let mut buf = vec![0u8; 4096];
            let read = stream.read(&mut buf).await.unwrap();
            let request: Value = serde_json::from_slice(&buf[..read]).unwrap();
            // Deliberately answer with the wrong envelope id.
            let wrong = json!({"id": "some-other-request", "result": {}});
            assert_ne!(wrong["id"], request["id"]);
            stream
                .write_all(wrong.to_string().as_bytes())
                .await
                .unwrap();
            stream.write_all(b"\n").await.unwrap();
        });
        let error = request_at(
            guard.path.clone(),
            "agent.list",
            json!({}),
            RequestLimits::test_bounds(),
        )
        .await
        .err()
        .unwrap();
        assert!(error.0.contains("response id mismatch"), "got: {}", error);
    }

    #[tokio::test]
    async fn error_envelope_maps_to_typed_error() {
        let guard = serve_one(|mut stream| async move {
            let mut buf = vec![0u8; 4096];
            let read = stream.read(&mut buf).await.unwrap();
            let request: Value = serde_json::from_slice(&buf[..read]).unwrap();
            let response = json!({"id": request["id"], "error": {"code": "no-agent", "message": "unknown target"}});
            stream
                .write_all(response.to_string().as_bytes())
                .await
                .unwrap();
            stream.write_all(b"\n").await.unwrap();
        });
        let error = request_at(
            guard.path.clone(),
            "agent.get",
            json!({}),
            RequestLimits::test_bounds(),
        )
        .await
        .err()
        .unwrap();
        assert!(
            error.0.contains("no-agent: unknown target"),
            "got: {}",
            error
        );
    }

    #[tokio::test]
    async fn never_answer_peer_hits_deadline_and_reports_ambiguity() {
        let guard = serve_one(|mut stream| async move {
            // Accept, read the request, then never answer. Holding the stream
            // keeps the connection open until the client deadline fires.
            let mut buf = vec![0u8; 4096];
            let _ = stream.read(&mut buf).await.unwrap();
            tokio::time::sleep(Duration::from_secs(30)).await;
        });
        let started = std::time::Instant::now();
        let error = request_at(
            guard.path.clone(),
            "agent.list",
            json!({}),
            RequestLimits::test_bounds(),
        )
        .await
        .err()
        .unwrap();
        assert!(started.elapsed() < Duration::from_secs(5));
        assert!(error.0.contains("deadline"), "got: {}", error);
        assert!(error.0.contains("not retried"), "got: {}", error);
    }

    #[tokio::test]
    async fn oversized_line_without_newline_is_rejected() {
        let guard = serve_one(|mut stream| async move {
            let mut buf = vec![0u8; 4096];
            let _ = stream.read(&mut buf).await.unwrap();
            let junk = vec![b'a'; TEST_MAX_RESPONSE_LINE_BYTES + 1024];
            stream.write_all(&junk).await.unwrap();
            // Hold open after the oversized dump; no LF ever arrives.
            tokio::time::sleep(Duration::from_secs(30)).await;
        });
        let error = request_at(
            guard.path.clone(),
            "agent.list",
            json!({}),
            RequestLimits::test_bounds(),
        )
        .await
        .err()
        .unwrap();
        assert!(error.0.contains("exceeds"), "got: {}", error);
    }

    #[tokio::test]
    async fn oversized_line_with_trailing_newline_is_rejected() {
        let guard = serve_one(|mut stream| async move {
            let mut buf = vec![0u8; 4096];
            let _ = stream.read(&mut buf).await.unwrap();
            let mut junk = vec![b'x'; TEST_MAX_RESPONSE_LINE_BYTES + 1];
            junk.push(b'\n');
            stream.write_all(&junk).await.unwrap();
        });
        let error = request_at(
            guard.path.clone(),
            "agent.list",
            json!({}),
            RequestLimits::test_bounds(),
        )
        .await
        .err()
        .unwrap();
        assert!(error.0.contains("exceeds"), "got: {}", error);
    }

    #[tokio::test]
    async fn line_exactly_at_the_limit_is_accepted() {
        let guard = serve_one(|mut stream| async move {
            let mut buf = vec![0u8; 4096];
            let read = stream.read(&mut buf).await.unwrap();
            let request: Value = serde_json::from_slice(&buf[..read]).unwrap();
            // The payload may fill the cap; LF is framing, not payload.
            let mut response = json!({"id": request["id"], "result": {"pad": ""}});
            let deficit = TEST_MAX_RESPONSE_LINE_BYTES - response.to_string().len();
            response["result"]["pad"] = Value::String("a".repeat(deficit));
            let line = format!("{}\n", response);
            assert_eq!(line.len(), TEST_MAX_RESPONSE_LINE_BYTES + 1);
            stream.write_all(line.as_bytes()).await.unwrap();
        });
        let result = request_at(
            guard.path.clone(),
            "agent.list",
            json!({}),
            RequestLimits::test_bounds(),
        )
        .await
        .unwrap();
        assert!(result["pad"].as_str().unwrap().len() > 0);
    }

    #[tokio::test]
    async fn truncated_json_at_eof_is_fail_closed() {
        let guard = serve_one(|mut stream| async move {
            let mut buf = vec![0u8; 4096];
            let _ = stream.read(&mut buf).await.unwrap();
            // Valid-looking start, then close mid-line.
            stream.write_all(b"{\"id\":\"bridge").await.unwrap();
        });
        let error = request_at(
            guard.path.clone(),
            "agent.list",
            json!({}),
            RequestLimits::test_bounds(),
        )
        .await
        .err()
        .unwrap();
        assert!(error.0.contains("closed mid-response"), "got: {}", error);
    }

    #[tokio::test]
    async fn clean_disconnect_before_any_byte_is_typed() {
        let guard = serve_one(|mut stream| async move {
            let mut buf = vec![0u8; 4096];
            let _ = stream.read(&mut buf).await.unwrap();
            // Close without writing anything.
        });
        let error = request_at(
            guard.path.clone(),
            "agent.list",
            json!({}),
            RequestLimits::test_bounds(),
        )
        .await
        .err()
        .unwrap();
        assert!(
            error.0.contains("closed before a response"),
            "got: {}",
            error
        );
    }

    #[test]
    fn test_strip_ansi_colors_and_controls() {
        let input =
            "\x1b[0m\x1b[38;2;138;180;248mRunning command...\x1b[0m\r\n\x1b[38;5;14mworking\x1b[0m";
        assert_eq!(strip_ansi(input), "Running command...\nworking");
    }

    #[test]
    fn test_strip_ansi_osc_and_plain_text() {
        let input = "hello \x1b]0;terminal title\x07world\r\n";
        assert_eq!(strip_ansi(input), "hello world\n");
    }

    #[tokio::test]
    async fn line_limit_covers_a_newline_arriving_in_a_later_buffer() {
        let mut oversized = tokio::io::BufReader::with_capacity(3, &b"abcdef\n"[..]);
        assert!(read_limited_line(&mut oversized, 5)
            .await
            .unwrap_err()
            .0
            .contains("exceeds"));
        let mut exact = tokio::io::BufReader::with_capacity(3, &b"abcdef\n"[..]);
        assert_eq!(read_limited_line(&mut exact, 6).await.unwrap(), b"abcdef");
    }

    #[tokio::test]
    async fn response_missing_result_is_rejected() {
        let guard = serve_one(|mut stream| async move {
            let mut buf = vec![0u8; 4096];
            let read = stream.read(&mut buf).await.unwrap();
            let request: Value = serde_json::from_slice(&buf[..read]).unwrap();
            let response = json!({"id": request["id"]});
            stream
                .write_all(response.to_string().as_bytes())
                .await
                .unwrap();
            stream.write_all(b"\n").await.unwrap();
        });
        let error = request_at(
            guard.path.clone(),
            "agent.list",
            json!({}),
            RequestLimits::test_bounds(),
        )
        .await
        .err()
        .unwrap();
        assert!(error.0.contains("missing result"), "got: {}", error);
    }
}
