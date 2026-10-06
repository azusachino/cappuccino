//! Agent catalog: exact `herdr agent list` parity, including unnamed panes,
//! plus honest branch reporting (a branch is the session's own only when the
//! git toplevel equals the agent cwd). Mirrors the daemon's catalog contract
//! so both transports render identically in the clients.

use serde_json::{json, Value};

/// One agent row on the wire; field names match the reference daemon so the
/// Swift models need no per-transport variants.
#[derive(Debug, Clone, PartialEq)]
pub struct AgentRow {
    pub machine_id: String,
    pub session_id: String,
    pub pane_id: String,
    pub label: String,
    pub agent: String,
    pub status: String,
    pub working: bool,
    pub cwd: String,
    pub active_branch: Option<String>,
    pub branch_source: String, // "own" | "none"
}

impl AgentRow {
    pub fn to_json(&self) -> Value {
        json!({
            "machine_id": self.machine_id,
            "session_id": self.session_id,
            "pane_id": self.pane_id,
            "label": self.label,
            "agent": self.agent,
            "status": self.status,
            "working": self.working,
            "cwd": self.cwd,
            "active_branch": self.active_branch,
            "branch_source": self.branch_source,
        })
    }
}

/// Maps an `agent.list` result envelope to rows. Unnamed agents (Herdr has no
/// `name` for them) are kept for exact parity: pane id becomes the
/// deterministic session_id and the agent kind the label — no invented names.
pub fn map_agents(result: &Value, machine_id: &str) -> Result<Vec<AgentRow>, String> {
    let agents = result["agents"]
        .as_array()
        .ok_or_else(|| "agent.list result missing agents array".to_string())?;
    Ok(agents
        .iter()
        .filter_map(|agent| {
            let pane_id = agent["pane_id"].as_str()?.to_string();
            if pane_id.is_empty() {
                return None;
            }
            let name = agent["name"].as_str().unwrap_or_default();
            let kind = agent["agent"].as_str().unwrap_or("unknown").to_string();
            let cwd = agent["cwd"].as_str().unwrap_or_default().to_string();
            let branch = own_branch(&cwd);
            Some(AgentRow {
                machine_id: machine_id.to_string(),
                session_id: if name.is_empty() {
                    pane_id.clone()
                } else {
                    name.to_string()
                },
                pane_id,
                label: if name.is_empty() {
                    kind.clone()
                } else {
                    agent["terminal_title_stripped"]
                        .as_str()
                        .or_else(|| agent["terminal_title"].as_str())
                        .unwrap_or(name)
                        .to_string()
                },
                agent: kind,
                status: agent["agent_status"]
                    .as_str()
                    .unwrap_or("unknown")
                    .to_string(),
                working: agent["agent_status"].as_str() == Some("working"),
                cwd,
                active_branch: branch.branch,
                branch_source: branch.source,
            })
        })
        .collect())
}

pub struct BranchReport {
    pub branch: Option<String>,
    pub source: String, // "own" | "none"
}

/// The branch is the session's own only when the repository toplevel equals
/// the agent cwd; a branch discovered from an enclosing checkout is inherited
/// context and is never presented as the session's branch.
pub fn own_branch(cwd: &str) -> BranchReport {
    if cwd.is_empty() {
        return BranchReport {
            branch: None,
            source: "none".into(),
        };
    }
    let toplevel = match git(&["-C", cwd, "rev-parse", "--show-toplevel"]) {
        Some(top) => top,
        None => {
            return BranchReport {
                branch: None,
                source: "none".into(),
            }
        }
    };
    if !same_directory(cwd, &toplevel) {
        return BranchReport {
            branch: None,
            source: "none".into(),
        };
    }
    match git(&["-C", cwd, "--no-optional-locks", "branch", "--show-current"]) {
        Some(branch) if !branch.is_empty() => BranchReport {
            branch: Some(branch),
            source: "own".into(),
        },
        _ => BranchReport {
            branch: None,
            source: "none".into(),
        },
    }
}

fn git(arguments: &[&str]) -> Option<String> {
    let output = std::process::Command::new("git")
        .args(arguments)
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let text = String::from_utf8(output.stdout).ok()?;
    let trimmed = text.trim().to_string();
    if trimmed.is_empty() {
        None
    } else {
        Some(trimmed)
    }
}

/// Device + inode identity: symlink-proof where string normalization is not
/// (/var vs /private/var).
pub fn same_directory(a: &str, b: &str) -> bool {
    use std::os::unix::fs::MetadataExt;
    let (Ok(meta_a), Ok(meta_b)) = (std::fs::metadata(a), std::fs::metadata(b)) else {
        return a == b;
    };
    meta_a.dev() == meta_b.dev() && meta_a.ino() == meta_b.ino()
}

/// Stable per-machine identity, same store the reference daemon uses
/// (~/Library/Application Support/cappuccino-spike/machine-id, 0600).
pub fn machine_id() -> String {
    let dir = format!(
        "{}/Library/Application Support/cappuccino-spike",
        home_dir()
    );
    let file = format!("{dir}/machine-id");
    if let Ok(existing) = std::fs::read_to_string(&file) {
        let trimmed = existing.trim().to_string();
        if !trimmed.is_empty() {
            return trimmed;
        }
    }
    let fresh = uuid_v4();
    let _ = std::fs::create_dir_all(&dir);
    let _ = std::fs::write(&file, &fresh);
    fresh
}

/// UUID v4 from /dev/urandom (macOS/BSD); a deterministic fallback keeps the
/// bridge alive on systems without it.
fn uuid_v4() -> String {
    use std::io::Read;
    let mut bytes = [0u8; 16];
    if std::fs::File::open("/dev/urandom")
        .and_then(|mut file| file.read_exact(&mut bytes))
        .is_err()
    {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        bytes = nanos.to_le_bytes().to_vec().try_into().unwrap();
    }
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    let hex: String = bytes.iter().map(|byte| format!("{byte:02x}")).collect();
    format!(
        "{}-{}-{}-{}-{}",
        &hex[0..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..32]
    )
}

pub fn home_dir() -> String {
    std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unnamed_panes_get_pane_id_fallback_and_kind_label() {
        let result: Value = serde_json::from_str(
            r#"{"agents":[
                {"agent":"pi","pane_id":"w1:p5Y","agent_status":"idle","cwd":"",
                 "name":"cap-spike-agent","terminal_title_stripped":"pi - agent-workdir"},
                {"agent":"pi","pane_id":"w1:p43","agent_status":"working","cwd":"","name":""}
            ]}"#,
        )
        .unwrap();
        let rows = map_agents(&result, "m-1").unwrap();
        assert_eq!(
            rows.len(),
            2,
            "unnamed panes are part of exact herdr parity"
        );
        assert_eq!(rows[0].session_id, "cap-spike-agent");
        assert_eq!(rows[0].label, "pi - agent-workdir");
        assert_eq!(
            rows[1].session_id, "w1:p43",
            "pane id is the deterministic fallback"
        );
        assert_eq!(rows[1].label, "pi", "agent kind is the fallback label");
        assert_eq!(rows[1].branch_source, "none", "empty cwd has no branch");
        assert_eq!(rows[0].active_branch, None);
    }

    #[test]
    fn rows_render_the_daemon_wire_shape() {
        let result: Value = serde_json::from_str(
            r#"{"agents":[{"agent":"pi","pane_id":"w1:a","agent_status":"working","cwd":"","name":"s-aurora"}]}"#,
        )
        .unwrap();
        let row = &map_agents(&result, "m-1").unwrap()[0];
        let json = row.to_json();
        assert_eq!(json["session_id"], "s-aurora");
        assert_eq!(json["branch_source"], "none");
        assert_eq!(json["active_branch"], Value::Null);
        assert_eq!(json["working"], true);
    }

    #[test]
    fn missing_agents_array_is_a_clean_error() {
        let result: Value = serde_json::from_str("{}").unwrap();
        assert!(map_agents(&result, "m-1").is_err());
    }

    #[test]
    fn machine_id_is_stable() {
        let first = machine_id();
        let second = machine_id();
        assert_eq!(first, second);
        assert_eq!(first.len(), 36);
    }
}
