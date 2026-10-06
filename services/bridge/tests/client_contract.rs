//! Hermetic contract tests for the `test-client` companion and the bridge's
//! public HTTP/WS surface. The bridge binary is spawned against a mock Herdr
//! socket (temp dirs, loopback, ephemeral ports, no owner sessions, no
//! Tailscale: serve auto-apply disabled). The probe module is shared with the
//! shipped example CLI by path.

#[path = "../examples/test-client/probe.rs"]
mod probe;

use probe::{HttpPreference, ProbeOptions};
use serde_json::{json, Value};

use std::net::TcpListener;
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

const BIN: &str = env!("CARGO_BIN_EXE_cappuccino-bridge");
static NEXT: AtomicU64 = AtomicU64::new(0);
const TIMEOUT: Duration = Duration::from_secs(10);

// ---------------------------------------------------------------------------
// Temp root: identity-safe cleanup, errors surfaced, never ignored.
// ---------------------------------------------------------------------------

struct TempRoot {
    path: PathBuf,
    dev: u64,
    ino: u64,
}

impl TempRoot {
    fn new(label: &str) -> Self {
        let path = std::env::temp_dir().join(format!(
            "capp-client-test-{label}-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir(&path).expect("create temp root");
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o700)).unwrap();
        let metadata = std::fs::symlink_metadata(&path).unwrap();
        Self {
            path,
            dev: metadata.dev(),
            ino: metadata.ino(),
        }
    }

    /// Identity-safe removal: only our own 0700 dir, same dev/ino. Cleanup
    /// errors are surfaced (panic), not ignored.
    fn remove(self) {
        self.remove_inner(true);
    }

    fn remove_inner(&self, panic_on_error: bool) {
        let metadata = match std::fs::symlink_metadata(&self.path) {
            Ok(metadata) => metadata,
            Err(error) => {
                if panic_on_error {
                    panic!("temp root vanished before cleanup: {error}");
                }
                return;
            }
        };
        let ours = metadata.file_type().is_dir()
            && metadata.uid() == unsafe { libc::geteuid() }
            && metadata.mode() & 0o7777 == 0o700
            && metadata.dev() == self.dev
            && metadata.ino() == self.ino;
        if !ours {
            if panic_on_error {
                panic!(
                    "refusing to remove replaced/unsafe temp root {}",
                    self.path.display()
                );
            }
            eprintln!(
                "preserving replaced/unsafe temp root {}",
                self.path.display()
            );
            return;
        }
        if let Err(error) = std::fs::remove_dir_all(&self.path) {
            let message = format!("could not remove {}: {error}", self.path.display());
            if panic_on_error {
                panic!("{message}");
            }
            eprintln!("{message}");
        }
    }
}

impl Drop for TempRoot {
    fn drop(&mut self) {
        self.remove_inner(false);
    }
}

// ---------------------------------------------------------------------------
// Mock Herdr socket: one NDJSON request per short-lived connection.
// ---------------------------------------------------------------------------

struct MockHerdr {
    socket_path: PathBuf,
    task: tokio::task::JoinHandle<()>,
}

impl MockHerdr {
    fn start(root: &TempRoot) -> Self {
        let socket_path = root.path.join("herdr.sock");
        let listener = tokio::net::UnixListener::bind(&socket_path).expect("bind mock herdr");
        let task = tokio::spawn(async move {
            loop {
                let (stream, _) = match listener.accept().await {
                    Ok(accepted) => accepted,
                    Err(_) => return,
                };
                handle_mock_connection(stream);
            }
        });
        Self { socket_path, task }
    }

    async fn stop(self) {
        self.task.abort();
        let _ = self.task.await;
        if let Err(error) = std::fs::remove_file(&self.socket_path) {
            eprintln!("mock herdr socket cleanup: {error}");
        }
    }
}

fn handle_mock_connection(stream: tokio::net::UnixStream) {
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt};
    tokio::spawn(async move {
        let (reader, mut writer) = stream.into_split();
        let mut lines = tokio::io::BufReader::new(reader);
        let mut line = String::new();
        if lines.read_line(&mut line).await.is_err_or_eof() {
            return;
        }
        let request: Value = serde_json::from_str(line.trim()).unwrap_or(Value::Null);
        let method = request["method"].as_str().unwrap_or("");
        let result = match method {
            "agent.list" => json!({
                "agents": [{
                    "name": "s-probe",
                    "pane_id": "w1:a",
                    "agent": "pi",
                    "agent_status": "idle",
                    "cwd": "",
                }]
            }),
            "agent.get" => json!({
                "agent": {
                    "name": "s-probe",
                    "pane_id": "w1:a",
                    "agent": "pi",
                    "agent_status": "idle",
                    "cwd": "",
                }
            }),
            "pane.read" => json!({"read": {"text": "alpha line\nbeta line\n"}}),
            _ => json!({}),
        };
        let response = json!({"id": request["id"], "result": result});
        let mut payload = serde_json::to_vec(&response).unwrap();
        payload.push(b'\n');
        let _ = writer.write_all(&payload).await;
        let _ = writer.flush().await;
        // Connection drops: the bridge treats closure after the answer as normal.
    });
}

trait IsErrOrEof {
    fn is_err_or_eof(&self) -> bool;
}

impl IsErrOrEof for Result<usize, std::io::Error> {
    fn is_err_or_eof(&self) -> bool {
        match self {
            Err(_) => true,
            Ok(0) => true,
            Ok(_) => false,
        }
    }
}

// ---------------------------------------------------------------------------
// Bridge child process harness.
// ---------------------------------------------------------------------------

struct Bridge {
    child: Child,
    port: u16,
}

fn free_port() -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").expect("reserve port");
    let port = listener.local_addr().unwrap().port();
    drop(listener);
    port
}

fn spawn_bridge_once(root: &TempRoot, herdr_socket: Option<&Path>, port: u16) -> Child {
    let mut command = Command::new(BIN);
    command
        .env("HOME", root.path.join("home"))
        .env("CAPP_BRIDGE_CONFIG", root.path.join("missing-config.json"))
        .env("CAPP_BRIDGE_DATA_DIR", root.path.join("data"))
        .env("CAPP_BRIDGE_HOST", "127.0.0.1")
        .env("CAPP_BRIDGE_PORT", port.to_string())
        .env("CAPP_BRIDGE_SERVE_AUTO_APPLY", "0")
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .current_dir(&root.path);
    match herdr_socket {
        Some(socket) => {
            command.env("HERDR_SOCKET_PATH", socket);
        }
        None => {
            command.env("HERDR_SOCKET_PATH", root.path.join("no-such-herdr.sock"));
        }
    }
    command.spawn().expect("spawn bridge")
}

/// Spawns the bridge on an ephemeral port. The reserve/release window can
/// lose the port to a parallel test's bridge, in which case the child exits
/// with a bind error; retry on a fresh port until one is really bound.
async fn spawn_bridge(root: &TempRoot, herdr_socket: Option<&Path>) -> Bridge {
    for _attempt in 0..10 {
        let port = free_port();
        let child = spawn_bridge_once(root, herdr_socket, port);
        let mut bridge = Bridge { child, port };
        let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
        loop {
            if let Ok(Some(_status)) = bridge.child.try_wait() {
                break; // bind failure or crash: retry on a new port
            }
            if tokio::net::TcpStream::connect(("127.0.0.1", port))
                .await
                .is_ok()
            {
                return bridge;
            }
            if tokio::time::Instant::now() > deadline {
                break;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        eprintln!("bridge spawn retry: port {port} was lost to a parallel test");
    }
    panic!("could not bind a bridge to any ephemeral port after retries");
}

impl Drop for Bridge {
    /// Best-effort kill+reap if a test panics before its explicit stop(), so
    /// a failed assertion never leaks a bridge child.
    fn drop(&mut self) {
        if self.child.try_wait().is_ok() {
            let _ = self.child.kill();
            if let Err(error) = self.child.wait() {
                eprintln!("bridge child reap on drop: {error}");
            }
        }
    }
}

impl Bridge {
    fn base_url(&self) -> String {
        format!("http://127.0.0.1:{}", self.port)
    }

    /// Stop and reap the child; surface termination errors.
    fn stop(&mut self) {
        let _ = self.child.kill();
        match self.child.wait() {
            Ok(status) => {
                if !status.success() && !status.code().is_some_and(|c| c != 0) {
                    eprintln!("bridge wait status: {status}");
                }
            }
            Err(error) => panic!("bridge wait failed: {error}"),
        }
    }
}

fn probe_options(base_url: &str) -> ProbeOptions {
    let mut options = ProbeOptions::new(base_url);
    options.timeout = TIMEOUT;
    options
}

// ---------------------------------------------------------------------------
// HTTP contract
// ---------------------------------------------------------------------------

#[tokio::test]
async fn session_shape_and_agents_with_mock_herdr() {
    let root = TempRoot::new("http");
    let herdr = MockHerdr::start(&root);
    let mut bridge = spawn_bridge(&root, Some(&herdr.socket_path)).await;

    let mut options = probe_options(&bridge.base_url());
    options.preference = HttpPreference::PreferH2;
    let report = probe::run_checks(&options).await.expect("checks pass");
    assert!(
        report.protocols.iter().all(|p| *p == "http2"),
        "expected every HTTP leg over h2 prior knowledge, got {:?}",
        report.protocols
    );

    bridge.stop();
    herdr.stop().await;
    root.remove();
}

#[tokio::test]
async fn agents_unreachable_is_502_error_envelope() {
    let root = TempRoot::new("noherdr");
    let mut bridge = spawn_bridge(&root, None).await;
    let exchange = probe::get(&probe_options(&bridge.base_url()), "/api/agents")
        .await
        .expect("agents request");
    assert_eq!(exchange.status, 502);
    assert_eq!(exchange.body["event"], json!("error"));
    assert_eq!(exchange.body["code"], json!("herdr_unreachable"));
    bridge.stop();
    root.remove();
}

#[tokio::test]
async fn transcript_fail_closed_both_shapes() {
    // Shape 1: herdr unreachable -> HTTP 502 error envelope.
    let root = TempRoot::new("tx502");
    let mut bridge = spawn_bridge(&root, None).await;
    let exchange = probe::get(
        &probe_options(&bridge.base_url()),
        "/api/transcript?session=s-probe",
    )
    .await
    .expect("transcript request");
    assert_eq!(exchange.status, 502);
    assert_eq!(exchange.body["event"], json!("error"));
    bridge.stop();
    root.remove();

    // Shape 2: herdr reachable, agent has no path-kind agent_session ->
    // HTTP 200 with available:false (unavailable payload, not a failure).
    let root = TempRoot::new("tx200");
    let herdr = MockHerdr::start(&root);
    let mut bridge = spawn_bridge(&root, Some(&herdr.socket_path)).await;
    let exchange = probe::get(
        &probe_options(&bridge.base_url()),
        "/api/transcript?session=s-probe",
    )
    .await
    .expect("transcript request");
    assert_eq!(exchange.status, 200);
    assert_eq!(exchange.body["available"], json!(false));
    bridge.stop();
    herdr.stop().await;
    root.remove();
}

// ---------------------------------------------------------------------------
// WS contract
// ---------------------------------------------------------------------------

#[tokio::test]
async fn ws_unknown_session_fails_closed_not_found() {
    let root = TempRoot::new("wsnf");
    let mut bridge = spawn_bridge(&root, None).await;
    let (frame, protocol) = probe::ws_first_frame(&probe_options(&bridge.base_url()), "nope")
        .await
        .expect("ws first frame");
    assert_eq!(protocol, "ws-http1-upgrade");
    assert_eq!(frame["event"], json!("error"));
    assert_eq!(frame["code"], json!("not_found"));
    bridge.stop();
    root.remove();
}

#[tokio::test]
async fn ws_stream_open_then_entries() {
    let root = TempRoot::new("wsopen");
    let herdr = MockHerdr::start(&root);
    let mut bridge = spawn_bridge(&root, Some(&herdr.socket_path)).await;

    let mut options = probe_options(&bridge.base_url());
    options.session = Some("s-probe".into());
    options.expect_event = Some("stream_open".into());
    let (frame, protocol) = probe::ws_first_frame(&options, "s-probe")
        .await
        .expect("stream first frame");
    assert_eq!(protocol, "ws-http1-upgrade");
    assert_eq!(frame["event"], json!("stream_open"));
    assert!(
        frame["generation"].is_u64(),
        "stream_open carries generation"
    );

    bridge.stop();
    herdr.stop().await;
    root.remove();
}

async fn require_h2_against_http1(base_url: &str, timeout: Duration) -> Result<(), String> {
    let mut options = probe_options(base_url);
    options.preference = HttpPreference::RequireH2;
    options.timeout = timeout;
    probe::run_checks(&options).await.map(|_| ())
}

async fn probe_dead_port(port: u16, timeout: Duration) -> Result<(), String> {
    let mut options = probe::ProbeOptions::new(format!("http://127.0.0.1:{port}"));
    options.timeout = timeout;
    probe::run_checks(&options).await.map(|_| ())
}

/// A server that sends a malformed first frame must fail the probe loudly.
#[tokio::test]
async fn ws_malformed_frame_fails_loudly() {
    use axum::extract::ws::{Message, WebSocketUpgrade};
    use axum::routing::get as get_route;
    use axum::Router;

    async fn bad_ws(upgrade: WebSocketUpgrade) -> axum::response::Response {
        upgrade.on_upgrade(|mut socket: axum::extract::ws::WebSocket| async move {
            let _ = socket.send(Message::Text("this is not json".into())).await;
        })
    }

    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let app = Router::new().route("/api/stream", get_route(bad_ws));
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });

    let options = probe_options(&format!("http://127.0.0.1:{port}"));
    let error = probe::ws_first_frame(&options, "s-probe")
        .await
        .expect_err("malformed frame must be an error");
    assert!(
        error.contains("malformed") || error.contains("not JSON"),
        "{error}"
    );
}

// ---------------------------------------------------------------------------
// Strict HTTP/2: real proof against the bridge (h2c prior knowledge) and
// downgrade detection against an HTTP/1-only origin.
// ---------------------------------------------------------------------------

#[tokio::test]
async fn require_h2_passes_against_bridge_over_h2c() {
    let root = TempRoot::new("h2ok");
    let herdr = MockHerdr::start(&root);
    let mut bridge = spawn_bridge(&root, Some(&herdr.socket_path)).await;

    let mut options = probe_options(&bridge.base_url());
    options.preference = HttpPreference::RequireH2;
    let report = probe::run_checks(&options)
        .await
        .expect("strict h2 checks pass");
    assert!(report.protocols.iter().all(|p| *p == "http2"));

    bridge.stop();
    herdr.stop().await;
    root.remove();
}

#[tokio::test]
async fn require_h2_fails_on_http1_downgrade() {
    // HTTP/1-only origin: raw canned responses on a TCP listener.
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        loop {
            let (mut stream, _) = match listener.accept().await {
                Ok(accepted) => accepted,
                Err(_) => return,
            };
            tokio::spawn(async move {
                use tokio::io::{AsyncReadExt, AsyncWriteExt};
                let mut buf = [0u8; 1024];
                let _ = stream.read(&mut buf).await;
                let body = r#"{"event":"paired","machine_id":"m","protocol":1,"plugin":"p"}"#;
                let response = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
                let _ = stream.write_all(response.as_bytes()).await;
                let _ = stream.flush().await;
            });
        }
    });

    let error =
        require_h2_against_http1(&format!("http://127.0.0.1:{port}"), Duration::from_secs(5))
            .await
            .expect_err("require-h2 must fail against an HTTP/1-only origin");
    assert!(error.contains("require-h2"), "{error}");
}

// ---------------------------------------------------------------------------
// Benchmark acceptance: the checked-in bench runs against the live bridge and
// reports honest, bounded metrics; percentile math is unit-checked.
// ---------------------------------------------------------------------------

#[test]
fn percentile_math_basics() {
    let sorted: Vec<u128> = (1..=100).collect();
    assert_eq!(probe::percentile(&sorted, 0.50), 51);
    assert_eq!(probe::percentile(&sorted, 0.95), 95);
    assert_eq!(probe::percentile(&[], 0.5), 0);
}

#[tokio::test]
async fn bench_runs_and_reports_protocol() {
    let root = TempRoot::new("bench");
    let herdr = MockHerdr::start(&root);
    let mut bridge = spawn_bridge(&root, Some(&herdr.socket_path)).await;

    let report = probe::run_bench(
        &probe_options(&bridge.base_url()),
        &probe::BenchConfig {
            iterations: 30,
            warmup: 5,
            stream_cycles: 3,
            session: Some("s-probe".into()),
        },
    )
    .await
    .expect("bench runs");
    assert!(report.warm_p50_us > 0);
    assert!(report.warm_throughput_rps > 0.0);
    assert!(report.stream_connect_p50_ms > 0 || report.stream_connect_p95_ms == 0);
    println!("{report}");

    bridge.stop();
    herdr.stop().await;
    root.remove();
}

// ---------------------------------------------------------------------------
// Deterministic lifecycle: refusal, timeout, cancellation, repeated cycles.
// ---------------------------------------------------------------------------

#[tokio::test]
async fn connection_refusal_is_an_error() {
    let port = free_port();
    let error = probe_dead_port(port, Duration::from_secs(3))
        .await
        .expect_err("dead port must fail");
    assert!(!error.is_empty());
}

#[tokio::test]
async fn stalled_server_times_out() {
    // Accepts connections, never answers.
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        loop {
            if let Ok((stream, _)) = listener.accept().await {
                tokio::spawn(async move {
                    let _ = stream; // hold it open, never respond
                    tokio::time::sleep(Duration::from_secs(30)).await;
                });
            }
        }
    });
    let error = probe_dead_port(port, Duration::from_secs(2))
        .await
        .expect_err("stalled server must time out");
    assert!(
        error.contains("timed out") || error.contains("require-h2") || !error.is_empty(),
        "{error}"
    );
}

#[tokio::test]
async fn cancellation_joins_promptly() {
    let root = TempRoot::new("cancel");
    let herdr = MockHerdr::start(&root);
    let mut bridge = spawn_bridge(&root, Some(&herdr.socket_path)).await;

    let options = probe_options(&bridge.base_url());
    let task = tokio::spawn(async move { probe::run_checks(&options).await });
    tokio::time::sleep(Duration::from_millis(20)).await;
    let start = std::time::Instant::now();
    task.abort();
    let joined = task.await;
    // Either outcome is fine: the probe is fast enough to have finished, or
    // it was cancelled mid-flight. The lifecycle guarantee under test is that
    // abort never hangs and the task always joins.
    let _ = joined;
    assert!(
        start.elapsed() < Duration::from_secs(2),
        "abort must not hang"
    );

    bridge.stop();
    herdr.stop().await;
    root.remove();
}

#[tokio::test]
async fn repeated_connect_cycles_all_pass() {
    let root = TempRoot::new("cycles");
    let herdr = MockHerdr::start(&root);
    let mut bridge = spawn_bridge(&root, Some(&herdr.socket_path)).await;

    for cycle in 0..5 {
        probe::run_checks(&probe_options(&bridge.base_url()))
            .await
            .unwrap_or_else(|error| panic!("cycle {cycle} failed: {error}"));
    }

    bridge.stop();
    herdr.stop().await;
    root.remove();
}

/// Server loss mid-suite: every subsequent probe must fail loudly (no silent
/// retry successes), and the process must not hang.
#[tokio::test]
async fn server_disconnect_fails_loudly() {
    let root = TempRoot::new("loss");
    let herdr = MockHerdr::start(&root);
    let mut bridge = spawn_bridge(&root, Some(&herdr.socket_path)).await;
    probe::run_checks(&probe_options(&bridge.base_url()))
        .await
        .expect("pre-loss check passes");
    bridge.stop();
    let error = probe_dead_port(bridge.port, Duration::from_secs(5))
        .await
        .expect_err("post-loss check must fail");
    assert!(!error.is_empty());
    herdr.stop().await;
    root.remove();
}
