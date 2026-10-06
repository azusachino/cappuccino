//! Read-only probe used by both the `test-client` example CLI and the
//! integration tests (included by path). HTTP legs prefer HTTP/2 prior
//! knowledge (h2c) and report the observed protocol; `require_h2` fails
//! instead of falling back. The WebSocket leg uses the classic HTTP/1.1
//! Upgrade handshake — the bridge's only implemented WS transport (RFC 8441
//! extended CONNECT is not implemented by the bridge and is not attempted).
//!
//! Everything is bounded: one deadline per operation, an explicit HTTP body
//! byte cap, an explicit WS frame/message cap (configured on the tungstenite
//! connection, not assumed from library defaults), and a whole-session
//! budget. The probe only ever issues GET requests and WS reads; it never
//! sends. Metric errors (rusage failures) are surfaced, never zero-filled.

use bytes::Bytes;
use http::{StatusCode, Uri, Version};
use http_body_util::Full;
use hyper::Request;
use hyper_util::client::legacy::{connect::HttpConnector, Client, Error as HyperError};
use hyper_util::rt::TokioExecutor;
use serde_json::{json, Value};
use std::time::{Duration, Instant};
use tokio_tungstenite::tungstenite::protocol::WebSocketConfig;
use tokio_tungstenite::tungstenite::Message;

/// Hard cap on any single HTTP response body the probe will buffer.
pub const MAX_HTTP_BODY_BYTES: usize = 1024 * 1024;
/// Explicit WS frame/message caps (client-side, set on the connection).
pub const MAX_WS_MESSAGE_BYTES: usize = 1024 * 1024;
pub const MAX_WS_FRAME_BYTES: usize = 64 * 1024;
/// Error codes the bridge is known to put on a first WS frame.
pub const KNOWN_WS_ERROR_CODES: [&str; 4] =
    ["not_found", "bad_request", "protocol", "herdr_unreachable"];

/// How the HTTP legs choose a protocol version.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HttpPreference {
    /// Try h2c prior knowledge first; transparently retry HTTP/1.1 on
    /// transport failure. The used version is always reported.
    PreferH2,
    /// h2c prior knowledge only; a downgrade to HTTP/1.1 is an error.
    RequireH2,
}

#[derive(Debug, Clone)]
pub struct ProbeOptions {
    pub base_url: String,
    pub preference: HttpPreference,
    /// Deadline for every single operation (request, frame, connect).
    pub timeout: Duration,
    /// Whole-session budget for a multi-check run or a benchmark.
    pub session_deadline: Duration,
    /// Optional WS stream probe target.
    pub session: Option<String>,
    /// First WS event is checked against this when set ("stream_open" or
    /// "error"); any known event passes when `None`.
    pub expect_event: Option<String>,
}

impl ProbeOptions {
    pub fn new(base_url: impl Into<String>) -> Self {
        Self {
            base_url: base_url.into(),
            preference: HttpPreference::PreferH2,
            timeout: Duration::from_secs(10),
            session_deadline: Duration::from_secs(60),
            session: None,
            expect_event: None,
        }
    }
}

#[derive(Debug)]
pub struct HttpExchange {
    pub status: StatusCode,
    pub body: Value,
    /// True when `PreferH2` had to fall back to HTTP/1.1.
    pub h2_fallback: bool,
    /// Protocol label for reports: "http2" or "http1".
    pub protocol: &'static str,
}

pub fn version_label(version: Version) -> &'static str {
    match version {
        Version::HTTP_2 => "http2",
        Version::HTTP_11 | Version::HTTP_10 => "http1",
        _ => "unknown",
    }
}

fn http1_client() -> Client<HttpConnector, Full<Bytes>> {
    Client::builder(TokioExecutor::new()).build_http()
}

fn h2_client() -> Client<HttpConnector, Full<Bytes>> {
    Client::builder(TokioExecutor::new())
        .http2_only(true)
        .build_http()
}

/// Shared check-leg clients. Creating a fresh client per GET retains freed
/// pool/allocator memory that shows up as a monotonic RSS trend across many
/// check cycles; the check legs reuse one client per protocol instead. The
/// benchmark's cold leg still builds fresh clients deliberately (it measures
/// new-connection latency).
fn shared_client(h2: bool) -> &'static Client<HttpConnector, Full<Bytes>> {
    static H1: std::sync::OnceLock<Client<HttpConnector, Full<Bytes>>> = std::sync::OnceLock::new();
    static H2: std::sync::OnceLock<Client<HttpConnector, Full<Bytes>>> = std::sync::OnceLock::new();
    if h2 {
        H2.get_or_init(h2_client)
    } else {
        H1.get_or_init(http1_client)
    }
}

/// Collects a response body under an explicit byte cap; oversized bodies are
/// an error, never silently truncated.
async fn collect_capped(body: hyper::body::Incoming) -> Result<Bytes, String> {
    let mut buffer = Vec::new();
    let mut body = body;
    loop {
        let frame = match http_body_util::BodyExt::frame(&mut body).await {
            Some(Ok(frame)) => frame,
            Some(Err(error)) => return Err(format!("read body: {error}")),
            None => break,
        };
        let data = frame
            .into_data()
            .map_err(|_| "unexpected trailers in body")?;
        if buffer.len() + data.len() > MAX_HTTP_BODY_BYTES {
            return Err(format!(
                "response body exceeds {MAX_HTTP_BODY_BYTES} byte cap"
            ));
        }
        buffer.extend_from_slice(&data);
    }
    Ok(Bytes::from(buffer))
}

async fn send_with(
    client: &Client<HttpConnector, Full<Bytes>>,
    url: Uri,
) -> Result<(Version, StatusCode, Value), String> {
    let request = Request::builder()
        .uri(url)
        .body(Full::new(Bytes::new()))
        .map_err(|error| format!("build request: {error}"))?;
    let response = client
        .request(request)
        .await
        .map_err(|error: HyperError| format!("transport: {error}"))?;
    let version = response.version();
    let status = response.status();
    let body = collect_capped(response.into_body()).await?;
    let body: Value =
        serde_json::from_slice(&body).map_err(|error| format!("response is not JSON: {error}"))?;
    Ok((version, status, body))
}

/// One GET with the configured preference and fallback policy.
pub async fn get(options: &ProbeOptions, path: &str) -> Result<HttpExchange, String> {
    let url: Uri = format!("{}{}", options.base_url.trim_end_matches('/'), path)
        .parse()
        .map_err(|error| format!("bad URL: {error}"))?;
    let require = options.preference == HttpPreference::RequireH2;
    if require || options.preference == HttpPreference::PreferH2 {
        match tokio::time::timeout(options.timeout, send_with(shared_client(true), url.clone()))
            .await
        {
            Ok(Ok((version, status, body))) => {
                if require && version != Version::HTTP_2 {
                    return Err(format!(
                        "require-h2: server answered with {} (downgrade is an error)",
                        version_label(version)
                    ));
                }
                return Ok(HttpExchange {
                    status,
                    body,
                    h2_fallback: false,
                    protocol: version_label(version),
                });
            }
            Ok(Err(error)) => {
                if require {
                    return Err(format!("require-h2 transport: {error}"));
                }
                // h2c to an HTTP/1-only origin fails here; fall through to
                // HTTP/1.1 with a fresh deadline.
            }
            Err(_elapsed) => {
                if require {
                    return Err("require-h2: request timed out".into());
                }
                // h2c to an HTTP/1-only origin can also manifest as a timeout;
                // fall through to HTTP/1.1 with a fresh deadline.
            }
        }
    }
    let (version, status, body) =
        tokio::time::timeout(options.timeout, send_with(shared_client(false), url))
            .await
            .map_err(|_| "request timed out".to_string())??;
    if require {
        return Err("require-h2: only HTTP/1.1 was reachable".into());
    }
    Ok(HttpExchange {
        status,
        body,
        h2_fallback: true,
        protocol: version_label(version),
    })
}

fn ws_config() -> WebSocketConfig {
    let mut config = WebSocketConfig::default();
    config.max_message_size = Some(MAX_WS_MESSAGE_BYTES);
    config.max_frame_size = Some(MAX_WS_FRAME_BYTES);
    config
}

/// What a stream probe observed. `entries` are consumed and validated when
/// the first frame is `stream_open` and a follow-up frame arrives in time.
#[derive(Debug, Default)]
pub struct StreamProbe {
    pub first_event: String,
    pub generation: Option<u64>,
    pub error_code: Option<String>,
    /// Nonempty-entry count; empty `entries` frames validate but never count
    /// as consumption.
    pub entries_consumed: usize,
    /// Frames read after the first one (entries/reset/control events).
    pub followup_events: Vec<String>,
    pub protocol: &'static str,
    /// True when the connection was consumed to server EOF.
    pub closed_by_server: bool,
    /// Microsecond phase timings: TCP connect + WS handshake, handshake ->
    /// first frame, handshake -> first nonempty entries frame. Zero when the
    /// phase was not reached.
    pub handshake_us: u128,
    pub first_frame_us: u128,
    pub first_entry_us: u128,
}

fn urlencode(value: &str) -> String {
    let mut out = String::new();
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(byte as char)
            }
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

/// Validates one bridge stream frame against the documented wire semantics.
/// `last_generation` tracks reset monotonicity across the session: a
/// `stream_reset` must carry a strictly larger generation. Gap entries must
/// match the producer's documented placeholder shape exactly.
fn validate_stream_frame(frame: &Value, last_generation: &mut Option<u64>) -> Result<(), String> {
    let event = frame["event"].as_str().ok_or("frame has no event field")?;
    match event {
        "stream_open" => {
            let generation = frame["generation"]
                .as_u64()
                .ok_or("stream_open missing u64 generation")?;
            *last_generation = Some(generation);
            Ok(())
        }
        "error" => {
            let code = frame["code"].as_str().unwrap_or("");
            if !KNOWN_WS_ERROR_CODES.contains(&code) {
                return Err(format!("unknown WS error code '{code}'"));
            }
            if frame["message"].as_str().is_none() {
                return Err("error frame missing message".into());
            }
            Ok(())
        }
        "entries" => {
            let entries = frame["entries"]
                .as_array()
                .ok_or("entries frame missing entries array")?;
            for entry in entries {
                let is_gap = entry["kind"] == json!("gap");
                if is_gap {
                    // Documented producer shape (src/reconcile.rs): seq null,
                    // id "...gap...", branch null, complete true, and a
                    // "gap: missing entries X-Y" text with a valid range.
                    if !entry["seq"].is_null() {
                        return Err(format!("gap entry must have null seq: {entry}"));
                    }
                    if entry["id"] != json!("...gap...") {
                        return Err(format!("gap entry has wrong id: {entry}"));
                    }
                    if !entry["branch"].is_null() {
                        return Err(format!("gap entry must have null branch: {entry}"));
                    }
                    if entry["complete"] != json!(true) {
                        return Err(format!("gap entry must be complete: {entry}"));
                    }
                    let text = entry["text"].as_str().unwrap_or("");
                    let Some(range) = text.strip_prefix("gap: missing entries ") else {
                        return Err(format!("gap entry text malformed: {text}"));
                    };
                    let Some((start, end)) = range.split_once('-') else {
                        return Err(format!("gap range malformed: {text}"));
                    };
                    let (start, end) = (start.parse::<u64>(), end.parse::<u64>());
                    let (Ok(start), Ok(end)) = (start, end) else {
                        return Err(format!("gap range not numeric: {text}"));
                    };
                    if start > end {
                        return Err(format!("gap range inverted: {text}"));
                    }
                } else {
                    if entry["seq"].as_u64().is_none() {
                        return Err(format!("entries frame has malformed entry: {entry}"));
                    }
                    if entry["id"].as_str().is_none_or(|id| id.is_empty()) {
                        return Err(format!("entry missing nonempty id: {entry}"));
                    }
                    if entry["text"].as_str().is_none() {
                        return Err("entry missing text".into());
                    }
                    if !entry["complete"].is_boolean() {
                        return Err(format!("entry missing boolean complete: {entry}"));
                    }
                }
            }
            Ok(())
        }
        "stream_reset" => {
            let generation = frame["generation"]
                .as_u64()
                .ok_or("stream_reset missing u64 generation")?;
            if let Some(last) = last_generation {
                if generation <= *last {
                    return Err(format!(
                        "stream_reset generation regression: {generation} <= {last}"
                    ));
                }
            }
            *last_generation = Some(generation);
            Ok(())
        }
        other => Err(format!("unknown stream event '{other}'")),
    }
}

/// Opens the WS stream (classic HTTP/1.1 Upgrade with explicit frame/message
/// caps and an explicit handshake deadline), validates and returns the first
/// frame with per-phase timings, and consumes/validates follow-up frames
/// (entries / stream_reset / control) while the session budget allows.
/// Non-text data frames after the handshake are rejected, never ignored.
pub async fn ws_stream_probe(
    options: &ProbeOptions,
    session: &str,
    followup_budget: Duration,
) -> Result<StreamProbe, String> {
    use futures_util::{SinkExt, StreamExt};
    let started = Instant::now();
    let url = format!(
        "{}{}",
        options
            .base_url
            .trim_end_matches('/')
            .replacen("http", "ws", 1),
        format!("/api/stream?session={}", urlencode(session))
    );
    let addr: std::net::SocketAddr = url
        .strip_prefix("ws://")
        .and_then(|rest| rest.split('/').next().unwrap_or("").parse().ok())
        .ok_or_else(|| format!("bad ws URL: {url}"))?;
    let tcp = tokio::time::timeout(options.timeout, tokio::net::TcpStream::connect(addr))
        .await
        .map_err(|_| "ws connect timed out".to_string())?
        .map_err(|error| format!("ws connect: {error}"))?;
    let tcp_done_us = started.elapsed().as_micros();
    let (stream, _response) = tokio::time::timeout(
        options.timeout,
        tokio_tungstenite::client_async_with_config(url.as_str(), tcp, Some(ws_config())),
    )
    .await
    .map_err(|_| "ws handshake timed out".to_string())?
    .map_err(|error| format!("ws handshake: {error}"))?;
    let handshake_us = started.elapsed().as_micros();
    let (mut write, mut read) = stream.split();

    let mut probe = StreamProbe {
        protocol: "ws-http1-upgrade",
        handshake_us,
        ..Default::default()
    };
    let mut last_generation: Option<u64> = None;

    let overall = tokio::time::Instant::now() + options.session_deadline.min(options.timeout * 4);
    let bounded = |budget: Duration| {
        let cap = overall.min(tokio::time::Instant::now() + budget);
        cap.saturating_duration_since(tokio::time::Instant::now())
    };

    // First frame: required, bounded.
    let first = tokio::time::timeout(bounded(options.timeout), read.next())
        .await
        .map_err(|_| "ws first frame timed out".to_string())?;
    probe.first_frame_us = started.elapsed().as_micros() - handshake_us;
    let _ = tcp_done_us;
    let first = match first {
        None => {
            let _ = SinkExt::close(&mut write).await;
            return Err("server closed before the first frame".into());
        }
        Some(item) => match item {
            Ok(Message::Text(text)) => text.to_string(),
            Ok(Message::Close(frame)) => {
                let _ = SinkExt::close(&mut write).await;
                return Err(format!(
                    "server closed before the first frame: {}",
                    frame.map(|frame| frame.to_string()).unwrap_or_default()
                ));
            }
            Ok(Message::Binary(data)) => {
                let _ = SinkExt::close(&mut write).await;
                return Err(format!(
                    "unexpected binary data before the first frame ({} bytes)",
                    data.len()
                ));
            }
            Ok(other) => {
                let _ = SinkExt::close(&mut write).await;
                return Err(format!("unexpected first frame kind: {other}"));
            }
            Err(error) => {
                let _ = SinkExt::close(&mut write).await;
                return Err(format!("ws read: {error}"));
            }
        },
    };
    let value: Value =
        serde_json::from_str(&first).map_err(|error| format!("malformed frame: {error}"))?;
    validate_stream_frame(&value, &mut last_generation)?;
    probe.first_event = value["event"].as_str().unwrap_or("").to_string();
    probe.generation = value["generation"].as_u64();
    probe.error_code = value["code"].as_str().map(str::to_string);

    // Follow-up frames within the explicit budget: entries / stream_reset /
    // control frames. For error-first frames (fail-closed responses) a small
    // bounded read observes the server's close instead of asserting it.
    let followup_budget = if probe.first_event == "stream_open" {
        followup_budget
    } else {
        Duration::from_millis(500).min(followup_budget.max(Duration::from_millis(1)))
    };
    if !followup_budget.is_zero() {
        loop {
            let remaining = bounded(followup_budget);
            if remaining.is_zero() {
                break;
            }
            match tokio::time::timeout(remaining, read.next()).await {
                Err(_) => break, // budget exhausted: bounded stop, not an error
                Ok(None) => {
                    probe.closed_by_server = true;
                    break;
                }
                Ok(Some(Ok(Message::Text(text)))) => {
                    if probe.first_event != "stream_open" {
                        let _ = SinkExt::close(&mut write).await;
                        return Err(format!(
                            "unexpected follow-up frame after error first frame: {text}"
                        ));
                    }
                    let value: Value = match serde_json::from_str(&text) {
                        Ok(value) => value,
                        Err(error) => {
                            let _ = SinkExt::close(&mut write).await;
                            return Err(format!("malformed frame: {error}"));
                        }
                    };
                    if let Err(error) = validate_stream_frame(&value, &mut last_generation) {
                        let _ = SinkExt::close(&mut write).await;
                        return Err(error);
                    }
                    let event = value["event"].as_str().unwrap_or("").to_string();
                    if event == "entries" {
                        let count = value["entries"].as_array().map(Vec::len).unwrap_or(0);
                        if count > 0 && probe.first_entry_us == 0 {
                            probe.first_entry_us = started.elapsed().as_micros() - handshake_us;
                        }
                        probe.entries_consumed += count;
                    }
                    probe.followup_events.push(event);
                    if probe.followup_events.len() >= 16 {
                        break; // bounded consumption for a probe/benchmark
                    }
                }
                Ok(Some(Ok(Message::Close(_)))) => {
                    probe.closed_by_server = true;
                    break;
                }
                Ok(Some(Ok(Message::Ping(payload)))) => {
                    // Answer the control frame explicitly and keep reading.
                    probe.followup_events.push("ping".into());
                    let _ = tokio::time::timeout(
                        Duration::from_millis(250),
                        SinkExt::send(&mut write, Message::Pong(payload)),
                    )
                    .await;
                }
                Ok(Some(Ok(Message::Pong(_)))) => {
                    probe.followup_events.push("pong".into());
                }
                Ok(Some(Ok(Message::Binary(data)))) => {
                    let _ = SinkExt::close(&mut write).await;
                    return Err(format!(
                        "unexpected binary data frame ({} bytes); the bridge stream is text-only",
                        data.len()
                    ));
                }
                Ok(Some(Ok(Message::Frame(_)))) => {
                    let _ = SinkExt::close(&mut write).await;
                    return Err("unexpected raw frame".into());
                }
                Ok(Some(Err(error))) => {
                    // Server loss mid-stream: recorded, the first-frame
                    // contract was already satisfied.
                    probe.followup_events.push(format!("read-error: {error}"));
                    break;
                }
            }
        }
    }
    let _ = SinkExt::close(&mut write).await;
    Ok(probe)
}

#[derive(Debug, Default)]
pub struct CheckReport {
    pub lines: Vec<String>,
    pub protocols: Vec<&'static str>,
    /// Validated nonempty entries consumed by the optional WS leg.
    pub stream_entries_consumed: usize,
}

impl CheckReport {
    fn record(&mut self, line: String, protocol: &'static str) {
        self.lines.push(line);
        self.protocols.push(protocol);
    }
}

fn require(cond: bool, message: &str) -> Result<(), String> {
    if cond {
        Ok(())
    } else {
        Err(message.to_string())
    }
}

/// Runs the read-only HTTP checks (and the WS leg when a session is set)
/// under the whole-session budget. Every mismatch, malformed frame, unknown
/// code/event, timeout, disconnect or deadline is an error.
pub async fn run_checks(options: &ProbeOptions) -> Result<CheckReport, String> {
    tokio::time::timeout(options.session_deadline, run_checks_inner(options))
        .await
        .map_err(|_| format!("session deadline {:?} exceeded", options.session_deadline))?
}

async fn run_checks_inner(options: &ProbeOptions) -> Result<CheckReport, String> {
    let mut report = CheckReport::default();

    let session = get(options, "/api/session").await?;
    require(session.status == StatusCode::OK, "session: expected 200")?;
    require(
        session.body["event"] == json!("paired"),
        "session: expected event 'paired'",
    )?;
    require(
        session.body["protocol"] == json!(1),
        "session: expected protocol 1",
    )?;
    require(
        session.body["plugin"]
            .as_str()
            .is_some_and(|p| !p.is_empty()),
        "session: missing plugin",
    )?;
    require(
        session.body["machine_id"]
            .as_str()
            .is_some_and(|m| !m.is_empty()),
        "session: missing nonempty machine_id",
    )?;
    report.record(
        format!(
            "session: ok ({}, plugin {})",
            session.protocol, session.body["plugin"]
        ),
        session.protocol,
    );

    let agents = get(options, "/api/agents").await?;
    require(agents.status == StatusCode::OK, "agents: expected 200")?;
    require(
        agents.body["event"] == json!("agents"),
        "agents: expected event 'agents'",
    )?;
    require(
        agents.body["agents"].as_array().is_some(),
        "agents: missing agents array",
    )?;
    report.record(
        format!(
            "agents: ok ({}, {} row(s))",
            agents.protocol,
            agents.body["agents"].as_array().map(Vec::len).unwrap_or(0)
        ),
        agents.protocol,
    );

    // Transcript probe: fail-closed in every mode. 502 must carry an error
    // envelope; a 200 must be coherent — available:true requires a nonempty
    // transcript_path, available:false requires a null path. Never accept a
    // payload that claims availability without a path or vice versa.
    let probe_session = options
        .session
        .clone()
        .unwrap_or_else(|| "__probe__".into());
    let transcript = get(options, &format!("/api/transcript?session={probe_session}")).await?;
    match transcript.status {
        StatusCode::OK => {
            let available = transcript.body["available"] == json!(true);
            let path = transcript.body["transcript_path"].as_str().unwrap_or("");
            if available {
                require(
                    !path.is_empty(),
                    "transcript: available:true without a path",
                )?;
            } else {
                require(
                    transcript.body["transcript_path"].is_null(),
                    "transcript: available:false must carry a null path",
                )?;
            }
        }
        StatusCode::BAD_GATEWAY => require(
            transcript.body["event"] == json!("error"),
            "transcript: 502 must carry event 'error'",
        )?,
        status => return Err(format!("transcript: unexpected status {status}")),
    }
    report.record(
        format!("transcript: fail-closed ok ({})", transcript.protocol),
        transcript.protocol,
    );

    if let Some(name) = &options.session {
        let stream = ws_stream_probe(options, name, Duration::from_secs(2)).await?;
        let event = stream.first_event.clone();
        if let Some(expected) = &options.expect_event {
            require(
                &event == expected,
                &format!("ws: expected first event {expected}, got {event}"),
            )?;
        }
        match event.as_str() {
            "stream_open" => {
                require(
                    stream.generation.is_some(),
                    "ws: stream_open missing generation",
                )?;
            }
            "error" => {
                require(
                    stream
                        .error_code
                        .as_deref()
                        .is_some_and(|code| KNOWN_WS_ERROR_CODES.contains(&code)),
                    "ws: error frame with unknown code",
                )?;
            }
            other => return Err(format!("ws: unexpected first event {other}")),
        }
        report.record(
            format!(
                "stream[{name}]: first event {event} ({}), entries consumed {}",
                stream.protocol, stream.entries_consumed
            ),
            stream.protocol,
        );
        report.stream_entries_consumed = stream.entries_consumed;
    }

    Ok(report)
}

// ---------------------------------------------------------------------------
// Benchmark (checked-in, synthetic, bounded). Cold/warm HTTP with consumed
// bodies plus stream consumption/churn. Reports p50/p95 (microseconds),
// throughput, a before/after CPU delta and sampled steady-state RSS trend
// together with the observed protocol. No thresholds are asserted; numbers
// are recorded as a baseline for regression budgets. Metric errors fail.
// ---------------------------------------------------------------------------

pub struct BenchConfig {
    pub iterations: usize,
    pub warmup: usize,
    pub stream_cycles: usize,
    pub session: Option<String>,
    /// Error-only mode: cycles whose first frame is a known error count in
    /// `stream_error_cycles` instead of failing the run. Default (false)
    /// requires every cycle to be a successful stream_open with at least one
    /// validated entry; error-only churn is an explicit, separately labelled
    /// mode, never silent success.
    pub allow_error_streams: bool,
}

/// Hard caps on requested workload sizes; larger requests are invalid counts,
/// rejected with a clear error before any work starts.
pub const MAX_BENCH_ITERATIONS: usize = 100_000;
pub const MAX_BENCH_STREAM_CYCLES: usize = 10_000;
pub const MAX_BENCH_WARMUP: usize = 10_000;

impl Default for BenchConfig {
    fn default() -> Self {
        Self {
            iterations: 200,
            warmup: 20,
            stream_cycles: 50,
            session: None,
            allow_error_streams: false,
        }
    }
}

#[derive(Debug)]
pub struct BenchReport {
    pub protocol: &'static str,
    pub cold_p50_us: u128,
    pub cold_p95_us: u128,
    pub warm_p50_us: u128,
    pub warm_p95_us: u128,
    pub warm_throughput_rps: f64,
    /// WS stream phases, honest and separate (microseconds): TCP+WS
    /// handshake; handshake -> first frame; handshake -> first nonempty
    /// entries frame; and the full cycle including follow-up consumption.
    pub stream_handshake_p50_us: u128,
    pub stream_first_frame_p50_us: u128,
    pub stream_first_entry_p50_us: u128,
    pub stream_cycle_p50_us: u128,
    pub stream_cycle_p95_us: u128,
    /// Cycles that were successful streams: first frame `stream_open` with
    /// at least one validated entry. With `allow_error_streams`, cycles
    /// failing closed with a known error code are counted separately here.
    pub stream_cycles_completed: usize,
    pub stream_error_cycles: usize,
    pub stream_error_only_mode: bool,
    /// Stream entries actually consumed and validated across cycles.
    pub stream_entries_consumed: usize,
    /// CPU (user+system) delta for the whole workload, milliseconds.
    pub cpu_delta_ms: u64,
    /// Peak RSS (getrusage ru_maxrss) at the end of the workload.
    pub peak_rss: u64,
    pub rss_unit: &'static str,
    /// Sampled client RSS trend: first sample, last sample, growth (same
    /// units as `rss_unit`). Bounded sample count; documents steady-state
    /// behavior beyond the single peak number.
    pub rss_first_sample: u64,
    pub rss_last_sample: u64,
    pub rss_growth: u64,
    pub rss_samples: usize,
}

pub fn percentile(sorted: &[u128], fraction: f64) -> u128 {
    if sorted.is_empty() {
        return 0;
    }
    let index = ((sorted.len() as f64 - 1.0) * fraction).round() as usize;
    sorted[index.min(sorted.len() - 1)]
}

/// CPU time and peak RSS via getrusage (portable macOS/Linux). Errors are
/// returned, never zero-filled. ru_maxrss is bytes on macOS, KiB on Linux.
pub fn rusage_snapshot() -> Result<(u64, u64, &'static str), String> {
    let mut real: libc::rusage = unsafe { std::mem::zeroed() };
    let ok = unsafe { libc::getrusage(libc::RUSAGE_SELF, &mut real) } == 0;
    if !ok {
        return Err("getrusage failed; benchmark metrics are unavailable".into());
    }
    let cpu = ((real.ru_utime.tv_sec + real.ru_stime.tv_sec) as u64) * 1000
        + ((real.ru_utime.tv_usec + real.ru_stime.tv_usec) as u64) / 1000;
    if cfg!(target_os = "macos") {
        Ok((real.ru_maxrss as u64, cpu, "bytes"))
    } else {
        Ok((real.ru_maxrss as u64, cpu, "KiB"))
    }
}

/// Current resident-set size of this process (NOT the rusage high-water
/// peak), in KiB, via the minimal supported OS measurement: Linux reads
/// `/proc/self/statm`; macOS calls `proc_pid_rusage(ri_resident_size)` from
/// libproc. Errors are surfaced to the caller.
pub fn current_rss_kib() -> Result<u64, String> {
    if cfg!(target_os = "linux") {
        let statm = std::fs::read_to_string("/proc/self/statm")
            .map_err(|error| format!("rss sample: {error}"))?;
        let resident_pages = statm
            .split_whitespace()
            .nth(1)
            .and_then(|field| field.parse::<u64>().ok())
            .ok_or("rss sample: malformed statm")?;
        let page_kib = std::fs::read_to_string("/proc/self/status")
            .ok()
            .and_then(|status| {
                status.lines().find_map(|line| {
                    let value = line.strip_prefix("VmPageSize:")?;
                    value.trim().split_whitespace().next()?.parse::<u64>().ok()
                })
            })
            .unwrap_or(4);
        Ok(resident_pages * page_kib)
    } else if cfg!(target_os = "macos") {
        // libproc proc_pid_rusage with rusage_info_current (flavor 0):
        // ri_resident_size is the current resident set in bytes. The struct
        // prefix below matches the documented rusage_info_current layout up
        // to the fields we need; libproc writes the full OS-defined struct,
        // so the buffer is sized to that layout's first fields plus slack.
        #[repr(C)]
        struct RusageInfoCurrent {
            ri_uuid: [u8; 16],
            ri_user_time: u64,
            ri_system_time: u64,
            ri_pkg_idle_wkups: u64,
            ri_interrupt_wkups: u64,
            ri_pageins: u64,
            ri_wired_size: u64,
            ri_resident_size: u64,
            ri_phys_footprint: u64,
            _rest: [u64; 14],
        }
        extern "C" {
            fn proc_pid_rusage(pid: i32, flavor: u32, buffer: *mut u8) -> i32;
        }
        let mut info: RusageInfoCurrent = unsafe { std::mem::zeroed() };
        let ok = unsafe {
            proc_pid_rusage(
                libc::getpid(),
                0,
                &mut info as *mut RusageInfoCurrent as *mut u8,
            )
        } == 0;
        if !ok {
            return Err("proc_pid_rusage failed; current RSS is unavailable".into());
        }
        Ok(info.ri_resident_size / 1024)
    } else {
        Err("current RSS sampling is unsupported on this platform".into())
    }
}

pub async fn run_bench(
    options: &ProbeOptions,
    config: &BenchConfig,
) -> Result<BenchReport, String> {
    tokio::time::timeout(options.session_deadline, run_bench_inner(options, config))
        .await
        .map_err(|_| {
            format!(
                "bench session deadline {:?} exceeded",
                options.session_deadline
            )
        })?
}

async fn run_bench_inner(
    options: &ProbeOptions,
    config: &BenchConfig,
) -> Result<BenchReport, String> {
    // Invalid counts are rejected up front with clear errors.
    if config.iterations == 0 || config.iterations > MAX_BENCH_ITERATIONS {
        return Err(format!(
            "bench: iterations must be 1..={MAX_BENCH_ITERATIONS}, got {}",
            config.iterations
        ));
    }
    if config.warmup > MAX_BENCH_WARMUP {
        return Err(format!(
            "bench: warmup must be <= {MAX_BENCH_WARMUP}, got {}",
            config.warmup
        ));
    }
    if config.stream_cycles > MAX_BENCH_STREAM_CYCLES {
        return Err(format!(
            "bench: stream-cycles must be <= {MAX_BENCH_STREAM_CYCLES}, got {}",
            config.stream_cycles
        ));
    }

    // Choose the transport once, honestly: prefer h2, report the fallback.
    let session = get(options, "/api/session").await?;
    validate_session_parts(&session.status, &session.body)?;

    let url: Uri = format!("{}/api/session", options.base_url.trim_end_matches('/'))
        .parse()
        .map_err(|error| format!("bad URL: {error}"))?;
    let transport_is_http1 = session.h2_fallback;

    let (_, cpu_before, rss_unit) = rusage_snapshot()?;
    let rss_first_sample = current_rss_kib()?;
    let mut rss_samples = vec![rss_first_sample];

    let make_request = || {
        Request::builder()
            .uri(url.clone())
            .body(Full::new(Bytes::new()))
            .map_err(|error| format!("build request: {error}"))
    };

    let mut cold = Vec::new();
    for _ in 0..config.warmup {
        let client = if transport_is_http1 {
            http1_client()
        } else {
            h2_client()
        };
        let request = make_request()?;
        let response = tokio::time::timeout(options.timeout, client.request(request))
            .await
            .map_err(|_| "bench warmup timed out")?
            .map_err(|e| format!("transport: {e}"))?;
        // Consume every body and validate every measured response, not just
        // the first one.
        let (version, status, body) =
            tokio::time::timeout(options.timeout, send_existing(response))
                .await
                .map_err(|_| "bench warmup body timed out")??;
        let _ = version;
        validate_session_parts(&status, &body)?;
    }
    for _ in 0..config.iterations {
        let start = Instant::now();
        // Cold: a fresh client per request (new connection).
        let client = if transport_is_http1 {
            http1_client()
        } else {
            h2_client()
        };
        let request = make_request()?;
        let response = tokio::time::timeout(options.timeout, client.request(request))
            .await
            .map_err(|_| "bench cold request timed out")?
            .map_err(|e| format!("transport: {e}"))?;
        let (version, status, body) =
            tokio::time::timeout(options.timeout, send_existing(response))
                .await
                .map_err(|_| "bench cold body timed out")??;
        let _ = version;
        validate_session_parts(&status, &body)?;
        cold.push(start.elapsed().as_micros());
    }

    let client = if transport_is_http1 {
        http1_client()
    } else {
        h2_client()
    };
    let mut warm = Vec::new();
    let warm_start = Instant::now();
    for _ in 0..config.iterations {
        let start = Instant::now();
        let request = make_request()?;
        let response = tokio::time::timeout(options.timeout, client.request(request))
            .await
            .map_err(|_| "bench warm request timed out")?
            .map_err(|e| format!("transport: {e}"))?;
        let (version, status, body) =
            tokio::time::timeout(options.timeout, send_existing(response))
                .await
                .map_err(|_| "bench warm body timed out")??;
        let _ = version;
        validate_session_parts(&status, &body)?;
        warm.push(start.elapsed().as_micros());
    }
    let warm_total = warm_start.elapsed();

    cold.sort_unstable();
    warm.sort_unstable();

    // Stream consumption/churn (HTTP/1.1 Upgrade WS, per implemented bridge).
    // A successful cycle requires a `stream_open` first frame AND at least
    // one validated entry; known error-first frames count only in the
    // explicit error-only mode, never as silent success.
    let mut cycle_us = Vec::new();
    let mut handshake_us_samples = Vec::new();
    let mut first_frame_us_samples = Vec::new();
    let mut first_entry_us_samples = Vec::new();
    let mut stream_cycles_completed = 0usize;
    let mut stream_error_cycles = 0usize;
    let mut stream_entries_consumed = 0usize;
    if let (Some(name), true) = (&config.session, config.stream_cycles > 0) {
        for cycle in 0..config.stream_cycles {
            let start = Instant::now();
            let mut stream_options = options.clone();
            stream_options.timeout = options.timeout.min(Duration::from_secs(3));
            let probe = ws_stream_probe(&stream_options, name, Duration::from_millis(900))
                .await
                .map_err(|error| format!("bench stream cycle {cycle}: {error}"))?;
            require_known_first_event(&probe)?;
            let is_success = probe.first_event == "stream_open" && probe.entries_consumed >= 1;
            let is_error_only = probe.first_event == "error" && probe.error_code.is_some();
            if is_success {
                stream_cycles_completed += 1;
                stream_entries_consumed += probe.entries_consumed;
                cycle_us.push(start.elapsed().as_micros());
                handshake_us_samples.push(probe.handshake_us);
                first_frame_us_samples.push(probe.first_frame_us);
                if probe.first_entry_us > 0 {
                    first_entry_us_samples.push(probe.first_entry_us);
                }
            } else if is_error_only && config.allow_error_streams {
                stream_error_cycles += 1;
                cycle_us.push(start.elapsed().as_micros());
            } else if is_error_only {
                return Err(format!(
                    "bench stream cycle {cycle}: session '{name}' failed closed with error '{}';                      successful benchmark streams require entries (use --error-streams for the \
                     explicit error-only mode)",
                    probe.error_code.as_deref().unwrap_or("?")
                ));
            } else {
                return Err(format!(
                    "bench stream cycle {cycle}: {} entries consumed; a successful cycle needs                      at least one validated entry",
                    probe.entries_consumed
                ));
            }
            if cycle % (config.stream_cycles / 4).max(1) == 0 {
                rss_samples.push(current_rss_kib()?);
            }
        }
    }
    cycle_us.sort_unstable();
    handshake_us_samples.sort_unstable();
    first_frame_us_samples.sort_unstable();
    first_entry_us_samples.sort_unstable();
    if !cycle_us.is_empty() {
        rss_samples.push(current_rss_kib()?);
    }

    let (peak_rss, cpu_after, _) = rusage_snapshot()?;
    let rss_last_sample = *rss_samples.last().ok_or("rss trend: no samples")?;
    Ok(BenchReport {
        protocol: session.protocol,
        cold_p50_us: percentile(&cold, 0.50),
        cold_p95_us: percentile(&cold, 0.95),
        warm_p50_us: percentile(&warm, 0.50),
        warm_p95_us: percentile(&warm, 0.95),
        warm_throughput_rps: config.iterations as f64 / warm_total.as_secs_f64(),
        stream_handshake_p50_us: percentile(&handshake_us_samples, 0.50),
        stream_first_frame_p50_us: percentile(&first_frame_us_samples, 0.50),
        stream_first_entry_p50_us: percentile(&first_entry_us_samples, 0.50),
        stream_cycle_p50_us: percentile(&cycle_us, 0.50),
        stream_cycle_p95_us: percentile(&cycle_us, 0.95),
        stream_cycles_completed,
        stream_error_cycles,
        stream_error_only_mode: config.allow_error_streams,
        stream_entries_consumed,
        cpu_delta_ms: cpu_after.saturating_sub(cpu_before),
        peak_rss,
        rss_unit,
        rss_first_sample,
        rss_last_sample,
        rss_growth: rss_last_sample.saturating_sub(rss_first_sample),
        rss_samples: rss_samples.len(),
    })
}

/// Validates a measured /api/session exchange: status, event shape, protocol
/// and machine_id. Applied to every measured response, not just the first.
fn validate_session_parts(status: &StatusCode, body: &Value) -> Result<(), String> {
    if *status != StatusCode::OK {
        return Err(format!("bench: /api/session returned {status}"));
    }
    if body["event"] != json!("paired") {
        return Err("bench: /api/session body is not a paired event".into());
    }
    if body["protocol"] != json!(1) {
        return Err("bench: /api/session protocol != 1".into());
    }
    if body["machine_id"].as_str().unwrap_or("").is_empty() {
        return Err("bench: session missing nonempty machine_id".into());
    }
    Ok(())
}

fn require_known_first_event(probe: &StreamProbe) -> Result<(), String> {
    match probe.first_event.as_str() {
        "stream_open" => probe
            .generation
            .ok_or_else(|| "bench stream: stream_open missing generation".to_string())
            .map(|_| ()),
        "error" => probe
            .error_code
            .as_deref()
            .ok_or_else(|| "bench stream: error frame missing code".to_string())
            .and_then(|code| {
                if KNOWN_WS_ERROR_CODES.contains(&code) {
                    Ok(())
                } else {
                    Err(format!("bench stream: unknown error code {code}"))
                }
            }),
        other => Err(format!("bench stream: unexpected first event {other}")),
    }
}

/// Validates an already-received response (status/body shape) and reports it;
/// the body was consumed by the caller via `collect_capped`.
async fn send_existing(
    response: http::Response<hyper::body::Incoming>,
) -> Result<(Version, StatusCode, Value), String> {
    let version = response.version();
    let status = response.status();
    let body = collect_capped(response.into_body()).await?;
    let body: Value =
        serde_json::from_slice(&body).map_err(|error| format!("response is not JSON: {error}"))?;
    Ok((version, status, body))
}

impl std::fmt::Display for BenchReport {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        writeln!(f, "protocol: {}", self.protocol)?;
        writeln!(f, "cold_p50_us: {}", self.cold_p50_us)?;
        writeln!(f, "cold_p95_us: {}", self.cold_p95_us)?;
        writeln!(f, "warm_p50_us: {}", self.warm_p50_us)?;
        writeln!(f, "warm_p95_us: {}", self.warm_p95_us)?;
        writeln!(f, "warm_throughput_rps: {:.1}", self.warm_throughput_rps)?;
        writeln!(f, "stream_error_only_mode: {}", self.stream_error_only_mode)?;
        writeln!(
            f,
            "stream_cycles_completed: {}",
            self.stream_cycles_completed
        )?;
        writeln!(f, "stream_error_cycles: {}", self.stream_error_cycles)?;
        writeln!(
            f,
            "stream_handshake_p50_us: {}",
            self.stream_handshake_p50_us
        )?;
        writeln!(
            f,
            "stream_first_frame_p50_us: {}",
            self.stream_first_frame_p50_us
        )?;
        writeln!(
            f,
            "stream_first_entry_p50_us: {}",
            self.stream_first_entry_p50_us
        )?;
        writeln!(
            f,
            "stream_entries_consumed: {}",
            self.stream_entries_consumed
        )?;
        writeln!(f, "stream_cycle_p50_us: {}", self.stream_cycle_p50_us)?;
        writeln!(f, "stream_cycle_p95_us: {}", self.stream_cycle_p95_us)?;
        writeln!(f, "cpu_delta_ms: {}", self.cpu_delta_ms)?;
        writeln!(f, "peak_rss: {} ({})", self.peak_rss, self.rss_unit)?;
        writeln!(f, "rss_first_sample: {}", self.rss_first_sample)?;
        writeln!(f, "rss_last_sample: {}", self.rss_last_sample)?;
        writeln!(f, "rss_growth: {}", self.rss_growth)?;
        write!(f, "rss_samples: {}", self.rss_samples)
    }
}

// Split helpers above use futures_util traits directly.
