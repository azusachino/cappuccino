//! Read-only probe used by both the `test-client` example CLI and the
//! integration tests (included by path). HTTP legs prefer HTTP/2 prior
//! knowledge (h2c) and report the observed protocol; `require_h2` fails
//! instead of falling back. The WebSocket leg uses the classic HTTP/1.1
//! Upgrade handshake — the bridge's only implemented WS transport (RFC 8441
//! extended CONNECT is not implemented by the bridge and is not attempted).
//!
//! Everything is bounded: one deadline per operation and an overall budget.
//! The probe only ever issues GET requests and WS reads; it never sends.

use bytes::Bytes;
use http::{StatusCode, Uri, Version};
use http_body_util::Full;
use hyper::Request;
use hyper_util::client::legacy::{connect::HttpConnector, Client, Error as HyperError};
use hyper_util::rt::TokioExecutor;
use serde_json::{json, Value};
use std::time::{Duration, Instant};
use tokio_tungstenite::tungstenite::Message;

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
    let body = http_body_util::BodyExt::collect(response.into_body())
        .await
        .map_err(|error| format!("read body: {error}"))?
        .to_bytes();
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
        match tokio::time::timeout(options.timeout, send_with(&h2_client(), url.clone())).await {
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
        tokio::time::timeout(options.timeout, send_with(&http1_client(), url))
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

/// Connects the WS stream and returns the first text frame as JSON plus the
/// protocol label (always the classic HTTP/1.1 Upgrade handshake here).
pub async fn ws_first_frame(
    options: &ProbeOptions,
    session: &str,
) -> Result<(Value, &'static str), String> {
    let url = format!(
        "{}{}",
        options
            .base_url
            .trim_end_matches('/')
            .replacen("http", "ws", 1),
        format!("/api/stream?session={}", urlencode(session))
    );
    let (stream, _response) = tokio::time::timeout(
        options.timeout,
        tokio_tungstenite::connect_async(url.as_str()),
    )
    .await
    .map_err(|_| "ws connect timed out".to_string())?
    .map_err(|error| format!("ws connect: {error}"))?;
    let (mut write, mut read) = stream.split();
    // Read the first frame; the probe never writes after the handshake.
    let first = tokio::time::timeout(options.timeout, read.next())
        .await
        .map_err(|_| "ws first frame timed out".to_string())?;
    // Close our side politely without waiting on the peer.
    let _ = futures_util::SinkExt::close(&mut write).await;
    let first = first.ok_or("server closed before the first frame")?;
    let item = first.map_err(|error| format!("ws read: {error}"))?;
    let text = match item {
        Message::Text(text) => text.to_string(),
        Message::Close(frame) => {
            return Err(format!(
                "server closed before the first frame: {}",
                frame.map(|frame| frame.to_string()).unwrap_or_default()
            ))
        }
        other => return Err(format!("unexpected first frame kind: {other}")),
    };
    let value: Value =
        serde_json::from_str(&text).map_err(|error| format!("malformed frame: {error}"))?;
    Ok((value, "ws-http1-upgrade"))
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

#[derive(Debug, Default)]
pub struct CheckReport {
    pub lines: Vec<String>,
    pub protocols: Vec<&'static str>,
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

/// Runs the read-only HTTP checks (and the WS leg when a session is set).
/// Every mismatch, malformed frame, timeout or disconnect is an error.
pub async fn run_checks(options: &ProbeOptions) -> Result<CheckReport, String> {
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

    // Transcript probe: an unknown session must fail closed — either a 502
    // protocol error or a 200 with available:false. Never a 200 that claims
    // availability.
    let probe_session = options
        .session
        .clone()
        .unwrap_or_else(|| "__probe__".into());
    let transcript = get(options, &format!("/api/transcript?session={probe_session}")).await?;
    match transcript.status {
        StatusCode::OK => require(
            transcript.body["available"] == json!(false),
            "transcript: 200 must carry available:false",
        )?,
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

    if options.preference == HttpPreference::RequireH2 {
        // Strictness was already enforced per-request; reaching this point
        // means every leg answered over HTTP/2.
    }

    if let Some(name) = &options.session {
        let (frame, protocol) = ws_first_frame(options, name).await?;
        let event = frame["event"]
            .as_str()
            .ok_or("ws: first frame has no event")?
            .to_string();
        if let Some(expected) = &options.expect_event {
            require(
                &event == expected,
                &format!("ws: expected first event {expected}, got {event}"),
            )?;
        } else {
            require(
                matches!(event.as_str(), "stream_open" | "error"),
                &format!("ws: unexpected first event {event}"),
            )?;
        }
        report.record(
            format!("stream[{name}]: first event {event} ({protocol})"),
            protocol,
        );
    }

    Ok(report)
}

// ---------------------------------------------------------------------------
// Benchmark (checked-in, synthetic, bounded). Cold/warm HTTP plus stream
// connection churn. Reports p50/p95, throughput and getrusage-derived CPU /
// peak RSS together with the observed protocol. No thresholds are asserted;
// numbers are recorded as a baseline for regression budgets.
// ---------------------------------------------------------------------------

pub struct BenchConfig {
    pub iterations: usize,
    pub warmup: usize,
    pub stream_cycles: usize,
    pub session: Option<String>,
}

impl Default for BenchConfig {
    fn default() -> Self {
        Self {
            iterations: 200,
            warmup: 20,
            stream_cycles: 50,
            session: None,
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
    pub stream_connect_p50_ms: u128,
    pub stream_connect_p95_ms: u128,
    pub cpu_delta_ms: u64,
    pub peak_rss: u64,
    pub rss_unit: &'static str,
}

pub fn percentile(sorted: &[u128], fraction: f64) -> u128 {
    if sorted.is_empty() {
        return 0;
    }
    let index = ((sorted.len() as f64 - 1.0) * fraction).round() as usize;
    sorted[index.min(sorted.len() - 1)]
}

/// Peak RSS and CPU time via getrusage (portable across macOS and Linux).
/// ru_maxrss is KiB on Linux and bytes on macOS; the unit is reported.
pub fn rusage_snapshot() -> (u64, u64, &'static str) {
    let mut real: libc::rusage = unsafe { std::mem::zeroed() };
    let ok = unsafe { libc::getrusage(libc::RUSAGE_SELF, &mut real) } == 0;
    let cpu = ((real.ru_utime.tv_sec + real.ru_stime.tv_sec) as u64) * 1000
        + ((real.ru_utime.tv_usec + real.ru_stime.tv_usec) as u64) / 1000;
    // ru_maxrss is bytes on macOS and KiB on Linux; report the unit.
    if cfg!(target_os = "macos") {
        (if ok { real.ru_maxrss as u64 } else { 0 }, cpu, "bytes")
    } else {
        (if ok { real.ru_maxrss as u64 } else { 0 }, cpu, "KiB")
    }
}

pub async fn run_bench(
    options: &ProbeOptions,
    config: &BenchConfig,
) -> Result<BenchReport, String> {
    // Choose the transport once, honestly: prefer h2, report the fallback.
    let session = get(options, "/api/session").await?;
    if session.status != StatusCode::OK {
        return Err(format!("bench: /api/session returned {}", session.status));
    }

    let url: Uri = format!("{}/api/session", options.base_url.trim_end_matches('/'))
        .parse()
        .map_err(|error| format!("bad URL: {error}"))?;
    let make_request = || {
        Request::builder()
            .uri(url.clone())
            .body(Full::new(Bytes::new()))
            .map_err(|error| format!("build request: {error}"))
    };

    let mut cold = Vec::new();
    let transport_is_http1 = session.h2_fallback;
    for _ in 0..config.warmup {
        let client = if transport_is_http1 {
            http1_client()
        } else {
            h2_client()
        };
        let request = make_request()?;
        tokio::time::timeout(options.timeout, client.request(request))
            .await
            .map_err(|_| "bench warmup timed out")?
            .map_err(|e| format!("transport: {e}"))?;
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
        drop(response);
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
        drop(response);
        warm.push(start.elapsed().as_micros());
    }
    let warm_total = warm_start.elapsed();

    cold.sort_unstable();
    warm.sort_unstable();

    // Stream connection churn (HTTP/1.1 Upgrade WS, per implemented bridge).
    let mut stream_ms = Vec::new();
    if let (Some(name), true) = (&config.session, config.stream_cycles > 0) {
        for _ in 0..config.stream_cycles {
            let start = Instant::now();
            let (frame, _) = ws_first_frame(options, name).await?;
            if frame["event"].as_str().is_none() {
                return Err("bench: stream frame has no event".into());
            }
            stream_ms.push(start.elapsed().as_millis());
        }
    }
    stream_ms.sort_unstable();

    let (peak_rss, _, rss_unit) = rusage_snapshot();
    let (_, cpu_after, _) = rusage_snapshot();
    Ok(BenchReport {
        protocol: session.protocol,
        cold_p50_us: percentile(&cold, 0.50),
        cold_p95_us: percentile(&cold, 0.95),
        warm_p50_us: percentile(&warm, 0.50),
        warm_p95_us: percentile(&warm, 0.95),
        warm_throughput_rps: config.iterations as f64 / warm_total.as_secs_f64(),
        stream_connect_p50_ms: percentile(&stream_ms, 0.50),
        stream_connect_p95_ms: percentile(&stream_ms, 0.95),
        cpu_delta_ms: cpu_after,
        peak_rss,
        rss_unit,
    })
}

impl std::fmt::Display for BenchReport {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        writeln!(f, "protocol: {}", self.protocol)?;
        writeln!(f, "cold_p50_us: {}", self.cold_p50_us)?;
        writeln!(f, "cold_p95_us: {}", self.cold_p95_us)?;
        writeln!(f, "warm_p50_us: {}", self.warm_p50_us)?;
        writeln!(f, "warm_p95_us: {}", self.warm_p95_us)?;
        writeln!(f, "warm_throughput_rps: {:.1}", self.warm_throughput_rps)?;
        writeln!(f, "stream_connect_p50_ms: {}", self.stream_connect_p50_ms)?;
        writeln!(f, "stream_connect_p95_ms: {}", self.stream_connect_p95_ms)?;
        writeln!(f, "cpu_total_ms: {}", self.cpu_delta_ms)?;
        write!(f, "peak_rss: {} ({})", self.peak_rss, self.rss_unit)
    }
}

/// Re-exported for tests and the CLI: split helpers used above.
use futures_util::StreamExt;
