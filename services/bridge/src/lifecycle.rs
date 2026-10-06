//! Standalone process lifecycle used by the Herdr plugin manifest.

use crate::config::BridgeConfig;
use std::{
    collections::VecDeque,
    fs::{self, File, OpenOptions},
    io::{BufRead, BufReader, Write},
    net::{SocketAddr, TcpStream},
    os::{fd::AsRawFd, unix::process::CommandExt},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const START_TIMEOUT: Duration = Duration::from_secs(5);
const STOP_TIMEOUT: Duration = Duration::from_secs(3);
const POLL_INTERVAL: Duration = Duration::from_millis(100);
const MANAGED_ARG: &str = "__serve";

#[derive(Debug, PartialEq, Eq)]
struct ProcessRecord {
    pid: u32,
    executable: PathBuf,
    nonce: String,
}

pub fn execute(command: &str) -> Result<(), String> {
    match command {
        "start" => start(),
        "stop" => stop(),
        "status" => status(),
        "logs" => logs(),
        _ => Err(format!("unknown command '{command}'")),
    }
}

fn start() -> Result<(), String> {
    let config = BridgeConfig::load()?;
    validate_bind(&config)?;
    let state = state_dir();
    fs::create_dir_all(&state).map_err(|error| format!("create {}: {error}", state.display()))?;
    let _lock = lifecycle_lock(&state)?;
    let record_path = state.join("bridge.pid");

    if let Some(record) = read_record(&record_path) {
        if is_managed_process(&record)? {
            if healthy(&config.bind, config.port) {
                println!(
                    "cappuccino-bridge: already running (pid {}, port {})",
                    record.pid, config.port
                );
                ensure_serve(&config);
                return Ok(());
            }
            return Err(format!(
                "managed bridge process {} exists but is not healthy",
                record.pid
            ));
        }
        let _ = fs::remove_file(&record_path);
    }

    let executable =
        std::env::current_exe().map_err(|error| format!("resolve executable: {error}"))?;
    let nonce = process_nonce();
    let log_path = state.join("bridge.log");
    let log = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(&log_path)
        .map_err(|error| format!("open {}: {error}", log_path.display()))?;
    let stdout = log
        .try_clone()
        .map_err(|error| format!("clone log: {error}"))?;
    let mut child = Command::new(&executable);
    child
        .arg(MANAGED_ARG)
        .arg(&nonce)
        .stdin(Stdio::null())
        .stdout(Stdio::from(stdout))
        .stderr(Stdio::from(log));
    child.process_group(0);
    let mut child = child
        .spawn()
        .map_err(|error| format!("spawn {}: {error}", executable.display()))?;
    let record = ProcessRecord {
        pid: child.id(),
        executable,
        nonce,
    };
    if let Err(error) = write_record(&record_path, &record) {
        let _ = child.kill();
        let _ = child.wait();
        return Err(error);
    }

    let deadline = Instant::now() + START_TIMEOUT;
    loop {
        if let Some(status) = child
            .try_wait()
            .map_err(|error| format!("check startup: {error}"))?
        {
            let _ = fs::remove_file(&record_path);
            return Err(format!(
                "bridge exited during startup ({status}); see {}",
                log_path.display()
            ));
        }
        if is_managed_process(&record)? && healthy(&config.bind, config.port) {
            println!(
                "cappuccino-bridge: started (pid {}, port {})",
                record.pid, config.port
            );
            ensure_serve(&config);
            return Ok(());
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            let _ = fs::remove_file(&record_path);
            return Err(format!(
                "bridge did not become healthy; see {}",
                log_path.display()
            ));
        }
        thread::sleep(POLL_INTERVAL);
    }
}

fn stop() -> Result<(), String> {
    let state = state_dir();
    fs::create_dir_all(&state).map_err(|error| format!("create {}: {error}", state.display()))?;
    let _lock = lifecycle_lock(&state)?;
    let record_path = state.join("bridge.pid");
    let Some(record) = read_record(&record_path) else {
        println!("cappuccino-bridge: not running");
        return Ok(());
    };
    if !is_managed_process(&record)? {
        let _ = fs::remove_file(&record_path);
        println!("cappuccino-bridge: not running (stale pid file cleared)");
        return Ok(());
    }

    // Verify the exact executable and per-start marker before signaling; never use a process-name kill.
    if unsafe { libc::kill(record.pid as libc::pid_t, libc::SIGTERM) } != 0 {
        let error = std::io::Error::last_os_error();
        if error.raw_os_error() != Some(libc::ESRCH) {
            return Err(format!("signal bridge process {}: {error}", record.pid));
        }
    }
    let deadline = Instant::now() + STOP_TIMEOUT;
    while Instant::now() < deadline {
        if !is_managed_process(&record)? {
            let _ = fs::remove_file(&record_path);
            println!("cappuccino-bridge: stopped");
            return Ok(());
        }
        thread::sleep(POLL_INTERVAL);
    }
    Err(format!(
        "bridge process {} did not stop before timeout",
        record.pid
    ))
}

fn status() -> Result<(), String> {
    let config = BridgeConfig::load()?;
    validate_bind(&config)?;
    let state = state_dir();
    fs::create_dir_all(&state).map_err(|error| format!("create {}: {error}", state.display()))?;
    let _lock = lifecycle_lock(&state)?;
    let record = read_record(&state.join("bridge.pid"));
    match record {
        Some(record) if is_managed_process(&record)? && healthy(&config.bind, config.port) => {
            println!(
                "cappuccino-bridge: running (pid {}, port {})",
                record.pid, config.port
            );
            ensure_serve(&config);
            Ok(())
        }
        _ => {
            println!("cappuccino-bridge: not running");
            Err("bridge is not running".to_string())
        }
    }
}

fn logs() -> Result<(), String> {
    let path = state_dir().join("bridge.log");
    let file = File::open(&path).map_err(|error| format!("open {}: {error}", path.display()))?;
    let mut tail = VecDeque::with_capacity(80);
    for line in BufReader::new(file).lines() {
        let line = line.map_err(|error| format!("read {}: {error}", path.display()))?;
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

fn state_dir() -> PathBuf {
    std::env::var_os("HERDR_PLUGIN_STATE_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            let home = std::env::var_os("HOME")
                .map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from("/tmp"));
            home.join(".local/state/cappuccino-bridge")
        })
}

fn lifecycle_lock(state: &Path) -> Result<File, String> {
    let path = state.join("bridge.lock");
    let file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(&path)
        .map_err(|error| format!("open {}: {error}", path.display()))?;
    if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX) } != 0 {
        return Err(format!(
            "lock {}: {}",
            path.display(),
            std::io::Error::last_os_error()
        ));
    }
    Ok(file)
}

fn process_nonce() -> String {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    format!("{}-{nanos}", std::process::id())
}

fn read_record(path: &Path) -> Option<ProcessRecord> {
    let contents = fs::read_to_string(path).ok()?;
    let mut lines = contents.lines();
    let pid: u32 = lines.next()?.parse().ok()?;
    let executable = PathBuf::from(lines.next()?);
    let nonce = lines.next()?.to_string();
    if pid == 0
        || executable.as_os_str().is_empty()
        || lines.next().is_some()
        || nonce.is_empty()
        || !nonce.bytes().all(|b| b.is_ascii_digit() || b == b'-')
    {
        return None;
    }
    Some(ProcessRecord {
        pid,
        executable,
        nonce,
    })
}

fn write_record(path: &Path, record: &ProcessRecord) -> Result<(), String> {
    let temp = path.with_extension(format!("pid.tmp-{}", std::process::id()));
    let mut file =
        File::create(&temp).map_err(|error| format!("create {}: {error}", temp.display()))?;
    writeln!(
        file,
        "{}\n{}\n{}",
        record.pid,
        record.executable.display(),
        record.nonce
    )
    .map_err(|error| format!("write {}: {error}", temp.display()))?;
    file.sync_all()
        .map_err(|error| format!("sync {}: {error}", temp.display()))?;
    fs::rename(&temp, path).map_err(|error| format!("install {}: {error}", path.display()))
}

fn is_managed_process(record: &ProcessRecord) -> Result<bool, String> {
    let pid = record.pid.to_string();
    let state = Command::new("ps")
        .args(["-p", &pid, "-o", "stat="])
        .output()
        .map_err(|error| format!("inspect process {}: {error}", record.pid))?;
    if !state.status.success()
        || String::from_utf8_lossy(&state.stdout)
            .trim_start()
            .starts_with('Z')
    {
        return Ok(false);
    }
    let command = Command::new("ps")
        .args(["-ww", "-p", &pid, "-o", "command="])
        .output()
        .map_err(|error| format!("inspect process {}: {error}", record.pid))?;
    let command = String::from_utf8_lossy(&command.stdout);
    Ok(command_matches(&command, &record.executable, &record.nonce))
}

fn command_matches(command: &str, executable: &Path, nonce: &str) -> bool {
    let expected = format!("{} {MANAGED_ARG} {nonce}", executable.display());
    command.trim() == expected
}

fn healthy(bind: &str, port: u16) -> bool {
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

    #[test]
    fn process_record_rejects_malformed_content() {
        assert_eq!(
            read_record_from("42\n/tmp/cappuccino-bridge\n123-456\n"),
            Some(ProcessRecord {
                pid: 42,
                executable: PathBuf::from("/tmp/cappuccino-bridge"),
                nonce: "123-456".into()
            })
        );
        for contents in [
            "",
            "0\n/tmp/bridge\n1\n",
            "42\n\n",
            "42\n/tmp/bridge\nnot-valid\n",
            "42\n/tmp/bridge\n123-456\nextra\n",
        ] {
            assert_eq!(read_record_from(contents), None, "{contents:?}");
        }
    }

    #[test]
    fn managed_process_match_requires_executable_subcommand_and_nonce() {
        let exe = Path::new("/tmp/cappuccino-bridge");
        assert!(command_matches(
            "/tmp/cappuccino-bridge __serve 12-34",
            exe,
            "12-34"
        ));
        assert!(!command_matches(
            "/tmp/cappuccino-bridge start",
            exe,
            "12-34"
        ));
        assert!(!command_matches(
            "/tmp/cappuccino-bridge __serve 12-35",
            exe,
            "12-34"
        ));
        assert!(!command_matches("/tmp/other __serve 12-34", exe, "12-34"));
    }

    fn read_record_from(contents: &str) -> Option<ProcessRecord> {
        let path = std::env::temp_dir().join(format!("cap-pid-record-{}", std::process::id()));
        fs::write(&path, contents).unwrap();
        let record = read_record(&path);
        let _ = fs::remove_file(path);
        record
    }
}
