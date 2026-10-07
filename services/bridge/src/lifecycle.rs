//! Standalone lifecycle commands and private Unix-domain shutdown control.

use crate::{
    config::BridgeConfig,
    state::{FileIdentity, StateDir},
};
use std::{
    collections::VecDeque,
    fs::{self, File},
    io::{self, BufRead, BufReader, Read, Write},
    os::{
        fd::AsRawFd,
        unix::net::{UnixListener, UnixStream},
    },
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

const START_TIMEOUT: Duration = Duration::from_secs(5);
const STOP_TIMEOUT: Duration = Duration::from_secs(3);
const CONTROL_TIMEOUT: Duration = Duration::from_millis(500);
const POLL_INTERVAL: Duration = Duration::from_millis(50);
const MANAGED_ARG: &str = "__serve";
const RECORD_FILE: &str = "bridge.control";
const LEGACY_PID_FILE: &str = "bridge.pid";
const LOCK_FILE: &str = "bridge.lock";
const LOG_FILE: &str = "bridge.log";
const CONTROL_SOCKET: &str = "bridge.sock";
const PLUGIN_STATE_SUBDIR: &str = "state";

pub fn execute(command: &str) -> Result<(), String> {
    match command {
        "start" => start(),
        "stop" => stop(),
        "status" => status(),
        "logs" => logs(),
        _ => Err(format!("unknown command '{command}'")),
    }
}

pub fn state_path() -> Result<PathBuf, String> {
    if let Some(path) = std::env::var_os("HERDR_PLUGIN_STATE_DIR") {
        return plugin_state_path(&PathBuf::from(path));
    }
    let home = std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/tmp"));
    Ok(home.join(".local/state/cappuccino-bridge"))
}

fn plugin_state_path(plugin_dir: &Path) -> Result<PathBuf, String> {
    for name in [
        LOCK_FILE,
        LOG_FILE,
        CONTROL_SOCKET,
        RECORD_FILE,
        LEGACY_PID_FILE,
    ] {
        let path = plugin_dir.join(name);
        match fs::symlink_metadata(&path) {
            Ok(_) => return Ok(plugin_dir.to_owned()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => {
                return Err(format!(
                    "inspect legacy bridge state {}: {error}",
                    path.display()
                ))
            }
        }
    }
    Ok(plugin_dir.join(PLUGIN_STATE_SUBDIR))
}

fn start() -> Result<(), String> {
    let config = BridgeConfig::load()?;
    validate_bind(&config)?;
    let state = StateDir::open(&state_path()?)?;
    let _lock = lifecycle_lock(&state)?;
    reject_legacy_pid_record(&state)?;

    if let Some(token) = read_record(&state)? {
        let pid = control_request(&state, &token, "PING")?;
        if !healthy(&config.bind, config.port) {
            return Err("bridge control endpoint answered but HTTP health check failed".into());
        }
        println!(
            "cappuccino-bridge: already running (pid {pid}, port {})",
            config.port
        );
        ensure_serve(&config);
        return Ok(());
    }
    state.require_absent(CONTROL_SOCKET)?;

    let executable =
        std::env::current_exe().map_err(|error| format!("resolve executable: {error}"))?;
    let token = random_token()?;
    let record = format!("{token}\n");
    let log = state.open_or_create_file(LOG_FILE)?;
    log.set_len(0)
        .map_err(|error| format!("truncate bridge log: {error}"))?;
    let mut record_file = state.create_file(RECORD_FILE)?;
    record_file
        .write_all(record.as_bytes())
        .map_err(|error| format!("write control record: {error}"))?;
    record_file
        .sync_all()
        .map_err(|error| format!("sync control record: {error}"))?;
    let child_stdout = log
        .try_clone()
        .map_err(|error| format!("clone bridge log: {error}"))?;
    let mut child_command = Command::new(&executable);
    child_command
        .arg(MANAGED_ARG)
        .arg(&token)
        .stdin(Stdio::null())
        .stdout(Stdio::from(child_stdout))
        .stderr(Stdio::from(log));
    let mut child = match child_command.spawn() {
        Ok(child) => child,
        Err(error) => {
            state.remove_owned_file(RECORD_FILE, &record)?;
            return Err(format!("spawn {}: {error}", executable.display()));
        }
    };

    match wait_for_ready(&mut child, &state, &token, &config, START_TIMEOUT) {
        Ok(pid) => {
            println!(
                "cappuccino-bridge: started (pid {pid}, port {})",
                config.port
            );
            ensure_serve(&config);
            Ok(())
        }
        Err(error) => Err(error),
    }
}

fn stop() -> Result<(), String> {
    let config = BridgeConfig::load()?;
    validate_bind(&config)?;
    let state = StateDir::open(&state_path()?)?;
    let _lock = lifecycle_lock(&state)?;
    reject_legacy_pid_record(&state)?;
    let Some(token) = read_record(&state)? else {
        println!("cappuccino-bridge: not running");
        return Ok(());
    };

    let pid = control_request(&state, &token, "STOP")?;
    let deadline = Instant::now() + STOP_TIMEOUT;
    loop {
        let control_gone = control_request(&state, &token, "PING").is_err();
        if control_gone && !healthy(&config.bind, config.port) {
            state.remove_owned_file(RECORD_FILE, &format!("{token}\n"))?;
            println!("cappuccino-bridge: stopped (pid {pid})");
            return Ok(());
        }
        if Instant::now() >= deadline {
            return Err(format!(
                "bridge process {pid} did not stop before timeout; state was preserved"
            ));
        }
        thread::sleep(POLL_INTERVAL);
    }
}

fn status() -> Result<(), String> {
    let config = BridgeConfig::load()?;
    validate_bind(&config)?;
    let state = StateDir::open(&state_path()?)?;
    let _lock = lifecycle_lock(&state)?;
    reject_legacy_pid_record(&state)?;
    let Some(token) = read_record(&state)? else {
        println!("cappuccino-bridge: not running");
        return Err("bridge is not running".into());
    };
    let pid = control_request(&state, &token, "PING")?;
    if !healthy(&config.bind, config.port) {
        return Err("bridge control endpoint answered but HTTP health check failed".into());
    }
    println!(
        "cappuccino-bridge: running (pid {pid}, port {})",
        config.port
    );
    ensure_serve(&config);
    Ok(())
}

fn logs() -> Result<(), String> {
    let state = StateDir::open(&state_path()?)?;
    let file = state
        .open_existing(LOG_FILE)?
        .ok_or_else(|| "bridge log does not exist".to_string())?;
    let mut tail = VecDeque::with_capacity(80);
    for line in BufReader::new(file).lines() {
        let line = line.map_err(|error| format!("read bridge log: {error}"))?;
        if tail.len() == 80 {
            tail.pop_front();
        }
        tail.push_back(line);
    }
    for line in tail {
        println!("{line}");
    }
    Ok(())
}

fn lifecycle_lock(state: &StateDir) -> Result<File, String> {
    let file = state.open_or_create_file(LOCK_FILE)?;
    if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX) } != 0 {
        return Err(format!(
            "lock lifecycle state: {}",
            io::Error::last_os_error()
        ));
    }
    Ok(file)
}

fn reject_legacy_pid_record(state: &StateDir) -> Result<(), String> {
    if state.open_existing(LEGACY_PID_FILE)?.is_some() {
        return Err(format!(
            "legacy PID record {} remains; verify any old bridge process and remove the file manually before using this lifecycle",
            state.path().join(LEGACY_PID_FILE).display()
        ));
    }
    Ok(())
}

fn read_record(state: &StateDir) -> Result<Option<String>, String> {
    let Some(file) = state.open_existing(RECORD_FILE)? else {
        return Ok(None);
    };
    let mut contents = String::new();
    file.take(128)
        .read_to_string(&mut contents)
        .map_err(|error| format!("read lifecycle control record: {error}"))?;
    if contents.len() != 65 || !contents.ends_with('\n') {
        return Err(format!(
            "malformed lifecycle control record {}; refusing to replace or remove it",
            state.path().join(RECORD_FILE).display()
        ));
    }
    let token = contents.trim_end_matches('\n');
    if !valid_token(token) {
        return Err(format!(
            "invalid lifecycle control token in {}; refusing to replace or remove it",
            state.path().join(RECORD_FILE).display()
        ));
    }
    Ok(Some(token.to_owned()))
}

fn wait_for_ready(
    child: &mut Child,
    state: &StateDir,
    token: &str,
    config: &BridgeConfig,
    timeout: Duration,
) -> Result<u32, String> {
    let deadline = Instant::now() + timeout;
    loop {
        if let Some(status) = child
            .try_wait()
            .map_err(|error| format!("inspect owned child: {error}"))?
        {
            state.remove_owned_socket_path(CONTROL_SOCKET)?;
            state.remove_owned_file(RECORD_FILE, &format!("{token}\n"))?;
            return Err(format!("bridge child exited during startup ({status})"));
        }
        if let Ok(pid) = control_request(state, token, "PING") {
            if pid == child.id() && healthy(&config.bind, config.port) {
                return Ok(pid);
            }
        }
        if Instant::now() >= deadline {
            terminate_owned_child(child)?;
            state.remove_owned_socket_path(CONTROL_SOCKET)?;
            state.remove_owned_file(RECORD_FILE, &format!("{token}\n"))?;
            return Err(format!(
                "bridge did not become ready within {} ms; owned child stopped",
                timeout.as_millis()
            ));
        }
        thread::sleep(POLL_INTERVAL);
    }
}

fn terminate_owned_child(child: &mut Child) -> Result<(), String> {
    match child
        .try_wait()
        .map_err(|error| format!("inspect spawned bridge child: {error}"))?
    {
        Some(_) => Ok(()),
        None => {
            // This is the unreaped Child handle created by this start operation, never a PID from state.
            child
                .kill()
                .map_err(|error| format!("stop spawned bridge child: {error}"))?;
            child
                .wait()
                .map_err(|error| format!("reap spawned bridge child: {error}"))?;
            Ok(())
        }
    }
}

pub struct ControlServer {
    listener: UnixListener,
    state: StateDir,
    identity: FileIdentity,
    token: String,
}

impl ControlServer {
    pub fn bind(state: &StateDir, token: &str) -> Result<Self, String> {
        state.require_absent(CONTROL_SOCKET)?;
        let cleanup_state = state.try_clone()?;
        let path = state.socket_path(CONTROL_SOCKET)?;
        if !valid_token(token) {
            return Err("invalid control token".into());
        }
        let listener = UnixListener::bind(&path)
            .map_err(|error| format!("bind private control socket {}: {error}", path.display()))?;
        let created_identity = state.created_socket_identity(CONTROL_SOCKET)?;
        let identity = match state.socket_identity(CONTROL_SOCKET) {
            Ok(identity) if identity == created_identity => identity,
            Ok(_) => {
                return Err("control socket identity changed after bind".into());
            }
            Err(error) => {
                let _ = state.remove_socket(CONTROL_SOCKET, created_identity);
                return Err(format!(
                    "control socket did not have required owner-only mode: {error}"
                ));
            }
        };
        if let Err(error) = listener.set_nonblocking(true) {
            let _ = state.remove_socket(CONTROL_SOCKET, identity);
            return Err(format!("set control socket nonblocking: {error}"));
        }
        Ok(Self {
            listener,
            state: cleanup_state,
            identity,
            token: token.to_owned(),
        })
    }

    pub fn spawn(
        self,
        shutdown: tokio::sync::oneshot::Sender<()>,
        stopping: Arc<AtomicBool>,
    ) -> io::Result<JoinHandle<()>> {
        thread::Builder::new()
            .name("cappuccino-control".into())
            .spawn(move || self.serve(shutdown, stopping))
    }

    fn serve(self, shutdown: tokio::sync::oneshot::Sender<()>, stopping: Arc<AtomicBool>) {
        loop {
            if stopping.load(Ordering::Acquire) {
                return;
            }
            match self.listener.accept() {
                Ok((mut stream, _)) => {
                    let request = read_control_line(&mut stream);
                    match request.as_deref() {
                        Ok(line) if valid_control_line(line, "PING", &self.token) => {
                            let _ = writeln!(stream, "OK {}", std::process::id());
                        }
                        Ok(line) if valid_control_line(line, "STOP", &self.token) => {
                            let _ = writeln!(stream, "STOPPING {}", std::process::id());
                            let _ = shutdown.send(());
                            return;
                        }
                        _ => {
                            let _ = stream.write_all(b"DENIED\n");
                        }
                    }
                }
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                    thread::park_timeout(POLL_INTERVAL)
                }
                Err(_) => {
                    let _ = shutdown.send(());
                    return;
                }
            }
        }
    }
}

impl Drop for ControlServer {
    fn drop(&mut self) {
        let _ = self.state.remove_socket(CONTROL_SOCKET, self.identity);
    }
}

fn read_control_line(stream: &mut UnixStream) -> io::Result<String> {
    stream.set_read_timeout(Some(CONTROL_TIMEOUT))?;
    let mut bytes = Vec::with_capacity(80);
    let mut byte = [0u8; 1];
    loop {
        match stream.read(&mut byte)? {
            0 => {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "empty control request",
                ))
            }
            _ if byte[0] == b'\n' => {
                return String::from_utf8(bytes)
                    .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))
            }
            _ if bytes.len() < 80 => bytes.push(byte[0]),
            _ => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "control request too long",
                ))
            }
        }
    }
}

fn valid_control_line(line: &str, action: &str, token: &str) -> bool {
    let Some((provided_action, provided_token)) = line.split_once(' ') else {
        return false;
    };
    provided_action == action && constant_time_eq(provided_token.as_bytes(), token.as_bytes())
}

fn constant_time_eq(left: &[u8], right: &[u8]) -> bool {
    if left.len() != right.len() {
        return false;
    }
    left.iter()
        .zip(right)
        .fold(0u8, |difference, (a, b)| difference | (a ^ b))
        == 0
}

fn control_request(state: &StateDir, token: &str, action: &str) -> Result<u32, String> {
    state.socket_identity(CONTROL_SOCKET)?;
    let path = state.socket_path(CONTROL_SOCKET)?;
    let mut stream = UnixStream::connect(&path)
        .map_err(|error| format!("connect private bridge control socket: {error}"))?;
    stream
        .set_read_timeout(Some(CONTROL_TIMEOUT))
        .map_err(|error| format!("set control timeout: {error}"))?;
    stream
        .set_write_timeout(Some(CONTROL_TIMEOUT))
        .map_err(|error| format!("set control timeout: {error}"))?;
    writeln!(stream, "{action} {token}")
        .map_err(|error| format!("write control request: {error}"))?;
    let mut response = String::new();
    BufReader::new(stream)
        .take(128)
        .read_to_string(&mut response)
        .map_err(|error| format!("read control response: {error}"))?;
    let expected = if action == "PING" { "OK" } else { "STOPPING" };
    let mut fields = response.split_whitespace();
    if fields.next() != Some(expected) {
        return Err(
            "bridge control endpoint rejected request or returned an invalid response".into(),
        );
    }
    let pid: u32 = fields
        .next()
        .ok_or("bridge control response omitted process id")?
        .parse()
        .map_err(|_| "bridge control response had invalid process id")?;
    if pid == 0 || fields.next().is_some() {
        return Err("bridge control response was malformed".into());
    }
    Ok(pid)
}

fn random_token() -> Result<String, String> {
    let mut bytes = [0u8; 32];
    File::open("/dev/urandom")
        .and_then(|mut random| random.read_exact(&mut bytes))
        .map_err(|error| format!("read operating-system random source: {error}"))?;
    let mut token = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        use std::fmt::Write as _;
        write!(&mut token, "{byte:02x}").expect("string write");
    }
    Ok(token)
}

pub fn valid_token(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn validate_bind(config: &BridgeConfig) -> Result<(), String> {
    if ["127.0.0.1", "localhost", "::1"].contains(&config.bind.as_str()) {
        Ok(())
    } else {
        Err(format!(
            "refusing non-loopback bind {}; tailnet exposure belongs to tailscale serve",
            config.bind
        ))
    }
}

fn healthy(bind: &str, port: u16) -> bool {
    use std::{
        io::{BufRead, BufReader, Write},
        net::{SocketAddr, TcpStream},
    };
    let addresses = match bind {
        "::1" => vec![SocketAddr::from(([0, 0, 0, 0, 0, 0, 0, 1], port))],
        "localhost" => vec![
            SocketAddr::from(([127, 0, 0, 1], port)),
            SocketAddr::from(([0, 0, 0, 0, 0, 0, 0, 1], port)),
        ],
        _ => vec![SocketAddr::from(([127, 0, 0, 1], port))],
    };
    let Some(mut stream) = addresses
        .into_iter()
        .find_map(|address| TcpStream::connect_timeout(&address, Duration::from_millis(250)).ok())
    else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(Duration::from_millis(250)));
    if stream
        .write_all(b"GET /api/session HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
        .is_err()
    {
        return false;
    }
    let mut response = String::new();
    matches!(BufReader::new(stream).read_line(&mut response), Ok(_) if response.starts_with("HTTP/1.1 200 ") || response.starts_with("HTTP/1.0 200 "))
}

fn ensure_serve(config: &BridgeConfig) {
    let manual = format!(
        "tailscale serve --bg --https=443 http://127.0.0.1:{}",
        config.port
    );
    if !config.serve_auto_apply {
        println!("tailscale serve auto-apply is disabled; expose manually if needed:\n  {manual}");
        return;
    }
    let status = Command::new("tailscale").args(["serve", "status"]).output();
    let Ok(status) = status else {
        println!("tailscale CLI not found; expose manually:\n  {manual}");
        return;
    };
    let output = String::from_utf8_lossy(&status.stdout);
    if output.contains(&format!("127.0.0.1:{}", config.port)) {
        if let Some(url) = output
            .split_whitespace()
            .find(|part| part.starts_with("https://"))
        {
            println!(
                "tailscale serve already exposes port {}; tailnet URL: {url}",
                config.port
            );
        } else {
            println!("tailscale serve already exposes port {}", config.port);
        }
        return;
    }
    let target = format!("http://127.0.0.1:{}", config.port);
    match Command::new("tailscale")
        .args(["serve", "--bg", "--https=443", &target])
        .status()
    {
        Ok(exit) if exit.success() => println!(
            "tailscale serve configured for {}; manual command: {manual}",
            target
        ),
        _ => println!("tailscale serve failed; expose manually:\n  {manual}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        fs,
        os::unix::fs::{MetadataExt, PermissionsExt},
    };

    struct TestChild(Child);

    impl Drop for TestChild {
        fn drop(&mut self) {
            if matches!(self.0.try_wait(), Ok(None)) {
                let _ = self.0.kill();
                let _ = self.0.wait();
            }
        }
    }

    #[test]
    fn plugin_state_uses_private_child_and_preserves_legacy_layout() {
        let token = random_token().unwrap();
        let plugin_dir = PathBuf::from("/tmp").join(format!(
            "cappuccino-state-path-{}-{}",
            std::process::id(),
            &token[..16]
        ));
        fs::create_dir(&plugin_dir).unwrap();
        assert_eq!(
            plugin_state_path(&plugin_dir).unwrap(),
            plugin_dir.join(PLUGIN_STATE_SUBDIR)
        );
        fs::write(plugin_dir.join(LEGACY_PID_FILE), b"legacy\n").unwrap();
        assert_eq!(plugin_state_path(&plugin_dir).unwrap(), plugin_dir);
        fs::remove_dir_all(plugin_dir).unwrap();
    }

    #[test]
    fn token_validation_and_constant_time_comparison_require_exact_token() {
        let token = "ab".repeat(32);
        assert!(valid_token(&token));
        assert!(!valid_token(&token[..62]));
        assert!(!valid_token(&format!("{token}x")));
        assert!(constant_time_eq(token.as_bytes(), token.as_bytes()));
        assert!(!constant_time_eq(
            token.as_bytes(),
            "cd".repeat(32).as_bytes()
        ));
    }

    #[test]
    fn readiness_timeout_terminates_only_the_owned_child_handle() {
        let root = PathBuf::from("/tmp").canonicalize().unwrap();
        let token = random_token().unwrap();
        let state_root = root.join(format!("r{}-{}", std::process::id(), &token[..16]));
        fs::create_dir(&state_root).unwrap();
        fs::set_permissions(&state_root, fs::Permissions::from_mode(0o700)).unwrap();
        let root_identity = fs::symlink_metadata(&state_root).unwrap();
        let state = StateDir::open(&state_root.join("state")).unwrap();
        let record = format!("{}\n", "ab".repeat(32));
        let mut record_file = state.create_file(RECORD_FILE).unwrap();
        record_file.write_all(record.as_bytes()).unwrap();
        record_file.sync_all().unwrap();
        let socket_path = state.socket_path(CONTROL_SOCKET).unwrap();
        let socket = UnixListener::bind(&socket_path).unwrap();
        fs::set_permissions(&socket_path, fs::Permissions::from_mode(0o600)).unwrap();
        drop(socket);
        let mut child = TestChild(
            Command::new("/bin/cat")
                .stdin(Stdio::piped())
                .spawn()
                .unwrap(),
        );
        let result = wait_for_ready(
            &mut child.0,
            &state,
            "ab".repeat(32).as_str(),
            &BridgeConfig {
                bind: "127.0.0.1".into(),
                port: 1,
                data_dir: PathBuf::new(),
                auth: crate::config::AuthConfig::default(),
                serve_auto_apply: false,
            },
            Duration::from_millis(100),
        );
        assert!(result.unwrap_err().contains("did not become ready"));
        assert!(child.0.try_wait().unwrap().is_some());
        assert!(state.open_existing(RECORD_FILE).unwrap().is_none());
        assert!(!socket_path.exists());
        drop(state);
        let current = fs::symlink_metadata(&state_root).unwrap();
        assert!(current.file_type().is_dir());
        assert_eq!(current.uid(), unsafe { libc::geteuid() });
        assert_eq!(current.mode() & 0o7777, 0o700);
        assert_eq!(current.dev(), root_identity.dev());
        assert_eq!(current.ino(), root_identity.ino());
        fs::remove_dir_all(state_root).unwrap();
    }
}
