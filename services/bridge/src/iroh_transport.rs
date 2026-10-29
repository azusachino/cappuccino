//! iroh transport (opt-in, `iroh.enabled`): a QUIC tunnel into the loopback
//! HTTP server. Each accepted bi-stream on ALPN `cappuccino/1` is piped to a
//! fresh TCP connection to the bridge's own listener, so the HTTP contract is
//! unchanged. Trust is the EndpointId allowlist, enforced by an endpoint hook
//! at handshake; ALPN `cappuccino-pair/1` admits unknown ids but serves only a
//! pairing-code exchange. Design: docs/design/public-exposure-iroh.md.

use axum::{
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
    Extension, Router,
};
use hmac::{Hmac, Mac};
use iroh::{
    endpoint::{
        presets, AfterHandshakeOutcome, Connection, EndpointHooks, QuicTransportConfig, RecvStream,
        SendStream, VarInt, WeakConnectionHandle,
    },
    Endpoint, EndpointAddr, EndpointId, SecretKey, TransportAddr,
};
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use std::{
    collections::{BTreeMap, BTreeSet},
    fmt::Write as _,
    fs::{self, DirBuilder, OpenOptions, Permissions},
    future::Future,
    io::Write as _,
    os::unix::fs::{DirBuilderExt, OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
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
/// Length of a pairing code's window. The code rotates at each boundary.
pub const PAIR_WINDOW_SECS: u64 = 60;
/// Domain separator for the pairing code HMAC.
const PAIR_CODE_DOMAIN: &[u8] = b"cappuccino-pair/1";
/// A code is 64 hex characters; anything longer is refused before comparison.
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

/// Source of wall-clock seconds. Injected so window arithmetic is testable.
pub trait Clock: Send + Sync {
    fn unix_secs(&self) -> u64;
}

struct SystemClock;

impl Clock for SystemClock {
    fn unix_secs(&self) -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|elapsed| elapsed.as_secs())
            .unwrap_or(0)
    }
}

/// Allowlist and rotating pairing-code state. Both sit behind one lock so a
/// redemption is atomic with respect to revocation.
pub struct Pairing {
    allowlist_path: PathBuf,
    /// In-memory only: never persisted or logged. A restart invalidates codes.
    secret: [u8; 32],
    clock: Arc<dyn Clock>,
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
    /// Windows whose code a successful pairing has used. Only the current and
    /// previous windows can still be redeemed, so older entries are pruned.
    burned: BTreeSet<u64>,
    /// Admitted pairing attempts per remote id within PAIR_ATTEMPT_WINDOW.
    pair_attempts: BTreeMap<EndpointId, Vec<Instant>>,
}

/// Why a pairing exchange was refused. Only the variant name reaches the
/// audit log; the presented code never does.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PairFailure {
    /// Matches neither the current nor the previous window's code.
    Mismatch,
    /// Matches a code whose window a successful pairing already used.
    Burned,
    RateLimited,
    Persist,
}

/// What `GET /pair` and `pair` show for the current window.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CodeStatus {
    pub code: String,
    pub seconds_left: u64,
    pub burned: bool,
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

#[derive(Serialize, Deserialize)]
struct AllowlistFile {
    v: u32,
    endpoints: Vec<String>,
}

impl std::fmt::Debug for Pairing {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Pairing")
            .field("allowlist_path", &self.allowlist_path)
            .finish_non_exhaustive()
    }
}

/// Window index for a Unix time: the code changes every PAIR_WINDOW_SECS.
fn window_of(now: u64) -> u64 {
    now / PAIR_WINDOW_SECS
}

/// The pairing code for `window`: lowercase hex of
/// HMAC-SHA256(secret, PAIR_CODE_DOMAIN || window as u64 big-endian).
fn code_for(secret: &[u8; 32], window: u64) -> String {
    let mut mac = <Hmac<Sha256>>::new_from_slice(secret).expect("HMAC accepts any key length");
    mac.update(PAIR_CODE_DOMAIN);
    mac.update(&window.to_be_bytes());
    hex(&mac.finalize().into_bytes())
}

impl Pairing {
    /// Loads the persisted allowlist and starts a fresh in-memory pairing
    /// secret. A missing file is an empty allowlist; an unreadable or
    /// malformed one stops startup rather than admitting anyone.
    pub fn load(data_dir: &Path) -> Result<Self, String> {
        Self::load_with_clock(data_dir, Arc::new(SystemClock))
    }

    fn load_with_clock(data_dir: &Path, clock: Arc<dyn Clock>) -> Result<Self, String> {
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
            secret: rand::random(),
            clock,
            state: Mutex::new(PairingState {
                allowed,
                burned: BTreeSet::new(),
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

    /// The current window's code, its remaining lifetime, and whether a
    /// successful pairing already used it. Reads only; no state changes.
    pub fn code_status(&self) -> CodeStatus {
        let now = self.clock.unix_secs();
        let window = window_of(now);
        let burned = self.state.lock().unwrap().burned.contains(&window);
        CodeStatus {
            code: code_for(&self.secret, window),
            seconds_left: PAIR_WINDOW_SECS - now % PAIR_WINDOW_SECS,
            burned,
        }
    }

    /// Records that the operator read the code from the local CLI. The page
    /// view is not audited: it changes nothing and runs on every reload.
    pub fn audit_code_read(&self, source: &str) {
        let status = self.code_status();
        self.audit(format!(
            "code_read source={source} seconds_left={} burned={}",
            status.seconds_left, status.burned
        ));
    }

    /// Accepts the code of the current or previous window, compared
    /// constant-time against both. A match burns that window, so the same code
    /// cannot pair a second phone. A wrong guess burns nothing, and every
    /// attempt counts against the per-id rate limit.
    pub fn redeem(&self, presented: &[u8], id: EndpointId) -> Result<(), PairFailure> {
        let mut state = self.state.lock().unwrap();
        if !admit_attempt(&mut state.pair_attempts, id, Instant::now()) {
            drop(state);
            self.audit(format!("limit_hit limit=pair_attempts endpoint={id}"));
            return Err(PairFailure::RateLimited);
        }
        let window = window_of(self.clock.unix_secs());
        let Some(matched) = self.matching_window(presented, window) else {
            drop(state);
            self.audit(format!("pair_failed endpoint={id} reason=mismatch"));
            return Err(PairFailure::Mismatch);
        };
        if state.burned.contains(&matched) {
            drop(state);
            self.audit(format!("pair_failed endpoint={id} reason=burned"));
            return Err(PairFailure::Burned);
        }
        let mut next = state.allowed.clone();
        next.insert(id);
        // Persist before burning: a failed write leaves the code redeemable.
        if let Err(error) = persist_allowlist(&self.allowlist_path, &next) {
            drop(state);
            self.audit(format!("pair_failed endpoint={id} reason=persist_failed"));
            eprintln!("cappuccino-bridge: {error}");
            return Err(PairFailure::Persist);
        }
        state.allowed = next;
        state.burned.insert(matched);
        // Only the current and previous windows stay redeemable, so anything
        // older can be forgotten.
        state
            .burned
            .retain(|burned| burned.saturating_add(1) >= window);
        drop(state);
        self.audit(format!("pair_ok endpoint={id}"));
        Ok(())
    }

    /// The window whose code equals `presented`, among the current and the
    /// previous one. Both codes are always compared, so timing does not reveal
    /// which window matched.
    fn matching_window(&self, presented: &[u8], window: u64) -> Option<u64> {
        let mut matched = None;
        for candidate in [Some(window), window.checked_sub(1)].into_iter().flatten() {
            let code = code_for(&self.secret, candidate);
            let equal: bool = code.as_bytes().ct_eq(presented).into();
            if equal && matched.is_none() {
                matched = Some(candidate);
            }
        }
        matched
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
    /// The current window's pairing payload and status. Waits for the endpoint
    /// to be online so the payload carries the relay URL; if that takes too
    /// long the payload is still emitted with whatever addresses are known.
    /// The status is read after the wait, so it describes the window the
    /// payload was built for. Reads only: nothing is issued or recorded.
    pub async fn pairing_view(&self) -> (String, CodeStatus) {
        let _ = tokio::time::timeout(self.online_wait, self.endpoint.online()).await;
        let status = self.pairing.code_status();
        let payload = encode_payload(&self.endpoint.addr(), &status.code);
        (payload, status)
    }
}

/// Pairing payload, format v1 (compact JSON, same text in the QR and the
/// copy-paste line):
/// `{"v":1,"endpoint_id":"<id>","relay":"<url>|null","addrs":["ip:port"...],"token":"<64 hex>"}`
///
/// With a relay the direct addresses are omitted: the phone dials through the
/// relay and iroh finds the direct path after connecting, and the addresses
/// would make the QR several times larger. Without a relay (endpoint not yet
/// online) they are the only way in, so they stay.
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
    if relay.is_some() {
        addrs.clear();
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
pub async fn start(data_dir: &Path, target: Target) -> Result<Arc<IrohHub>, String> {
    ensure_private_dir(data_dir)?;
    let key = load_or_create_secret_key(&data_dir.join(SECRET_KEY_FILE))?;
    let pairing = Arc::new(Pairing::load(data_dir)?);
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

/// Creates the data dir owner-only (0700) when it is missing. An existing dir
/// keeps its mode; only missing ancestors take the default umask.
fn ensure_private_dir(path: &Path) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| format!("create data dir {}: {error}", path.display()))?;
    }
    match DirBuilder::new().mode(0o700).create(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => Ok(()),
        Err(error) => Err(format!("create data dir {}: {error}", path.display())),
    }
}

/// Unix socket path limit: macOS `sun_path` is 104 bytes including the NUL.
/// Checked explicitly so a long configured data dir fails with a clear error.
const UNIX_SOCKET_PATH_MAX: usize = 104;

fn bind_admin_socket(path: &Path) -> Result<UnixListener, String> {
    if path.as_os_str().len() >= UNIX_SOCKET_PATH_MAX {
        return Err(format!(
            "admin socket path {} is {} bytes; Unix socket paths must be under {UNIX_SOCKET_PATH_MAX} bytes, so set data_dir to a shorter path",
            path.display(),
            path.as_os_str().len()
        ));
    }
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
///   `CODE`             -> `OK {"payload":..|null,"seconds_left":N,"burned":bool}`
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
        None if request == "CODE" => {
            let (payload, status) = hub.pairing_view().await;
            hub.pairing.audit_code_read("cli");
            let body = serde_json::json!({
                "payload": (!status.burned).then_some(payload),
                "seconds_left": status.seconds_left,
                "burned": status.burned,
            });
            format!("OK {body}\n")
        }
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
    let (payload, status) = hub.pairing_view().await;
    let html = match render_pair_page(&payload, &status) {
        Ok(html) => html,
        Err(error) => return (StatusCode::INTERNAL_SERVER_ERROR, error).into_response(),
    };
    (
        [
            (header::CONTENT_TYPE, "text/html; charset=utf-8"),
            (header::CACHE_CONTROL, "no-store"),
        ],
        html,
    )
        .into_response()
}

/// Page CSS. The countdown is a CSS-only animation: `--left` is registered as
/// an integer where `@property` exists, so the counter ticks once a second.
/// Elsewhere `--left` stays at its unanimated value `--s`, so the page still
/// shows the number and the bar still moves. The bar uses `transform`, which
/// needs no registration.
const PAIR_PAGE_CSS: &str = r#"
body{font-family:system-ui,sans-serif;margin:0;padding:2rem 1.25rem;text-align:center}
main{max-width:40rem;margin:0 auto}
h1{font-size:1.5rem;margin:0 0 .5rem}
p{line-height:1.5}
svg{display:block;margin:1.25rem auto;width:min(80vw,22rem);height:auto;shape-rendering:crispEdges}
.countdown{font-size:1.75rem;font-weight:600;margin:.75rem 0}
.bar{height:4px;background:#e5e5e5;border-radius:2px;overflow:hidden;margin:0 0 1.5rem}
.fill{height:100%;background:#333;transform-origin:left;transform:scaleX(var(--f));animation:shrink linear forwards}
pre{text-align:left;white-space:pre-wrap;word-break:break-all;font-family:ui-monospace,monospace;font-size:.85rem;background:#f5f5f5;padding:.75rem;border-radius:6px}
@property --left{syntax:'<integer>';inherits:false;initial-value:0}
@keyframes tick{from{--left:var(--s)}to{--left:0}}
@keyframes shrink{from{transform:scaleX(var(--f))}to{transform:scaleX(0)}}
.tick{--left:var(--s);animation:tick linear forwards;counter-reset:left var(--left)}
.tick::after{content:counter(left)}
@media (prefers-reduced-motion:reduce){.tick,.fill{animation:none}}
"#;

/// The `/pair` page for one window. It reloads itself at the window boundary
/// with a meta refresh, and the countdown and bar are CSS-only animations
/// lasting exactly the seconds left (no script, no external assets). Once this
/// window's code has paired a phone, it shows that instead of a code.
pub fn render_pair_page(payload: &str, status: &CodeStatus) -> Result<String, String> {
    let seconds = status.seconds_left;
    let fraction = seconds as f64 / PAIR_WINDOW_SECS as f64;
    let countdown = format!(
        "<span class=\"tick\" style=\"--s:{seconds};animation-duration:{seconds}s\"></span>"
    );
    let bar = format!(
        "<div class=\"bar\"><div class=\"fill\" \
         style=\"--f:{fraction:.4};animation-duration:{seconds}s\"></div></div>"
    );
    let body = if status.burned {
        format!("<h1>Paired</h1><p class=\"countdown\">New code in {countdown} s</p>{bar}")
    } else {
        format!(
            "<h1>Pair a phone</h1>\
             <p>Scan with the Cappuccino app, or copy the text below. The code changes every \
             minute; a code still works for a minute after it changes. Each code pairs one \
             phone.</p>\
             {svg}<p class=\"countdown\">New code in {countdown} s</p>{bar}<pre>{payload}</pre>",
            svg = qr_svg(payload)?,
            payload = html_escape(payload),
        )
    };
    Ok(format!(
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>Cappuccino pairing</title>\
         <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\
         <meta http-equiv=\"refresh\" content=\"{seconds}\">\
         <style>{PAIR_PAGE_CSS}</style>\
         </head><body><main>{body}</main></body></html>"
    ))
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

/// Quiet zone in modules on each side. One module keeps a 60-column terminal
/// enough for the typical pairing payload; the ISO default of four is not
/// needed for a high-contrast terminal rendering.
const TERMINAL_QUIET_ZONE: usize = 1;

/// Terminal rendering for `pair`. Error correction L: the code is shown once
/// on a screen, not printed, so the smallest symbol wins over damage tolerance.
pub fn qr_terminal(payload: &str) -> Result<String, String> {
    let code = qrcode::QrCode::with_error_correction_level(payload.as_bytes(), qrcode::EcLevel::L)
        .map_err(|error| format!("qr encode: {error}"))?;
    Ok(render_half_blocks(&code, TERMINAL_QUIET_ZONE))
}

/// Half-block rendering: each text line covers two module rows.
fn render_half_blocks(code: &qrcode::QrCode, quiet: usize) -> String {
    let width = code.width();
    let colors = code.to_colors();
    let total = width + 2 * quiet;
    let dark = |col: usize, row: usize| -> bool {
        let (Some(x), Some(y)) = (col.checked_sub(quiet), row.checked_sub(quiet)) else {
            return false;
        };
        x < width && y < width && colors[y * width + x] == qrcode::Color::Dark
    };
    let mut out = String::new();
    for row in (0..total).step_by(2) {
        for col in 0..total {
            out.push(match (dark(col, row), dark(col, row + 1)) {
                (true, true) => '█',
                (true, false) => '▀',
                (false, true) => '▄',
                (false, false) => ' ',
            });
        }
        out.push('\n');
    }
    out
}

/// Widest rendered line, in terminal columns (every glyph here is one column).
pub fn display_width(text: &str) -> usize {
    text.lines()
        .map(|line| line.chars().count())
        .max()
        .unwrap_or(0)
}

/// Terminal width: the stdout TIOCGWINSZ size, else `COLUMNS`. None when
/// neither is known, so the caller simply prints without a hint.
fn terminal_columns() -> Option<usize> {
    let mut size = std::mem::MaybeUninit::<libc::winsize>::zeroed();
    // SAFETY: TIOCGWINSZ writes one winsize into the buffer on success.
    let ok = unsafe { libc::ioctl(libc::STDOUT_FILENO, libc::TIOCGWINSZ, size.as_mut_ptr()) };
    if ok == 0 {
        let columns = unsafe { size.assume_init() }.ws_col;
        if columns > 0 {
            return Some(columns as usize);
        }
    }
    std::env::var("COLUMNS").ok()?.trim().parse().ok()
}

/// The one-line hint printed before a QR that will not fit the terminal.
pub fn narrow_terminal_hint(qr_width: usize, columns: Option<usize>) -> Option<&'static str> {
    match columns {
        Some(columns) if qr_width > columns => {
            Some("Terminal is narrower than the QR; widen it or open /pair on a tailnet device.")
        }
        _ => None,
    }
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
            let reply = admin_request(&config.data_dir, "CODE")?;
            let view: serde_json::Value = serde_json::from_str(&reply)
                .map_err(|error| format!("bridge sent an unreadable code: {error}"))?;
            let seconds = view["seconds_left"].as_u64().unwrap_or_default();
            match view["payload"].as_str() {
                Some(payload) => {
                    let qr = qr_terminal(payload)?;
                    if let Some(hint) = narrow_terminal_hint(display_width(&qr), terminal_columns())
                    {
                        println!("{hint}\n");
                    }
                    println!("{qr}");
                    println!("{payload}\n");
                    println!(
                        "valid for about {seconds} s (rotates every {PAIR_WINDOW_SECS} s; \
                         the previous code is accepted for one more window)"
                    );
                }
                None => {
                    println!("this code already paired a phone; a new code in about {seconds} s")
                }
            }
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

    /// A clock the test moves by hand. Starts mid-window (30 s before the
    /// boundary) so the boundary tests have room on either side.
    struct ManualClock(AtomicU64);

    impl ManualClock {
        const START: u64 = 1_800_000_030;

        fn new() -> Arc<Self> {
            Arc::new(ManualClock(AtomicU64::new(Self::START)))
        }

        fn advance(&self, secs: u64) {
            self.0.fetch_add(secs, Ordering::SeqCst);
        }
    }

    impl Clock for ManualClock {
        fn unix_secs(&self) -> u64 {
            self.0.load(Ordering::SeqCst)
        }
    }

    struct TestBridge {
        pairing: Arc<Pairing>,
        addr: EndpointAddr,
        dir: PathBuf,
        clock: Arc<ManualClock>,
    }

    /// The pairing code the bridge shows right now.
    fn code(bridge: &TestBridge) -> String {
        bridge.pairing.code_status().code
    }

    /// A bridge endpoint with direct-address dialing only (no relays, no
    /// discovery), tunnelling into `port` on loopback.
    async fn spawn_bridge(port: u16) -> TestBridge {
        spawn_bridge_with_key(port, SecretKey::generate()).await
    }

    async fn spawn_bridge_with_key(port: u16, key: SecretKey) -> TestBridge {
        let dir = scratch_dir("bridge");
        let clock = ManualClock::new();
        let pairing = Arc::new(Pairing::load_with_clock(&dir, clock.clone()).unwrap());
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
        TestBridge {
            pairing,
            addr,
            dir,
            clock,
        }
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
        let pairing = Arc::new(Pairing::load(&dir).unwrap());
        let server = test_endpoint(SecretKey::generate(), &pairing).await;
        let server_addr = EndpointAddr::new(server.id()).with_ip_addr(server.bound_sockets()[0]);
        let client = client_endpoint().await;
        let token = pairing.code_status().code;
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
        let bridge = spawn_bridge(1).await;
        let client = client_endpoint().await;
        assert_tunnel_refused(&client, &bridge.addr).await;
    }

    #[tokio::test]
    async fn unknown_id_can_pair_on_the_pair_alpn_and_is_then_accepted() {
        let echo = spawn_echo().await;
        let bridge = spawn_bridge(echo).await;
        let client = client_endpoint().await;
        let token = code(&bridge);
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
        let bridge = spawn_bridge(echo).await;
        let client = client_endpoint().await;
        let token = code(&bridge);
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
        let bridge = spawn_bridge(1).await;
        let client = client_endpoint().await;
        let token = code(&bridge);
        let wrong_same_len = format!(
            "{}{}",
            &token[..63],
            if token.ends_with('0') { "1" } else { "0" }
        );
        assert_eq!(pair(&client, &bridge.addr, &wrong_same_len).await, "ERR\n");
        assert_eq!(pair(&client, &bridge.addr, "short").await, "ERR\n");
        assert!(!bridge.pairing.is_allowed(&client.id()));
        assert_tunnel_refused(&client, &bridge.addr).await;
        // Wrong guesses burn nothing: the window's real code still pairs.
        assert!(!bridge.pairing.code_status().burned);
        assert_eq!(pair(&client, &bridge.addr, &token).await, "OK\n");
    }

    #[tokio::test]
    async fn token_is_single_use() {
        let bridge = spawn_bridge(1).await;
        let token = code(&bridge);
        let first = client_endpoint().await;
        let second = client_endpoint().await;
        assert_eq!(pair(&first, &bridge.addr, &token).await, "OK\n");
        assert_eq!(pair(&second, &bridge.addr, &token).await, "ERR\n");
        assert!(!bridge.pairing.is_allowed(&second.id()));
    }

    #[tokio::test]
    async fn code_is_stable_within_a_window_and_rotates_at_the_boundary() {
        let bridge = spawn_bridge(1).await;
        let first = code(&bridge);
        assert_eq!(first.len(), 64);
        bridge.clock.advance(20);
        assert_eq!(code(&bridge), first, "same window, same code");
        // Start was 30 s before the boundary, so 20 s more is still inside.
        bridge.clock.advance(10);
        assert_ne!(code(&bridge), first, "next window, next code");
    }

    #[tokio::test]
    async fn previous_window_code_is_accepted_for_one_more_window() {
        let bridge = spawn_bridge(1).await;
        let previous = code(&bridge);
        bridge.clock.advance(30);
        let client = client_endpoint().await;
        assert_eq!(pair(&client, &bridge.addr, &previous).await, "OK\n");
        assert!(bridge.pairing.is_allowed(&client.id()));
    }

    #[tokio::test]
    async fn code_from_two_windows_ago_is_rejected() {
        let bridge = spawn_bridge(1).await;
        let old = code(&bridge);
        bridge.clock.advance(90);
        let client = client_endpoint().await;
        assert_eq!(pair(&client, &bridge.addr, &old).await, "ERR\n");
        assert!(!bridge.pairing.is_allowed(&client.id()));
    }

    #[tokio::test]
    async fn burned_code_is_rejected_and_the_next_window_code_works() {
        let bridge = spawn_bridge(1).await;
        let used = code(&bridge);
        let first = client_endpoint().await;
        assert_eq!(pair(&first, &bridge.addr, &used).await, "OK\n");
        let second = client_endpoint().await;
        assert_eq!(pair(&second, &bridge.addr, &used).await, "ERR\n");
        assert!(!bridge.pairing.is_allowed(&second.id()));
        assert!(bridge.pairing.code_status().burned);

        bridge.clock.advance(30);
        assert!(!bridge.pairing.code_status().burned);
        let next = code(&bridge);
        assert_eq!(pair(&second, &bridge.addr, &next).await, "OK\n");
    }

    #[tokio::test]
    async fn burned_code_stays_rejected_while_its_window_is_still_redeemable() {
        // Burn window W, move to W+1: W's code is still inside the grace
        // window, so it must stay rejected rather than pair a second phone.
        let bridge = spawn_bridge(1).await;
        let used = code(&bridge);
        let first = client_endpoint().await;
        assert_eq!(pair(&first, &bridge.addr, &used).await, "OK\n");
        bridge.clock.advance(30);
        let second = client_endpoint().await;
        assert_eq!(pair(&second, &bridge.addr, &used).await, "ERR\n");
    }

    #[tokio::test]
    async fn revoke_closes_a_live_connection_and_refuses_redial() {
        let echo = spawn_echo().await;
        let bridge = spawn_bridge(echo).await;
        let client = client_endpoint().await;
        let token = code(&bridge);
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
        let bridge = spawn_bridge(1).await;
        let token = code(&bridge);
        let client = client_endpoint().await;
        assert_eq!(pair(&client, &bridge.addr, &token).await, "OK\n");
        let reloaded = Pairing::load(&bridge.dir).unwrap();
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
        let pairing = Arc::new(Pairing::load_with_clock(&dir, ManualClock::new()).unwrap());
        let endpoint = test_endpoint(SecretKey::generate(), &pairing).await;
        let hub = Arc::new(IrohHub {
            endpoint: endpoint.clone(),
            pairing: Arc::clone(&pairing),
            online_wait: Duration::ZERO,
        });
        let listener = bind_admin_socket(&dir.join(ADMIN_SOCKET_FILE)).unwrap();
        tokio::spawn(serve_admin(listener, Arc::clone(&hub)));

        let body = admin_request(&dir, "CODE").unwrap();
        let view: serde_json::Value = serde_json::from_str(&body).unwrap();
        assert_eq!(view["burned"], false);
        let seconds = view["seconds_left"].as_u64().unwrap();
        assert!((1..=PAIR_WINDOW_SECS).contains(&seconds), "{seconds}");
        let payload: serde_json::Value =
            serde_json::from_str(view["payload"].as_str().unwrap()).unwrap();
        assert_eq!(payload["v"], 1);
        assert_eq!(payload["endpoint_id"], endpoint.id().to_string());
        let code = payload["token"].as_str().unwrap();
        assert_eq!(code.len(), 64);
        assert!(pairing
            .redeem(
                code.as_bytes(),
                EndpointId::from(SecretKey::generate().public())
            )
            .is_ok());
        // Reading the code is a read: after the redemption it reports burned
        // and carries no payload.
        let body = admin_request(&dir, "CODE").unwrap();
        let view: serde_json::Value = serde_json::from_str(&body).unwrap();
        assert_eq!(view["burned"], true);
        assert!(view["payload"].is_null());

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
        let pairing = Arc::new(Pairing::load(&dir).unwrap());
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
        let token = code(&bridge);
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
        let bridge = spawn_bridge(echo).await;
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
        let bridge = spawn_bridge(echo).await;
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
        let bridge = spawn_bridge(echo).await;
        let mut live = Vec::new();
        let mut clients = Vec::new();
        while live.len() < MAX_TUNNEL_CONNECTIONS {
            let client = client_endpoint().await;
            bridge.clock.advance(PAIR_WINDOW_SECS);
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
        bridge.clock.advance(PAIR_WINDOW_SECS);
        pair_client(&bridge, &client).await;
        let excess = client.connect(bridge.addr.clone(), ALPN_TUNNEL).await;
        assert_refused_with(excess, CLOSE_LIMIT).await;
    }

    #[tokio::test]
    async fn pair_connections_are_capped_and_excess_is_refused() {
        let bridge = spawn_bridge(1).await;
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
        let bridge = spawn_bridge(1).await;
        let token = code(&bridge);
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
    async fn audit_log_records_events_and_never_contains_codes_or_secrets() {
        let key = SecretKey::generate();
        let key_bytes = key.to_bytes();
        let echo = spawn_echo().await;
        let bridge = spawn_bridge_with_key(echo, key).await;

        let shown = code(&bridge);
        bridge.pairing.audit_code_read("cli");
        let mistaken = client_endpoint().await;
        assert_eq!(pair(&mistaken, &bridge.addr, "not-the-code").await, "ERR\n");
        let paired = client_endpoint().await;
        assert_eq!(pair(&paired, &bridge.addr, &shown).await, "OK\n");
        let reused = client_endpoint().await;
        assert_eq!(pair(&reused, &bridge.addr, &shown).await, "ERR\n");
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
            "code_read source=cli".to_string(),
            format!("pair_failed endpoint={} reason=mismatch", mistaken.id()),
            format!("pair_ok endpoint={}", paired.id()),
            format!("pair_failed endpoint={} reason=burned", reused.id()),
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

        // Neither the displayed code (in any window) nor the pairing secret,
        // nor the bridge's iroh key, may appear in any audit line.
        let pairing_secret = bridge.pairing.secret;
        let secret_forms = [
            shown.clone(),
            shown.to_uppercase(),
            hex(&pairing_secret),
            hex(&pairing_secret).to_uppercase(),
            data_encoding::BASE32_NOPAD.encode(&pairing_secret),
            data_encoding::BASE32_NOPAD
                .encode(&pairing_secret)
                .to_lowercase(),
            hex(&key_bytes),
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

    async fn get_pair_page(app: &Router) -> String {
        let response = app
            .clone()
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
        String::from_utf8(bytes.to_vec()).unwrap()
    }

    #[tokio::test]
    async fn pair_page_is_idempotent_within_a_window_and_refreshes_itself() {
        let dir = scratch_dir("pair-page-window");
        let clock = ManualClock::new();
        let pairing = Arc::new(Pairing::load_with_clock(&dir, clock.clone()).unwrap());
        let endpoint = test_endpoint(SecretKey::generate(), &pairing).await;
        let hub = Arc::new(IrohHub {
            endpoint,
            pairing: Arc::clone(&pairing),
            online_wait: Duration::ZERO,
        });
        let app = with_routes(Router::<()>::new(), Some(hub));
        let shown = pairing.code_status().code;
        let audit_before = pairing.audit_lines();

        let first = get_pair_page(&app).await;
        let second = get_pair_page(&app).await;
        assert_eq!(
            first, second,
            "two loads in one window render the same page"
        );
        assert!(first.contains(&shown));
        assert!(first.contains("<meta http-equiv=\"refresh\" content=\"30\">"));
        assert!(first.contains("<svg"));
        assert!(first.contains("--s:30;animation-duration:30s"));
        assert!(first.contains("--f:0.5000;animation-duration:30s"));
        assert!(!first.contains("<script"), "no script on the pairing page");
        assert!(!first.contains("@supports"), "no feature gate");
        assert!(!first.contains("static"), "no fallback span to hide");
        assert!(first.contains("a code still works for a minute after it changes"));
        // counter-reset must sit on the element that animates --left (inherits
        // is false, so the ::after child would see the initial 0).
        assert!(first.contains(
            ".tick{--left:var(--s);animation:tick linear forwards;counter-reset:left var(--left)}"
        ));
        assert!(first.contains(".tick::after{content:counter(left)}"));
        assert!(!first.contains("::after{content:counter(left);counter-reset"));
        assert_eq!(
            pairing.audit_lines(),
            audit_before,
            "viewing the page writes no audit line"
        );

        clock.advance(10);
        let later = get_pair_page(&app).await;
        assert!(
            later.contains("content=\"20\""),
            "countdown follows the clock"
        );
        assert!(later.contains(&shown));
        assert!(later.contains("--s:20;animation-duration:20s"));

        clock.advance(20);
        let next_window = get_pair_page(&app).await;
        assert!(next_window.contains("content=\"60\""));
        assert!(
            !next_window.contains(&shown),
            "a new window shows a new code"
        );
    }

    #[tokio::test]
    async fn pair_page_shows_paired_once_the_window_code_is_used() {
        let dir = scratch_dir("pair-page-burned");
        let clock = ManualClock::new();
        let pairing = Arc::new(Pairing::load_with_clock(&dir, clock.clone()).unwrap());
        let endpoint = test_endpoint(SecretKey::generate(), &pairing).await;
        let hub = Arc::new(IrohHub {
            endpoint,
            pairing: Arc::clone(&pairing),
            online_wait: Duration::ZERO,
        });
        let app = with_routes(Router::<()>::new(), Some(hub));
        let shown = pairing.code_status().code;
        pairing
            .redeem(
                shown.as_bytes(),
                EndpointId::from(SecretKey::generate().public()),
            )
            .unwrap();

        let page = get_pair_page(&app).await;
        assert!(page.contains("<h1>Paired</h1>"));
        assert!(page.contains("New code in <span class=\"tick\""));
        assert!(!page.contains("static"), "no fallback span to hide");
        assert!(!page.contains("<svg"), "no QR for a used code");
        assert!(!page.contains(&shown));
        assert!(page.contains("content=\"30\""));
        assert!(
            page.contains("--s:30;animation-duration:30s"),
            "burned countdown animates"
        );
        assert!(!page.contains("<script"));
    }
}

#[cfg(test)]
mod pairing_payload_tests {
    use super::*;

    const RELAY: &str = "https://aps1-1.relay.n0.iroh-canary.iroh.link./";

    /// Deterministic endpoint: a fixed seed keeps the QR assertions stable.
    fn addr_for_seed(seed: u8, with_relay: bool) -> EndpointAddr {
        let mut addr = EndpointAddr::new(SecretKey::from_bytes(&[seed; 32]).public());
        if with_relay {
            addr = addr.with_relay_url(RELAY.parse().unwrap());
        }
        // Six direct addresses, including long IPv6 ones, as a real bridge
        // reports. The relay-present payload must drop all of them.
        for socket in [
            "192.168.1.23:52345",
            "10.0.0.4:52345",
            "[2408:8207:1234:5678:abcd:ef01:2345:6789]:52345",
            "[2408:8207:1234:5678:abcd:ef01:2345:6790]:52345",
            "[fd7a:115c:a1e0::1234:5678]:52345",
            "[fd7a:115c:a1e0::9abc:def0]:52345",
        ] {
            addr = addr.with_ip_addr(socket.parse().unwrap());
        }
        addr
    }

    fn payload_json(addr: &EndpointAddr) -> serde_json::Value {
        serde_json::from_str(&encode_payload(addr, &"c".repeat(64))).unwrap()
    }

    #[test]
    fn payload_omits_direct_addresses_when_a_relay_is_present() {
        let json = payload_json(&addr_for_seed(7, true));
        assert_eq!(json["v"], 1);
        assert_eq!(json["relay"], RELAY);
        assert_eq!(json["addrs"], serde_json::json!([]));
        assert_eq!(json["token"], "c".repeat(64));
        assert_eq!(json["endpoint_id"].as_str().unwrap().len(), 64);
    }

    #[test]
    fn payload_keeps_direct_addresses_when_no_relay_is_known() {
        let json = payload_json(&addr_for_seed(7, false));
        assert!(json["relay"].is_null());
        assert_eq!(json["addrs"].as_array().unwrap().len(), 6);
    }

    #[test]
    fn terminal_qr_for_realistic_payloads_fits_sixty_columns() {
        // The crate picks the smallest version per payload; digit runs in a
        // hex id can save bits, so widths vary by key. Sweep several keys.
        for seed in 0..64 {
            let payload = encode_payload(&addr_for_seed(seed, true), &"c".repeat(64));
            let qr = qr_terminal(&payload).unwrap();
            let code =
                qrcode::QrCode::with_error_correction_level(payload.as_bytes(), qrcode::EcLevel::L)
                    .unwrap();
            let total = code.width() + 2 * TERMINAL_QUIET_ZONE;
            assert_eq!(display_width(&qr), total, "seed {seed}");
            assert!(total <= 60, "seed {seed}: QR is {total} columns wide");
            assert_eq!(qr.lines().count(), total.div_ceil(2), "seed {seed}");
        }
    }

    #[test]
    fn narrow_terminal_hint_only_when_the_qr_does_not_fit() {
        assert!(narrow_terminal_hint(59, Some(80)).is_none());
        assert!(narrow_terminal_hint(59, Some(59)).is_none());
        assert!(narrow_terminal_hint(59, Some(58)).is_some());
        assert!(narrow_terminal_hint(59, None).is_none());
    }

    #[test]
    fn terminal_qr_carries_the_level_l_modules_of_the_payload() {
        let payload = encode_payload(&addr_for_seed(7, true), &"c".repeat(64));
        let code =
            qrcode::QrCode::with_error_correction_level(payload.as_bytes(), qrcode::EcLevel::L)
                .unwrap();
        let width = code.width();
        // Parse the half-block text back into a module grid and compare.
        let qr = render_half_blocks(&code, TERMINAL_QUIET_ZONE);
        let lines: Vec<Vec<char>> = qr.lines().map(|line| line.chars().collect()).collect();
        let colors = code.to_colors();
        for y in 0..width {
            for x in 0..width {
                let padded_y = y + TERMINAL_QUIET_ZONE;
                let glyph = lines[padded_y / 2][x + TERMINAL_QUIET_ZONE];
                let dark = if padded_y % 2 == 0 {
                    matches!(glyph, '█' | '▀')
                } else {
                    matches!(glyph, '█' | '▄')
                };
                assert_eq!(
                    dark,
                    colors[y * width + x] == qrcode::Color::Dark,
                    "module ({x}, {y})"
                );
            }
        }
    }
}

#[cfg(test)]
mod data_dir_tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    fn fresh_parent(name: &str) -> PathBuf {
        static COUNTER: AtomicU64 = AtomicU64::new(0);
        let unique = COUNTER.fetch_add(1, Ordering::Relaxed);
        let dir = std::env::temp_dir().join(format!(
            "cap-datadir-{name}-{}-{unique}",
            std::process::id()
        ));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn missing_data_dir_is_created_owner_only() {
        let parent = fresh_parent("missing");
        let data_dir = parent.join("cappuccino-bridge");
        ensure_private_dir(&data_dir).unwrap();
        let mode = fs::metadata(&data_dir).unwrap().permissions().mode();
        assert_eq!(mode & 0o777, 0o700);
    }

    #[test]
    fn existing_data_dir_keeps_its_mode() {
        let parent = fresh_parent("existing");
        let data_dir = parent.join("cappuccino-bridge");
        fs::create_dir(&data_dir).unwrap();
        fs::set_permissions(&data_dir, Permissions::from_mode(0o755)).unwrap();
        ensure_private_dir(&data_dir).unwrap();
        let mode = fs::metadata(&data_dir).unwrap().permissions().mode();
        assert_eq!(mode & 0o777, 0o755);
    }

    #[test]
    fn overlong_admin_socket_path_fails_with_a_clear_error() {
        let long = format!("/{}/iroh-admin.sock", "d".repeat(100));
        let error = bind_admin_socket(Path::new(&long)).unwrap_err();
        assert!(
            error.contains("Unix socket paths must be under 104 bytes"),
            "{error}"
        );
        assert!(error.contains("set data_dir to a shorter path"), "{error}");
    }
}
