//! Leak-regression harness (bounded, `#[ignore]`-gated: run explicitly with
//! `cargo test --test resource_soak -- --ignored --nocapture`, or the
//! `resource-bridge-client` Make target — also enforced inside the aggregate
//! `check-bridge` gate).
//!
//! Records an FD / RSS / child / temp-state baseline, runs bounded repeated
//! probe cycles **per scenario class** (success, error/refusal, mid-flight
//! cancellation, server disconnect), then asserts teardown recovery after
//! every class: open FDs return to (or below) baseline within tolerance,
//! owned children are reaped, mock tasks join under a deadline, and temp
//! state is removed. RSS is evidenced by sampled client RSS plus peak, never
//! by peak alone. Short samples prove regression relative to baseline, never
//! absolute zero-leak.

#[path = "../examples/test-client/probe.rs"]
mod probe;

use serde_json::json;
use std::net::TcpListener;
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

const BIN: &str = env!("CARGO_BIN_EXE_cappuccino-bridge");
static NEXT: AtomicU64 = AtomicU64::new(0);
/// Tolerance for post-scenario FD recovery. Only *growth* over the baseline
/// is a leak signal; the count may legitimately drop when the async runtime
/// tears down epoll/event fds that were open at baseline time.
const FD_TOLERANCE: isize = 8;
const CYCLES: usize = 20;
const TEARDOWN_DEADLINE: Duration = Duration::from_secs(10);

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

struct MockHerdr {
    socket_path: PathBuf,
    tasks: Arc<Mutex<Vec<tokio::task::JoinHandle<()>>>>,
    listener_task: tokio::task::JoinHandle<()>,
}

impl MockHerdr {
    fn start(root: &TempRoot) -> Self {
        let socket_path = root.path.join("herdr.sock");
        let listener = tokio::net::UnixListener::bind(&socket_path).unwrap();
        let tasks: Arc<Mutex<Vec<tokio::task::JoinHandle<()>>>> = Arc::new(Mutex::new(Vec::new()));
        let tasks_task = Arc::clone(&tasks);
        let listener_task = tokio::spawn(async move {
            loop {
                let (stream, _) = match listener.accept().await {
                    Ok(accepted) => accepted,
                    Err(_) => return,
                };
                let task = tokio::spawn(handle_mock_connection(stream));
                tasks_task.lock().unwrap().push(task);
            }
        });
        Self {
            socket_path,
            tasks,
            listener_task,
        }
    }

    /// Aborts and joins every tracked connection task within the teardown
    /// deadline, then removes the socket. Errors are surfaced, never ignored.
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

fn handle_mock_connection(stream: tokio::net::UnixStream) -> impl std::future::Future<Output = ()> {
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt};
    async move {
        let (reader, mut writer) = stream.into_split();
        let mut lines = tokio::io::BufReader::new(reader);
        let mut line = String::new();
        if matches!(lines.read_line(&mut line).await, Ok(0) | Err(_)) {
            return;
        }
        let request: serde_json::Value = serde_json::from_str(line.trim()).unwrap_or(json!(null));
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

    /// Kill, reap within a bounded wait, and assert the child is really gone
    /// (no zombie). Termination errors are surfaced, not ignored.
    fn stop(&mut self) {
        let _ = self.child.kill();
        let deadline = std::time::Instant::now() + TEARDOWN_DEADLINE;
        loop {
            match self.child.try_wait() {
                Ok(Some(status)) => {
                    eprintln!("bridge stopped: {status}");
                    return;
                }
                Ok(None) => {
                    assert!(
                        std::time::Instant::now() < deadline,
                        "bridge child did not exit before the reap deadline"
                    );
                    std::thread::sleep(Duration::from_millis(10));
                }
                Err(error) => panic!("bridge wait failed: {error}"),
            }
        }
    }
}

impl Drop for Bridge {
    /// Best-effort kill+reap if a test panics before its explicit stop().
    fn drop(&mut self) {
        if self.child.try_wait().is_ok() {
            let _ = self.child.kill();
            if let Err(error) = self.child.wait() {
                eprintln!("bridge child reap on drop: {error}");
            }
        }
    }
}

async fn soak_cycles_success(base_url: &str, cycles: usize) -> usize {
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

async fn soak_cycles_refusal(port: u16, cycles: usize) -> usize {
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

/// Repeated mid-flight cancellations: the probe is aborted while blocked on a
/// stalled server, guaranteed to be in flight; tasks must join promptly.
async fn soak_cycles_cancel(port: u16, cycles: usize) -> Duration {
    let mut worst = Duration::ZERO;
    for _ in 0..cycles {
        let mut options = probe::ProbeOptions::new(format!("http://127.0.0.1:{port}"));
        options.timeout = Duration::from_secs(10);
        options.session_deadline = Duration::from_secs(15);
        let task = tokio::spawn(async move { probe::run_checks(&options).await });
        tokio::time::sleep(Duration::from_millis(30)).await;
        let start = std::time::Instant::now();
        task.abort();
        let joined = task.await;
        assert!(
            joined.is_err(),
            "cancel cycle must abort mid-flight, not complete"
        );
        worst = worst.max(start.elapsed());
    }
    worst
}

/// Repeated server disconnects: an accept-and-close server; probes must fail
/// loudly every cycle.
async fn soak_cycles_disconnect(port: u16, cycles: usize) -> usize {
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

fn assert_fd_recovery(label: &str, before: usize, after: usize) {
    println!("soak[{label}]: fds {before} -> {after} (tolerance +{FD_TOLERANCE})");
    assert!(
        after as isize - before as isize <= FD_TOLERANCE,
        "fd leak after {label}: {before} -> {after}"
    );
}

/// Success-path soak: full probe cycles against a live bridge + mock herdr,
/// benchmark consumption included, then error/refusal, cancellation and
/// disconnect scenario classes, each followed by FD recovery assertions.
#[tokio::test]
#[ignore]
async fn leak_baseline_recovery_success_path() {
    let fds_before = open_fd_count().expect("fd baseline");
    let (rss0, cpu0, unit) = probe::rusage_snapshot().expect("rusage baseline");
    let root = TempRoot::new("success");
    let herdr = MockHerdr::start(&root);
    let mut bridge = spawn_bridge(&root, &herdr.socket_path);
    bridge.wait_ready().await;

    // Bounded benchmark pass with entries/stream consumption under
    // observation (also exercises bench code in this crate).
    let mut bench_options = probe::ProbeOptions::new(bridge.base_url());
    bench_options.session = Some("s-probe".into());
    let bench = probe::run_bench(
        &bench_options,
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
        "soak[success]: bench protocol={} warm_p50_us={} stream_cycles={} entries={} cpu_delta_ms={} rss {}->{} growth {} ({})",
        bench.protocol, bench.warm_p50_us, bench.stream_cycles_completed,
        bench.stream_entries_consumed, bench.cpu_delta_ms,
        bench.rss_first_sample, bench.rss_last_sample, bench.rss_growth, bench.rss_unit
    );
    assert_eq!(
        bench.stream_cycles_completed, 5,
        "bench stream cycles must complete"
    );

    let failures = soak_cycles_success(&bridge.base_url(), CYCLES).await;
    assert_eq!(failures, 0, "all success-path cycles must pass");
    let (rss1, cpu1, _) = probe::rusage_snapshot().expect("rusage after success");
    assert_fd_recovery("success", fds_before, open_fd_count().expect("fds"));
    println!(
        "soak[success]: sampled rss {} -> {} (peak {} {unit}), cpu delta {} ms, cycles={CYCLES} failures={failures}",
        rss0, rss1, rss1, cpu1.saturating_sub(cpu0)
    );

    bridge.stop();
    herdr.stop().await;
    let root_path = root.path.clone();
    root.remove();

    let fds_after = open_fd_count().expect("fds after teardown");
    assert_fd_recovery("teardown", fds_before, fds_after);
    assert!(
        !std::fs::symlink_metadata(&root_path).is_ok(),
        "temp root must be cleaned"
    );
    // Bounded sampled-RSS growth evidence (baseline-relative budget, macOS
    // bytes / Linux KiB): the soak stays within a small multiple of the
    // starting resident set; peak alone is never claimed as trend proof.
    let growth_budget = if cfg!(target_os = "macos") {
        rss0 + 64 * 1024 * 1024
    } else {
        rss0 + 64 * 1024
    };
    assert!(
        rss1 <= growth_budget,
        "sampled RSS trend exceeds budget: {rss0} -> {rss1}"
    );
}

/// Refusal, cancellation and disconnect scenario classes against throwaway
/// servers, each with its own FD recovery assertion.
#[tokio::test]
#[ignore]
async fn leak_recovery_error_cancel_disconnect() {
    let fds_before = open_fd_count().expect("fd baseline");

    // Refusal (dead port): must keep failing loudly.
    let dead_port = {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.local_addr().unwrap().port()
    };
    let unexpected = soak_cycles_refusal(dead_port, CYCLES).await;
    assert_eq!(unexpected, 0, "error path must keep failing loudly");
    assert_fd_recovery("refusal", fds_before, open_fd_count().expect("fds"));

    // Mid-flight cancellation against a stalled server. Held connections are
    // tracked server-side and released before the FD assertion, so the check
    // measures the probe's own recovery, not the test server's held sockets.
    let stall = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let stall_port = stall.local_addr().unwrap().port();
    let held: Arc<Mutex<Vec<tokio::net::TcpStream>>> = Arc::new(Mutex::new(Vec::new()));
    let held_holder = Arc::clone(&held);
    let holder = tokio::spawn(async move {
        loop {
            if let Ok((stream, _)) = stall.accept().await {
                held_holder.lock().unwrap().push(stream);
            }
        }
    });
    let worst = soak_cycles_cancel(stall_port, CYCLES).await;
    println!(
        "soak[cancel]: {CYCLES} aborts joined, worst join {:?}",
        worst
    );
    assert!(
        worst < Duration::from_secs(2),
        "cancellation join must be prompt"
    );
    holder.abort();
    let _ = holder.await;
    held.lock().unwrap().clear(); // release held connections before asserting
    assert_fd_recovery("cancel", fds_before, open_fd_count().expect("fds"));

    // Server disconnect: accept-and-close; every probe must fail loudly.
    let closer = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let closer_port = closer.local_addr().unwrap().port();
    let holder = tokio::spawn(async move {
        loop {
            if let Ok((stream, _)) = closer.accept().await {
                drop(stream); // immediate disconnect
            }
        }
    });
    let unexpected = soak_cycles_disconnect(closer_port, CYCLES).await;
    assert_eq!(unexpected, 0, "disconnect path must keep failing loudly");
    holder.abort();
    let _ = holder.await;
    assert_fd_recovery("disconnect", fds_before, open_fd_count().expect("fds"));
}

/// Repeated bridge child spawn/stop: children are killed and reaped (asserted
/// via try_wait) every cycle and the task-owned root is removed.
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
        // Reaped: try_wait after stop must report the final status, not None.
        match bridge.child.try_wait() {
            Ok(Some(_status)) => {}
            other => panic!("churn cycle {cycle}: child not reaped: {other:?}"),
        }
    }
    herdr.stop().await;
    root.remove();
}
