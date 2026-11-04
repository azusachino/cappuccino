//! Pi session index (contract section 5.1, P1 and P3): the entry tree of one session file.
//!
//! The index is derived from the file's bytes alone. The bridge keeps one per file, shared by all
//! clients, and extends it by reading only the bytes appended since the last request. It is rebuilt
//! when the file's identity changes, when the file shrinks, or when the bytes before the indexed
//! position no longer match what was indexed.
//!
//! The active branch is the path from the file's last entry up to the root through `parentId`. Pi
//! does not store the leaf, and its own loader takes the last entry in file order, so this matches.

use std::collections::{HashMap, HashSet};
use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom};
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};

use serde_json::Value;

/// Bytes kept from the end of the indexed region, to detect a rewritten file.
const TAIL_BYTES: usize = 512;
/// At most this many files are kept; the cache is cleared beyond it.
const MAX_FILES: usize = 16;

/// The branch and the context edits of one file, as of its complete records.
#[derive(Debug, Default)]
pub struct Snapshot {
    /// Entry ids on the active branch.
    pub active: HashSet<String>,
    /// Byte offsets of the records that are on the active branch, or are not entries at all.
    pub kept_offsets: HashSet<u64>,
    /// Byte offset of every entry's record.
    pub entry_offsets: HashSet<u64>,
    /// Turn ids whose context was removed by an active context edit, with the offset of that edit.
    pub omitted: HashMap<String, u64>,
    /// Byte offset of each entry's record, by entry id.
    pub offsets: HashMap<String, u64>,
}

/// A context edit: the turn it changes, whether it removes context, and where it is.
struct Edit {
    entry: String,
    turn: String,
    removes: bool,
    offset: u64,
}

struct Index {
    identity: (u64, u64),
    indexed: u64,
    tail: Vec<u8>,
    parents: HashMap<String, Option<String>>,
    offsets: HashMap<String, u64>,
    leaf: Option<String>,
    /// The turn an entry belongs to: itself, or for a tool result the assistant entry that called the tool.
    turn_of: HashMap<String, String>,
    /// Which assistant entry made a tool call, by call id.
    call_owner: HashMap<String, String>,
    edits: Vec<Edit>,
    snapshot: Arc<Snapshot>,
}

static CACHE: OnceLock<Mutex<HashMap<PathBuf, Index>>> = OnceLock::new();

/// The snapshot of a Pi session file, extended to its complete records.
pub fn snapshot(path: &Path) -> io::Result<Arc<Snapshot>> {
    let mut file = File::open(path)?;
    let meta = file.metadata()?;
    let identity = (meta.dev(), meta.ino());
    let end = crate::sync::complete_end(&mut file)?;

    let cache = CACHE.get_or_init(|| Mutex::new(HashMap::new()));
    let mut cache = cache
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let mut index = match cache.remove(path) {
        Some(index) if index.identity == identity && index.indexed <= end => {
            if index.still_matches(&mut file)? {
                index
            } else {
                Index::empty(identity)
            }
        }
        _ => Index::empty(identity),
    };
    if index.indexed < end {
        index.absorb(&mut file, end)?;
        index.snapshot = Arc::new(index.build_snapshot());
    }
    let snapshot = index.snapshot.clone();
    if cache.len() >= MAX_FILES {
        cache.clear();
    }
    cache.insert(path.to_path_buf(), index);
    Ok(snapshot)
}

impl Index {
    fn empty(identity: (u64, u64)) -> Self {
        Index {
            identity,
            indexed: 0,
            tail: Vec::new(),
            parents: HashMap::new(),
            offsets: HashMap::new(),
            leaf: None,
            turn_of: HashMap::new(),
            call_owner: HashMap::new(),
            edits: Vec::new(),
            snapshot: Arc::new(Snapshot::default()),
        }
    }

    /// Whether the bytes before the indexed position are the ones that were indexed.
    fn still_matches(&self, file: &mut File) -> io::Result<bool> {
        if self.tail.is_empty() {
            return Ok(true);
        }
        let start = self.indexed - self.tail.len() as u64;
        let mut now = vec![0u8; self.tail.len()];
        file.seek(SeekFrom::Start(start))?;
        file.read_exact(&mut now)?;
        Ok(now == self.tail)
    }

    /// Reads the complete records from the indexed position up to `end` and records them.
    fn absorb(&mut self, file: &mut File, end: u64) -> io::Result<()> {
        file.seek(SeekFrom::Start(self.indexed))?;
        let mut bytes = vec![0u8; (end - self.indexed) as usize];
        file.read_exact(&mut bytes)?;
        let mut position = self.indexed;
        for raw in bytes.split_inclusive(|byte| *byte == b'\n') {
            let offset = position;
            position += raw.len() as u64;
            let record = raw.strip_suffix(b"\n").unwrap_or(raw);
            self.record(offset, record);
        }
        self.indexed = end;
        let keep_from = bytes.len().saturating_sub(TAIL_BYTES);
        self.tail = bytes[keep_from..].to_vec();
        Ok(())
    }

    fn record(&mut self, offset: u64, bytes: &[u8]) {
        let Ok(value) = serde_json::from_slice::<Value>(bytes) else {
            return;
        };
        let kind = value["type"].as_str().unwrap_or("");
        if kind == "session" {
            return;
        }
        let Some(id) = value["id"].as_str() else {
            return;
        };
        self.parents.insert(
            id.to_string(),
            value["parentId"].as_str().map(str::to_string),
        );
        self.offsets.insert(id.to_string(), offset);
        self.leaf = Some(id.to_string());

        let mut owner = id.to_string();
        if kind == "message" {
            let message = &value["message"];
            match message["role"].as_str().unwrap_or("") {
                "assistant" => {
                    for block in message["content"].as_array().into_iter().flatten() {
                        if matches!(block["type"].as_str(), Some("toolCall" | "tool_call")) {
                            if let Some(call) = block["id"].as_str() {
                                self.call_owner.insert(call.to_string(), id.to_string());
                            }
                        }
                    }
                }
                "toolResult" | "tool_result" => {
                    if let Some(owned) = message["toolCallId"]
                        .as_str()
                        .and_then(|call| self.call_owner.get(call))
                    {
                        owner = owned.clone();
                    }
                }
                _ => {}
            }
        }
        self.turn_of.insert(id.to_string(), owner.clone());

        if kind == "context_edit" {
            if let Some(target) = value["targetId"].as_str() {
                let turn = self
                    .turn_of
                    .get(target)
                    .cloned()
                    .unwrap_or_else(|| target.to_string());
                self.edits.push(Edit {
                    entry: id.to_string(),
                    turn,
                    removes: value["replacement"].is_null(),
                    offset,
                });
            }
        }
    }

    /// The branch walked up from the leaf, and the edits that are on it. A cycle or a missing parent
    /// ends the walk.
    fn build_snapshot(&self) -> Snapshot {
        let mut active = HashSet::new();
        let mut current = self.leaf.clone();
        while let Some(id) = current {
            if !active.insert(id.clone()) {
                break;
            }
            current = self.parents.get(&id).cloned().flatten();
        }
        let mut omitted: HashMap<String, u64> = HashMap::new();
        for edit in &self.edits {
            if !active.contains(&edit.entry) {
                continue;
            }
            if edit.removes {
                omitted.insert(edit.turn.clone(), edit.offset);
            } else {
                omitted.remove(&edit.turn);
            }
        }
        let kept_offsets = self
            .offsets
            .iter()
            .filter(|(id, _)| active.contains(*id))
            .map(|(_, offset)| *offset)
            .collect();
        Snapshot {
            active,
            kept_offsets,
            entry_offsets: self.offsets.values().copied().collect(),
            omitted,
            offsets: self.offsets.clone(),
        }
    }
}
