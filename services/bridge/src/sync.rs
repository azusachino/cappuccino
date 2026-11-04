//! Conversation sync (contract section 7). The phone keeps a replicated log per session; the
//! bridge serves records from the agent's session file and keeps no per-client state.
//!
//! Pages start at the record that opens a turn, so every turn in a page is parsed from its own
//! first record. A token is opaque to the phone: hex-encoded JSON of `{file, offset, record}`.
//! `offset` is the byte offset of the record a page starts from, and `record` is that record's
//! turn id. A token is trusted only when the record at its offset still has that id.

use crate::conversation::{self, ConversationTurn, Parsed};
use crate::transcript::ResolvedTranscript;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::fs::File;
use std::io::{self, BufRead, BufReader, Read, Seek, SeekFrom};
use std::path::Path;

/// The agent whose file a session reads from. Its record rules decide turn boundaries.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Pi,
    Claude,
    Agy,
}

impl Kind {
    pub fn of(resolved: &ResolvedTranscript) -> Kind {
        match resolved {
            ResolvedTranscript::Pi(_) => Kind::Pi,
            ResolvedTranscript::Claude(_) => Kind::Claude,
            ResolvedTranscript::Agy(_) => Kind::Agy,
        }
    }
}

/// The session file's identity: its file stem for Pi and Claude Code, and the conversation
/// directory for Agy, whose file is always `transcript.jsonl`.
pub fn file_id(resolved: &ResolvedTranscript) -> String {
    let path = resolved.path();
    let name = match Kind::of(resolved) {
        Kind::Agy => path.parent().and_then(Path::file_name),
        _ => path.file_stem(),
    };
    name.and_then(|name| name.to_str())
        .unwrap_or_default()
        .to_string()
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Token {
    pub file: String,
    pub offset: u64,
    pub record: String,
}

pub fn encode(token: &Token) -> String {
    to_hex(&serde_json::to_vec(token).unwrap_or_default())
}

/// Decodes a token. Anything that is not a token this bridge issued returns `None`.
pub fn decode(text: &str) -> Option<Token> {
    serde_json::from_slice(&from_hex(text)?).ok()
}

fn to_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn from_hex(text: &str) -> Option<Vec<u8>> {
    if text.len() % 2 != 0 {
        return None;
    }
    (0..text.len())
        .step_by(2)
        .map(|index| u8::from_str_radix(text.get(index..index + 2)?, 16).ok())
        .collect()
}

/// One complete record: its byte offset in the file and its bytes without the newline.
#[derive(Debug, Clone)]
pub struct Line {
    pub offset: u64,
    pub bytes: Vec<u8>,
}

/// Byte offset just past the last newline. A record still being written is not part of the file yet.
fn complete_end(file: &mut File) -> io::Result<u64> {
    const CHUNK: u64 = 64 * 1024;
    let mut end = file.seek(SeekFrom::End(0))?;
    while end > 0 {
        let start = end.saturating_sub(CHUNK);
        let mut chunk = vec![0u8; (end - start) as usize];
        file.seek(SeekFrom::Start(start))?;
        file.read_exact(&mut chunk)?;
        if let Some(position) = chunk.iter().rposition(|byte| *byte == b'\n') {
            return Ok(start + position as u64 + 1);
        }
        end = start;
    }
    Ok(0)
}

/// Complete records from `start` (a record boundary) up to `end`, until `budget` bytes are read.
/// Returns the records, the offset after them, and whether `end` was reached.
fn read_forward(
    file: &mut File,
    start: u64,
    end: u64,
    budget: u64,
) -> io::Result<(Vec<Line>, u64, bool)> {
    file.seek(SeekFrom::Start(start))?;
    let mut reader = BufReader::new(file.by_ref().take(end.saturating_sub(start)));
    let mut lines = Vec::new();
    let mut position = start;
    while position < end && position - start < budget {
        let mut bytes = Vec::new();
        let read = reader.read_until(b'\n', &mut bytes)?;
        if read == 0 || bytes.last() != Some(&b'\n') {
            break;
        }
        bytes.pop();
        lines.push(Line {
            offset: position,
            bytes,
        });
        position += read as u64;
    }
    Ok((lines, position, position >= end))
}

/// Complete records that end at `end` (a record boundary), reading back at least `min_bytes` when
/// the file has them. Returns the records and whether they begin at the start of the file.
fn read_backward(file: &mut File, end: u64, min_bytes: u64) -> io::Result<(Vec<Line>, bool)> {
    const CHUNK: u64 = 256 * 1024;
    let mut start = end;
    let mut buffer: Vec<u8> = Vec::new();
    while start > 0 {
        let needed = min_bytes.saturating_sub(end - start).max(4096);
        let take = CHUNK.min(needed).min(start);
        start -= take;
        let mut chunk = vec![0u8; take as usize];
        file.seek(SeekFrom::Start(start))?;
        file.read_exact(&mut chunk)?;
        chunk.extend_from_slice(&buffer);
        buffer = chunk;
        if end - start >= min_bytes {
            break;
        }
    }

    let mut base = start;
    if start > 0 {
        // A window that starts inside a record begins with its tail. Skip to the next record.
        let boundary = {
            let mut before = [0u8; 1];
            file.seek(SeekFrom::Start(start - 1))?;
            file.read_exact(&mut before)?;
            before[0] == b'\n'
        };
        if !boundary {
            match buffer.iter().position(|byte| *byte == b'\n') {
                Some(position) => {
                    buffer.drain(..=position);
                    base = start + position as u64 + 1;
                }
                None => return Ok((Vec::new(), false)),
            }
        }
    }

    let mut lines = Vec::new();
    let mut position = base;
    let mut rest = &buffer[..];
    while let Some(newline) = rest.iter().position(|byte| *byte == b'\n') {
        lines.push(Line {
            offset: position,
            bytes: rest[..newline].to_vec(),
        });
        position += newline as u64 + 1;
        rest = &rest[newline + 1..];
    }
    Ok((lines, start == 0))
}

/// Parses records with the agent's own rules. Each parsed turn carries the index of the record that opened it.
fn parse(kind: Kind, lines: &[Line]) -> Parsed {
    let mut text = String::new();
    for line in lines {
        match kind {
            Kind::Claude => text.push_str(&conversation::compact_claude_line(&line.bytes)),
            _ => text.push_str(&String::from_utf8_lossy(&line.bytes)),
        }
        text.push('\n');
    }
    match kind {
        Kind::Pi => conversation::parse_pi_with_openers(&text),
        Kind::Claude => conversation::parse_claude_with_openers(&text),
        Kind::Agy => conversation::parse_agy_with_openers(&text),
    }
}

/// The id a record would open a turn with, read without parsing the whole file.
fn record_id(kind: Kind, bytes: &[u8]) -> Option<String> {
    let value: Value = serde_json::from_slice(bytes).ok()?;
    match kind {
        Kind::Claude => value["uuid"].as_str().map(str::to_string),
        Kind::Pi => value["id"].as_str().map(str::to_string),
        Kind::Agy => value["step_index"]
            .as_u64()
            .map(|index| format!("step-{index}")),
    }
}

/// A token's record matches a line when the line's id is the record, or the record is a
/// suffixed duplicate (`<id>#<hash>`) of the line's id.
fn record_matches(kind: Kind, bytes: &[u8], record: &str) -> bool {
    match record_id(kind, bytes) {
        Some(id) => {
            record == id
                || record
                    .strip_prefix(&id)
                    .is_some_and(|rest| rest.starts_with('#'))
        }
        None => false,
    }
}

/// A page of turns and the tokens that continue it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Page {
    pub turns: Vec<ConversationTurn>,
    /// Forward position after this page; `None` for an earlier page.
    pub after: Option<Token>,
    /// Earlier position; `None` at the start of the file.
    pub before: Option<Token>,
    /// True when this page reached the end of the file.
    pub caught_up: bool,
}

fn forward(
    file: &mut File,
    session: &str,
    kind: Kind,
    start: u64,
    end: u64,
    budget: u64,
) -> io::Result<Page> {
    let mut lines = Vec::new();
    let mut next = start;
    let reached = loop {
        let (more, after, reached) = read_forward(file, next, end, budget)?;
        lines.extend(more);
        next = after;
        // Stop at the end of the file, or once two turns open: the first is complete, so this
        // page always makes progress.
        if reached || parse(kind, &lines).opened_at_line.len() >= 2 {
            break reached;
        }
    };
    let parsed = parse(kind, &lines);
    // Hold the token at the earliest turn still waiting for a tool result, so that result is sent
    // with its turn when it arrives. The first turn of a page is never held, so each page moves on.
    let held = parsed
        .turns
        .iter()
        .enumerate()
        .skip(1)
        .find(|(_, turn)| waiting_for_result(turn))
        .map(|(index, _)| index);
    let (offset, record) = match held.or(parsed.turns.len().checked_sub(1)) {
        Some(index) => (
            lines[parsed.opened_at_line[index]].offset,
            parsed.turns[index].id.clone(),
        ),
        None => (next, String::new()),
    };
    Ok(Page {
        turns: parsed.turns,
        after: Some(Token {
            file: session.to_string(),
            offset,
            record,
        }),
        before: None,
        caught_up: reached,
    })
}

/// A turn has a tool call whose result has not arrived yet.
fn waiting_for_result(turn: &ConversationTurn) -> bool {
    turn.parts.iter().any(|part| {
        matches!(
            part,
            conversation::ConversationPart::ToolCall { output: None, .. }
        )
    })
}

/// The turns that end before `end`, with the first partial turn left for the next page.
struct Earlier {
    turns: Vec<ConversationTurn>,
    before: Option<Token>,
    last_opener: Option<(u64, String)>,
}

fn backward(
    file: &mut File,
    session: &str,
    kind: Kind,
    end: u64,
    budget: u64,
) -> io::Result<Earlier> {
    if end == 0 {
        return Ok(Earlier {
            turns: Vec::new(),
            before: None,
            last_opener: None,
        });
    }
    let mut min_bytes = budget;
    let (lines, at_start) = loop {
        let (lines, at_start) = read_backward(file, end, min_bytes)?;
        if at_start || parse(kind, &lines).opened_at_line.len() >= 2 {
            break (lines, at_start);
        }
        min_bytes = min_bytes.saturating_mul(2);
    };
    let parsed = parse(kind, &lines);
    let last_opener = parsed
        .opened_at_line
        .last()
        .zip(parsed.turns.last())
        .map(|(&index, turn)| (lines[index].offset, turn.id.clone()));
    if at_start {
        return Ok(Earlier {
            turns: parsed.turns,
            before: None,
            last_opener,
        });
    }
    // The window may begin inside the first turn, so that turn is returned by the next page.
    let first = &parsed.turns[1];
    let before = Token {
        file: session.to_string(),
        offset: lines[parsed.opened_at_line[1]].offset,
        record: first.id.clone(),
    };
    Ok(Earlier {
        turns: parsed.turns[1..].to_vec(),
        before: Some(before),
        last_opener,
    })
}

/// The newest page, for a first sync or a resync.
fn newest(file: &mut File, session: &str, kind: Kind, budget: u64) -> io::Result<Page> {
    let end = complete_end(file)?;
    let earlier = backward(file, session, kind, end, budget)?;
    let after = match earlier.last_opener {
        Some((offset, record)) => Token {
            file: session.to_string(),
            offset,
            record,
        },
        None => Token {
            file: session.to_string(),
            offset: end,
            record: String::new(),
        },
    };
    Ok(Page {
        turns: earlier.turns,
        after: Some(after),
        before: earlier.before,
        caught_up: true,
    })
}

/// Whether a token's record is still at its offset. An offset past the end of the complete records,
/// or a record with another id, makes the token stale.
fn still_valid(file: &mut File, session: &str, kind: Kind, token: &Token) -> io::Result<bool> {
    if token.file != session {
        return Ok(false);
    }
    let end = complete_end(file)?;
    if token.offset == end && token.record.is_empty() {
        return Ok(true);
    }
    if token.offset >= end {
        return Ok(false);
    }
    let (lines, _, _) = read_forward(file, token.offset, end, 1)?;
    Ok(lines
        .first()
        .is_some_and(|line| record_matches(kind, &line.bytes, &token.record)))
}

/// What the phone asked for.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Request {
    /// No token: the newest page.
    Initial,
    /// A token this bridge could not decode: the newest page as a resync.
    Unreadable,
    /// Records after the phone's forward position.
    After(Token),
    /// The page before the phone's earliest turn.
    Before(Token),
}

/// The bridge's answer. A token that no longer matches the file gets the newest page as a resync.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Served {
    Page(Page),
    Resync(Page),
}

/// Serves one sync request for a session file.
pub fn serve(
    path: &Path,
    session: &str,
    kind: Kind,
    request: Request,
    budget: u64,
) -> io::Result<Served> {
    let mut file = File::open(path)?;
    match request {
        Request::Initial => Ok(Served::Page(newest(&mut file, session, kind, budget)?)),
        Request::Unreadable => Ok(Served::Resync(newest(&mut file, session, kind, budget)?)),
        Request::After(token) => {
            if still_valid(&mut file, session, kind, &token)? {
                let end = complete_end(&mut file)?;
                Ok(Served::Page(forward(
                    &mut file,
                    session,
                    kind,
                    token.offset,
                    end,
                    budget,
                )?))
            } else {
                Ok(Served::Resync(newest(&mut file, session, kind, budget)?))
            }
        }
        Request::Before(token) => {
            if still_valid(&mut file, session, kind, &token)? {
                let earlier = backward(&mut file, session, kind, token.offset, budget)?;
                Ok(Served::Page(Page {
                    turns: earlier.turns,
                    after: None,
                    before: earlier.before,
                    caught_up: false,
                }))
            } else {
                Ok(Served::Resync(newest(&mut file, session, kind, budget)?))
            }
        }
    }
}

/// The wire body (contract section 7.3). `after_token` is present for the newest page and for a
/// forward request; `before_token` for the newest page and for an earlier request; `caught_up` for
/// the newest page and for a forward request. `stats` is added by the caller for the newest page.
pub fn body(mode: &str, page: &Page, request: &Request) -> Value {
    let mut body = json!({
        "mode": mode,
        "upserts": page.turns,
    });
    let newest_page = matches!(request, Request::Initial) || mode == "resync";
    let fields = &mut body;
    if newest_page || matches!(request, Request::After(_)) {
        fields["after_token"] = page
            .after
            .as_ref()
            .map_or(Value::Null, |t| json!(encode(t)));
        fields["caught_up"] = json!(page.caught_up);
    }
    if newest_page || matches!(request, Request::Before(_)) {
        fields["before_token"] = page
            .before
            .as_ref()
            .map_or(Value::Null, |t| json!(encode(t)));
    }
    body
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    /// A temporary session file that is removed on drop.
    struct Scratch(std::path::PathBuf);

    impl Scratch {
        fn new(name: &str) -> Self {
            let dir = std::env::temp_dir().join(format!("cap-sync-{}-{name}", std::process::id()));
            std::fs::create_dir_all(&dir).unwrap();
            Scratch(dir)
        }
        fn write(&self, file: &str, text: &str) -> std::path::PathBuf {
            let path = self.0.join(file);
            std::fs::write(&path, text).unwrap();
            path
        }
    }

    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    /// Claude Code records: each user message opens a turn, and each assistant answer follows it.
    fn claude_session(turns: usize, text_bytes: usize) -> String {
        claude_session_from(0, turns, text_bytes)
    }

    /// Like `claude_session`, numbering its records from `first` so that appended records have new ids.
    fn claude_session_from(first: usize, turns: usize, text_bytes: usize) -> String {
        let mut out = String::new();
        for index in first..first + turns {
            out.push_str(&format!(
                "{{\"type\":\"user\",\"uuid\":\"u{index}\",\"timestamp\":\"2026-11-04T00:00:00Z\",\"message\":{{\"role\":\"user\",\"content\":\"{}\"}}}}\n",
                "q".repeat(text_bytes)
            ));
            out.push_str(&format!(
                "{{\"type\":\"assistant\",\"uuid\":\"a{index}\",\"timestamp\":\"2026-11-04T00:00:01Z\",\"message\":{{\"content\":[{{\"type\":\"text\",\"text\":\"answer {index}\"}}]}}}}\n"
            ));
        }
        out
    }

    /// Applies upserts by turn id, as the phone does.
    fn apply(log: &mut BTreeMap<String, ConversationTurn>, turns: &[ConversationTurn]) {
        for turn in turns {
            log.insert(turn.id.clone(), turn.clone());
        }
    }

    fn full_history(kind: Kind, text: &str) -> Vec<ConversationTurn> {
        match kind {
            Kind::Claude => conversation::parse_claude_transcript(text),
            Kind::Pi => conversation::parse_pi_transcript(text),
            Kind::Agy => conversation::parse_agy_transcript(text),
        }
    }

    fn page_of(served: Served) -> Page {
        match served {
            Served::Page(page) | Served::Resync(page) => page,
        }
    }

    #[test]
    fn token_round_trips_and_rejects_anything_else() {
        let token = Token {
            file: "s-1".into(),
            offset: 4096,
            record: "u7".into(),
        };
        assert_eq!(decode(&encode(&token)), Some(token));
        assert_eq!(decode("not-hex"), None);
        assert_eq!(decode("7b7d"), None);
    }

    #[test]
    fn forward_catch_up_adds_new_records_to_a_log_built_from_earlier_pages() {
        let scratch = Scratch::new("forward");
        let path = scratch.write("session.jsonl", &claude_session(6, 300));
        let mut log = BTreeMap::new();
        let newest = page_of(serve(&path, "session", Kind::Claude, Request::Initial, 900).unwrap());
        apply(&mut log, &newest.turns);
        let mut before = newest.before.clone();
        while let Some(token) = before {
            let page = page_of(
                serve(&path, "session", Kind::Claude, Request::Before(token), 900).unwrap(),
            );
            apply(&mut log, &page.turns);
            before = page.before.clone();
        }
        let mut text = claude_session(6, 300);
        text.push_str(&claude_session_from(6, 9, 300));
        std::fs::write(&path, &text).unwrap();

        let mut token = newest.after.clone();
        let mut pages = 0;
        loop {
            let page = page_of(
                serve(
                    &path,
                    "session",
                    Kind::Claude,
                    Request::After(token.clone().unwrap()),
                    900,
                )
                .unwrap(),
            );
            apply(&mut log, &page.turns);
            pages += 1;
            assert!(pages < 100, "catch-up does not finish");
            if page.caught_up {
                break;
            }
            token = page.after.clone();
        }
        let expected = full_history(Kind::Claude, &text);
        assert!(pages >= 2, "small budget must split the new records");
        assert_eq!(log.len(), expected.len());
        for turn in &expected {
            assert_eq!(log.get(&turn.id), Some(turn), "turn {}", turn.id);
        }
    }

    #[test]
    fn earlier_pages_reach_the_start_of_the_file_exactly_once() {
        let scratch = Scratch::new("backward");
        let text = claude_session(15, 200);
        let path = scratch.write("session.jsonl", &text);
        let newest = page_of(serve(&path, "session", Kind::Claude, Request::Initial, 900).unwrap());
        let mut seen: Vec<String> = newest.turns.iter().map(|t| t.id.clone()).collect();
        let mut before = newest.before.clone();
        while let Some(token) = before {
            let page = page_of(
                serve(&path, "session", Kind::Claude, Request::Before(token), 900).unwrap(),
            );
            assert!(!page.turns.is_empty(), "every earlier page makes progress");
            seen.extend(page.turns.iter().map(|t| t.id.clone()));
            before = page.before.clone();
        }
        let expected: Vec<String> = full_history(Kind::Claude, &text)
            .into_iter()
            .map(|t| t.id)
            .collect();
        let mut sorted = seen.clone();
        sorted.sort();
        sorted.dedup();
        assert_eq!(sorted.len(), seen.len(), "a turn was returned twice");
        let mut expected_sorted = expected.clone();
        expected_sorted.sort();
        assert_eq!(sorted, expected_sorted);
    }

    #[test]
    fn the_newest_page_carries_both_tokens_and_is_caught_up() {
        let scratch = Scratch::new("newest");
        let path = scratch.write("session.jsonl", &claude_session(3, 10));
        let page =
            page_of(serve(&path, "session", Kind::Claude, Request::Initial, 1 << 20).unwrap());
        assert!(page.caught_up);
        assert_eq!(page.before, None, "the whole file fits: nothing is earlier");
        let last = page.turns.last().unwrap();
        assert_eq!(page.after.as_ref().unwrap().record, last.id);
    }

    #[test]
    fn an_oversized_turn_is_never_split() {
        let scratch = Scratch::new("oversized");
        let path = scratch.write("session.jsonl", &claude_session(2, 5000));
        // A 100-byte budget is far below one turn: the newest page must still hold the whole turn.
        let page = page_of(serve(&path, "session", Kind::Claude, Request::Initial, 100).unwrap());
        let question = page
            .turns
            .iter()
            .find(|turn| turn.role == "user")
            .expect("the oversized user turn");
        assert_eq!(question.text.as_ref().map(String::len), Some(5000));
        assert!(page.before.is_some(), "earlier turns remain");
    }

    #[test]
    fn an_unterminated_record_is_not_served() {
        let scratch = Scratch::new("partial");
        let mut text = claude_session(2, 10);
        text.push_str("{\"type\":\"user\",\"uuid\":\"u9\",\"mess");
        let path = scratch.write("session.jsonl", &text);
        let page =
            page_of(serve(&path, "session", Kind::Claude, Request::Initial, 1 << 20).unwrap());
        assert!(page.turns.iter().all(|turn| turn.id != "u9"));
    }

    #[test]
    fn a_token_whose_record_changed_resyncs() {
        let scratch = Scratch::new("resync");
        let path = scratch.write("session.jsonl", &claude_session(4, 10));
        let stale = Token {
            file: "session".into(),
            offset: 0,
            record: "not-the-record".into(),
        };
        let served = serve(
            &path,
            "session",
            Kind::Claude,
            Request::After(stale),
            1 << 20,
        )
        .unwrap();
        assert!(matches!(served, Served::Resync(_)));
    }

    #[test]
    fn a_token_for_another_session_resyncs() {
        let scratch = Scratch::new("other-session");
        let path = scratch.write("session.jsonl", &claude_session(2, 10));
        let token = Token {
            file: "other".into(),
            offset: 0,
            record: "u0".into(),
        };
        let served = serve(
            &path,
            "session",
            Kind::Claude,
            Request::After(token),
            1 << 20,
        )
        .unwrap();
        assert!(matches!(served, Served::Resync(_)));
    }

    #[test]
    fn a_token_past_the_end_resyncs() {
        let scratch = Scratch::new("past-end");
        let path = scratch.write("session.jsonl", &claude_session(2, 10));
        let token = Token {
            file: "session".into(),
            offset: 1 << 30,
            record: "u0".into(),
        };
        let served = serve(
            &path,
            "session",
            Kind::Claude,
            Request::After(token),
            1 << 20,
        )
        .unwrap();
        assert!(matches!(served, Served::Resync(_)));
    }

    #[test]
    fn pi_and_agy_pages_follow_their_own_record_ids() {
        let scratch = Scratch::new("other-agents");
        let pi = "{\"type\":\"session\",\"id\":\"s\"}\n{\"type\":\"message\",\"id\":\"p1\",\"message\":{\"role\":\"user\",\"content\":\"hi\"}}\n{\"type\":\"message\",\"id\":\"p2\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"yo\"}]}}\n";
        let path = scratch.write("pi.jsonl", pi);
        let page = page_of(serve(&path, "pi", Kind::Pi, Request::Initial, 1 << 20).unwrap());
        let ids: Vec<&str> = page.turns.iter().map(|t| t.id.as_str()).collect();
        assert_eq!(ids, ["p1", "p2"]);

        let agy = "{\"source\":\"USER_EXPLICIT\",\"type\":\"USER_INPUT\",\"step_index\":0,\"content\":\"hi\"}\n{\"source\":\"MODEL\",\"type\":\"PLANNER_RESPONSE\",\"step_index\":1,\"content\":\"yo\"}\n";
        let path = scratch.write("transcript.jsonl", agy);
        let page = page_of(serve(&path, "agy", Kind::Agy, Request::Initial, 1 << 20).unwrap());
        let ids: Vec<&str> = page.turns.iter().map(|t| t.id.as_str()).collect();
        assert_eq!(ids, ["step-0", "step-1"]);
    }

    #[test]
    fn a_late_tool_result_is_sent_again_with_its_turn() {
        let scratch = Scratch::new("late-result");
        let call = "{\"type\":\"assistant\",\"uuid\":\"a1\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"bash\",\"input\":{}}]}}\n";
        let user = "{\"type\":\"user\",\"uuid\":\"u0\",\"message\":{\"role\":\"user\",\"content\":\"run\"}}\n";
        let result = "{\"type\":\"user\",\"uuid\":\"r1\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"ok\"}]}}\n";
        let head = format!("{user}{call}");
        let path = scratch.write("session.jsonl", &head);
        let first =
            page_of(serve(&path, "session", Kind::Claude, Request::Initial, 1 << 20).unwrap());
        let token = first.after.clone().unwrap();
        std::fs::write(&path, format!("{head}{result}")).unwrap();
        let second = page_of(
            serve(
                &path,
                "session",
                Kind::Claude,
                Request::After(token),
                1 << 20,
            )
            .unwrap(),
        );
        let agent = second
            .turns
            .iter()
            .find(|t| t.id == "a1")
            .expect("the call's turn");
        let output = agent.parts.iter().find_map(|part| match part {
            conversation::ConversationPart::ToolCall { output, .. } => output.clone(),
            _ => None,
        });
        assert_eq!(output.as_deref(), Some("ok"));
    }
}

#[cfg(test)]
mod hold_tests {
    use super::*;

    #[test]
    fn a_call_that_never_returns_does_not_stall_catch_up() {
        let dir = std::env::temp_dir().join(format!("cap-sync-hold-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("session.jsonl");
        let mut text = String::from(
            "{\"type\":\"assistant\",\"uuid\":\"a0\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"lost\",\"name\":\"bash\",\"input\":{}}]}}\n",
        );
        for index in 1..30 {
            text.push_str(&format!(
                "{{\"type\":\"user\",\"uuid\":\"u{index}\",\"message\":{{\"role\":\"user\",\"content\":\"{}\"}}}}\n",
                "q".repeat(120)
            ));
        }
        std::fs::write(&path, &text).unwrap();
        let mut request = Request::Initial;
        let mut rounds = 0;
        loop {
            rounds += 1;
            assert!(
                rounds < 200,
                "catch-up must finish even while a call is held"
            );
            let served = serve(&path, "session", Kind::Claude, request.clone(), 600).unwrap();
            let page = match served {
                Served::Page(page) | Served::Resync(page) => page,
            };
            if let Some(after) = page.after.clone() {
                if page.caught_up && matches!(request, Request::After(_)) {
                    break;
                }
                request = Request::After(after);
            } else {
                break;
            }
        }
        let _ = std::fs::remove_dir_all(&dir);
    }
}
