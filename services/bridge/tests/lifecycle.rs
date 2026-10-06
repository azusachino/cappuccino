use std::{
    fs,
    io::{BufRead, BufReader, Read, Write},
    net::{TcpListener, TcpStream},
    os::unix::fs::{FileTypeExt, MetadataExt, PermissionsExt},
    os::unix::net::UnixListener,
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc, Barrier,
    },
    thread::{self, JoinHandle},
    time::Duration,
};

const BIN: &str = env!("CARGO_BIN_EXE_cappuccino-bridge");
const PLUGIN_STATE_SUBDIR: &str = "state";
static NEXT: AtomicU64 = AtomicU64::new(0);

struct TestRoot {
    path: PathBuf,
    dev: u64,
    ino: u64,
    active_bridge: Option<(PathBuf, u16)>,
}

impl TestRoot {
    fn new() -> Self {
        let parent = Path::new("/tmp").canonicalize().unwrap();
        let path = parent.join(format!(
            "cappuccino-lifecycle-test-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
        let metadata = fs::symlink_metadata(&path).unwrap();
        Self {
            path,
            dev: metadata.dev(),
            ino: metadata.ino(),
            active_bridge: None,
        }
    }

    fn state(&self) -> PathBuf {
        self.path.join("state")
    }

    fn bridge_state(&self) -> PathBuf {
        self.state().join(PLUGIN_STATE_SUBDIR)
    }

    fn track_bridge(&mut self, state: &Path, port: u16) {
        self.active_bridge = Some((state.to_owned(), port));
    }

    fn command(&self, action: &str, state: &Path, port: u16) -> Command {
        let mut command = Command::new(BIN);
        command
            .arg(action)
            .env("HOME", self.path.join("home"))
            .env("HERDR_PLUGIN_STATE_DIR", state)
            .env("CAPP_BRIDGE_CONFIG", self.path.join("missing-config.json"))
            .env("CAPP_BRIDGE_DATA_DIR", self.path.join("data"))
            .env("CAPP_BRIDGE_HOST", "127.0.0.1")
            .env("CAPP_BRIDGE_PORT", port.to_string())
            .env("CAPP_BRIDGE_SERVE_AUTO_APPLY", "0")
            .current_dir(&self.path);
        command
    }
}

impl Drop for TestRoot {
    fn drop(&mut self) {
        if let Some((state, port)) = self.active_bridge.take() {
            let stopped = self
                .command("stop", &state, port)
                .output()
                .is_ok_and(|output| output.status.success());
            let bridge_state = state.join(PLUGIN_STATE_SUBDIR);
            let no_state = ["bridge.control", "bridge.sock", "bridge.pid"]
                .iter()
                .all(|name| !bridge_state.join(name).exists());
            if !stopped || http_ok(port) || !no_state {
                eprintln!(
                    "preserving lifecycle test state for manual recovery: {}",
                    self.path.display()
                );
                return;
            }
        }
        let Ok(metadata) = fs::symlink_metadata(&self.path) else {
            return;
        };
        if metadata.file_type().is_dir()
            && metadata.uid() == unsafe { libc::geteuid() }
            && metadata.mode() & 0o7777 == 0o700
            && metadata.dev() == self.dev
            && metadata.ino() == self.ino
        {
            if let Err(error) = fs::remove_dir_all(&self.path) {
                eprintln!(
                    "could not remove lifecycle test state {}: {error}",
                    self.path.display()
                );
            }
        } else {
            eprintln!(
                "preserving replaced/unsafe lifecycle test root: {}",
                self.path.display()
            );
        }
    }
}

struct TestServers {
    stops: Vec<Arc<AtomicBool>>,
    threads: Vec<JoinHandle<()>>,
    socket: Option<(PathBuf, u64, u64)>,
}

impl TestServers {
    fn new() -> Self {
        Self {
            stops: Vec::new(),
            threads: Vec::new(),
            socket: None,
        }
    }

    fn add(&mut self, stop: Arc<AtomicBool>, thread: JoinHandle<()>) {
        self.stops.push(stop);
        self.threads.push(thread);
    }

    fn track_socket(&mut self, path: &Path) {
        let metadata = fs::symlink_metadata(path).unwrap();
        assert!(metadata.file_type().is_socket());
        self.socket = Some((path.to_owned(), metadata.dev(), metadata.ino()));
    }
}

impl Drop for TestServers {
    fn drop(&mut self) {
        for stop in &self.stops {
            stop.store(true, Ordering::Release);
        }
        for thread in &self.threads {
            thread.thread().unpark();
        }
        for thread in self.threads.drain(..) {
            let _ = thread.join();
        }
        if let Some((path, dev, ino)) = self.socket.take() {
            if let Ok(metadata) = fs::symlink_metadata(&path) {
                if metadata.file_type().is_socket()
                    && metadata.uid() == unsafe { libc::geteuid() }
                    && metadata.dev() == dev
                    && metadata.ino() == ino
                {
                    let _ = fs::remove_file(path);
                }
            }
        }
    }
}

struct ChildGuard(Child);

impl Drop for ChildGuard {
    fn drop(&mut self) {
        if matches!(self.0.try_wait(), Ok(None)) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }
}

fn port() -> u16 {
    TcpListener::bind(("127.0.0.1", 0))
        .unwrap()
        .local_addr()
        .unwrap()
        .port()
}

fn assert_ok(output: std::process::Output) {
    assert!(
        output.status.success(),
        "exit={:?}\nstdout={}\nstderr={}",
        output.status.code(),
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
}

fn assert_private(path: &Path, expect_socket: bool) {
    let metadata = fs::symlink_metadata(path).unwrap();
    let file_type = metadata.file_type();
    assert!(
        if expect_socket {
            file_type.is_socket()
        } else {
            file_type.is_file()
        },
        "wrong file type: {}",
        path.display()
    );
    assert_eq!(metadata.uid(), unsafe { libc::geteuid() });
    assert_eq!(metadata.mode() & 0o7777, 0o600, "{}", path.display());
}

fn http_ok(port: u16) -> bool {
    let Ok(mut stream) = TcpStream::connect_timeout(
        &std::net::SocketAddr::from(([127, 0, 0, 1], port)),
        Duration::from_millis(200),
    ) else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(Duration::from_millis(200)));
    if stream
        .write_all(b"GET /api/session HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        .is_err()
    {
        return false;
    }
    let mut status = String::new();
    BufReader::new(stream).read_line(&mut status).is_ok() && status.starts_with("HTTP/1.1 200 ")
}

fn create_private_dir(path: &Path) {
    fs::create_dir(path).unwrap();
    fs::set_permissions(path, fs::Permissions::from_mode(0o700)).unwrap();
}

#[test]
fn concurrent_start_is_serialized_private_and_never_invokes_tailscale_when_disabled() {
    let mut root = TestRoot::new();
    let plugin_state = root.state();
    let state = root.bridge_state();
    let port = port();
    root.track_bridge(&plugin_state, port);
    let tool_dir = root.path.join("tools");
    let cwd = root.path.join("cwd");
    create_private_dir(&tool_dir);
    create_private_dir(&cwd);
    let touch = ["/usr/bin/touch", "/bin/touch"]
        .into_iter()
        .map(PathBuf::from)
        .find(|path| path.is_file())
        .expect("touch executable");
    std::os::unix::fs::symlink(touch, tool_dir.join("tailscale")).unwrap();

    let barrier = Arc::new(Barrier::new(3));
    let workers: Vec<_> = (0..2)
        .map(|_| {
            let root_path = root.path.clone();
            let state = plugin_state.clone();
            let tool_dir = tool_dir.clone();
            let cwd = cwd.clone();
            let barrier = Arc::clone(&barrier);
            thread::spawn(move || {
                let mut command = Command::new(BIN);
                command
                    .arg("start")
                    .env("HOME", root_path.join("home"))
                    .env("HERDR_PLUGIN_STATE_DIR", state)
                    .env("CAPP_BRIDGE_CONFIG", root_path.join("missing-config.json"))
                    .env("CAPP_BRIDGE_DATA_DIR", root_path.join("data"))
                    .env("CAPP_BRIDGE_HOST", "127.0.0.1")
                    .env("CAPP_BRIDGE_PORT", port.to_string())
                    .env("CAPP_BRIDGE_SERVE_AUTO_APPLY", "0")
                    .env("PATH", tool_dir)
                    .current_dir(cwd);
                barrier.wait();
                command.output().unwrap()
            })
        })
        .collect();
    barrier.wait();
    let outputs: Vec<_> = workers
        .into_iter()
        .map(|worker| worker.join().unwrap())
        .collect();
    for output in &outputs {
        assert!(
            output.status.success(),
            "exit={:?}\nstdout={}\nstderr={}",
            output.status.code(),
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
    }
    let stdout = outputs
        .iter()
        .map(|output| String::from_utf8_lossy(&output.stdout))
        .collect::<String>();
    assert!(stdout.contains("started (pid"));
    assert!(stdout.contains("already running (pid"));
    assert!(http_ok(port));

    let state_meta = fs::metadata(&state).unwrap();
    assert_eq!(state_meta.uid(), unsafe { libc::geteuid() });
    assert_eq!(state_meta.mode() & 0o7777, 0o700);
    for name in ["bridge.lock", "bridge.control", "bridge.log"] {
        assert_private(&state.join(name), false);
    }
    assert_private(&state.join("bridge.sock"), true);
    let record = fs::read_to_string(state.join("bridge.control")).unwrap();
    assert_eq!(record.len(), 65);
    assert!(record.ends_with('\n'));

    assert_ok(
        root.command("status", &plugin_state, port)
            .output()
            .unwrap(),
    );
    assert_ok(root.command("logs", &plugin_state, port).output().unwrap());
    assert_ok(root.command("stop", &plugin_state, port).output().unwrap());
    assert!(!state.join("bridge.control").exists());
    assert!(!state.join("bridge.sock").exists());
    assert!(!http_ok(port));

    let restarted = root.command("start", &plugin_state, port).output().unwrap();
    assert_ok(restarted);
    assert_ok(root.command("stop", &plugin_state, port).output().unwrap());
    assert!(!state.join("bridge.control").exists());
    assert!(!state.join("bridge.sock").exists());
    assert!(!http_ok(port));
    assert!(
        fs::read_dir(&cwd).unwrap().next().is_none(),
        "sentinel tailscale executable was invoked"
    );
}

#[test]
fn malformed_and_stale_records_fail_closed_without_signaling_an_unrelated_process() {
    let root = TestRoot::new();
    let state = root.state();
    create_private_dir(&state);
    let mut unrelated = ChildGuard(
        Command::new("/bin/cat")
            .stdin(Stdio::piped())
            .spawn()
            .unwrap(),
    );
    let port = port();

    let malformed_control = "not-a-token\n";
    fs::write(state.join("bridge.control"), malformed_control).unwrap();
    fs::set_permissions(
        state.join("bridge.control"),
        fs::Permissions::from_mode(0o600),
    )
    .unwrap();
    let output = root.command("stop", &state, port).output().unwrap();
    assert!(!output.status.success());
    assert_eq!(
        fs::read_to_string(state.join("bridge.control")).unwrap(),
        malformed_control
    );
    assert!(matches!(unrelated.0.try_wait(), Ok(None)));
    fs::remove_file(state.join("bridge.control")).unwrap();

    let malformed = format!("{}\n/bin/cat\n123-456\n", unrelated.0.id());
    fs::write(state.join("bridge.pid"), &malformed).unwrap();
    fs::set_permissions(state.join("bridge.pid"), fs::Permissions::from_mode(0o600)).unwrap();
    let output = root.command("stop", &state, port).output().unwrap();
    assert!(!output.status.success());
    assert_eq!(
        fs::read_to_string(state.join("bridge.pid")).unwrap(),
        malformed
    );
    assert!(matches!(unrelated.0.try_wait(), Ok(None)));

    fs::remove_file(state.join("bridge.pid")).unwrap();
    let stale = format!("{}\n", "ab".repeat(32));
    fs::write(state.join("bridge.control"), &stale).unwrap();
    fs::set_permissions(
        state.join("bridge.control"),
        fs::Permissions::from_mode(0o600),
    )
    .unwrap();
    let output = root.command("stop", &state, port).output().unwrap();
    assert!(!output.status.success());
    assert_eq!(
        fs::read_to_string(state.join("bridge.control")).unwrap(),
        stale
    );
    assert!(matches!(unrelated.0.try_wait(), Ok(None)));
}

#[test]
fn unsafe_custom_state_directories_are_rejected_without_chmod_or_following_symlinks() {
    let root = TestRoot::new();
    let port = port();
    let unsafe_plugin_dir = root.path.join("unsafe-state");
    create_private_dir(&unsafe_plugin_dir);
    let unsafe_dir = unsafe_plugin_dir.join(PLUGIN_STATE_SUBDIR);
    create_private_dir(&unsafe_dir);
    fs::set_permissions(&unsafe_dir, fs::Permissions::from_mode(0o755)).unwrap();
    let output = root
        .command("status", &unsafe_plugin_dir, port)
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert_eq!(fs::metadata(&unsafe_dir).unwrap().mode() & 0o7777, 0o755);
    assert!(fs::read_dir(&unsafe_dir).unwrap().next().is_none());

    let target = root.path.join("private-target");
    create_private_dir(&target);
    fs::write(target.join("keep"), "untouched").unwrap();
    fs::set_permissions(target.join("keep"), fs::Permissions::from_mode(0o600)).unwrap();
    let alias = root.path.join("state-alias");
    std::os::unix::fs::symlink(&target, &alias).unwrap();
    let output = root.command("status", &alias, port).output().unwrap();
    assert!(!output.status.success());
    assert_eq!(
        fs::read_to_string(target.join("keep")).unwrap(),
        "untouched"
    );
    assert_eq!(fs::read_dir(target).unwrap().count(), 1);
}

#[test]
fn symlink_and_nonregular_state_files_fail_closed_without_touching_targets() {
    let root = TestRoot::new();
    let port = port();
    for (name, action) in [
        ("bridge.lock", "status"),
        ("bridge.pid", "status"),
        ("bridge.control", "status"),
        ("bridge.log", "logs"),
        ("bridge.sock", "start"),
    ] {
        let state = root.path.join(format!("state-{name}"));
        create_private_dir(&state);
        if name == "bridge.pid" || name == "bridge.control" {
            fs::write(state.join("bridge.lock"), b"").unwrap();
            fs::set_permissions(state.join("bridge.lock"), fs::Permissions::from_mode(0o600))
                .unwrap();
        }
        let target = root.path.join(format!("target-{name}"));
        fs::write(&target, "do not modify").unwrap();
        fs::set_permissions(&target, fs::Permissions::from_mode(0o600)).unwrap();
        std::os::unix::fs::symlink(&target, state.join(name)).unwrap();
        let output = root.command(action, &state, port).output().unwrap();
        assert!(!output.status.success(), "{name} should fail closed");
        assert_eq!(fs::read_to_string(&target).unwrap(), "do not modify");
    }

    let state = root.path.join("state-fifo");
    create_private_dir(&state);
    let fifo =
        std::ffi::CString::new(state.join("bridge.log").as_os_str().as_encoded_bytes()).unwrap();
    assert_eq!(unsafe { libc::mkfifo(fifo.as_ptr(), 0o600) }, 0);
    let output = root.command("logs", &state, port).output().unwrap();
    assert!(!output.status.success());
}

#[test]
fn startup_bind_failure_keeps_an_unrelated_listener_available() {
    let root = TestRoot::new();
    let plugin_state = root.state();
    let state = root.bridge_state();
    let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
    let port = listener.local_addr().unwrap().port();
    let output = root.command("start", &plugin_state, port).output().unwrap();
    assert!(!output.status.success());
    assert!(String::from_utf8_lossy(&output.stderr).contains("exited during startup"));

    let mut client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
    client.write_all(b"sentinel").unwrap();
    let (_accepted, _) = listener.accept().unwrap();
    assert!(!state.join("bridge.control").exists());
    assert!(!state.join("bridge.sock").exists());
}

#[test]
fn stop_timeout_preserves_control_record_and_unrelated_listeners() {
    let root = TestRoot::new();
    let state = root.state();
    create_private_dir(&state);
    let port_listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
    let port = port_listener.local_addr().unwrap().port();
    let mut servers = TestServers::new();
    let tcp_stop = Arc::new(AtomicBool::new(false));
    let tcp_thread = serve_health(port_listener, Arc::clone(&tcp_stop));
    servers.add(Arc::clone(&tcp_stop), tcp_thread);

    let token = "cd".repeat(32);
    fs::write(state.join("bridge.control"), format!("{token}\n")).unwrap();
    fs::set_permissions(
        state.join("bridge.control"),
        fs::Permissions::from_mode(0o600),
    )
    .unwrap();
    let socket_path = state.join("bridge.sock");
    let control_listener = UnixListener::bind(&socket_path).unwrap();
    fs::set_permissions(&socket_path, fs::Permissions::from_mode(0o600)).unwrap();
    servers.track_socket(&socket_path);
    let control_stop = Arc::new(AtomicBool::new(false));
    let control_thread = serve_control(control_listener, token.clone(), Arc::clone(&control_stop));
    servers.add(Arc::clone(&control_stop), control_thread);

    let output = root.command("stop", &state, port).output().unwrap();
    assert!(!output.status.success());
    assert!(String::from_utf8_lossy(&output.stderr).contains("state was preserved"));
    assert_eq!(
        fs::read_to_string(state.join("bridge.control")).unwrap(),
        format!("{token}\n")
    );
    assert!(http_ok(port));
    assert!(state.join("bridge.sock").exists());
}

fn serve_health(listener: TcpListener, stop: Arc<AtomicBool>) -> JoinHandle<()> {
    listener.set_nonblocking(true).unwrap();
    thread::spawn(move || {
        while !stop.load(Ordering::Acquire) {
            match listener.accept() {
                Ok((mut stream, _)) => {
                    let _ = stream.set_read_timeout(Some(Duration::from_millis(200)));
                    let mut request = [0; 256];
                    let _ = stream.read(&mut request);
                    let _ = stream.write_all(
                        b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                    );
                }
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::park_timeout(Duration::from_millis(5))
                }
                Err(_) => return,
            }
        }
    })
}

fn serve_control(listener: UnixListener, token: String, stop: Arc<AtomicBool>) -> JoinHandle<()> {
    listener.set_nonblocking(true).unwrap();
    thread::spawn(move || {
        while !stop.load(Ordering::Acquire) {
            match listener.accept() {
                Ok((mut stream, _)) => {
                    let mut line = String::new();
                    let _ = BufReader::new(&stream).read_line(&mut line);
                    let response = if line.trim_end() == format!("STOP {token}") {
                        "STOPPING 4444\n".to_string()
                    } else if line.trim_end() == format!("PING {token}") {
                        "OK 4444\n".to_string()
                    } else {
                        "DENIED\n".to_string()
                    };
                    let _ = stream.write_all(response.as_bytes());
                }
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::park_timeout(Duration::from_millis(5))
                }
                Err(_) => return,
            }
        }
    })
}
