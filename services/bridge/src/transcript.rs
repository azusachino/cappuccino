//! Transcript resolution for pi, claude and agy sessions.
//! Stores are checked, not trusted. A path outside the canonical store,
//! a symlink escaping it, or a non-jsonl file answers None.

use serde_json::Value;
use std::path::{Path, PathBuf};

/// Explicit store/config overrides are exclusive. Otherwise accept reported
/// paths in Pi's default store; never infer session identity from cwd.
fn session_stores(
    home: &Path,
    session_dir: Option<PathBuf>,
    agent_dir: Option<PathBuf>,
) -> Vec<PathBuf> {
    if let Some(store) = session_dir {
        return vec![store];
    }
    if let Some(dir) = agent_dir {
        return vec![dir.join("sessions")];
    }
    vec![
        home.join(".pi/agent/sessions"),
        home.join(".luna/agent/sessions"),
    ]
}

fn pi_session_stores() -> Vec<PathBuf> {
    session_stores(
        Path::new(&home_dir()),
        std::env::var_os("PI_CODING_AGENT_SESSION_DIR").map(PathBuf::from),
        std::env::var_os("PI_CODING_AGENT_DIR").map(PathBuf::from),
    )
}

fn reported_pi_transcript(agent_info: &Value, stores: &[PathBuf]) -> Option<PathBuf> {
    let report = &agent_info["agent_session"];
    if report["kind"].as_str() != Some("path") {
        return None;
    }
    let path = Path::new(report["value"].as_str()?);
    stores
        .iter()
        .find_map(|store| transcript_in_store(path, store))
}

/// Claude Code's project store: `CLAUDE_CONFIG_DIR` moves it; otherwise `~/.claude`.
pub fn claude_projects_dir() -> PathBuf {
    std::env::var_os("CLAUDE_CONFIG_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| Path::new(&home_dir()).join(".claude"))
        .join("projects")
}

/// Claude session IDs are UUIDs; anything with separators or dots could name another file.
fn is_claude_session_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 128 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
}

/// Herdr keeps only the session ID that Claude's SessionStart hook reported (it drops the
/// path), so find `<id>.jsonl` in the project store. Exactly one match is required; the ID,
/// not the pane's cwd or a newest file, establishes identity.
fn reported_claude_transcript(agent_info: &Value, projects: &Path) -> Option<PathBuf> {
    let report = &agent_info["agent_session"];
    if report["source"].as_str() != Some("herdr:claude")
        || report["agent"].as_str() != Some("claude")
        || report["kind"].as_str() != Some("id")
    {
        return None;
    }
    let id = report["value"].as_str()?;
    if !is_claude_session_id(id) {
        return None;
    }
    let file = format!("{id}.jsonl");
    let mut matches = std::fs::read_dir(projects)
        .ok()?
        .filter_map(Result::ok)
        .map(|project| project.path().join(&file))
        .filter_map(|candidate| transcript_in_store(&candidate, projects));
    let found = matches.next()?;
    matches.next().is_none().then_some(found)
}

/// Agy session IDs are UUIDs; anything with separators or dots could name another file.
fn is_agy_session_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 128 && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
}

/// Resolves reported agy session from Herdr's antigravity_cli integration.
fn reported_agy_transcript(agent_info: &Value, brain_dir: &Path) -> Option<PathBuf> {
    let report = &agent_info["agent_session"];
    if report["source"].as_str() != Some("herdr:antigravity_cli")
        || report["agent"].as_str() != Some("agy")
    {
        return None;
    }
    match report["kind"].as_str() {
        Some("id") => {
            let id = report["value"].as_str()?;
            if !is_agy_session_id(id) {
                return None;
            }
            let path = brain_dir
                .join(id)
                .join(".system_generated/logs/transcript.jsonl");
            transcript_in_store(&path, brain_dir)
        }
        Some("path") => {
            let path = Path::new(report["value"].as_str()?);
            transcript_in_store(path, brain_dir)
        }
        _ => None,
    }
}

/// agy's brain store: `ANTIGRAVITY_APP_DATA_DIR` moves it; otherwise
/// `~/.gemini/antigravity-cli/brain`.
pub fn agy_brain_dir() -> PathBuf {
    if let Ok(explicit) = std::env::var("ANTIGRAVITY_APP_DATA_DIR") {
        return PathBuf::from(explicit).join("brain");
    }
    PathBuf::from(home_dir())
        .join(".gemini")
        .join("antigravity-cli")
        .join("brain")
}

pub fn home_dir() -> String {
    std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string())
}

/// The canonical transcript path inside the store, or None. A path outside
/// the store, a link escaping it, a non-`.jsonl` file or a directory wearing
/// the extension answers no transcript.
pub fn transcript_in_store(path: &Path, session_dir: &Path) -> Option<PathBuf> {
    let canonical = std::fs::canonicalize(path).ok()?;
    let root = std::fs::canonicalize(session_dir).ok()?;
    if !canonical.starts_with(&root) {
        return None;
    }
    if canonical.extension()? != "jsonl" {
        return None;
    }
    let metadata = std::fs::metadata(&canonical).ok()?;
    if !metadata.is_file() {
        return None;
    }
    Some(canonical)
}

/// Resolved transcript information including the agent type and canonical path.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ResolvedTranscript {
    Pi(PathBuf),
    Claude(PathBuf),
    Agy(PathBuf),
}

impl ResolvedTranscript {
    pub fn path(&self) -> &Path {
        match self {
            ResolvedTranscript::Pi(p) => p.as_path(),
            ResolvedTranscript::Claude(p) => p.as_path(),
            ResolvedTranscript::Agy(p) => p.as_path(),
        }
    }

    pub fn agent_type(&self) -> &'static str {
        match self {
            ResolvedTranscript::Pi(_) => "pi",
            ResolvedTranscript::Claude(_) => "claude",
            ResolvedTranscript::Agy(_) => "agy",
        }
    }

    /// Identity of the resolved canonical session, never its reusable pane locator.
    /// Pi supplies its session-header ID; Claude its reported session ID; Agy's process-bound presence lock selected
    /// this exact brain session directory. Hashing keeps raw IDs/paths off the wire.
    pub fn conversation_id(&self, pi_header: Option<&str>) -> Option<String> {
        let header;
        let session = match self {
            Self::Pi(_) => {
                header = serde_json::from_str::<Value>(pi_header?).ok()?;
                if header["type"].as_str()? != "session" {
                    return None;
                }
                header["id"].as_str()?
            }
            // The resolver only selects `<reported session id>.jsonl`.
            Self::Claude(path) => path.file_stem()?.to_str()?,
            Self::Agy(path) => {
                if !path.ends_with(".system_generated/logs/transcript.jsonl") {
                    return None;
                }
                path.parent()?.parent()?.parent()?.file_name()?.to_str()?
            }
        };
        if session.is_empty()
            || session.len() > 256
            || session.trim() != session
            || session.chars().any(char::is_control)
        {
            return None;
        }
        use sha2::{Digest, Sha256};
        let mut digest = Sha256::new();
        digest.update(b"cappuccino:canonical-conversation:v1\0");
        digest.update(self.agent_type().as_bytes());
        digest.update([0]);
        digest.update(session.as_bytes());
        Some(format!("{}:{:x}", self.agent_type(), digest.finalize()))
    }
}

/// Resolves one session locator (agent name or pane id) to its transcript.
/// Returns Ok(Some(path)) for Pi, Claude or Agy, or Ok(None) if not available.
pub async fn resolve_transcript(session: &str) -> Result<Option<PathBuf>, String> {
    Ok(resolve_session_transcript(session)
        .await?
        .map(|r| r.path().to_path_buf()))
}

/// Resolves session locator to a typed ResolvedTranscript (Pi, Claude or Agy).
pub async fn resolve_session_transcript(
    session: &str,
) -> Result<Option<ResolvedTranscript>, String> {
    let info = crate::herdr::agent_get(session)
        .await
        .map_err(|error| format!("{error}"))?;
    let agent_info = &info["agent"];
    let agent_name = agent_info["agent"].as_str().unwrap_or("");
    let pane_id = agent_info["pane_id"].as_str().unwrap_or("");

    if let Some(path) = reported_pi_transcript(agent_info, &pi_session_stores()) {
        return Ok(Some(ResolvedTranscript::Pi(path)));
    }

    if agent_name == "claude" {
        if let Some(path) = reported_claude_transcript(agent_info, &claude_projects_dir()) {
            return Ok(Some(ResolvedTranscript::Claude(path)));
        }
    }

    if let Some(path) = reported_agy_transcript(agent_info, &agy_brain_dir()) {
        return Ok(Some(ResolvedTranscript::Agy(path)));
    }

    // If agent is agy (or pane runs agy), attempt agy resolution
    if agent_name == "agy" || is_agy_pane(pane_id, agent_info) {
        if let Some(path) = resolve_agy_transcript_for_pane(pane_id).await {
            return Ok(Some(ResolvedTranscript::Agy(path)));
        }
    }

    Ok(None)
}

fn is_agy_pane(pane_id: &str, agent_info: &Value) -> bool {
    // A Pi title can mention agy in the task/cwd; never cross agent stores.
    if agent_info["agent"].as_str() == Some("pi") {
        return false;
    }
    if agent_info["terminal_title"]
        .as_str()
        .unwrap_or("")
        .contains("agy")
    {
        return true;
    }
    if pane_id.is_empty() {
        return false;
    }
    false
}

/// The selected pane must contain exactly one foreground Agy process.
fn agy_pid(info: &Value, pane_id: &str) -> Option<u32> {
    let process_info = &info["process_info"];
    if process_info["pane_id"].as_str()? != pane_id {
        return None;
    }
    let mut pids = process_info["foreground_processes"]
        .as_array()?
        .iter()
        .filter(|process| process["name"].as_str() == Some("agy"))
        .filter_map(|process| u32::try_from(process["pid"].as_u64()?).ok());
    let pid = pids.next().filter(|pid| *pid > 0)?;
    if pids.next().is_some() {
        return None;
    }
    Some(pid)
}

/// Only a presence file OPEN BY that process identifies its session. Stale files,
/// directory mtimes, terminal titles and cwd are never session identity.
fn agy_open_transcript(files: &str, brain_dir: &Path) -> Option<PathBuf> {
    let presence = std::fs::canonicalize(brain_dir.parent()?.join("presence")).ok()?;
    let mut sessions = std::collections::BTreeSet::new();
    for line in files.lines().filter_map(|line| line.strip_prefix('n')) {
        let path = Path::new(line);
        if path.extension().and_then(|ext| ext.to_str()) != Some("lock") {
            continue;
        }
        let canonical = match std::fs::canonicalize(path) {
            Ok(canonical) => canonical,
            Err(_) => continue,
        };
        if canonical.parent() != Some(presence.as_path()) {
            continue;
        }
        let Some(session) = canonical.file_stem().and_then(|s| s.to_str()) else {
            continue;
        };
        sessions.insert(session.to_string());
    }
    if sessions.len() != 1 {
        return None;
    }
    let path = brain_dir
        .join(sessions.first()?)
        .join(".system_generated/logs/transcript.jsonl");
    transcript_in_store(&path, brain_dir)
}

async fn resolve_agy_transcript_for_pane(pane_id: &str) -> Option<PathBuf> {
    let params = serde_json::json!({"pane_id": pane_id});
    let info = crate::herdr::request("pane.process_info", params.clone())
        .await
        .ok()?;
    let pid = agy_pid(&info, pane_id)?;
    // lsof is a passive, process-scoped FD query. Missing tool, timeout, changed
    // process or ambiguous ownership means no canonical history, never a guess.
    let output = tokio::time::timeout(
        std::time::Duration::from_secs(2),
        tokio::process::Command::new("lsof")
            .args(["-a", "-n", "-p", &pid.to_string(), "-Fn"])
            .kill_on_drop(true)
            .output(),
    )
    .await
    .ok()?
    .ok()?;
    if !output.status.success() {
        return None;
    }
    let path = agy_open_transcript(std::str::from_utf8(&output.stdout).ok()?, &agy_brain_dir())?;
    let current = crate::herdr::request("pane.process_info", params)
        .await
        .ok()?;
    if agy_pid(&current, pane_id) != Some(pid) {
        return None;
    }
    Some(path)
}

/// Shape returned by GET /api/transcript.
pub fn transcript_json(session_id: &str, path: Option<&Path>) -> Value {
    match path {
        Some(path) => serde_json::json!({
            "session_id": session_id,
            "transcript_path": path.to_string_lossy(),
            "available": true,
        }),
        None => serde_json::json!({
            "session_id": session_id,
            "transcript_path": Value::Null,
            "available": false,
            "reason": "no canonical pi transcript in the sessions store",
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn claude_report(id: &str) -> Value {
        serde_json::json!({
            "agent": "claude", "cwd": "/synthetic",
            "agent_session": {"source": "herdr:claude", "agent": "claude", "kind": "id", "value": id}
        })
    }

    #[test]
    fn claude_resolves_only_the_reported_id_inside_the_store() {
        let projects = TempStore::new("claude-projects");
        let id = "0f6c2a3e-1111-4abc-9def-123456789abc";
        std::fs::create_dir_all(projects.path.join("-synthetic")).unwrap();
        std::fs::create_dir_all(projects.path.join("-other")).unwrap();
        let selected = projects.path.join("-synthetic").join(format!("{id}.jsonl"));
        std::fs::write(&selected, "{}\n").unwrap();
        // A newer, unreported session in the same project is never chosen.
        std::fs::write(projects.path.join("-synthetic/newer.jsonl"), "{}\n").unwrap();

        assert_eq!(
            reported_claude_transcript(&claude_report(id), &projects.path),
            Some(std::fs::canonicalize(&selected).unwrap())
        );
        assert_eq!(
            ResolvedTranscript::Claude(selected.clone())
                .conversation_id(None)
                .map(|id| id.starts_with("claude:")),
            Some(true)
        );
    }

    #[test]
    fn claude_rejects_foreign_sources_unsafe_ids_and_ambiguous_matches() {
        let projects = TempStore::new("claude-reject");
        let id = "abc-123";
        for project in ["-a", "-b"] {
            std::fs::create_dir_all(projects.path.join(project)).unwrap();
        }
        std::fs::write(projects.path.join(format!("-a/{id}.jsonl")), "{}\n").unwrap();

        let mut foreign = claude_report(id);
        foreign["agent_session"]["source"] = "custom".into();
        assert_eq!(reported_claude_transcript(&foreign, &projects.path), None);
        let mut path_kind = claude_report(id);
        path_kind["agent_session"]["kind"] = "path".into();
        assert_eq!(reported_claude_transcript(&path_kind, &projects.path), None);
        for unsafe_id in ["../-a/abc-123", "abc.123", "", "a/b"] {
            assert_eq!(
                reported_claude_transcript(&claude_report(unsafe_id), &projects.path),
                None,
                "{unsafe_id}"
            );
        }
        assert_eq!(
            reported_claude_transcript(&claude_report("missing"), &projects.path),
            None
        );

        // The same ID in two projects cannot establish one session.
        std::fs::write(projects.path.join(format!("-b/{id}.jsonl")), "{}\n").unwrap();
        assert_eq!(
            reported_claude_transcript(&claude_report(id), &projects.path),
            None
        );
    }

    struct TempStore {
        path: PathBuf,
    }

    impl TempStore {
        fn new(tag: &str) -> Self {
            let dir = std::env::temp_dir().join(format!("cap-bridge-{tag}-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).unwrap();
            Self { path: dir }
        }
    }

    impl Drop for TempStore {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.path);
        }
    }

    #[test]
    fn reported_path_accepts_both_installations_without_cwd_guessing() {
        let home = TempStore::new("pi-stores");
        let stores = session_stores(&home.path, None, None);
        for store in &stores {
            std::fs::create_dir_all(store).unwrap();
            let selected = store.join("selected.jsonl");
            let other = store.join("newer.jsonl");
            std::fs::write(&selected, "{}\n").unwrap();
            std::fs::write(&other, "{}\n").unwrap();
            let info = serde_json::json!({
                "agent": "pi", "cwd": "/synthetic", "terminal_title": "π",
                "agent_session": {"kind": "path", "value": selected}
            });
            assert_eq!(
                reported_pi_transcript(&info, &stores),
                Some(std::fs::canonicalize(selected).unwrap())
            );
            assert_eq!(
                reported_pi_transcript(
                    &serde_json::json!({"agent": "pi", "cwd": "/synthetic"}),
                    &stores
                ),
                None
            );
            assert_eq!(
                reported_pi_transcript(
                    &serde_json::json!({"agent_session": {"kind": "id", "value": other}}),
                    &stores
                ),
                None
            );
        }
    }

    #[test]
    fn agy_session_is_owned_by_the_selected_process_not_newest_file() {
        let home = TempStore::new("agy-owner");
        let brain = home.path.join("brain");
        let presence = home.path.join("presence");
        std::fs::create_dir_all(&presence).unwrap();
        for session in ["selected", "newer-closed"] {
            let logs = brain.join(session).join(".system_generated/logs");
            std::fs::create_dir_all(&logs).unwrap();
            std::fs::write(logs.join("transcript.jsonl"), "{}\n").unwrap();
            std::fs::write(presence.join(format!("{session}.lock")), "").unwrap();
        }
        let owned = format!("p42\nn{}\n", presence.join("selected.lock").display());
        let expected =
            std::fs::canonicalize(brain.join("selected/.system_generated/logs/transcript.jsonl"))
                .unwrap();
        assert_eq!(agy_open_transcript(&owned, &brain), Some(expected));
        assert_eq!(agy_open_transcript("p42\n", &brain), None);
        let ambiguous = format!("{owned}n{}\n", presence.join("newer-closed.lock").display());
        assert_eq!(agy_open_transcript(&ambiguous, &brain), None);
        let info = serde_json::json!({"process_info": {
            "pane_id": "selected-pane", "foreground_processes": [
                {"name": "agy", "pid": 42}, {"name": "node", "pid": 43}
            ]
        }});
        assert_eq!(agy_pid(&info, "selected-pane"), Some(42));
        assert_eq!(agy_pid(&info, "other-pane"), None);
    }

    #[test]
    fn pi_title_cannot_trigger_agy_discovery() {
        assert!(!is_agy_pane(
            "pane",
            &serde_json::json!({
                "agent": "pi", "terminal_title": "π - agy-integration"
            })
        ));
    }

    #[test]
    fn explicit_pi_overrides_are_exclusive() {
        let home = Path::new("/synthetic/home");
        let config = PathBuf::from("/synthetic/config");
        let store = PathBuf::from("/synthetic/sessions");
        assert_eq!(
            session_stores(home, Some(store.clone()), Some(config.clone())),
            vec![store]
        );
        assert_eq!(
            session_stores(home, None, Some(config.clone())),
            vec![config.join("sessions")]
        );
        assert_eq!(
            session_stores(home, None, None),
            vec![
                home.join(".pi/agent/sessions"),
                home.join(".luna/agent/sessions"),
            ]
        );
    }

    #[test]
    fn reported_agy_session_resolves_inside_brain_dir() {
        let home = TempStore::new("agy-reported");
        let brain = home.path.join("brain");
        let session = "925039ca-99d9-499b-9485-7a7b0ed89cd5";
        let logs = brain.join(session).join(".system_generated/logs");
        std::fs::create_dir_all(&logs).unwrap();
        let transcript = logs.join("transcript.jsonl");
        std::fs::write(&transcript, "{}\n").unwrap();
        let expected = std::fs::canonicalize(&transcript).unwrap();

        let id_info = serde_json::json!({
            "agent": "agy",
            "agent_session": {
                "source": "herdr:antigravity_cli",
                "agent": "agy",
                "kind": "id",
                "value": session,
            }
        });
        assert_eq!(
            reported_agy_transcript(&id_info, &brain),
            Some(expected.clone())
        );

        let path_info = serde_json::json!({
            "agent": "agy",
            "agent_session": {
                "source": "herdr:antigravity_cli",
                "agent": "agy",
                "kind": "path",
                "value": transcript.to_str().unwrap(),
            }
        });
        assert_eq!(reported_agy_transcript(&path_info, &brain), Some(expected));

        let bad_id = serde_json::json!({
            "agent": "agy",
            "agent_session": {
                "source": "herdr:antigravity_cli",
                "agent": "agy",
                "kind": "id",
                "value": "../escaping-id",
            }
        });
        assert_eq!(reported_agy_transcript(&bad_id, &brain), None);

        let wrong_source = serde_json::json!({
            "agent": "agy",
            "agent_session": {
                "source": "herdr:other",
                "agent": "agy",
                "kind": "id",
                "value": session,
            }
        });
        assert_eq!(reported_agy_transcript(&wrong_source, &brain), None);
    }

    #[test]
    fn agy_open_transcript_ignores_missing_and_foreign_locks() {
        let home = TempStore::new("agy-foreign-locks");
        let brain = home.path.join("brain");
        let presence = home.path.join("presence");
        std::fs::create_dir_all(&presence).unwrap();
        let logs = brain.join("active-session").join(".system_generated/logs");
        std::fs::create_dir_all(&logs).unwrap();
        std::fs::write(logs.join("transcript.jsonl"), "{}\n").unwrap();
        std::fs::write(presence.join("active-session.lock"), "").unwrap();

        let lsof_output = format!(
            "p123\nfcwd\nn/some/cwd\nn{}\nn/nonexistent/missing.lock\nn/path/to/packed-refs.lock\n",
            presence.join("active-session.lock").display()
        );
        let expected = std::fs::canonicalize(logs.join("transcript.jsonl")).unwrap();
        assert_eq!(agy_open_transcript(&lsof_output, &brain), Some(expected));
    }

    #[test]
    fn file_inside_store_is_accepted() {
        let store = TempStore::new("inside");
        let transcript = store.path.join("session-abc.jsonl");
        std::fs::write(&transcript, "{}\n").unwrap();
        let expected = std::fs::canonicalize(&transcript).unwrap();
        assert_eq!(
            transcript_in_store(&transcript, &store.path).as_deref(),
            Some(expected.as_path())
        );
    }

    #[test]
    fn path_outside_store_is_refused() {
        let store = TempStore::new("outside");
        let elsewhere = TempStore::new("elsewhere-store");
        let transcript = elsewhere.path.join("escape.jsonl");
        std::fs::write(&transcript, "{}\n").unwrap();
        assert_eq!(transcript_in_store(&transcript, &store.path), None);
    }

    #[test]
    fn symlink_escape_is_refused() {
        let store = TempStore::new("symlink");
        let elsewhere = TempStore::new("symlink-elsewhere");
        std::fs::create_dir_all(&store.path).unwrap();
        let real = elsewhere.path.join("real.jsonl");
        std::fs::write(&real, "{}\n").unwrap();
        let link = store.path.join("link.jsonl");
        std::os::unix::fs::symlink(&real, &link).unwrap();
        assert_eq!(transcript_in_store(&link, &store.path), None);
    }

    #[test]
    fn non_jsonl_and_directories_are_refused() {
        let store = TempStore::new("shapes");
        let text = store.path.join("notes.txt");
        std::fs::write(&text, "hello").unwrap();
        let directory = store.path.join("fake.jsonl");
        std::fs::create_dir_all(&directory).unwrap();
        assert_eq!(transcript_in_store(&text, &store.path), None);
        assert_eq!(transcript_in_store(&directory, &store.path), None);
    }

    #[test]
    fn missing_store_refuses_everything() {
        let transcript = PathBuf::from("/tmp/definitely-not-a-store/x.jsonl");
        assert_eq!(
            transcript_in_store(&transcript, &PathBuf::from("/tmp/definitely-not-a-store")),
            None
        );
    }

    #[tokio::test]
    async fn resolution_without_herdr_fails_cleanly() {
        let saved = std::env::var("HERDR_SOCKET_PATH").ok();
        std::env::set_var(
            "HERDR_SOCKET_PATH",
            "/nonexistent/cappuccino-transcript.sock",
        );
        let result = resolve_transcript("cap-spike-agent").await;
        std::env::remove_var("HERDR_SOCKET_PATH");
        if let Some(saved) = saved {
            std::env::set_var("HERDR_SOCKET_PATH", saved);
        }
        assert!(result.is_err());
    }
}
