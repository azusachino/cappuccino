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
use std::os::unix::fs::{MetadataExt, PermissionsExt};
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
        let metadata = match std::fs::symlink_metadata(&self.path) {
            Ok(metadata) => metadata,
            Err(error) => panic!("temp root vanished before cleanup: {error}"),
        };
        let ours = metadata.is_dir()
            && metadata.uid() == unsafe { libc::geteuid() }
            && metadata.mode() & 0o7777 == 0o700
            && metadata.dev() == self.dev
            && metadata.ino() == self.ino;
        assert!(
            ours,
            "refusing to remove replaced/unsafe temp root {}",
            self.path.display()
        );
        std::fs::remove_dir_all(&self.path)
            .unwrap_or_else(|error| panic!("could not remove {}: {error}", self.path.display()));
    }
}

impl Drop for TempRoot {
    fn drop(&mut self) {
        if let Ok(metadata) = std::fs::symlink_metadata(&self.path) {
            let ours = metadata.is_dir()
                && metadata.uid() == unsafe { libc::geteuid() }
                && metadata.mode() & 0o7777 == 0o700
                && metadata.dev() == self.dev
                && metadata.ino() == self.ino;
            if !ours {
                eprintln!(
                    "preserving replaced/unsafe temp root {}",
                    self.path.display()
                );
                return;
            }
            if let Err(error) = std::fs::remove_dir_all(&self.path) {
                eprintln!("could not remove {}: {error}", self.path.display());
            }
        }
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
    tasks: Arc<Mutex<Vec<tokio::task::JoinHandle<()>>>>,
    listener_task: tokio::task::JoinHandle<()>,
}

impl MockHerdr {
    fn start(root: &TempRoot) -> Self {
        let socket_path = root.path.join("herdr.sock");
        let listener = tokio::net::UnixListener::bind(&socket_path).expect("bind mock herdr");
        let cwd: Arc<Mutex<Option<PathBuf>>> = Arc::new(Mutex::new(None));
        let agent_session: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));
        let tasks: Arc<Mutex<Vec<tokio::task::JoinHandle<()>>>> = Arc::new(Mutex::new(Vec::new()));
        let cwd_task = Arc::clone(&cwd);
        let session_task = Arc::clone(&agent_session);
        let tasks_task = Arc::clone(&tasks);
        let listener_task = tokio::spawn(async move {
            loop {
                let (stream, _) = match listener.accept().await {
                    Ok(accepted) => accepted,
                    Err(_) => return,
                };
                let cwd = Arc::clone(&cwd_task);
                let agent_session = Arc::clone(&session_task);
                let task = tokio::spawn(handle_mock_connection(stream, cwd, agent_session));
                let mut guard = tasks_task.lock().unwrap();
                // Completed tasks are pruned: a retained JoinHandle keeps the
                // finished task's whole allocation alive, which accumulates
                // across the bridge's 400 ms polls and reads as an RSS leak.
                guard.retain(|task| !task.is_finished());
                guard.push(task);
            }
        });
        Self {
            socket_path,
            cwd,
            agent_session,
            tasks,
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

    /// Aborts and joins every tracked connection task within the teardown
    /// deadline, then removes the socket. Errors are surfaced, not ignored.
    async fn stop(self) {
        self.listener_task.abort();
        let _ = self.listener_task.await;
        let tasks: Vec<_> = self.tasks.lock().unwrap().drain(..).collect();
        let deadline = tokio::time::Instant::now() + TEARDOWN_DEADLINE;
        for task in tasks {
            task.abort();
            if tokio::time::timeout_at(deadline, task).await.is_err() {
                panic!("mock herdr connection task did not join before teardown deadline");
            }
        }
        if let Err(error) = std::fs::remove_file(&self.socket_path) {
            panic!("mock herdr socket cleanup: {error}");
        }
    }
}

fn handle_mock_connection(
    stream: tokio::net::UnixStream,
    cwd: Arc<Mutex<Option<PathBuf>>>,
    agent_session: Arc<Mutex<Option<String>>>,
) -> impl std::future::Future<Output = ()> {
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt};
    async move {
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
}

// ---------------------------------------------------------------------------
// Bridge child process harness: bind-retry, bounded reap, identity cleanup.
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

impl Bridge {
    fn base_url(&self) -> String {
        format!("http://127.0.0.1:{}", self.port)
    }

    /// Kill, then reap within a bounded wait; assert the child is really gone
    /// (no zombie). Termination errors are surfaced, not ignored.
    fn stop(&mut self) {
        let _ = self.child.kill();
        let deadline = std::time::Instant::now() + TEARDOWN_DEADLINE;
        loop {
            match self.child.try_wait() {
                Ok(Some(_status)) => break, // reaped
                Ok(None) => {
                    if std::time::Instant::now() > deadline {
                        panic!("bridge child did not exit before the reap deadline");
                    }
                    std::thread::sleep(Duration::from_millis(10));
                }
                Err(error) => panic!("bridge wait failed: {error}"),
            }
        }
        // The final blocking wait reaps and must succeed now.
        let status = self.child.wait().expect("bridge final reap");
        eprintln!("bridge stopped: {status}");
    }
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
    bridge.stop();
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
    bridge.stop();
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
    bridge.stop();
    herdr.stop().await;
    root.remove();
}

#[tokio::test]
async fn session_requires_nonempty_machine_id() {
    // A paired payload without machine_id must fail the check: spin an
    // HTTP/1 origin returning a machine_id-less paired body.
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let mut tracker = TaskTracker::default();
    tracker.spawn(async move {
        loop {
            let Ok((mut stream, _)) = listener.accept().await else {
                return;
            };
            tokio::spawn(async move {
                use tokio::io::{AsyncReadExt, AsyncWriteExt};
                let mut buf = [0u8; 1024];
                let _ = stream.read(&mut buf).await;
                let body = r#"{"event":"paired","protocol":1,"plugin":"p"}"#;
                let response = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
                let _ = stream.write_all(response.as_bytes()).await;
            });
        }
    });
    let mut options = probe_options(&format!("http://127.0.0.1:{port}"));
    options.timeout = Duration::from_secs(5);
    options.session_deadline = Duration::from_secs(10);
    let error = probe::run_checks(&options)
        .await
        .expect_err("missing machine_id must fail");
    assert!(error.contains("machine_id"), "{error}");
    tracker.stop().await;
}

// ---------------------------------------------------------------------------
// WS contract
// ---------------------------------------------------------------------------

#[tokio::test]
async fn ws_unknown_session_fails_closed_not_found() {
    let root = TempRoot::new("wsnf");
    let mut bridge = spawn_bridge(&root, None).await;
    let stream = ws_probe(
        &probe_options(&bridge.base_url()),
        "nope",
        Duration::from_millis(500),
    )
    .await;
    assert_eq!(stream.first_event, "error");
    assert_eq!(stream.error_code.as_deref(), Some("not_found"));
    assert_eq!(stream.protocol, "ws-http1-upgrade");
    // Fail-closed: no further frames after the error, and the server closes
    // (observed as EOF, a WS close, or a read error at the TCP layer).
    assert!(
        stream.followup_events.iter().all(
            |event| !event.is_empty() && (event.starts_with("read-error:") || event == "close")
        ),
        "unexpected follow-up frames after error first frame: {:?}",
        stream.followup_events
    );
    bridge.stop();
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

    bridge.stop();
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

    bridge.stop();
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

    server.abort();
    let _ = server.await;
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

    bridge.stop();
    herdr.stop().await;
    root.remove();
}

#[tokio::test]
async fn require_h2_fails_on_http1_downgrade() {
    // HTTP/1-only origin: raw canned responses on a TCP listener.
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let mut tracker = TaskTracker::default();
    tracker.spawn(async move {
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

    let mut options = probe_options(&format!("http://127.0.0.1:{port}"));
    options.preference = HttpPreference::RequireH2;
    options.timeout = Duration::from_secs(5);
    let error = probe::run_checks(&options)
        .await
        .expect_err("require-h2 must fail against an HTTP/1-only origin");
    assert!(error.contains("require-h2"), "{error}");
    tracker.stop().await;
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
    let mut tracker = TaskTracker::default();
    tracker.spawn(async move {
        loop {
            if let Ok((stream, _)) = listener.accept().await {
                tokio::spawn(async move {
                    let _held = stream; // hold it open, never respond
                    tokio::time::sleep(Duration::from_secs(30)).await;
                });
            }
        }
    });
    let mut options = probe::ProbeOptions::new(format!("http://127.0.0.1:{port}"));
    options.timeout = Duration::from_secs(2);
    options.session_deadline = Duration::from_secs(3);
    let error = probe::run_checks(&options)
        .await
        .expect_err("stalled server must time out");
    assert!(!error.is_empty());
    tracker.stop().await;
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
    let holder = tokio::spawn(async move {
        loop {
            if let Ok((stream, _)) = listener.accept().await {
                held_holder.lock().unwrap().push(stream);
                accepted_holder.notify_one();
            }
        }
    });

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
    task.abort();
    let joined = task.await;
    assert!(
        joined.is_err(),
        "mid-flight task must be cancelled, not completed"
    );
    assert!(
        start.elapsed() < Duration::from_secs(2),
        "abort must not hang"
    );

    holder.abort();
    let _ = holder.await;
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

    bridge.stop();
    herdr.stop().await;
    root.remove();
}

// ---------------------------------------------------------------------------
// Tracked fixture tasks: every spawned fixture server is aborted and joined
// under a deadline; a panicked task is surfaced, never counted as expected
// cancellation.
// ---------------------------------------------------------------------------

#[derive(Default)]
struct TaskTracker(Vec<tokio::task::JoinHandle<()>>);

impl TaskTracker {
    fn spawn(&mut self, task: impl std::future::Future<Output = ()> + Send + 'static) {
        self.0.push(tokio::spawn(task));
    }

    /// Abort and join every tracked task within the teardown deadline. A
    /// panicked task is an error; expected cancellation is not.
    async fn stop(&mut self) {
        let tasks = std::mem::take(&mut self.0);
        let deadline = tokio::time::Instant::now() + TEARDOWN_DEADLINE;
        for task in tasks {
            task.abort();
            match tokio::time::timeout_at(deadline, task).await {
                Err(_) => panic!("fixture task did not join before the teardown deadline"),
                Ok(Err(join_error)) => {
                    if !join_error.is_cancelled() {
                        panic!("fixture task panicked: {join_error}");
                    }
                }
                Ok(Ok(())) => {}
            }
        }
    }
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
    let mut tracker = TaskTracker::default();
    tracker.spawn(async move {
        let _ = axum::serve(listener, app).await;
    });
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
    let (base, mut tracker) = spawn_frame_server(frames).await;
    let error = probe::ws_stream_probe(
        &stream_options(&base),
        "s-probe",
        Duration::from_millis(300),
    )
    .await
    .unwrap_err();
    assert!(error.contains("regression"), "{error}");
    tracker.stop().await;
}

#[tokio::test]
async fn invalid_gap_shape_is_rejected() {
    let frames = vec![
        json!({"event": "stream_open", "generation": 0}),
        json!({"event": "entries", "entries": [{
            "seq": 9, "id": "wrong-id", "kind": "gap",
            "text": "gap: missing entries 2-8", "branch": null, "complete": true }]}),
    ];
    let (base, mut tracker) = spawn_frame_server(frames).await;
    let error = probe::ws_stream_probe(
        &stream_options(&base),
        "s-probe",
        Duration::from_millis(300),
    )
    .await
    .unwrap_err();
    assert!(error.contains("gap entry must have null seq"), "{error}");
    tracker.stop().await;
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
    let mut tracker = TaskTracker::default();
    tracker.spawn(async move {
        let _ = axum::serve(listener, app).await;
    });
    let options = stream_options(&format!("http://127.0.0.1:{port}"));
    let error = probe::ws_stream_probe(&options, "s-probe", Duration::from_millis(300))
        .await
        .unwrap_err();
    assert!(error.contains("binary"), "{error}");
    tracker.stop().await;
}

#[tokio::test]
async fn empty_entries_do_not_count_as_consumption() {
    let frames = vec![
        json!({"event": "stream_open", "generation": 0}),
        json!({"event": "entries", "entries": []}),
    ];
    let (base, mut tracker) = spawn_frame_server(frames).await;
    let probe = ws_probe(
        &stream_options(&base),
        "s-probe",
        Duration::from_millis(300),
    )
    .await;
    assert_eq!(probe.first_event, "stream_open");
    assert_eq!(probe.entries_consumed, 0, "empty entries must not count");
    tracker.stop().await;
}
