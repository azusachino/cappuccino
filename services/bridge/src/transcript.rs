//! pi transcript resolution, learned from herdr-web-ui's server/pi.ts
//! (design only): the store is checked, not trusted. The herdr-reported
//! `agent_session` must be a `path` inside the canonical pi sessions store,
//! a regular `.jsonl` file — anything else is no transcript.

use serde_json::Value;
use std::path::{Path, PathBuf};

/// pi's sessions store: `PI_CODING_AGENT_SESSION_DIR` moves it; otherwise
/// `<pi agent dir>/sessions` where the agent dir is `~/.pi/agent`.
pub fn sessions_dir() -> PathBuf {
    if let Ok(explicit) = std::env::var("PI_CODING_AGENT_SESSION_DIR") {
        return PathBuf::from(explicit);
    }
    PathBuf::from(home_dir())
        .join(".pi")
        .join("agent")
        .join("sessions")
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

/// Resolves one session locator (agent name or pane id) to its transcript.
/// Fail-closed: this herdr build reports no `agent_session` for most panes,
/// and that absence is an explicit "no transcript", never a guess.
pub async fn resolve_transcript(session: &str) -> Result<Option<PathBuf>, String> {
    let info = crate::herdr::agent_get(session)
        .await
        .map_err(|error| format!("{error}"))?;
    let session_report = &info["agent"]["agent_session"];
    if session_report["kind"].as_str() != Some("path") {
        return Ok(None);
    }
    let value = session_report["value"]
        .as_str()
        .ok_or_else(|| "agent_session path is not a string".to_string())?;
    Ok(transcript_in_store(Path::new(value), &sessions_dir()))
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

    fn unique_temp(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("cap-bridge-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn file_inside_store_is_accepted() {
        let store = unique_temp("inside");
        let transcript = store.join("session-abc.jsonl");
        std::fs::write(&transcript, "{}\n").unwrap();
        // The function returns the canonical (symlink-resolved) path, which on
        // macOS may differ textually from the /tmp-aliased input.
        let expected = std::fs::canonicalize(&transcript).unwrap();
        assert_eq!(
            transcript_in_store(&transcript, &store).as_deref(),
            Some(expected.as_path())
        );
    }

    #[test]
    fn path_outside_store_is_refused() {
        let store = unique_temp("outside");
        let elsewhere = unique_temp("elsewhere-store");
        let transcript = elsewhere.join("escape.jsonl");
        std::fs::write(&transcript, "{}\n").unwrap();
        assert_eq!(transcript_in_store(&transcript, &store), None);
    }

    #[test]
    fn symlink_escape_is_refused() {
        let store = unique_temp("symlink");
        let elsewhere = unique_temp("symlink-elsewhere");
        std::fs::create_dir_all(&store).unwrap();
        let real = elsewhere.join("real.jsonl");
        std::fs::write(&real, "{}\n").unwrap();
        let link = store.join("link.jsonl");
        std::os::unix::fs::symlink(&real, &link).unwrap();
        assert_eq!(
            transcript_in_store(&link, &store),
            None,
            "a link out of the store is no evidence"
        );
    }

    #[test]
    fn non_jsonl_and_directories_are_refused() {
        let store = unique_temp("shapes");
        let text = store.join("notes.txt");
        std::fs::write(&text, "hello").unwrap();
        let directory = store.join("fake.jsonl");
        std::fs::create_dir_all(&directory).unwrap();
        assert_eq!(transcript_in_store(&text, &store), None);
        assert_eq!(transcript_in_store(&directory, &store), None);
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
        assert!(
            result.is_err(),
            "no socket means a typed error, never a fabricated transcript"
        );
    }
}
