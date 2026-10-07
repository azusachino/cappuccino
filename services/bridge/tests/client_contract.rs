//! Hermetic contract tests for the `test-client` companion and the bridge's
//! public HTTP/WS surface. The bridge binary is spawned against a mock Herdr
//! socket (temp dirs, loopback, ephemeral ports, no owner sessions, no
//! Tailscale: serve auto-apply disabled). The probe module is shared with the
//! shipped example CLI by path.
//!
//! Harness guarantees: mock socket connection tasks are tracked and joined
//! with a deadline on teardown; bridge children are killed, reaped with a
//! bounded wait and re-checked via `try_wait`; cleanup errors are surfaced.

#[path = "../examples/test-client/probe.rs"]
mod probe;

use probe::{HttpPreference, ProbeOptions, StreamProbe};
use serde_json::{json, Value};
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

const BIN: &str = env!("CARGO_BIN_EXE_cappuccino-bridge");
static NEXT: AtomicU64 = AtomicU64::new(0);
const TIMEOUT: Duration = Duration::from_secs(10);
const TEARDOWN_DEADLINE: Duration = Duration::from_secs(10);

// ---------------------------------------------------------------------------
// Temp root: identity-safe cleanup, errors surfaced, never ignored.
// ---------------------------------------------------------------------------

#[path = "common/resource_scope.rs"]
mod resource_scope;

struct TempRoot {
    root: resource_scope::PrivateDir,
}
impl std::ops::Deref for TempRoot {
    type Target = resource_scope::PrivateDir;
    fn deref(&self) -> &Self::Target {
        &self.root
    }
}
impl TempRoot {
    fn new(label: &str) -> Self {
        let path = std::env::temp_dir().join(format!(
            "capp-client-test-{label}-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        Self {
            root: resource_scope::PrivateDir::new(path),
        }
    }
    fn remove(self) {
        self.root.remove();
    }
}

// ---------------------------------------------------------------------------
// Mock Herdr socket: one NDJSON request per short-lived connection. All
// per-connection tasks are tracked and joined (aborted) with a deadline on
// stop; the socket path and the agent cwd are switchable for reset scenarios.
// ---------------------------------------------------------------------------

struct MockHerdr {
    socket_path: PathBuf,
    cwd: Arc<Mutex<Option<PathBuf>>>,
    agent_session: Arc<Mutex<Option<String>>>,
    connections: Arc<std::sync::atomic::AtomicUsize>,
    listener_task: tokio::task::JoinHandle<()>,
}

impl MockHerdr {
    fn start(root: &TempRoot) -> Self {
        let socket_path = root.path.join("herdr.sock");
        let listener = tokio::net::UnixListener::bind(&socket_path).expect("bind mock herdr");
        let cwd: Arc<Mutex<Option<PathBuf>>> = Arc::new(Mutex::new(None));
        let agent_session: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));
        let cwd_task = Arc::clone(&cwd);
        let session_task = Arc::clone(&agent_session);
        let active = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let connections_counter = Arc::clone(&active);
        let listener_task = tokio::spawn(async move {
            let mut connections = futures_util::stream::FuturesUnordered::new();
            loop {
                tokio::select! {
                    accepted = listener.accept() => match accepted {
                        Ok((stream, _)) => {
                            let guard = RunningGuard::new(Arc::clone(&connections_counter));
                            let cwd = Arc::clone(&cwd_task);
                            let session = Arc::clone(&session_task);
                            connections.push(async move {
                                let _guard = guard;
                                handle_mock_connection(stream, cwd, session).await;
                            });
                        },
                        Err(_) => return,
                    },
                    _ = futures_util::StreamExt::next(&mut connections), if !connections.is_empty() => {}
                }
            }
        });
        Self {
            socket_path,
            cwd,
            agent_session,
            connections: active,
            listener_task,
        }
    }

    /// Sets the agent cwd returned by agent.list / agent.get (for branch
    /// scenarios). `None` means the empty cwd (no branch).
    fn set_cwd(&self, cwd: Option<&Path>) {
        *self.cwd.lock().unwrap() = cwd.map(Path::to_path_buf);
    }

    /// Sets the path-kind `agent_session` returned by agent.get (for the
    /// available-transcript scenario).
    fn set_agent_session(&self, value: Option<&str>) {
        *self.agent_session.lock().unwrap() = value.map(str::to_string);
    }

    /// One listener owns all unspawned connection futures. Its terminal join
    /// drops every connection before the socket path is removed.
    async fn stop(self) {
        let socket = self.socket_path;
        task_scope::cancel_with_cleanup(self.listener_task, TEARDOWN_DEADLINE, false, move || {
            std::fs::remove_file(socket).map_err(|error| format!("mock socket cleanup: {error}"))
        })
        .await
        .expect_clean();
    }
}

async fn handle_mock_connection(
    stream: tokio::net::UnixStream,
    cwd: Arc<Mutex<Option<PathBuf>>>,
    agent_session: Arc<Mutex<Option<String>>>,
) {
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt};
    let (reader, mut writer) = stream.into_split();
    let mut lines = tokio::io::BufReader::new(reader);
    let mut line = String::new();
    if matches!(lines.read_line(&mut line).await, Ok(0) | Err(_)) {
        return;
    }
    let request: Value = serde_json::from_str(line.trim()).unwrap_or(Value::Null);
    let cwd_text = cwd
        .lock()
        .unwrap()
        .as_ref()
        .map(|path| path.to_string_lossy().into_owned())
        .unwrap_or_default();
    let session_value = agent_session.lock().unwrap().clone();
    let agent_session_json = match session_value {
        Some(value) => json!({"kind": "path", "value": value}),
        None => json!(null),
    };
    let result = match request["method"].as_str().unwrap_or("") {
        "agent.list" => json!({
            "agents": [{
                "name": "s-probe",
                "pane_id": "w1:a",
                "agent": "pi",
                "agent_status": "idle",
                "cwd": cwd_text,
            }]
        }),
        "agent.get" => json!({
            "agent": {
                "name": "s-probe",
                "pane_id": "w1:a",
                "agent": "pi",
                "agent_status": "idle",
                "cwd": cwd_text,
                "agent_session": agent_session_json,
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
}

// ---------------------------------------------------------------------------
// Bridge child process harness: bind-retry, bounded reap, identity cleanup.
// ---------------------------------------------------------------------------

struct Bridge {
    child: resource_scope::OwnedChild,
    port: u16,
}

fn free_port() -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").expect("reserve port");
    let port = listener.local_addr().unwrap().port();
    drop(listener);
    port
}

fn spawn_bridge_once(
    root: &TempRoot,
    herdr_socket: Option<&Path>,
    port: u16,
    extra_env: &[(&str, &str)],
) -> Child {
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
    for (key, value) in extra_env {
        command.env(key, value);
    }
    command.spawn().expect("spawn bridge")
}

/// Spawns the bridge on an ephemeral port. The reserve/release window can
/// lose the port to a parallel test's bridge, in which case the child exits
/// with a bind error; retry on a fresh port until one is really bound.
async fn spawn_bridge(root: &TempRoot, herdr_socket: Option<&Path>) -> Bridge {
    spawn_bridge_with(root, herdr_socket, &[]).await
}

async fn spawn_bridge_with(
    root: &TempRoot,
    herdr_socket: Option<&Path>,
    extra_env: &[(&str, &str)],
) -> Bridge {
    for _attempt in 0..10 {
        let port = free_port();
        let child = spawn_bridge_once(root, herdr_socket, port, extra_env);
        let mut bridge = Bridge {
            child: child.into(),
            port,
        };
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

impl Bridge {
    fn base_url(&self) -> String {
        format!("http://127.0.0.1:{}", self.port)
    }

    /// Kill, then reap within a bounded wait; assert the child is really gone
    /// (no zombie). Kill/wait/try_wait errors are surfaced: the normal path
    /// returns an explicit Result, an already-reaped child is a no-op.
    fn stop(&mut self) -> Result<std::process::ExitStatus, String> {
        self.child.stop(TEARDOWN_DEADLINE)
    }
}

/// Abort and join one tracked task under the teardown deadline. A panicked
/// task is surfaced; expected cancellation (`is_cancelled`) is accepted.
/// No join error is swallowed.
async fn join_tracked(task: tokio::task::JoinHandle<()>, _label: &str) {
    task_scope::cancel_and_join(task, TEARDOWN_DEADLINE, false)
        .await
        .expect_clean();
}

fn probe_options(base_url: &str) -> ProbeOptions {
    let mut options = ProbeOptions::new(base_url);
    options.timeout = TIMEOUT;
    options
}

async fn ws_probe(options: &ProbeOptions, session: &str, followup: Duration) -> StreamProbe {
    probe::ws_stream_probe(options, session, followup)
        .await
        .expect("ws stream probe")
}

fn git(repo: &Path, args: &[&str]) {
    let status = Command::new("git")
        .arg("-C")
        .arg(repo)
        .args(args)
        .status()
        .expect("run git");
    assert!(status.success(), "git {args:?} failed");
}

// ---------------------------------------------------------------------------
// HTTP contract
// ---------------------------------------------------------------------------

#[tokio::test]
async fn mock_herdr_stop_drops_all_concurrent_connection_futures() {
    let root = TempRoot::new("mockstop");
    let herdr = MockHerdr::start(&root);
    let mut clients = Vec::new();
    for _ in 0..8 {
        clients.push(
            tokio::net::UnixStream::connect(&herdr.socket_path)
                .await
                .unwrap(),
        );
    }
    // No requests: all accepted connections remain pending in read_line.
    let active = Arc::clone(&herdr.connections);
    tokio::time::timeout(TIMEOUT, async {
        while active.load(std::sync::atomic::Ordering::SeqCst) != clients.len() {
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("all pending connections accepted");
    herdr.stop().await;
    assert_eq!(active.load(std::sync::atomic::Ordering::SeqCst), 0);
    use tokio::io::AsyncReadExt;
    for mut client in clients {
        let mut byte = [0];
        let read = tokio::time::timeout(TIMEOUT, client.read(&mut byte))
            .await
            .expect("closed pending peer")
            .expect("clean EOF");
        assert_eq!(read, 0, "every accepted stream must close");
    }
    root.remove();
}

#[tokio::test]
async fn session_shape_and_agents_with_mock_herdr() {
    let root = TempRoot::new("http");
    let herdr = MockHerdr::start(&root);
    let mut bridge = spawn_bridge(&root, Some(&herdr.socket_path)).await;

    let mut options = probe_options(&bridge.base_url());
    options.preference = HttpPreference::PreferH2;
    // Under heavy parallel load a single h2c attempt can time out and fall
    // back; preference is proven when any attempt completes fully over h2,
    // while persistent fallback still fails the test.
    let mut report = None;
    for _attempt in 0..3 {
        let attempt = probe::run_checks(&options).await.expect("checks pass");
        if attempt.protocols.iter().all(|p| *p == "http2") {
            report = Some(attempt);
            break;
        }
        report = Some(attempt);
    }
    let report = report.expect("at least one attempt");
    assert!(
        report.protocols.iter().all(|p| *p == "http2"),
        "expected every HTTP leg over h2 prior knowledge, got {:?}",
        report.protocols
    );

    bridge.stop().expect("bridge stop");
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
    bridge.stop().expect("bridge stop");
    root.remove();
}

#[tokio::test]
async fn transcript_fail_closed_all_modes() {
    // Mode 1: herdr unreachable -> HTTP 502 error envelope.
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
    bridge.stop().expect("bridge stop");
    root.remove();

    // Mode 2: herdr reachable, agent has no path-kind agent_session ->
    // HTTP 200 with available:false and a null path.
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
    assert!(exchange.body["transcript_path"].is_null());
    bridge.stop().expect("bridge stop");
    herdr.stop().await;
    root.remove();

    // Mode 3: an available transcript — a path-kind agent_session inside the
    // Pi store (PI_CODING_AGENT_SESSION_DIR scoped to the temp root) must
    // yield HTTP 200 with available:true and a nonempty transcript_path.
    let root = TempRoot::new("tx-avail");
    let sessions = root.path.join("sessions");
    std::fs::create_dir(&sessions).unwrap();
    let transcript = sessions.join("s-probe.jsonl");
    std::fs::write(&transcript, b"{\"line\":1}\n").unwrap();
    let herdr = MockHerdr::start(&root);
    herdr.set_agent_session(Some(&transcript.to_string_lossy()));
    let mut bridge = spawn_bridge_with(
        &root,
        Some(&herdr.socket_path),
        &[("PI_CODING_AGENT_SESSION_DIR", sessions.to_str().unwrap())],
    )
    .await;
    let exchange = probe::get(
        &probe_options(&bridge.base_url()),
        "/api/transcript?session=s-probe",
    )
    .await
    .expect("transcript request");
    assert_eq!(exchange.status, 200, "payload: {}", exchange.body);
    assert_eq!(exchange.body["available"], json!(true));
    let path = exchange.body["transcript_path"]
        .as_str()
        .expect("available:true must carry a path");
    assert!(!path.is_empty());
    bridge.stop().expect("bridge stop");
    herdr.stop().await;
    root.remove();
}

#[tokio::test]
async fn session_requires_nonempty_machine_id() {
    // A paired payload without machine_id must fail the check: spin an
    // HTTP/1 origin returning a machine_id-less paired body.
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let tracker = Arc::new(TaskTracker::default());
    let server_tracker = Arc::clone(&tracker);
    if let Err(rejected) = tracker.spawn(async move {
        loop {
            let Ok((mut stream, _)) = listener.accept().await else {
                return;
            };
            // Per-connection work is owned by the tracker, not detached; a
            // spawn after closure is rejected and the future dropped without
            // starting (the accepted socket is dropped with it).
            if server_tracker.spawn(async move {
                use tokio::io::{AsyncReadExt, AsyncWriteExt};
                let mut buf = [0u8; 1024];
                let _ = stream.read(&mut buf).await;
                let body = r#"{"event":"paired","protocol":1,"plugin":"p"}"#;
                let response = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
                let _ = stream.write_all(response.as_bytes()).await;
            })
            .is_err()
            {
                // Spawn after closure: the future was dropped unstarted and
                // the accepted socket closed with it.
                eprintln!("connection after tracker closure: rejected, not started");
            }
        }
    }) {
        // A producer rejected after closure is the expected shutdown race:
        // the listener future was dropped without starting.
        eprintln!("producer after tracker closure: rejected, not started: {rejected}");
    }
    let mut options = probe_options(&format!("http://127.0.0.1:{port}"));
    options.timeout = Duration::from_secs(5);
    options.session_deadline = Duration::from_secs(10);
    let error = probe::run_checks(&options)
        .await
        .expect_err("missing machine_id must fail");
    assert!(error.contains("machine_id"), "{error}");
    tracker.stop().await.expect_clean();
}

// ---------------------------------------------------------------------------
// WS contract
// ---------------------------------------------------------------------------

#[tokio::test]
async fn ws_unknown_session_fails_closed_not_found() {
    let root = TempRoot::new("wsnf");
    let mut bridge = spawn_bridge(&root, None).await;
    let outcome = probe::ws_stream_probe(
        &probe_options(&bridge.base_url()),
        "nope",
        Duration::from_millis(500),
    )
    .await;
    match outcome {
        Ok(stream) => {
            // Clean close: the bridge sent a real WebSocket Close frame after
            // the fail-closed error frame.
            assert_eq!(stream.first_event, "error");
            assert_eq!(stream.error_code.as_deref(), Some("not_found"));
            assert!(
                stream.closed_by_server,
                "a received Close frame marks closure"
            );
            assert!(
                stream.followup_events.is_empty(),
                "{:?}",
                stream.followup_events
            );
        }
        Err(error) => {
            // If the bridge dropped the transport without a Close frame, the
            // probe must propagate that as a protocol error, not a success.
            assert!(
                error.contains("without a WebSocket Close frame")
                    || error.contains("closing handshake"),
                "{error}"
            );
        }
    }
    bridge.stop().expect("bridge stop");
    root.remove();
}

#[tokio::test]
async fn ws_stream_open_generation_then_entries() {
    let root = TempRoot::new("wsopen");
    let herdr = MockHerdr::start(&root);
    let mut bridge = spawn_bridge(&root, Some(&herdr.socket_path)).await;

    let stream = ws_probe(
        &probe_options(&bridge.base_url()),
        "s-probe",
        Duration::from_secs(3),
    )
    .await;
    assert_eq!(stream.first_event, "stream_open");
    assert_eq!(stream.generation, Some(0), "initial generation is zero");
    // The mock answers pane.read with two fresh lines and an idle agent, so
    // the 400ms poll loop must emit a validated entries event.
    assert!(
        stream
            .followup_events
            .iter()
            .any(|event| event == "entries"),
        "expected an entries event, got {:?}",
        stream.followup_events
    );
    assert!(
        stream.entries_consumed >= 2,
        "entries consumed: {}",
        stream.entries_consumed
    );

    bridge.stop().expect("bridge stop");
    herdr.stop().await;
    root.remove();
}

/// Branch change must surface as stream_reset with an increased generation.
#[tokio::test]
async fn ws_branch_change_produces_stream_reset() {
    let root = TempRoot::new("wsreset");
    let repo_main = root.path.join("repo-main");
    let repo_other = root.path.join("repo-other");
    std::fs::create_dir(&repo_main).unwrap();
    std::fs::create_dir(&repo_other).unwrap();
    git(&repo_main, &["init", "-q", "-b", "main"]);
    git(&repo_other, &["init", "-q", "-b", "feature"]);

    let herdr = MockHerdr::start(&root);
    herdr.set_cwd(Some(&repo_main));
    let mut bridge = spawn_bridge(&root, Some(&herdr.socket_path)).await;

    let options = probe_options(&bridge.base_url());
    let options = {
        let mut options = options;
        options.session = Some("s-probe".into());
        options
    };
    let handle = tokio::spawn({
        let options = options.clone();
        async move { ws_probe(&options, "s-probe", Duration::from_secs(4)).await }
    });
    // Let the stream open on the main branch, then flip the reported cwd.
    tokio::time::sleep(Duration::from_millis(700)).await;
    herdr.set_cwd(Some(&repo_other));
    let stream = handle.await.expect("stream probe task");

    assert_eq!(stream.first_event, "stream_open");
    assert!(
        stream
            .followup_events
            .iter()
            .any(|event| event == "stream_reset"),
        "expected stream_reset after a branch change, got {:?}",
        stream.followup_events
    );

    bridge.stop().expect("bridge stop");
    herdr.stop().await;
    root.remove();
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
    let server = tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });

    let options = probe_options(&format!("http://127.0.0.1:{port}"));
    let error = probe::ws_stream_probe(&options, "s-probe", Duration::ZERO)
        .await
        .expect_err("malformed frame must be an error");
    assert!(error.contains("malformed"), "{error}");

    join_tracked(server, "malformed-frame server").await;
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
    // Retry tolerates transient load spikes in the strict proof; a real
    // downgrade always errors on every attempt.
    let mut report = None;
    for _attempt in 0..3 {
        if let Ok(attempt) = probe::run_checks(&options).await {
            report = Some(attempt);
            break;
        }
    }
    let report = report.expect("strict h2 checks pass against the bridge");
    assert!(report.protocols.iter().all(|p| *p == "http2"));

    bridge.stop().expect("bridge stop");
    herdr.stop().await;
    root.remove();
}

#[tokio::test]
async fn require_h2_fails_on_http1_downgrade() {
    // HTTP/1-only origin: raw canned responses on a TCP listener.
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let tracker = Arc::new(TaskTracker::default());
    let server_tracker = Arc::clone(&tracker);
    if let Err(rejected) = tracker.spawn(async move {
        loop {
            let (mut stream, _) = match listener.accept().await {
                Ok(accepted) => accepted,
                Err(_) => return,
            };
            // Per-connection work is owned by the tracker, not detached; a
            // spawn after closure is rejected and the future dropped without
            // starting (the accepted socket is dropped with it).
            if server_tracker.spawn(async move {
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
            })
            .is_err()
            {
                // Spawn after closure: the future was dropped unstarted and
                // the accepted socket closed with it.
                eprintln!("connection after tracker closure: rejected, not started");
            }
        }
    }) {
        // A producer rejected after closure is the expected shutdown race:
        // the listener future was dropped without starting.
        eprintln!("producer after tracker closure: rejected, not started: {rejected}");
    }

    let mut options = probe_options(&format!("http://127.0.0.1:{port}"));
    options.preference = HttpPreference::RequireH2;
    options.timeout = Duration::from_secs(5);
    let error = probe::run_checks(&options)
        .await
        .expect_err("require-h2 must fail against an HTTP/1-only origin");
    assert!(error.contains("require-h2"), "{error}");
    tracker.stop().await.expect_clean();
}

// ---------------------------------------------------------------------------
// Deterministic lifecycle: refusal, timeout, mid-flight cancellation,
// repeated cycles, disconnect.
// ---------------------------------------------------------------------------

#[tokio::test]
async fn connection_refusal_is_an_error() {
    let port = free_port();
    let mut options = probe::ProbeOptions::new(format!("http://127.0.0.1:{port}"));
    options.timeout = Duration::from_secs(3);
    let error = probe::run_checks(&options)
        .await
        .expect_err("dead port must fail");
    assert!(!error.is_empty());
}

#[tokio::test]
async fn stalled_server_times_out() {
    // Accepts connections, never answers.
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let tracker = Arc::new(TaskTracker::default());
    let server_tracker = Arc::clone(&tracker);
    if let Err(rejected) = tracker.spawn(async move {
        loop {
            if let Ok((stream, _)) = listener.accept().await {
                // Per-connection hold tasks are owned by the tracker too.
                if server_tracker
                    .spawn(async move {
                        let _held = stream; // hold it open, never respond
                        tokio::time::sleep(Duration::from_secs(30)).await;
                    })
                    .is_err()
                {
                    eprintln!("hold task after closure: rejected, socket dropped");
                }
            }
        }
    }) {
        eprintln!("stall producer after closure: rejected, not started: {rejected}");
    }
    let mut options = probe::ProbeOptions::new(format!("http://127.0.0.1:{port}"));
    options.timeout = Duration::from_secs(2);
    options.session_deadline = Duration::from_secs(3);
    let error = probe::run_checks(&options)
        .await
        .expect_err("stalled server must time out");
    assert!(!error.is_empty());
    tracker.stop().await.expect_clean();
}

/// Deterministic mid-flight cancellation: the server stalls, so the probe is
/// guaranteed to be in flight when aborted; the task must join promptly.
#[tokio::test]
async fn cancellation_joins_promptly_midflight() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let held: Arc<Mutex<Vec<tokio::net::TcpStream>>> = Arc::new(Mutex::new(Vec::new()));
    let accepted = Arc::new(tokio::sync::Notify::new());
    let held_holder = Arc::clone(&held);
    let accepted_holder = Arc::clone(&accepted);
    let holder = TaskTracker::default();
    if let Err(rejected) = holder.spawn(async move {
        loop {
            if let Ok((stream, _)) = listener.accept().await {
                held_holder.lock().unwrap().push(stream);
                accepted_holder.notify_one();
            }
        }
    }) {
        eprintln!("holder producer after closure: rejected, not started: {rejected}");
    }

    let options = probe::ProbeOptions::new(format!("http://127.0.0.1:{port}"));
    let task = tokio::spawn(async move {
        let mut options = options;
        options.timeout = Duration::from_secs(10);
        probe::run_checks(&options).await
    });
    // Deterministic barrier: the holder signals after it has accepted the
    // connection, so the abort is provably mid-flight (no fixed sleep).
    accepted.notified().await;
    let start = std::time::Instant::now();
    require_midflight_cancelled(task, "mid-flight probe").await;
    assert!(
        start.elapsed() < Duration::from_secs(2),
        "abort must not hang"
    );

    holder.stop().await.expect_clean();
    held.lock().unwrap().clear();
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

    bridge.stop().expect("bridge stop");
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
    bridge.stop().expect("bridge stop");
    let mut options = probe::ProbeOptions::new(format!("http://127.0.0.1:{}", bridge.port));
    options.timeout = Duration::from_secs(5);
    let error = probe::run_checks(&options)
        .await
        .expect_err("post-loss check must fail");
    assert!(!error.is_empty());
    herdr.stop().await;
    root.remove();
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

    let mut options = probe_options(&bridge.base_url());
    options.session = Some("s-probe".into());
    let report = probe::run_bench(
        &options,
        &probe::BenchConfig {
            iterations: 30,
            warmup: 5,
            stream_cycles: 3,
            session: Some("s-probe".into()),
            allow_error_streams: false,
        },
    )
    .await
    .expect("bench runs");
    assert!(report.warm_p50_us > 0);
    assert!(report.warm_throughput_rps > 0.0);
    // Microsecond timings must not quantize to zero.
    assert!(
        report.stream_cycles_completed == 3,
        "stream cycles: {}",
        report.stream_cycles_completed
    );
    assert!(
        report.stream_entries_consumed >= 3,
        "entries consumed: {}",
        report.stream_entries_consumed
    );
    assert!(
        report.cpu_delta_ms > 0,
        "cpu delta must be measured, not zero-filled"
    );
    assert!(
        report.rss_samples >= 3,
        "rss trend samples: {}",
        report.rss_samples
    );
    println!("{report}");

    bridge.stop().expect("bridge stop");
    herdr.stop().await;
    root.remove();
}

// ---------------------------------------------------------------------------
// Tracked fixture tasks: every spawned fixture server is aborted and joined
// under a deadline; a panicked task is surfaced, never counted as expected
// cancellation.
// ---------------------------------------------------------------------------

#[path = "common/task_scope.rs"]
mod task_scope;
use task_scope::TaskTracker;

async fn require_midflight_cancelled<T: Send + 'static>(
    task: tokio::task::JoinHandle<T>,
    _label: &str,
) {
    task_scope::cancel_and_join(task, TEARDOWN_DEADLINE, true)
        .await
        .expect_clean();
}

// ---------------------------------------------------------------------------
// Negative stream-validation fixtures: synthetic axum WS servers exercising
// the client's protocol guards (generation regression, invalid gap shape,
// binary data, empty-entries-as-consumption).
// ---------------------------------------------------------------------------

/// Spawns a tracked axum WS server that streams the given frames as JSON
/// texts after an opening `stream_open` frame, then keeps the connection.
async fn spawn_frame_server(frames: Vec<Value>) -> (String, TaskTracker) {
    use axum::extract::ws::{Message, WebSocketUpgrade};
    use axum::routing::get as get_route;
    use axum::Router;

    async fn stream_frames(
        upgrade: WebSocketUpgrade,
        frames: Vec<Value>,
    ) -> axum::response::Response {
        upgrade.on_upgrade(move |mut socket: axum::extract::ws::WebSocket| async move {
            for frame in frames {
                let text = serde_json::to_string(&frame).unwrap();
                if socket.send(Message::Text(text.into())).await.is_err() {
                    return;
                }
            }
            // Hold the connection open so the probe consumes within budget.
            while socket.recv().await.is_some() {}
        })
    }

    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let app = Router::new().route(
        "/api/stream",
        get_route(
            move |upgrade: WebSocketUpgrade| async move { stream_frames(upgrade, frames).await },
        ),
    );
    let tracker = TaskTracker::default();
    tracker
        .spawn(async move {
            let _ = axum::serve(listener, app).await;
        })
        .expect("fixture server spawns before any closure");
    (format!("http://127.0.0.1:{port}"), tracker)
}

fn stream_options(base: &str) -> ProbeOptions {
    let mut options = probe_options(base);
    options.session = Some("s-probe".into());
    options
}

#[tokio::test]
async fn stream_reset_generation_regression_is_rejected() {
    let frames = vec![
        json!({"event": "stream_open", "generation": 5}),
        json!({"event": "stream_reset", "generation": 3}),
    ];
    let (base, tracker) = spawn_frame_server(frames).await;
    let error = probe::ws_stream_probe(
        &stream_options(&base),
        "s-probe",
        Duration::from_millis(300),
    )
    .await
    .unwrap_err();
    assert!(error.contains("regression"), "{error}");
    tracker.stop().await.expect_clean();
}

#[tokio::test]
async fn invalid_gap_shape_is_rejected() {
    let frames = vec![
        json!({"event": "stream_open", "generation": 0}),
        json!({"event": "entries", "entries": [{
            "seq": 9, "id": "wrong-id", "kind": "gap",
            "text": "gap: missing entries 2-8", "branch": null, "complete": true }]}),
    ];
    let (base, tracker) = spawn_frame_server(frames).await;
    let error = probe::ws_stream_probe(
        &stream_options(&base),
        "s-probe",
        Duration::from_millis(300),
    )
    .await
    .unwrap_err();
    assert!(error.contains("gap entry must have null seq"), "{error}");
    tracker.stop().await.expect_clean();
}

#[tokio::test]
async fn binary_data_frame_is_rejected() {
    use axum::extract::ws::{Message, WebSocketUpgrade};
    use axum::routing::get as get_route;
    use axum::Router;

    async fn binary_ws(upgrade: WebSocketUpgrade) -> axum::response::Response {
        upgrade.on_upgrade(|mut socket: axum::extract::ws::WebSocket| async move {
            let _ = socket
                .send(Message::Text(
                    "{\"event\":\"stream_open\",\"generation\":0}".into(),
                ))
                .await;
            let _ = socket.send(Message::Binary(vec![0xde, 0xad].into())).await;
            while socket.recv().await.is_some() {}
        })
    }
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let app = Router::new().route("/api/stream", get_route(binary_ws));
    let tracker = TaskTracker::default();
    tracker
        .spawn(async move {
            let _ = axum::serve(listener, app).await;
        })
        .expect("fixture server spawns before any closure");
    let options = stream_options(&format!("http://127.0.0.1:{port}"));
    let error = probe::ws_stream_probe(&options, "s-probe", Duration::from_millis(300))
        .await
        .unwrap_err();
    assert!(error.contains("binary"), "{error}");
    tracker.stop().await.expect_clean();
}

#[tokio::test]
async fn empty_entries_do_not_count_as_consumption() {
    let frames = vec![
        json!({"event": "stream_open", "generation": 0}),
        json!({"event": "entries", "entries": []}),
    ];
    let (base, tracker) = spawn_frame_server(frames).await;
    let probe = ws_probe(
        &stream_options(&base),
        "s-probe",
        Duration::from_millis(300),
    )
    .await;
    assert_eq!(probe.first_event, "stream_open");
    assert_eq!(probe.entries_consumed, 0, "empty entries must not count");
    tracker.stop().await.expect_clean();
}

// ---------------------------------------------------------------------------
// Round-4 regressions: tracked-task join semantics, child cleanup errors.
// ---------------------------------------------------------------------------

#[test]
fn tracked_cleanup_panics_on_injected_panic() {
    // Capture panic messages so we can prove the injected panic surfaced
    // through the tracker (tokio re-panics with an opaque payload, so the
    // message is observable at the hook, the propagation at catch_unwind).
    let captured: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));
    let captured_hook = Arc::clone(&captured);
    let previous_hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        captured_hook.lock().unwrap().push(info.to_string());
    }));
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        // A runtime is needed inside catch_unwind; build a tiny one.
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        runtime.block_on(async {
            let tracker = TaskTracker::default();
            tracker
                .spawn(async {
                    panic!("injected fixture panic");
                })
                .expect("task spawns before any closure");
            // Let the spawned task actually run so its panic (not a preemptive
            // abort) is what the tracker must surface.
            tokio::task::yield_now().await;
            tokio::task::yield_now().await;
            tracker.stop().await.expect_clean();
        });
    }));
    std::panic::set_hook(previous_hook);
    assert!(
        result.is_err(),
        "tracked cleanup must fail when a fixture task panicked"
    );
    let messages = captured.lock().unwrap();
    assert!(
        messages
            .iter()
            .any(|m| m.contains("injected fixture panic")),
        "the injected panic must be observed: {messages:?}"
    );
}

#[test]
fn tracked_cleanup_joins_pending_siblings_before_reporting_first_panic() {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    runtime.block_on(async {
        let tracker = TaskTracker::default();
        let running = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        struct PanicObserved(Option<tokio::sync::oneshot::Sender<()>>);
        impl Drop for PanicObserved {
            fn drop(&mut self) {
                let _ = self.0.take().unwrap().send(());
            }
        }
        let (trigger, gate) = tokio::sync::oneshot::channel();
        let (panicked, observe_panic) = tokio::sync::oneshot::channel();
        tracker
            .spawn(async move {
                let _observe = PanicObserved(Some(panicked));
                gate.await.unwrap();
                panic!("first injected panic");
            })
            .unwrap();
        let ready = Arc::new(tokio::sync::Barrier::new(4));
        for _ in 0..3 {
            let running = Arc::clone(&running);
            let ready = Arc::clone(&ready);
            tracker
                .spawn(async move {
                    let _guard = RunningGuard::new(running);
                    ready.wait().await;
                    futures_util::future::pending::<()>().await;
                })
                .unwrap();
        }
        ready.wait().await;
        assert_eq!(running.load(std::sync::atomic::Ordering::SeqCst), 3);
        trigger.send(()).unwrap();
        observe_panic.await.unwrap();
        let outcome = tracker.stop().await;
        assert_eq!(
            running.load(std::sync::atomic::Ordering::SeqCst),
            0,
            "all pending siblings must be terminal before reporting panic"
        );
        assert!(
            outcome
                .errors
                .iter()
                .any(|error| error.contains("panicked")),
            "panic reported after joins: {:?}",
            outcome.errors
        );
        assert!(
            outcome
                .errors
                .iter()
                .all(|error| !error.contains("unresolved")),
            "cooperative fixtures should join: {:?}",
            outcome.errors
        );
    });
}

#[test]
fn tracked_cleanup_accepts_expected_cancellation() {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    runtime.block_on(async {
        let tracker = TaskTracker::default();
        tracker
            .spawn(async {
                // Runs until aborted: expected cancellation, not a panic.
                futures_util::future::pending::<()>().await;
            })
            .expect("task spawns before any closure");
        tracker.stop().await.expect_clean(); // must not panic
    });
}

/// Normal child cleanup: stop reports an explicit success, the child is
/// reaped, and a second stop is a clean no-op (no resources left).
#[tokio::test]
async fn bridge_stop_is_explicit_and_idempotent() {
    let root = TempRoot::new("stopok");
    let herdr = MockHerdr::start(&root);
    let mut bridge = spawn_bridge(&root, Some(&herdr.socket_path)).await;
    let first = bridge.stop().expect("first stop succeeds");
    assert!(!first.success(), "killed child reports the kill signal");
    // Already-reaped second stop: clean no-op, no kill error surfaced.
    let second = bridge.stop().expect("second stop is a no-op");
    let _ = second;
    herdr.stop().await;
    root.remove();
}

/// A server that performs a real WebSocket close handshake after the opening
/// frame yields a clean `closed_by_server` observation — distinct from
/// transport loss, which must be an Err (see the abrupt-close test).
#[tokio::test]
async fn clean_close_frame_is_observed_as_closed() {
    use axum::extract::ws::{Message, WebSocketUpgrade};
    use axum::routing::get as get_route;
    use axum::Router;
    use futures_util::SinkExt;

    async fn clean_close_ws(upgrade: WebSocketUpgrade) -> axum::response::Response {
        upgrade.on_upgrade(|mut socket: axum::extract::ws::WebSocket| async move {
            let _ = socket
                .send(Message::Text(
                    "{\"event\":\"stream_open\",\"generation\":0}".into(),
                ))
                .await;
            // Deliberate close handshake, then drop.
            let _ = socket.close().await;
        })
    }
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let app = Router::new().route("/api/stream", get_route(clean_close_ws));
    let tracker = TaskTracker::default();
    tracker
        .spawn(async move {
            let _ = axum::serve(listener, app).await;
        })
        .expect("fixture server spawns before any closure");

    let options = stream_options(&format!("http://127.0.0.1:{port}"));
    let probe = probe::ws_stream_probe(&options, "s-probe", Duration::from_millis(500))
        .await
        .expect("a valid Close frame is a clean close, not an error");
    assert_eq!(probe.first_event, "stream_open");
    assert!(
        probe.closed_by_server,
        "closed_by_server requires an actual Close frame"
    );
    tracker.stop().await.expect_clean();
}

// ---------------------------------------------------------------------------
// Round-4 negative regressions: repeated stream_open, HTTP-only bench
// contract, control/teardown close failures.
// ---------------------------------------------------------------------------

/// A repeated stream_open is rejected regardless of its generation.
#[tokio::test]
async fn repeated_stream_open_is_rejected_any_generation() {
    for generation in [2u64, 5, 9] {
        let frames = vec![
            json!({"event": "stream_open", "generation": 5}),
            json!({"event": "stream_open", "generation": generation}),
        ];
        let (base, tracker) = spawn_frame_server(frames).await;
        let error = probe::ws_stream_probe(
            &stream_options(&base),
            "s-probe",
            Duration::from_millis(300),
        )
        .await
        .unwrap_err();
        assert!(
            error.contains("repeated stream_open"),
            "generation {generation}: {error}"
        );
        tracker.stop().await.expect_clean();
    }
}

/// A stream workload without a session is an up-front error; zero cycles is
/// the explicit HTTP-only form (library-level regression on run_bench).
#[tokio::test]
async fn bench_stream_workload_requires_session() {
    let mut options = probe::ProbeOptions::new("http://127.0.0.1:1");
    options.timeout = Duration::from_secs(1);
    options.session_deadline = Duration::from_secs(2);
    let config = probe::BenchConfig {
        iterations: 1,
        warmup: 0,
        stream_cycles: 5,
        session: None,
        allow_error_streams: false,
    };
    let error = probe::run_bench(&options, &config)
        .await
        .expect_err("stream workload without session must fail up front");
    assert!(error.contains("no session"), "{error}");
    // The explicit HTTP-only form is accepted (it will fail on the dead
    // port's transport, not on the count contract).
    let config = probe::BenchConfig {
        iterations: 1,
        warmup: 0,
        stream_cycles: 0,
        session: None,
        allow_error_streams: false,
    };
    let error = probe::run_bench(&options, &config)
        .await
        .expect_err("HTTP-only bench against a dead port fails on transport");
    assert!(!error.contains("no session"), "{error}");
}

/// A Ping answered with a failed/timed-out Pong (peer gone) is a propagated
/// error, never a silent success; the abrupt peer close is deliberate.
#[tokio::test]
async fn ping_then_abrupt_peer_close_is_propagated() {
    use axum::extract::ws::{Message, WebSocketUpgrade};
    use axum::routing::get as get_route;
    use axum::Router;

    async fn ping_drop_ws(upgrade: WebSocketUpgrade) -> axum::response::Response {
        upgrade.on_upgrade(|mut socket: axum::extract::ws::WebSocket| async move {
            let _ = socket
                .send(Message::Text(
                    "{\"event\":\"stream_open\",\"generation\":0}".into(),
                ))
                .await;
            // Ping the client, then drop the connection without a close
            // handshake, so the client's Pong send cannot succeed.
            let _ = socket.send(Message::Ping(vec![1u8].into())).await;
            drop(socket);
        })
    }
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let app = Router::new().route("/api/stream", get_route(ping_drop_ws));
    let tracker = TaskTracker::default();
    tracker
        .spawn(async move {
            let _ = axum::serve(listener, app).await;
        })
        .expect("fixture server spawns before any closure");

    let options = stream_options(&format!("http://127.0.0.1:{port}"));
    // Transport loss without a WebSocket Close frame MUST be an Err: no Ok,
    // no closed_by_server success, no read-error stored as a benign event.
    let error = probe::ws_stream_probe(&options, "s-probe", Duration::from_millis(500))
        .await
        .expect_err("abrupt transport loss without a Close frame must be an Err");
    assert!(
        error.contains("without a WebSocket Close frame")
            || error.contains("Pong")
            || error.contains("ws read")
            || error.contains("closing handshake"),
        "{error}"
    );
    tracker.stop().await.expect_clean();
}

// ---------------------------------------------------------------------------
// Round-7 regressions: atomic registration closure and deterministic race.
// ---------------------------------------------------------------------------

/// After `stop`, registration is permanently closed: the spawn is rejected
/// and the dropped future is never polled (the task never starts).
#[tokio::test]
async fn spawn_after_stop_is_rejected_without_task_start() {
    let tracker = Arc::new(TaskTracker::default());
    tracker.stop().await.expect_clean();
    let started = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let started_flag = Arc::clone(&started);
    let error = tracker
        .spawn(async move {
            started_flag.store(true, std::sync::atomic::Ordering::SeqCst);
        })
        .expect_err("spawn after stop must be rejected");
    assert!(error.contains("closed"), "{error}");
    assert!(
        !started.load(std::sync::atomic::Ordering::SeqCst),
        "the rejected future must never be polled into a task"
    );
    // The tracker remains cleanly stopped.
    tracker.stop().await.expect_clean();
}

/// Deterministic registration race: a producer spawning children while
/// `stop` runs cannot let a child escape cleanup. Every child that started
/// before closure is registered (abort+joined by stop) and every spawn after
/// closure is rejected; after stop no child is running and none can start.
#[tokio::test]
async fn registration_race_cannot_escape_cleanup() {
    let tracker = Arc::new(TaskTracker::default());
    let running = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let started = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let (ready, observe_ready) = tokio::sync::oneshot::channel();
    let (attempt, gate) = tokio::sync::oneshot::channel();
    let child_running = Arc::clone(&running);
    let child_started = Arc::clone(&started);
    tracker
        .spawn(async move {
            let _guard = RunningGuard::new(child_running);
            child_started.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            ready.send(()).unwrap();
            futures_util::future::pending::<()>().await;
        })
        .unwrap();
    observe_ready.await.unwrap();
    assert_eq!(running.load(std::sync::atomic::Ordering::SeqCst), 1);

    let producer_tracker = Arc::clone(&tracker);
    let rejected_started = Arc::clone(&started);
    let producer = tokio::spawn(async move {
        gate.await.unwrap();
        producer_tracker.spawn(async move {
            rejected_started.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            futures_util::future::pending::<()>().await;
        })
    });

    // Poll shutdown to its first suspension: registration has closed and the
    // cleanup job is registered, but the job has not yet joined the child.
    let mut stopping = Box::pin(tracker.stop());
    assert!(futures_util::poll!(&mut stopping).is_pending());
    attempt.send(()).unwrap();
    let rejection = producer.await.expect("producer joined");
    assert!(
        rejection.is_err(),
        "producer cannot register during cleanup"
    );
    let stop = stopping.await;
    assert_eq!(started.load(std::sync::atomic::Ordering::SeqCst), 1);
    assert!(
        running.load(std::sync::atomic::Ordering::SeqCst) == 0,
        "no child may still be running after stop: started={}, running={}",
        started.load(std::sync::atomic::Ordering::SeqCst),
        running.load(std::sync::atomic::Ordering::SeqCst)
    );
    stop.expect_clean();

    // Post-stop spawns stay rejected with no task start.
    let error = tracker
        .spawn(async {})
        .expect_err("tracker stays closed after stop");
    assert!(error.contains("closed"), "{error}");
}

/// Guard paired with the running counter: constructed only once a child is
/// about to run; Drop decrements, so a joined (aborted/dropped) child always
/// releases its slot.
struct RunningGuard(Arc<std::sync::atomic::AtomicUsize>);

impl RunningGuard {
    fn new(counter: Arc<std::sync::atomic::AtomicUsize>) -> Self {
        counter.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        Self(counter)
    }
}

impl Drop for RunningGuard {
    fn drop(&mut self) {
        self.0.fetch_sub(1, std::sync::atomic::Ordering::SeqCst);
    }
}
