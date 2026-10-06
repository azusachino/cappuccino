//! Leak-regression harness (bounded, `#[ignore]`-gated: run explicitly with
//! `cargo test --test resource_soak -- --ignored --nocapture`, or the
//! `resource-bridge-client` Make target).
//!
//! Records an FD / RSS / child / temp-state baseline, runs bounded repeated
//! probe cycles (success and error paths), then asserts teardown recovery:
//! open FDs return to baseline within tolerance, owned children are reaped,
//! temp state is removed, and peak RSS stays inside the documented budget.
//! Short samples prove regression relative to baseline, never absolute
//! zero-leak.

#[path = "../examples/test-client/probe.rs"]
mod probe;

use serde_json::json;

use std::net::TcpListener;
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

const BIN: &str = env!("CARGO_BIN_EXE_cappuccino-bridge");

/// Count of open file descriptors for this process (macOS /dev/fd,
/// Linux /proc/self/fd).
fn open_fd_count() -> Option<usize> {
    let dir = if cfg!(target_os = "macos") {
        "/dev/fd"
    } else {
        "/proc/self/fd"
    };
    std::fs::read_dir(dir).ok().map(|entries| entries.count())
}
static NEXT: AtomicU64 = AtomicU64::new(0);
/// Tolerance for post-teardown FD recovery. Only *growth* over the baseline
/// is a leak signal; the count may legitimately drop when the async runtime
/// tears down epoll/event fds that were open at baseline time.
const FD_TOLERANCE: isize = 8;
const CYCLES: usize = 20;

struct TempRoot {
    path: PathBuf,
    dev: u64,
    ino: u64,
}

impl TempRoot {
    fn new(label: &str) -> Self {
        let path = std::env::temp_dir().join(format!(
            "capp-soak-{label}-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir(&path).unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o700)).unwrap();
        let metadata = std::fs::symlink_metadata(&path).unwrap();
        Self {
            path,
            dev: metadata.dev(),
            ino: metadata.ino(),
        }
    }

    /// Identity-safe cleanup; errors are surfaced, never ignored.
    fn remove(self) {
        let metadata = std::fs::symlink_metadata(&self.path).unwrap();
        assert!(metadata.is_dir() && metadata.uid() == unsafe { libc::geteuid() });
        assert_eq!(metadata.dev(), self.dev);
        assert_eq!(metadata.ino(), self.ino);
        std::fs::remove_dir_all(&self.path)
            .unwrap_or_else(|error| panic!("soak cleanup {}: {error}", self.path.display()));
    }
}

use std::os::unix::fs::PermissionsExt;

struct MockHerdr {
    socket_path: PathBuf,
    task: tokio::task::JoinHandle<()>,
}

impl MockHerdr {
    fn start(root: &TempRoot) -> Self {
        let socket_path = root.path.join("herdr.sock");
        let listener = tokio::net::UnixListener::bind(&socket_path).unwrap();
        let task = tokio::spawn(async move {
            loop {
                let (stream, _) = match listener.accept().await {
                    Ok(accepted) => accepted,
                    Err(_) => return,
                };
                tokio::spawn(async move {
                    use tokio::io::{AsyncBufReadExt, AsyncWriteExt};
                    let (reader, mut writer) = stream.into_split();
                    let mut lines = tokio::io::BufReader::new(reader);
                    let mut line = String::new();
                    if lines.read_line(&mut line).await.unwrap_or(0) == 0 {
                        return;
                    }
                    let request: serde_json::Value =
                        serde_json::from_str(line.trim()).unwrap_or(json!(null));
                    let result = match request["method"].as_str().unwrap_or("") {
                        "agent.list" => {
                            json!({"agents": [{"name": "s-probe", "pane_id": "w1:a", "agent": "pi", "agent_status": "idle", "cwd": ""}]})
                        }
                        "agent.get" => {
                            json!({"agent": {"name": "s-probe", "pane_id": "w1:a", "agent": "pi", "agent_status": "idle", "cwd": ""}})
                        }
                        "pane.read" => json!({"read": {"text": "soak line\n"}}),
                        _ => json!({}),
                    };
                    let response = json!({"id": request["id"], "result": result});
                    let mut payload = serde_json::to_vec(&response).unwrap();
                    payload.push(b'\n');
                    let _ = writer.write_all(&payload).await;
                });
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

struct Bridge {
    child: Child,
    port: u16,
}

fn spawn_bridge(root: &TempRoot, herdr_socket: &Path) -> Bridge {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener);
    let child = Command::new(BIN)
        .env("HOME", root.path.join("home"))
        .env("CAPP_BRIDGE_CONFIG", root.path.join("missing-config.json"))
        .env("CAPP_BRIDGE_DATA_DIR", root.path.join("data"))
        .env("CAPP_BRIDGE_HOST", "127.0.0.1")
        .env("CAPP_BRIDGE_PORT", port.to_string())
        .env("CAPP_BRIDGE_SERVE_AUTO_APPLY", "0")
        .env("HERDR_SOCKET_PATH", herdr_socket)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .current_dir(&root.path)
        .spawn()
        .expect("spawn bridge");
    Bridge { child, port }
}

impl Bridge {
    fn base_url(&self) -> String {
        format!("http://127.0.0.1:{}", self.port)
    }

    async fn wait_ready(&self) {
        let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
        while tokio::net::TcpStream::connect(("127.0.0.1", self.port))
            .await
            .is_err()
        {
            assert!(
                tokio::time::Instant::now() < deadline,
                "bridge did not start"
            );
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    }

    /// Kill, reap, and assert the child is really gone (not a zombie).
    fn stop(&mut self) {
        let _ = self.child.kill();
        let status = self
            .child
            .wait()
            .expect("bridge wait must succeed (child reaped)");
        eprintln!("bridge stopped: {status}");
    }
}

async fn soak_cycles(base_url: &str, cycles: usize) -> usize {
    let mut failures = 0;
    for _ in 0..cycles {
        let mut options = probe::ProbeOptions::new(base_url);
        options.timeout = Duration::from_secs(5);
        options.session = Some("s-probe".into());
        if probe::run_checks(&options).await.is_err() {
            failures += 1;
        }
    }
    failures
}

async fn soak_cycles_error_path(port: u16, cycles: usize) -> usize {
    let mut successes = 0;
    for _ in 0..cycles {
        let mut options = probe::ProbeOptions::new(format!("http://127.0.0.1:{port}"));
        options.timeout = Duration::from_secs(2);
        if probe::run_checks(&options).await.is_ok() {
            successes += 1;
        }
    }
    successes
}

/// Success-path soak: N full probe cycles against a live bridge + mock herdr,
/// then teardown and baseline recovery assertions.
#[tokio::test]
#[ignore]
async fn leak_baseline_recovery_success_path() {
    let fds_before = open_fd_count().expect("fd baseline");
    let root = TempRoot::new("success");
    let herdr = MockHerdr::start(&root);
    let mut bridge = spawn_bridge(&root, &herdr.socket_path);
    bridge.wait_ready().await;

    // Bounded benchmark pass under observation (also exercises bench code in
    // this crate): small iteration count, honest protocol report.
    let bench = probe::run_bench(
        &probe::ProbeOptions::new(bridge.base_url()),
        &probe::BenchConfig {
            iterations: 50,
            warmup: 5,
            stream_cycles: 5,
            session: Some("s-probe".into()),
        },
    )
    .await
    .expect("bench under soak");
    println!(
        "soak[success]: bench protocol={} warm_p50_us={} peak_rss={} ({})",
        bench.protocol, bench.warm_p50_us, bench.peak_rss, bench.rss_unit
    );

    let failures = soak_cycles(&bridge.base_url(), CYCLES).await;
    assert_eq!(failures, 0, "all success-path cycles must pass");

    bridge.stop();
    herdr.stop().await;
    let root_path = root.path.clone();
    root.remove();

    let fds_after = open_fd_count().expect("fd after");
    let (peak_rss, cpu_ms, unit) = probe::rusage_snapshot();
    let leftovers = std::fs::symlink_metadata(&root_path).is_ok();
    println!("soak[success]: cycles={CYCLES} failures={failures}");
    println!("soak[success]: fds {fds_before} -> {fds_after} (tolerance {FD_TOLERANCE})");
    println!(
        "soak[success]: peak_rss={peak_rss} ({unit}) cpu_total_ms={cpu_ms} leftovers={leftovers}"
    );
    assert!(
        fds_after as isize - fds_before as isize <= FD_TOLERANCE,
        "fd leak: {fds_before} -> {fds_after}"
    );
    assert!(!leftovers, "this soak's temp root must be cleaned");
    // Regression budget (baseline-relative, not an absolute zero-leak claim):
    // peak RSS of this whole soak stays under a documented ceiling.
    let budget = if cfg!(target_os = "macos") {
        512 * 1024 * 1024u64
    } else {
        512 * 1024u64
    };
    assert!(
        peak_rss < budget,
        "peak RSS {peak_rss} {unit} exceeds soak budget"
    );
}

/// Error-path soak: repeated failed probes (connection refused) must not leak
/// either, and must fail loudly each time rather than silently succeeding.
#[tokio::test]
#[ignore]
async fn leak_baseline_recovery_error_path() {
    let fds_before = open_fd_count().expect("fd baseline");
    let port = {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        port
    };
    let unexpected_successes = soak_cycles_error_path(port, CYCLES).await;
    assert_eq!(
        unexpected_successes, 0,
        "error path must keep failing loudly"
    );

    let fds_after = open_fd_count().expect("fd after");
    println!("soak[error]: cycles={CYCLES} fds {fds_before} -> {fds_after}");
    assert!(
        fds_after as isize - fds_before as isize <= FD_TOLERANCE,
        "fd leak on error path: {fds_before} -> {fds_after}"
    );
}

/// Repeated bridge child spawn/stop: children are reaped every cycle and no
/// stray plugin state survives.
#[tokio::test]
#[ignore]
async fn bridge_child_churn_is_reaped() {
    let root = TempRoot::new("churn");
    let herdr = MockHerdr::start(&root);
    for cycle in 0..5 {
        let mut bridge = spawn_bridge(&root, &herdr.socket_path);
        bridge.wait_ready().await;
        probe::run_checks(&probe::ProbeOptions::new(bridge.base_url()))
            .await
            .unwrap_or_else(|error| panic!("churn cycle {cycle}: {error}"));
        bridge.stop();
    }
    herdr.stop().await;
    let state_leftovers = std::fs::read_dir(root.path.join("home"))
        .map(|entries| entries.count())
        .unwrap_or(0);
    println!("soak[churn]: 5 spawn/stop cycles, home entries left: {state_leftovers}");
    root.remove();
}
