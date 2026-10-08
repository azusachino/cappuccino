//! Transcript resolution for pi and agy sessions.
//! Stores are checked, not trusted. A path outside the canonical store,
//! a symlink escaping it, or a non-jsonl file answers None.

use serde_json::Value;
use std::path::{Path, PathBuf};

/// Explicit store/config overrides are exclusive. Otherwise accept reported
/// paths in either supported installation; never infer session identity from cwd.
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
        home.join(".luna/agent/sessions"),
        home.join(".pi/agent/sessions"),
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
    Agy(PathBuf),
}

impl ResolvedTranscript {
    pub fn path(&self) -> &Path {
        match self {
            ResolvedTranscript::Pi(p) => p.as_path(),
            ResolvedTranscript::Agy(p) => p.as_path(),
        }
    }

    #[allow(dead_code)]
    pub fn agent_type(&self) -> &'static str {
        match self {
            ResolvedTranscript::Pi(_) => "pi",
            ResolvedTranscript::Agy(_) => "agy",
        }
    }
}

/// Resolves one session locator (agent name or pane id) to its transcript.
/// Returns Ok(Some(path)) for Pi or Agy, or Ok(None) if not available.
pub async fn resolve_transcript(session: &str) -> Result<Option<PathBuf>, String> {
    Ok(resolve_session_transcript(session)
        .await?
        .map(|r| r.path().to_path_buf()))
}

/// Resolves session locator to a typed ResolvedTranscript (distinguishing agy vs pi).
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

    // If agent is agy (or pane runs agy), attempt agy resolution
    if agent_name == "agy" || is_agy_pane(pane_id, agent_info) {
        if let Some(path) = resolve_agy_transcript_for_pane(pane_id, agent_info) {
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

/// Resolves active agy session transcript.
/// Checks the agy brain directory for an active presence lock or newest session.
pub fn resolve_agy_transcript_for_pane(_pane_id: &str, _agent_info: &Value) -> Option<PathBuf> {
    let brain_dir = agy_brain_dir();
    let presence_dir = brain_dir.parent()?.join("presence");

    // Look for active presence locks
    if let Ok(entries) = std::fs::read_dir(&presence_dir) {
        let mut locks: Vec<PathBuf> = entries
            .filter_map(|e| e.ok())
            .map(|e| e.path())
            .filter(|p| p.extension().and_then(|ext| ext.to_str()) == Some("lock"))
            .collect();
        // Sort newest first
        locks.sort_by_key(|p| std::fs::metadata(p).and_then(|m| m.modified()).ok());
        locks.reverse();

        for lock in locks {
            if let Some(stem) = lock.file_stem().and_then(|s| s.to_str()) {
                let candidate = brain_dir
                    .join(stem)
                    .join(".system_generated")
                    .join("logs")
                    .join("transcript.jsonl");
                if candidate.is_file() {
                    return Some(candidate);
                }
            }
        }
    }

    // Fallback: look for the most recently modified transcript in brain/
    if let Ok(entries) = std::fs::read_dir(&brain_dir) {
        let mut candidates: Vec<(PathBuf, std::time::SystemTime)> = Vec::new();
        for entry in entries.filter_map(|e| e.ok()) {
            let log = entry
                .path()
                .join(".system_generated")
                .join("logs")
                .join("transcript.jsonl");
            if let Ok(meta) = std::fs::metadata(&log) {
                if meta.is_file() {
                    if let Ok(mod_time) = meta.modified() {
                        candidates.push((log, mod_time));
                    }
                }
            }
        }
        candidates.sort_by_key(|(_, t)| *t);
        if let Some((newest, _)) = candidates.pop() {
            return Some(newest);
        }
    }

    None
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
