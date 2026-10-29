//! iroh transport (opt-in, `iroh.enabled`): a QUIC tunnel into the loopback
//! HTTP server. Each accepted bi-stream on ALPN `cappuccino/1` is piped to a
//! fresh TCP connection to the bridge's own listener, so the HTTP contract is
//! unchanged. Trust is the EndpointId allowlist, enforced by an endpoint hook
//! at handshake; ALPN `cappuccino-pair/1` admits unknown ids but serves only a
//! one-time token exchange. Design: docs/design/public-exposure-iroh.md.

use crate::config::PAIR_TOKEN_TTL_MAX_SECS;
use axum::{
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
    Extension, Router,
};
use iroh::{
    endpoint::{
        presets, AfterHandshakeOutcome, Connection, EndpointHooks, QuicTransportConfig, RecvStream,
        SendStream, VarInt, WeakConnectionHandle,
    },
    Endpoint, EndpointAddr, EndpointId, SecretKey, TransportAddr,
};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fmt::Write as _,
    fs::{self, OpenOptions, Permissions},
    future::Future,
    io::Write as _,
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use subtle::ConstantTimeEq;
use tokio::{
    io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader},
    net::{TcpStream, UnixListener, UnixStream},
};

pub const ALPN_TUNNEL: &[u8] = b"cappuccino/1";
pub const ALPN_PAIR: &[u8] = b"cappuccino-pair/1";

const CLOSE_NOT_PAIRED: u32 = 1;
const CLOSE_REVOKED: u32 = 2;
const CLOSE_LIMIT: u32 = 3;
const CLOSE_PAIR_BUSY: u32 = 4;
const CLOSE_PAIR_DONE: u32 = 0;

/// Limits. Named constants rather than config: they bound resource use for a
/// single-owner bridge and are not per-machine tuning knobs. Design:
/// docs/design/public-exposure-iroh.md ("Limits").
/// Concurrent bi-streams per tunnel connection, enforced by QUIC flow control:
/// the phone's extra opens wait for a stream to finish.
pub const MAX_TUNNEL_STREAMS: u32 = 32;
/// Live tunnel connections per paired EndpointId; the excess is closed.
pub const MAX_TUNNEL_CONNECTIONS_PER_ID: usize = 4;
/// Live tunnel connections across all paired EndpointIds.
pub const MAX_TUNNEL_CONNECTIONS: usize = 16;
/// Unpaired `cappuccino-pair/1` connections served at once; the excess is closed.
pub const MAX_PAIR_CONNECTIONS: usize = 4;
/// Pairing attempts allowed per remote EndpointId in any PAIR_ATTEMPT_WINDOW.
pub const PAIR_ATTEMPTS_PER_WINDOW: usize = 5;
pub const PAIR_ATTEMPT_WINDOW: Duration = Duration::from_secs(60);
/// Connections still in the QUIC/TLS handshake; the accept loop waits when full,
/// so a flood of half-open handshakes cannot pin unbounded tasks.
pub const MAX_PENDING_HANDSHAKES: usize = 32;
/// A token is 64 hex characters; anything longer is refused before comparison.
const PAIR_REQUEST_MAX: usize = 128;
const PAIR_EXCHANGE_TIMEOUT: Duration = Duration::from_secs(10);
const STOPPED_TIMEOUT: Duration = Duration::from_secs(5);
const ONLINE_WAIT: Duration = Duration::from_secs(10);
const SECRET_KEY_FILE: &str = "iroh-secret.key";
const ALLOWLIST_FILE: &str = "iroh-allowlist.json";
pub const ADMIN_SOCKET_FILE: &str = "iroh-admin.sock";
const FILE_VERSION: u32 = 1;

/// Where tunnelled streams are piped: the bridge's own loopback listener.
#[derive(Clone, Debug)]
pub struct Target {
    pub host: String,
    pub port: u16,
}

/// Allowlist and the single outstanding pairing token. Both sit behind one
/// lock so a redemption is atomic with respect to revocation and issue.
pub struct Pairing {
    allowlist_path: PathBuf,
    ttl: Duration,
    state: Mutex<PairingState>,
    /// Live tunnel connections by remote id, held weakly so the connection
    /// still closes on drop; revocation upgrades them and closes them.
    live: Mutex<Vec<(EndpointId, WeakConnectionHandle)>>,
    /// Bounds concurrent unpaired `cappuccino-pair/1` exchanges.
    pair_slots: tokio::sync::Semaphore,
    audit: AuditSink,
}

#[derive(Default)]
struct PairingState {
    allowed: BTreeSet<EndpointId>,
    pending: Option<PendingToken>,
    /// Admitted pairing attempts per remote id within PAIR_ATTEMPT_WINDOW.
    pair_attempts: BTreeMap<EndpointId, Vec<Instant>>,
}

/// Why a pairing exchange was refused. Only the variant name reaches the
/// audit log; the presented token never does.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PairFailure {
    None,
    Expired,
    Mismatch,
    RateLimited,
    Persist,
}

/// Where audit lines go: stdout, which the managed lifecycle writes to
/// `bridge.log`. Test builds capture into memory so tests can read them back.
enum AuditSink {
    #[cfg(not(test))]
    Stdout,
    #[cfg(test)]
    Captured(Mutex<Vec<String>>),
}

#[cfg(not(test))]
fn default_audit_sink() -> AuditSink {
    AuditSink::Stdout
}

#[cfg(test)]
fn default_audit_sink() -> AuditSink {
    AuditSink::Captured(Mutex::new(Vec::new()))
}

struct PendingToken {
    value: String,
    expires_at: Instant,
}

#[derive(Serialize, Deserialize)]
struct AllowlistFile {
    v: u32,
    endpoints: Vec<String>,
}

impl std::fmt::Debug for Pairing {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Pairing")
            .field("allowlist_path", &self.allowlist_path)
            .field("ttl", &self.ttl)
            .finish_non_exhaustive()
    }
}

impl Pairing {
    /// Loads the persisted allowlist. A missing file is an empty allowlist;
    /// an unreadable or malformed one stops startup rather than admitting
    /// anyone.
    pub fn load(data_dir: &Path, ttl: Duration) -> Result<Self, String> {
        let allowlist_path = data_dir.join(ALLOWLIST_FILE);
        let allowed = match fs::read(&allowlist_path) {
            Ok(bytes) => {
                let file: AllowlistFile = serde_json::from_slice(&bytes).map_err(|error| {
                    format!(
                        "{} is not a valid allowlist: {error}",
                        allowlist_path.display()
                    )
                })?;
                if file.v != FILE_VERSION {
                    return Err(format!(
                        "{} has unsupported version {}",
                        allowlist_path.display(),
                        file.v
                    ));
                }
                file.endpoints
                    .iter()
                    .map(|text| {
                        text.parse::<EndpointId>()
                            .map_err(|error| format!("invalid paired endpoint id: {error}"))
                    })
                    .collect::<Result<BTreeSet<_>, _>>()?
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => BTreeSet::new(),
            Err(error) => return Err(format!("read {}: {error}", allowlist_path.display())),
        };
        Ok(Self {
            allowlist_path,
            ttl,
            state: Mutex::new(PairingState {
                allowed,
                pending: None,
                pair_attempts: BTreeMap::new(),
            }),
            live: Mutex::new(Vec::new()),
            pair_slots: tokio::sync::Semaphore::new(MAX_PAIR_CONNECTIONS),
            audit: default_audit_sink(),
        })
    }

    pub fn is_allowed(&self, id: &EndpointId) -> bool {
        self.state.lock().unwrap().allowed.contains(id)
    }

    /// Replaces any outstanding token with a fresh 32-byte random one.
    /// `source` names the issuing surface (`cli` or `page`) for the audit log.
    pub fn issue_token(&self, source: &str) -> String {
        let token = hex(&rand::random::<[u8; 32]>());
        self.state.lock().unwrap().pending = Some(PendingToken {
            value: token.clone(),
            expires_at: Instant::now() + self.ttl,
        });
        self.audit(format!(
            "token_issued source={source} ttl_secs={}",
            self.ttl.as_secs()
        ));
        token
    }

    /// Consumes the outstanding token on a constant-time match and persists
    /// the redeeming EndpointId. A wrong guess leaves the token in place; an
    /// expired one is discarded. Every attempt counts against the per-id rate
    /// limit, whether or not the token matches.
    pub fn redeem(&self, presented: &[u8], id: EndpointId) -> Result<(), PairFailure> {
        let mut state = self.state.lock().unwrap();
        if !admit_attempt(&mut state.pair_attempts, id, Instant::now()) {
            drop(state);
            self.audit(format!("limit_hit limit=pair_attempts endpoint={id}"));
            return Err(PairFailure::RateLimited);
        }
        let outcome = self.check_token(&mut state, presented);
        if let Err(failure) = outcome {
            drop(state);
            self.audit(format!(
                "pair_failed endpoint={id} reason={}",
                failure.label()
            ));
            return Err(failure);
        }
        let mut next = state.allowed.clone();
        next.insert(id);
        if let Err(error) = persist_allowlist(&self.allowlist_path, &next) {
            drop(state);
            self.audit(format!("pair_failed endpoint={id} reason=persist_failed"));
            eprintln!("cappuccino-bridge: {error}");
            return Err(PairFailure::Persist);
        }
        state.allowed = next;
        drop(state);
        self.audit(format!("pair_ok endpoint={id}"));
        Ok(())
    }

    fn check_token(&self, state: &mut PairingState, presented: &[u8]) -> Result<(), PairFailure> {
        let Some(pending) = state.pending.as_ref() else {
            return Err(PairFailure::None);
        };
        if Instant::now() >= pending.expires_at {
            state.pending = None;
            return Err(PairFailure::Expired);
        }
        let matches: bool = pending.value.as_bytes().ct_eq(presented).into();
        if !matches {
            return Err(PairFailure::Mismatch);
        }
        state.pending = None;
        Ok(())
    }

    /// Removes an id and closes its live tunnel connections. Returns whether
    /// the id was paired. Memory is updated before the file write, so a
    /// failed write still stops new dials.
    pub fn revoke(&self, id: &EndpointId) -> Result<bool, String> {
        let mut state = self.state.lock().unwrap();
        let was_paired = state.allowed.contains(id);
        let mut next = state.allowed.clone();
        next.remove(id);
        state.allowed = next.clone();
        let closed = self.close_live(id);
        self.audit(format!(
            "revoked endpoint={id} was_paired={was_paired} closed={closed}"
        ));
        if was_paired {
            persist_allowlist(&self.allowlist_path, &next)?;
        }
        Ok(was_paired)
    }

    /// Registers a tunnel connection for revocation. Refuses (without tracking)
    /// when the global or per-id connection cap is already reached.
    fn track(&self, id: EndpointId, handle: WeakConnectionHandle) -> Result<(), &'static str> {
        let mut live = self.live.lock().unwrap();
        live.retain(|(_, weak)| weak.upgrade().is_some());
        if live.len() >= MAX_TUNNEL_CONNECTIONS {
            return Err("tunnel_connections");
        }
        if live.iter().filter(|(tracked, _)| *tracked == id).count()
            >= MAX_TUNNEL_CONNECTIONS_PER_ID
        {
            return Err("tunnel_connections_per_id");
        }
        live.push((id, handle));
        Ok(())
    }

    /// Closes this id's live connections and returns how many were closed.
    fn close_live(&self, id: &EndpointId) -> usize {
        let mut live = self.live.lock().unwrap();
        let mut closed = 0;
        live.retain(|(tracked, weak)| {
            if tracked != id {
                return weak.upgrade().is_some();
            }
            if let Some(connection) = weak.upgrade() {
                connection.close(VarInt::from_u32(CLOSE_REVOKED), b"revoked");
                closed += 1;
            }
            false
        });
        closed
    }

    fn audit(&self, line: String) {
        match &self.audit {
            #[cfg(not(test))]
            AuditSink::Stdout => println!("cappuccino-bridge: audit {line}"),
            #[cfg(test)]
            AuditSink::Captured(lines) => lines.lock().unwrap().push(line),
        }
    }

    /// Audit lines captured by this pairing (test builds only).
    #[cfg(test)]
    fn audit_lines(&self) -> Vec<String> {
        match &self.audit {
            AuditSink::Captured(lines) => lines.lock().unwrap().clone(),
        }
    }
}

impl PairFailure {
    fn label(self) -> &'static str {
        match self {
            PairFailure::None => "none",
            PairFailure::Expired => "expired",
            PairFailure::Mismatch => "mismatch",
            PairFailure::RateLimited => "rate_limited",
            PairFailure::Persist => "persist_failed",
        }
    }
}

/// Records one attempt for `id` unless it already used its window budget.
/// Attempts older than the window are dropped first, and ids with no recent
/// attempts are removed, so the map stays small.
fn admit_attempt(
    attempts: &mut BTreeMap<EndpointId, Vec<Instant>>,
    id: EndpointId,
    now: Instant,
) -> bool {
    attempts.retain(|_, times| {
        times.retain(|at| now.duration_since(*at) < PAIR_ATTEMPT_WINDOW);
        !times.is_empty()
    });
    let times = attempts.entry(id).or_default();
    if times.len() >= PAIR_ATTEMPTS_PER_WINDOW {
        return false;
    }
    times.push(now);
    true
}

/// The endpoint hook runs once per handshake, for incoming and outgoing
/// connections alike. The bridge never dials out, so this only gates
/// incoming tunnel connections; the reject happens before any stream exists.
#[derive(Debug)]
struct AllowlistHook(Arc<Pairing>);

impl EndpointHooks for AllowlistHook {
    fn after_handshake<'a>(
        &'a self,
        conn: &'a Connection,
    ) -> impl Future<Output = AfterHandshakeOutcome> + Send + 'a {
        async move {
            if conn.alpn() == ALPN_TUNNEL && !self.0.is_allowed(&conn.remote_id()) {
                self.0.audit(format!(
                    "tunnel_rejected endpoint={} reason=not_paired",
                    conn.remote_id()
                ));
                return AfterHandshakeOutcome::Reject {
                    error_code: VarInt::from_u32(CLOSE_NOT_PAIRED),
                    reason: b"not paired".to_vec(),
                };
            }
            AfterHandshakeOutcome::Accept
        }
    }
}

/// Running transport: the endpoint, the pairing state and the loopback target
/// used by the `GET /pair` page and the admin socket.
pub struct IrohHub {
    pub endpoint: Endpoint,
    pub pairing: Arc<Pairing>,
    online_wait: Duration,
}

impl IrohHub {
    /// Issues a fresh token and returns the copy-paste pairing payload. The
    /// token is issued after the endpoint is online so the payload carries the
    /// relay URL; if that takes too long the payload is still emitted with
    /// whatever addresses are known.
    pub async fn pairing_payload(&self, source: &str) -> String {
        let _ = tokio::time::timeout(self.online_wait, self.endpoint.online()).await;
        let token = self.pairing.issue_token(source);
        encode_payload(&self.endpoint.addr(), &token)
    }
}

/// Pairing payload, format v1 (compact JSON, same text in the QR and the
/// copy-paste line):
/// `{"v":1,"endpoint_id":"<id>","relay":"<url>|null","addrs":["ip:port"...],"token":"<64 hex>"}`
pub fn encode_payload(addr: &EndpointAddr, token: &str) -> String {
    let mut relay = None;
    let mut addrs = Vec::new();
    for transport in &addr.addrs {
        match transport {
            TransportAddr::Relay(url) => relay = Some(url.to_string()),
            TransportAddr::Ip(socket) => addrs.push(socket.to_string()),
            _ => {}
        }
    }
    serde_json::json!({
        "v": 1,
        "endpoint_id": addr.id.to_string(),
        "relay": relay,
        "addrs": addrs,
        "token": token,
    })
    .to_string()
}

/// Starts the endpoint with the persisted key, the allowlist hook and the
/// accept loop, and serves the admin socket. Errors stop the bridge.
pub async fn start(data_dir: &Path, target: Target, ttl_secs: u64) -> Result<Arc<IrohHub>, String> {
    if !(1..=PAIR_TOKEN_TTL_MAX_SECS).contains(&ttl_secs) {
        return Err("pairing token TTL out of range".into());
    }
    fs::create_dir_all(data_dir)
        .map_err(|error| format!("create data dir {}: {error}", data_dir.display()))?;
    let key = load_or_create_secret_key(&data_dir.join(SECRET_KEY_FILE))?;
    let pairing = Arc::new(Pairing::load(data_dir, Duration::from_secs(ttl_secs))?);
    let endpoint = configure(Endpoint::builder(presets::N0), key, &pairing)
        .bind()
        .await
        .map_err(|error| format!("bind iroh endpoint: {error}"))?;
    let hub = Arc::new(IrohHub {
        endpoint: endpoint.clone(),
        pairing: Arc::clone(&pairing),
        online_wait: ONLINE_WAIT,
    });
    spawn_accept_loop(endpoint, pairing, target);
    let admin = bind_admin_socket(&data_dir.join(ADMIN_SOCKET_FILE))?;
    tokio::spawn(serve_admin(admin, Arc::clone(&hub)));
    Ok(hub)
}

fn configure(
    builder: iroh::endpoint::Builder,
    key: SecretKey,
    pairing: &Arc<Pairing>,
) -> iroh::endpoint::Builder {
    let transport = QuicTransportConfig::builder()
        .max_concurrent_bidi_streams(VarInt::from_u32(MAX_TUNNEL_STREAMS))
        .build();
    builder
        .secret_key(key)
        .alpns(vec![ALPN_TUNNEL.to_vec(), ALPN_PAIR.to_vec()])
        .transport_config(transport)
        .hooks(AllowlistHook(Arc::clone(pairing)))
}

fn spawn_accept_loop(endpoint: Endpoint, pairing: Arc<Pairing>, target: Target) {
    let handshakes = Arc::new(tokio::sync::Semaphore::new(MAX_PENDING_HANDSHAKES));
    tokio::spawn(async move {
        while let Some(incoming) = endpoint.accept().await {
            // Waits (rather than spawning) while the handshake pool is full.
            let Ok(permit) = Arc::clone(&handshakes).acquire_owned().await else {
                return;
            };
            let pairing = Arc::clone(&pairing);
            let target = target.clone();
            tokio::spawn(async move {
                // A hook rejection surfaces here as an error; nothing else runs.
                let conn = incoming.await;
                drop(permit);
                let Ok(conn) = conn else { return };
                if conn.alpn() == ALPN_TUNNEL {
                    serve_tunnel(conn, pairing, target).await;
                } else if conn.alpn() == ALPN_PAIR {
                    serve_pair(conn, pairing).await;
                } else {
                    conn.close(VarInt::from_u32(CLOSE_NOT_PAIRED), b"unknown alpn");
                }
            });
        }
    });
}

async fn serve_tunnel(conn: Connection, pairing: Arc<Pairing>, target: Target) {
    let id = conn.remote_id();
    // Track before checking: revoke clears the allowlist before it closes
    // tracked connections, so either revoke sees this entry or this check
    // sees the cleared allowlist.
    if let Err(limit) = pairing.track(id, conn.weak_handle()) {
        pairing.audit(format!("limit_hit limit={limit} endpoint={id}"));
        conn.close(VarInt::from_u32(CLOSE_LIMIT), b"limit");
        return;
    }
    if !pairing.is_allowed(&id) {
        pairing.audit(format!("tunnel_rejected endpoint={id} reason=revoked"));
        conn.close(VarInt::from_u32(CLOSE_REVOKED), b"revoked");
        return;
    }
    while let Ok((send, recv)) = conn.accept_bi().await {
        tokio::spawn(pipe(send, recv, target.clone()));
    }
}

/// One bi-stream becomes one TCP connection to the loopback listener. The two
/// directions are independent copies so a half-close in one does not cancel
/// the other: EOF from the phone shuts the TCP write side; EOF from TCP
/// finishes the QUIC send side and waits for the peer to acknowledge it.
async fn pipe(mut send: SendStream, mut recv: RecvStream, target: Target) {
    let Ok(tcp) = TcpStream::connect((target.host.as_str(), target.port)).await else {
        let _ = recv.stop(VarInt::from_u32(0));
        let _ = send.reset(VarInt::from_u32(0));
        return;
    };
    let (mut tcp_read, mut tcp_write) = tcp.into_split();
    let inbound = async {
        match tokio::io::copy(&mut recv, &mut tcp_write).await {
            Ok(_) => {
                let _ = tcp_write.shutdown().await;
            }
            Err(_) => {
                let _ = recv.stop(VarInt::from_u32(0));
            }
        }
    };
    let outbound = async {
        match tokio::io::copy(&mut tcp_read, &mut send).await {
            Ok(_) => {
                let _ = send.finish();
                let _ = tokio::time::timeout(STOPPED_TIMEOUT, send.stopped()).await;
            }
            Err(_) => {
                let _ = send.reset(VarInt::from_u32(0));
            }
        }
    };
    tokio::join!(inbound, outbound);
}

/// Serves exactly one token exchange: read the token, reply `OK` or `ERR`,
/// then wait for the dialer to close. Other streams are never accepted.
async fn serve_pair(conn: Connection, pairing: Arc<Pairing>) {
    // Held until this function returns, so the slot covers the whole exchange.
    let Ok(_slot) = pairing.pair_slots.try_acquire() else {
        pairing.audit(format!(
            "limit_hit limit=pair_connections endpoint={}",
            conn.remote_id()
        ));
        conn.close(VarInt::from_u32(CLOSE_PAIR_BUSY), b"busy");
        return;
    };
    let exchange = async {
        let (mut send, mut recv) = conn.accept_bi().await.ok()?;
        let presented = recv.read_to_end(PAIR_REQUEST_MAX).await.ok()?;
        let reply: &[u8] = match pairing.redeem(&presented, conn.remote_id()) {
            Ok(()) => b"OK\n",
            Err(_) => b"ERR\n",
        };
        send.write_all(reply).await.ok()?;
        send.finish().ok()?;
        let _ = tokio::time::timeout(STOPPED_TIMEOUT, send.stopped()).await;
        Some(())
    };
    let _ = tokio::time::timeout(PAIR_EXCHANGE_TIMEOUT, exchange).await;
    // Let the dialer close first so the reply is not cut off by our close.
    let _ = tokio::time::timeout(PAIR_EXCHANGE_TIMEOUT, conn.closed()).await;
    conn.close(VarInt::from_u32(CLOSE_PAIR_DONE), b"");
}

fn bind_admin_socket(path: &Path) -> Result<UnixListener, String> {
    // A live socket belongs to another bridge; only a dead one is replaced.
    if path.exists() {
        if std::os::unix::net::UnixStream::connect(path).is_ok() {
            return Err(format!("another bridge is serving {}", path.display()));
        }
        fs::remove_file(path).map_err(|error| format!("remove stale admin socket: {error}"))?;
    }
    let listener = UnixListener::bind(path)
        .map_err(|error| format!("bind admin socket {}: {error}", path.display()))?;
    fs::set_permissions(path, Permissions::from_mode(0o600))
        .map_err(|error| format!("chmod admin socket: {error}"))?;
    Ok(listener)
}

/// Local admin protocol for the `pair` subcommand. It lives on a 0600 Unix
/// socket in the data dir, so it is reachable only as the owning user and
/// never through the HTTP listener that Tailscale Serve exposes.
///   `ISSUE`            -> `OK <pairing payload>`
///   `REVOKE <id>`      -> `OK revoked|not-paired`
async fn serve_admin(listener: UnixListener, hub: Arc<IrohHub>) {
    while let Ok((stream, _)) = listener.accept().await {
        tokio::spawn(handle_admin(stream, Arc::clone(&hub)));
    }
}

async fn handle_admin(stream: UnixStream, hub: Arc<IrohHub>) {
    let (read, mut write) = stream.into_split();
    let mut line = String::new();
    if BufReader::new(read.take(256))
        .read_line(&mut line)
        .await
        .is_err()
    {
        return;
    }
    let request = line.trim_end();
    let reply = match request.split_once(' ') {
        None if request == "ISSUE" => format!("OK {}\n", hub.pairing_payload("cli").await),
        Some(("REVOKE", id)) => match id.parse::<EndpointId>() {
            Ok(id) => match hub.pairing.revoke(&id) {
                Ok(true) => "OK revoked\n".to_string(),
                Ok(false) => "OK not-paired\n".to_string(),
                Err(error) => format!("ERR {error}\n"),
            },
            Err(_) => "ERR invalid endpoint id\n".to_string(),
        },
        _ => "ERR unknown request\n".to_string(),
    };
    let _ = write.write_all(reply.as_bytes()).await;
}

/// Routes that exist only when iroh is enabled. With `None` the `/pair` path
/// is simply not registered, so it answers 404.
pub fn with_routes<S: Clone + Send + Sync + 'static>(
    app: Router<S>,
    hub: Option<Arc<IrohHub>>,
) -> Router<S> {
    match hub {
        Some(hub) => app.route("/pair", get(pair_page)).layer(Extension(hub)),
        None => app,
    }
}

async fn pair_page(Extension(hub): Extension<Arc<IrohHub>>) -> Response {
    let payload = hub.pairing_payload("page").await;
    let svg = match qr_svg(&payload) {
        Ok(svg) => svg,
        Err(error) => {
            return (StatusCode::INTERNAL_SERVER_ERROR, error).into_response();
        }
    };
    let html = format!(
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>Cappuccino pairing</title>\
         <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\
         <style>body{{font-family:system-ui,sans-serif;margin:2rem;max-width:40rem}}\
         svg{{width:18rem;height:18rem}}pre{{white-space:pre-wrap;word-break:break-all}}</style>\
         </head><body><h1>Pair a phone</h1>\
         <p>Scan with the Cappuccino app, or copy the text below. Reload for a new code; \
         each code is single-use and expires in ten minutes.</p>\
         {svg}<pre>{payload}</pre></body></html>",
        payload = html_escape(&payload),
    );
    (
        [
            (header::CONTENT_TYPE, "text/html; charset=utf-8"),
            (header::CACHE_CONTROL, "no-store"),
        ],
        html,
    )
        .into_response()
}

/// Inline SVG, generated server-side: no external assets.
pub fn qr_svg(payload: &str) -> Result<String, String> {
    let code =
        qrcode::QrCode::new(payload.as_bytes()).map_err(|error| format!("qr encode: {error}"))?;
    Ok(code
        .render::<qrcode::render::svg::Color>()
        .min_dimensions(256, 256)
        .build())
}

/// Terminal rendering for `pair`.
pub fn qr_terminal(payload: &str) -> Result<String, String> {
    let code =
        qrcode::QrCode::new(payload.as_bytes()).map_err(|error| format!("qr encode: {error}"))?;
    Ok(code
        .render::<qrcode::render::unicode::Dense1x2>()
        .quiet_zone(true)
        .build())
}

/// Client side of the admin protocol, used by the `pair` subcommand.
pub fn admin_request(data_dir: &Path, request: &str) -> Result<String, String> {
    let path = data_dir.join(ADMIN_SOCKET_FILE);
    let mut stream = std::os::unix::net::UnixStream::connect(&path).map_err(|error| {
        format!(
            "bridge is not running (cannot reach {}): {error}",
            path.display()
        )
    })?;
    let timeout = Some(Duration::from_secs(30));
    stream
        .set_read_timeout(timeout)
        .and_then(|()| stream.set_write_timeout(timeout))
        .map_err(|error| format!("set admin socket timeout: {error}"))?;
    stream
        .write_all(format!("{request}\n").as_bytes())
        .map_err(|error| format!("write admin request: {error}"))?;
    let mut reply = String::new();
    std::io::BufRead::read_line(&mut std::io::BufReader::new(stream), &mut reply)
        .map_err(|error| format!("read admin reply: {error}"))?;
    let reply = reply.trim_end().to_string();
    match reply.strip_prefix("OK ") {
        Some(body) => Ok(body.to_string()),
        None => Err(reply),
    }
}

fn load_or_create_secret_key(path: &Path) -> Result<SecretKey, String> {
    match fs::read(path) {
        Ok(bytes) => {
            let array: [u8; 32] = bytes
                .try_into()
                .map_err(|_| format!("{} is not a 32-byte key", path.display()))?;
            Ok(SecretKey::from_bytes(&array))
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            let key = SecretKey::generate();
            write_private(path, &key.to_bytes())?;
            Ok(key)
        }
        Err(error) => Err(format!("read {}: {error}", path.display())),
    }
}

fn persist_allowlist(path: &Path, allowed: &BTreeSet<EndpointId>) -> Result<(), String> {
    let file = AllowlistFile {
        v: FILE_VERSION,
        endpoints: allowed.iter().map(ToString::to_string).collect(),
    };
    let bytes = serde_json::to_vec_pretty(&file).map_err(|error| error.to_string())?;
    write_private(path, &bytes)
}

/// Writes through a temp file with owner-only mode, then renames over the
/// target, so a crash leaves either the old or the new content.
fn write_private(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let tmp = path.with_extension("tmp");
    let mut file = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(&tmp)
        .map_err(|error| format!("open {}: {error}", tmp.display()))?;
    file.set_permissions(Permissions::from_mode(0o600))
        .and_then(|()| file.write_all(bytes))
        .and_then(|()| file.sync_all())
        .map_err(|error| format!("write {}: {error}", tmp.display()))?;
    fs::rename(&tmp, path).map_err(|error| format!("replace {}: {error}", path.display()))
}

fn hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        let _ = write!(out, "{byte:02x}");
    }
    out
}

fn html_escape(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

/// `cappuccino-bridge pair [--revoke <EndpointId>]`: asks the running bridge
/// over the admin socket, so the token lives in that process.
pub fn run_pair_command(args: &[String]) -> Result<(), String> {
    let config = crate::config::BridgeConfig::load()?;
    if !config.iroh_enabled {
        return Err("iroh is disabled: set iroh.enabled to true in the bridge config".into());
    }
    match args {
        [] => {
            let payload = admin_request(&config.data_dir, "ISSUE")?;
            println!("{}\n", qr_terminal(&payload)?);
            println!("{payload}");
            Ok(())
        }
        [flag, id] if flag == "--revoke" => {
            let id: EndpointId = id
                .parse()
                .map_err(|error| format!("invalid EndpointId: {error}"))?;
            match admin_request(&config.data_dir, &format!("REVOKE {id}"))?.as_str() {
                "revoked" => println!("revoked {id}"),
                _ => println!("{id} was not paired"),
            }
            Ok(())
        }
        _ => Err("usage: cappuccino-bridge pair [--revoke <EndpointId>]".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::Request;
    use iroh::endpoint::ConnectionError;
    use std::sync::atomic::{AtomicU64, Ordering};
    use tokio::net::TcpListener;
    use tower::ServiceExt;

    const SHORT: Duration = Duration::from_secs(5);

    fn scratch_dir(name: &str) -> PathBuf {
        static COUNTER: AtomicU64 = AtomicU64::new(0);
        let unique = COUNTER.fetch_add(1, Ordering::Relaxed);
        let dir =
            std::env::temp_dir().join(format!("cap-iroh-{name}-{}-{unique}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    async fn test_endpoint(key: SecretKey, pairing: &Arc<Pairing>) -> Endpoint {
        configure(
            Endpoint::builder(presets::Minimal)
                .bind_addr("127.0.0.1:0")
                .unwrap(),
            key,
            pairing,
        )
        .bind()
        .await
        .unwrap()
    }

    async fn client_endpoint() -> Endpoint {
        Endpoint::builder(presets::Minimal)
            .bind_addr("127.0.0.1:0")
            .unwrap()
            .bind()
            .await
            .unwrap()
    }

    struct TestBridge {
        pairing: Arc<Pairing>,
        addr: EndpointAddr,
        dir: PathBuf,
    }

    /// A bridge endpoint with direct-address dialing only (no relays, no
    /// discovery), tunnelling into `port` on loopback.
    async fn spawn_bridge(port: u16, ttl: Duration) -> TestBridge {
        spawn_bridge_with_key(port, ttl, SecretKey::generate()).await
    }

    async fn spawn_bridge_with_key(port: u16, ttl: Duration, key: SecretKey) -> TestBridge {
        let dir = scratch_dir("bridge");
        let pairing = Arc::new(Pairing::load(&dir, ttl).unwrap());
        let endpoint = test_endpoint(key, &pairing).await;
        let addr = EndpointAddr::new(endpoint.id()).with_ip_addr(endpoint.bound_sockets()[0]);
        spawn_accept_loop(
            endpoint,
            Arc::clone(&pairing),
            Target {
                host: "127.0.0.1".into(),
                port,
            },
        );
        TestBridge { pairing, addr, dir }
    }

    /// Stub TCP server: reads to EOF, echoes the bytes, then closes.
    async fn spawn_echo() -> u16 {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(async move {
            loop {
                let (mut socket, _) = listener.accept().await.unwrap();
                tokio::spawn(async move {
                    let mut buf = Vec::new();
                    socket.read_to_end(&mut buf).await.unwrap();
                    socket.write_all(&buf).await.unwrap();
                    socket.shutdown().await.unwrap();
                });
            }
        });
        port
    }

    /// Runs one pairing exchange and returns the bridge's reply line.
    async fn pair(client: &Endpoint, bridge: &EndpointAddr, token: &str) -> String {
        let conn = client.connect(bridge.clone(), ALPN_PAIR).await.unwrap();
        let (mut send, mut recv) = conn.open_bi().await.unwrap();
        send.write_all(token.as_bytes()).await.unwrap();
        send.finish().unwrap();
        let reply = timeout_io(recv.read_to_end(128)).await;
        conn.close(VarInt::from_u32(0), b"");
        String::from_utf8(reply).unwrap()
    }

    async fn timeout_io<T, E: std::fmt::Debug>(future: impl Future<Output = Result<T, E>>) -> T {
        tokio::time::timeout(SHORT, future)
            .await
            .expect("timed out")
            .expect("io error")
    }

    /// A refused dial either fails to connect or is closed by the bridge
    /// with the not-paired code before any stream can be opened.
    async fn assert_tunnel_refused(client: &Endpoint, bridge: &EndpointAddr) {
        match client.connect(bridge.clone(), ALPN_TUNNEL).await {
            Err(_) => {}
            Ok(conn) => {
                let closed = tokio::time::timeout(SHORT, conn.closed())
                    .await
                    .expect("refused connection closed in time");
                assert!(
                    matches!(
                        &closed,
                        ConnectionError::ApplicationClosed(close)
                            if close.error_code == VarInt::from_u32(CLOSE_NOT_PAIRED)
                    ),
                    "unexpected close: {closed:?}"
                );
            }
        }
    }

    /// Reproduces the window between the handshake hook and `serve_tunnel`:
    /// the connection is admitted, revoked before anything is tracked, and
    /// then `serve_tunnel` must still refuse to serve it.
    #[tokio::test]
    async fn tunnel_admitted_then_revoked_before_serving_is_closed() {
        let dir = scratch_dir("revoke-window");
        let pairing = Arc::new(Pairing::load(&dir, Duration::from_secs(600)).unwrap());
        let server = test_endpoint(SecretKey::generate(), &pairing).await;
        let server_addr = EndpointAddr::new(server.id()).with_ip_addr(server.bound_sockets()[0]);
        let client = client_endpoint().await;
        let token = pairing.issue_token("test");
        pairing.redeem(token.as_bytes(), client.id()).unwrap();

        let (client_conn, server_conn) = tokio::time::timeout(SHORT, async {
            tokio::join!(client.connect(server_addr, ALPN_TUNNEL), async {
                server.accept().await.unwrap().await.unwrap()
            })
        })
        .await
        .expect("handshake completes");
        let client_conn = client_conn.unwrap();
        assert!(pairing.revoke(&client.id()).unwrap());

        let target = Target {
            host: "127.0.0.1".into(),
            port: 1,
        };
        tokio::time::timeout(
            SHORT,
            serve_tunnel(server_conn, Arc::clone(&pairing), target),
        )
        .await
        .expect("serve_tunnel refuses a revoked connection instead of serving it");
        let closed = tokio::time::timeout(SHORT, client_conn.closed())
            .await
            .expect("client sees the close");
        assert!(
            matches!(
                &closed,
                ConnectionError::ApplicationClosed(close)
                    if close.error_code == VarInt::from_u32(CLOSE_REVOKED)
            ),
            "{closed:?}"
        );
    }

    #[tokio::test]
    async fn unknown_id_is_rejected_on_the_tunnel_alpn() {
        let bridge = spawn_bridge(1, Duration::from_secs(600)).await;
        let client = client_endpoint().await;
        assert_tunnel_refused(&client, &bridge.addr).await;
    }

    #[tokio::test]
    async fn unknown_id_can_pair_on_the_pair_alpn_and_is_then_accepted() {
        let echo = spawn_echo().await;
        let bridge = spawn_bridge(echo, Duration::from_secs(600)).await;
        let client = client_endpoint().await;
        let token = bridge.pairing.issue_token("test");
        assert_eq!(pair(&client, &bridge.addr, &token).await, "OK\n");
        assert!(bridge.pairing.is_allowed(&client.id()));

        let conn = client
            .connect(bridge.addr.clone(), ALPN_TUNNEL)
            .await
            .expect("paired phone connects");
        let (mut send, mut recv) = conn.open_bi().await.unwrap();
        send.write_all(b"ping").await.unwrap();
        send.finish().unwrap();
        assert_eq!(timeout_io(recv.read_to_end(64)).await, b"ping");
    }

    #[tokio::test]
    async fn bi_stream_round_trips_bytes_through_tcp_with_half_close() {
        let echo = spawn_echo().await;
        let bridge = spawn_bridge(echo, Duration::from_secs(600)).await;
        let client = client_endpoint().await;
        let token = bridge.pairing.issue_token("test");
        assert_eq!(pair(&client, &bridge.addr, &token).await, "OK\n");

        let conn = client
            .connect(bridge.addr.clone(), ALPN_TUNNEL)
            .await
            .unwrap();
        // Two streams on one connection, each one TCP connection behind it.
        for body in [
            b"first request".as_slice(),
            b"second, larger body".as_slice(),
        ] {
            let (mut send, mut recv) = conn.open_bi().await.unwrap();
            send.write_all(body).await.unwrap();
            send.finish().unwrap();
            // The echo server only replies after seeing our EOF, so reading
            // to the end proves the finish reached the TCP side.
            assert_eq!(timeout_io(recv.read_to_end(1 << 20)).await, body);
        }
    }

    #[tokio::test]
    async fn wrong_token_is_rejected_and_does_not_pair() {
        let bridge = spawn_bridge(1, Duration::from_secs(600)).await;
        let client = client_endpoint().await;
        let token = bridge.pairing.issue_token("test");
        let wrong_same_len = format!(
            "{}{}",
            &token[..63],
            if token.ends_with('0') { "1" } else { "0" }
        );
        assert_eq!(pair(&client, &bridge.addr, &wrong_same_len).await, "ERR\n");
        assert_eq!(pair(&client, &bridge.addr, "short").await, "ERR\n");
        assert!(!bridge.pairing.is_allowed(&client.id()));
        assert_tunnel_refused(&client, &bridge.addr).await;
    }

    #[tokio::test]
    async fn token_is_single_use() {
        let bridge = spawn_bridge(1, Duration::from_secs(600)).await;
        let token = bridge.pairing.issue_token("test");
        let first = client_endpoint().await;
        let second = client_endpoint().await;
        assert_eq!(pair(&first, &bridge.addr, &token).await, "OK\n");
        assert_eq!(pair(&second, &bridge.addr, &token).await, "ERR\n");
        assert!(!bridge.pairing.is_allowed(&second.id()));
    }

    #[tokio::test]
    async fn expired_token_is_rejected() {
        let bridge = spawn_bridge(1, Duration::ZERO).await;
        let token = bridge.pairing.issue_token("test");
        let client = client_endpoint().await;
        assert_eq!(pair(&client, &bridge.addr, &token).await, "ERR\n");
        assert!(!bridge.pairing.is_allowed(&client.id()));
    }

    #[tokio::test]
    async fn issuing_a_new_token_replaces_the_outstanding_one() {
        let bridge = spawn_bridge(1, Duration::from_secs(600)).await;
        let old = bridge.pairing.issue_token("test");
        let new = bridge.pairing.issue_token("test");
        let client = client_endpoint().await;
        assert_eq!(pair(&client, &bridge.addr, &old).await, "ERR\n");
        assert_eq!(pair(&client, &bridge.addr, &new).await, "OK\n");
    }

    #[tokio::test]
    async fn revoke_closes_a_live_connection_and_refuses_redial() {
        let echo = spawn_echo().await;
        let bridge = spawn_bridge(echo, Duration::from_secs(600)).await;
        let client = client_endpoint().await;
        let token = bridge.pairing.issue_token("test");
        assert_eq!(pair(&client, &bridge.addr, &token).await, "OK\n");

        let conn = client
            .connect(bridge.addr.clone(), ALPN_TUNNEL)
            .await
            .unwrap();
        let (mut send, mut recv) = conn.open_bi().await.unwrap();
        send.write_all(b"alive").await.unwrap();
        send.finish().unwrap();
        assert_eq!(timeout_io(recv.read_to_end(64)).await, b"alive");

        assert!(bridge.pairing.revoke(&client.id()).unwrap());
        let closed = tokio::time::timeout(SHORT, conn.closed())
            .await
            .expect("revocation closes the live connection");
        assert!(
            matches!(
                &closed,
                ConnectionError::ApplicationClosed(close)
                    if close.error_code == VarInt::from_u32(CLOSE_REVOKED)
            ),
            "{closed:?}"
        );
        assert_tunnel_refused(&client, &bridge.addr).await;
        assert!(!bridge.pairing.revoke(&client.id()).unwrap());
    }

    #[tokio::test]
    async fn pairing_survives_restart_and_key_file_is_owner_only() {
        let bridge = spawn_bridge(1, Duration::from_secs(600)).await;
        let token = bridge.pairing.issue_token("test");
        let client = client_endpoint().await;
        assert_eq!(pair(&client, &bridge.addr, &token).await, "OK\n");
        let reloaded = Pairing::load(&bridge.dir, Duration::from_secs(600)).unwrap();
        assert!(reloaded.is_allowed(&client.id()));

        let key_path = bridge.dir.join(SECRET_KEY_FILE);
        let first = load_or_create_secret_key(&key_path).unwrap();
        let second = load_or_create_secret_key(&key_path).unwrap();
        assert_eq!(first.public(), second.public());
        let mode = fs::metadata(&key_path).unwrap().permissions().mode();
        assert_eq!(mode & 0o777, 0o600);
        let allow_mode = fs::metadata(bridge.dir.join(ALLOWLIST_FILE))
            .unwrap()
            .permissions()
            .mode();
        assert_eq!(allow_mode & 0o777, 0o600);
    }

    // The admin client is blocking std I/O, so the server needs another worker.
    #[tokio::test(flavor = "multi_thread")]
    async fn admin_socket_issues_v1_payload_and_revokes() {
        let dir = scratch_dir("admin");
        let pairing = Arc::new(Pairing::load(&dir, Duration::from_secs(600)).unwrap());
        let endpoint = test_endpoint(SecretKey::generate(), &pairing).await;
        let hub = Arc::new(IrohHub {
            endpoint: endpoint.clone(),
            pairing: Arc::clone(&pairing),
            online_wait: Duration::ZERO,
        });
        let listener = bind_admin_socket(&dir.join(ADMIN_SOCKET_FILE)).unwrap();
        tokio::spawn(serve_admin(listener, Arc::clone(&hub)));

        let body = admin_request(&dir, "ISSUE").unwrap();
        let payload: serde_json::Value = serde_json::from_str(&body).unwrap();
        assert_eq!(payload["v"], 1);
        assert_eq!(payload["endpoint_id"], endpoint.id().to_string());
        let token = payload["token"].as_str().unwrap();
        assert_eq!(token.len(), 64);
        assert!(pairing
            .redeem(
                token.as_bytes(),
                EndpointId::from(SecretKey::generate().public())
            )
            .is_ok());

        let id = SecretKey::generate().public();
        assert_eq!(
            admin_request(&dir, &format!("REVOKE {id}")).unwrap(),
            "not-paired"
        );
        assert!(admin_request(&dir, "REVOKE nonsense").is_err());
        assert!(admin_request(&dir, "BOGUS").is_err());
    }

    #[tokio::test]
    async fn pair_route_is_absent_when_iroh_is_disabled_and_served_when_enabled() {
        let disabled = with_routes(Router::<()>::new(), None);
        let response = disabled
            .oneshot(
                Request::get("/pair")
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);

        let dir = scratch_dir("pair-page");
        let pairing = Arc::new(Pairing::load(&dir, Duration::from_secs(600)).unwrap());
        let endpoint = test_endpoint(SecretKey::generate(), &pairing).await;
        let hub = Arc::new(IrohHub {
            endpoint,
            pairing: Arc::clone(&pairing),
            online_wait: Duration::ZERO,
        });
        let enabled = with_routes(Router::<()>::new(), Some(hub));
        let response = enabled
            .oneshot(
                Request::get("/pair")
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()[header::CACHE_CONTROL], "no-store");
        let bytes = axum::body::to_bytes(response.into_body(), 1 << 20)
            .await
            .unwrap();
        let html = String::from_utf8(bytes.to_vec()).unwrap();
        assert!(html.contains("<svg"));
        assert!(
            !html.contains("<script") && !html.contains("src="),
            "no scripts or external assets"
        );
    }

    /// Pairs `client` with `bridge` using a fresh token.
    async fn pair_client(bridge: &TestBridge, client: &Endpoint) {
        let token = bridge.pairing.issue_token("test");
        assert_eq!(pair(client, &bridge.addr, &token).await, "OK\n");
    }

    /// One echo round trip on a fresh stream. Returning proves the server's
    /// connection handler is running, which is where connections are tracked.
    async fn ping(conn: &Connection) {
        let (mut send, mut recv) = timeout_io(conn.open_bi()).await;
        send.write_all(b"ping").await.unwrap();
        send.finish().unwrap();
        assert_eq!(timeout_io(recv.read_to_end(64)).await, b"ping");
    }

    /// A dial the bridge refuses either fails outright or is closed with `code`.
    async fn assert_refused_with<E: std::fmt::Debug>(dial: Result<Connection, E>, code: u32) {
        let Ok(conn) = dial else { return };
        let closed = tokio::time::timeout(SHORT, conn.closed())
            .await
            .expect("refused connection closed in time");
        assert!(
            matches!(
                &closed,
                ConnectionError::ApplicationClosed(close)
                    if close.error_code == VarInt::from_u32(code)
            ),
            "unexpected close: {closed:?}"
        );
    }

    #[tokio::test]
    async fn tunnel_streams_are_capped_per_connection() {
        let echo = spawn_echo().await;
        let bridge = spawn_bridge(echo, Duration::from_secs(600)).await;
        let client = client_endpoint().await;
        pair_client(&bridge, &client).await;
        let conn = client
            .connect(bridge.addr.clone(), ALPN_TUNNEL)
            .await
            .unwrap();

        // The echo stub replies only after EOF, so every stream stays open.
        let mut held = Vec::new();
        for _ in 0..MAX_TUNNEL_STREAMS {
            let (mut send, recv) = timeout_io(conn.open_bi()).await;
            send.write_all(b"held").await.unwrap();
            held.push((send, recv));
        }
        // The waiting open lives in its own task: dropping a pending open_bi
        // would leave the stream-credit queue in an unspecified state.
        let waiting = {
            let conn = conn.clone();
            tokio::spawn(async move { conn.open_bi().await })
        };
        tokio::time::sleep(Duration::from_millis(500)).await;
        assert!(
            !waiting.is_finished(),
            "stream {} opened beyond the cap",
            MAX_TUNNEL_STREAMS + 1
        );

        // QUIC returns stream credit in batches: one finished stream is not
        // enough to admit the waiting open, but a batch of them is.
        for (mut send, mut recv) in held.drain(..MAX_TUNNEL_STREAMS as usize / 2) {
            send.finish().unwrap();
            assert_eq!(timeout_io(recv.read_to_end(64)).await, b"held");
        }
        let (mut send, mut recv) = tokio::time::timeout(Duration::from_secs(10), waiting)
            .await
            .expect("waiting open admitted after a batch of streams finished")
            .expect("waiting open task")
            .expect("open_bi");
        send.write_all(b"after").await.unwrap();
        send.finish().unwrap();
        assert_eq!(timeout_io(recv.read_to_end(64)).await, b"after");
    }

    #[tokio::test]
    async fn tunnel_connections_are_capped_per_paired_id() {
        let echo = spawn_echo().await;
        let bridge = spawn_bridge(echo, Duration::from_secs(600)).await;
        let client = client_endpoint().await;
        pair_client(&bridge, &client).await;

        let mut live = Vec::new();
        for _ in 0..MAX_TUNNEL_CONNECTIONS_PER_ID {
            let conn = client
                .connect(bridge.addr.clone(), ALPN_TUNNEL)
                .await
                .unwrap();
            ping(&conn).await;
            live.push(conn);
        }
        let excess = client.connect(bridge.addr.clone(), ALPN_TUNNEL).await;
        assert_refused_with(excess, CLOSE_LIMIT).await;
        // The admitted connections are unaffected by the refused one.
        ping(&live[0]).await;
    }

    #[tokio::test]
    async fn tunnel_connections_are_capped_overall() {
        let echo = spawn_echo().await;
        let bridge = spawn_bridge(echo, Duration::from_secs(600)).await;
        let mut live = Vec::new();
        let mut clients = Vec::new();
        while live.len() < MAX_TUNNEL_CONNECTIONS {
            let client = client_endpoint().await;
            pair_client(&bridge, &client).await;
            for _ in 0..MAX_TUNNEL_CONNECTIONS_PER_ID.min(MAX_TUNNEL_CONNECTIONS - live.len()) {
                let conn = client
                    .connect(bridge.addr.clone(), ALPN_TUNNEL)
                    .await
                    .unwrap();
                ping(&conn).await;
                live.push(conn);
            }
            clients.push(client);
        }
        // Another paired id is refused once the bridge-wide cap is reached.
        let client = client_endpoint().await;
        pair_client(&bridge, &client).await;
        let excess = client.connect(bridge.addr.clone(), ALPN_TUNNEL).await;
        assert_refused_with(excess, CLOSE_LIMIT).await;
    }

    #[tokio::test]
    async fn pair_connections_are_capped_and_excess_is_refused() {
        let bridge = spawn_bridge(1, Duration::from_secs(600)).await;
        // Hold each pair connection without opening its exchange stream, so the
        // exchange waits and keeps its slot until the timeout.
        let mut held = Vec::new();
        for _ in 0..MAX_PAIR_CONNECTIONS {
            let client = client_endpoint().await;
            let conn = client
                .connect(bridge.addr.clone(), ALPN_PAIR)
                .await
                .unwrap();
            held.push((client, conn));
        }
        tokio::time::timeout(SHORT, async {
            while bridge.pairing.pair_slots.available_permits() > 0 {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .expect("all pair slots are taken");

        let excess_client = client_endpoint().await;
        let excess = excess_client.connect(bridge.addr.clone(), ALPN_PAIR).await;
        assert_refused_with(excess, CLOSE_PAIR_BUSY).await;
        let expected = format!(
            "limit_hit limit=pair_connections endpoint={}",
            excess_client.id()
        );
        assert!(bridge
            .pairing
            .audit_lines()
            .iter()
            .any(|line| *line == expected));
    }

    #[tokio::test]
    async fn pair_attempts_are_rate_limited_and_wrong_guesses_keep_the_token() {
        let bridge = spawn_bridge(1, Duration::from_secs(600)).await;
        let token = bridge.pairing.issue_token("test");
        let abuser = client_endpoint().await;
        for _ in 0..PAIR_ATTEMPTS_PER_WINDOW {
            assert_eq!(pair(&abuser, &bridge.addr, "wrong").await, "ERR\n");
        }
        // Over budget for this id: even the right token is refused.
        assert_eq!(pair(&abuser, &bridge.addr, &token).await, "ERR\n");
        assert!(!bridge.pairing.is_allowed(&abuser.id()));

        // The outstanding token survived every wrong guess and the refusal.
        let other = client_endpoint().await;
        assert_eq!(pair(&other, &bridge.addr, &token).await, "OK\n");
        assert!(bridge
            .pairing
            .audit_lines()
            .iter()
            .any(|line| line.contains(&format!(
                "limit_hit limit=pair_attempts endpoint={}",
                abuser.id()
            ))));
    }

    #[test]
    fn pair_attempt_budget_resets_after_the_window() {
        let id = EndpointId::from(SecretKey::generate().public());
        let mut attempts = BTreeMap::new();
        let start = Instant::now();
        for _ in 0..PAIR_ATTEMPTS_PER_WINDOW {
            assert!(admit_attempt(&mut attempts, id, start));
        }
        assert!(!admit_attempt(
            &mut attempts,
            id,
            start + Duration::from_secs(1)
        ));
        assert!(admit_attempt(
            &mut attempts,
            id,
            start + PAIR_ATTEMPT_WINDOW
        ));
    }

    #[tokio::test]
    async fn audit_log_records_events_and_never_contains_token_or_secret_key() {
        let key = SecretKey::generate();
        let key_bytes = key.to_bytes();
        let echo = spawn_echo().await;
        let bridge = spawn_bridge_with_key(echo, Duration::from_secs(600), key).await;

        let token = bridge.pairing.issue_token("cli");
        let mistaken = client_endpoint().await;
        assert_eq!(
            pair(&mistaken, &bridge.addr, "not-the-token").await,
            "ERR\n"
        );
        let paired = client_endpoint().await;
        assert_eq!(pair(&paired, &bridge.addr, &token).await, "OK\n");
        let conn = paired
            .connect(bridge.addr.clone(), ALPN_TUNNEL)
            .await
            .unwrap();
        ping(&conn).await;
        let stranger = client_endpoint().await;
        assert_tunnel_refused(&stranger, &bridge.addr).await;
        assert!(bridge.pairing.revoke(&paired.id()).unwrap());

        let lines = bridge.pairing.audit_lines();
        let expect = [
            "token_issued source=cli".to_string(),
            format!("pair_failed endpoint={} reason=mismatch", mistaken.id()),
            format!("pair_ok endpoint={}", paired.id()),
            format!(
                "tunnel_rejected endpoint={} reason=not_paired",
                stranger.id()
            ),
            format!("revoked endpoint={} was_paired=true closed=1", paired.id()),
        ];
        for event in &expect {
            assert!(
                lines.iter().any(|line| line.contains(event.as_str())),
                "missing audit event {event}: {lines:#?}"
            );
        }

        let secret_forms = [
            token.clone(),
            token.to_uppercase(),
            hex(&key_bytes),
            hex(&key_bytes).to_uppercase(),
            data_encoding::BASE32_NOPAD.encode(&key_bytes),
            data_encoding::BASE32_NOPAD
                .encode(&key_bytes)
                .to_lowercase(),
        ];
        for line in &lines {
            for secret in &secret_forms {
                assert!(
                    !line.contains(secret.as_str()),
                    "secret in audit line: {line}"
                );
            }
        }
    }
}
