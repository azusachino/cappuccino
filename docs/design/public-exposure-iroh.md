# Public exposure via iroh — design proposal

Status: **proposed** (needs owner approval before implementation). Additive to
the existing tailscale-serve path; nothing in this doc removes it.

**Amends accepted decisions.** This supersedes "No public ingress or cloud
relay" in the [intent](../intent.md) (owner, 2026-10-06) and the tailnet-only
boundary in the [architecture](../architecture.md): iroh adds an
EndpointId-authenticated path that may traverse n0's public relays. There is
still no public ingress port. Both documents are amended in the same change
as this proposal, so accepting it accepts the amendment.

## Goal

Reach configured Herdr machines from any network — office, travel, mobile
data — without a public ingress port, a public hostname, or terminating the
bridge's traffic at a third party, while keeping the bridge's HTTP protocol
and the apps' `BridgeClient` unchanged.

## Why the current boundary does not cover this

Today the bridge binds to loopback and `tailscale serve` exposes it only
inside the tailnet; the architecture doc names the tailnet as the security
boundary and forbids public ingress. That boundary is real but it means the
app only works on tailnet devices. The gap this doc closes: machines must be
reachable from networks we do not control, with a boundary we can state in
one sentence — **a connection is trusted if and only if it arrived over an
iroh transport whose remote EndpointId is on the bridge's allowlist**, and an
EndpointId reaches the allowlist only by presenting a one-time pairing token.

## Decision

Add **iroh** (n0-computer) as a second transport, coexisting with
tailscale-serve. iroh gives dial-by-EndpointId (ed25519 public key), QUIC
with TLS 1.3, NAT hole-punching with encrypted relay fallback, and
first-party Kotlin bindings for the phone, published on Maven Central as
`computer.iroh:iroh-android` with prebuilt native libraries. iroh is past 1.0
and keeps wire compatibility across 1.x minors (see
[iroh research](../research/iroh-best-practices.md)).
Swift bindings are official, so a future iPhone app reuses the same design.

The bridge keeps speaking its existing HTTP/WS protocol on loopback. iroh is
a **tunnel**, not a protocol rewrite:

```text
phone (Kotlin)
  OkHttp with an iroh SocketFactory (each Socket = one iroh bi-stream)
  local iroh endpoint (Kotlin FFI) ── QUIC stream, ALPN cappuccino/1 ──┐
                                                                       ▼
bridge iroh endpoint (Rust, in cappuccino-bridge)
  ALPN cappuccino/1: remote EndpointId must be on the allowlist
  ALPN cappuccino-pair/1: any EndpointId, one-time token exchange only
  bi-stream ⇄ TCP pipe → 127.0.0.1:7392 (existing axum, unchanged)
```

`BridgeClient`, the bridge routes, and the behavior spec do not change. The
transport changes; the contract does not. There is no phone-side TCP listener:
OkHttp's `SocketFactory` hands out sockets backed by iroh streams, so REST and
the WebSocket upgrade both ride the tunnel and no other app on the phone can
reach it.

## Threat model

In scope: hostile networks the phone sits on (hotel/café Wi-Fi, carrier NAT),
other apps on the same phone, and a compromised relay. Out of scope: a stolen
or lost phone — the key is as safe as the app sandbox, and revocation is the
answer, not storage hardening. Internet strangers are mooted by having no
ingress.

## Security model

In order of enforcement:

1. **Node allowlist.** The bridge persists the EndpointIds (ed25519 public
   keys) of paired phones and rejects every other dial on ALPN
   `cappuccino/1` in the accept hook — before any application bytes flow.
   Pairing adds one id. The hook runs only at handshake, so removing an id
   also closes that phone's live connections.
2. **One-time pairing token.** `cappuccino-bridge pair` prints a QR code
   (copy-paste fallback; the app clears the clipboard after use) carrying the
   bridge's full EndpointAddr (EndpointId plus relay URL, so the first dial
   needs no discovery) and a 32-byte random token. An unpaired phone dials
   the separate ALPN `cappuccino-pair/1`, which admits unknown EndpointIds
   but serves only the token exchange (n0's auth-hook example uses the same
   pre-auth-ALPN split). The token is compared constant-time; on a match the
   bridge adds the phone's EndpointId to the allowlist and discards the
   token. After pairing, trust rests on the EndpointId alone — no
   per-connection token.
3. **Transport identity.** iroh's QUIC TLS already authenticates the remote
   EndpointId, so a man-in-the-middle cannot sit between phone and bridge
   without the bridge's private key.
4. **Relays.** Ship on n0's public relays: they hold no keys and see no
   plaintext, only metadata (IPs, EndpointIds, timing, volume). Self-hosting
   behind a Cloudflare Tunnel is deferred to a measured spike: the tunnel
   carries no UDP, so the relay loses QUIC address discovery, and mobile
   paths may stay relayed. The spike compares direct-path rates on n0 versus
   a tunnelled relay before anything is committed.
5. **Loopback only.** The axum server keeps binding 127.0.0.1. The iroh
   tunnel is the only path in; disabling iroh (`iroh.enabled = false`, the
   default) returns the system to today's boundary — the kill switch. It sits
   beside the existing `serve_auto_apply`; the pairing allowlist is the
   authenticator the reserved `auth` section in `services/bridge/src/config.rs`
   was kept for, and its settings live there.

Remaining hardening checklist (applies regardless of transport, tracked as
bridge issues): per-connection rate limit and body-size caps at the axum
layer, structured audit log of pairing and rejected dials, no tokens or keys
in logs.

**Revocation.** `cappuccino-bridge pair --revoke <EndpointId>` removes a phone
from the allowlist and closes that phone's live connections; every revocation
is audit-logged. Revocation is CLI-only: phones get no control call, so the
HTTP contract and the two ALPNs above stay the whole protocol surface.

**Versioning.** ALPN `cappuccino/1`, bumped on breaking protocol changes;
bridge and app upgrade in lockstep. A mismatch fails the iroh dial cleanly and
the app falls back to tailnet.

## App changes

- A machine can carry both a tailnet URL and an iroh pairing (EndpointId).
  **Auto fallback: iroh first, then tailnet.** The transport that worked is
  kept until Android reports a network change or a request fails, then the
  order is re-probed. On redial the old iroh connection is closed first
  (a new connect can otherwise stay pinned to a dead path). Existing
  tailnet-only machines keep working untouched.
- iroh cannot see Android network changes itself: the app forwards
  `ConnectivityManager` callbacks to iroh's network-change hook, and calls
  `IrohAndroid.installAndroidContext` at startup so DNS resolves through the
  system resolver.
- A small badge per machine shows the active transport: tailnet, iroh direct
  or iroh relayed. Mobile paths flap between direct and relayed, so the badge
  and cadence switch only after a hold-down period, tuned from the spike.
- Poll cadence: 5 s on a direct path, backing off to 30 s while iroh reports
  the path as relayed. Push from bridge to phone is a separate future design.
- One Kotlin iroh endpoint per app process (iroh's own recommendation),
  started on demand and closed when idle. The ed25519 secret key is a plain
  file in the app-private `no_backup` directory (iroh-ffi needs raw bytes, so
  Keystore cannot hold it non-exportably).
- OkHttp gets a custom `SocketFactory` whose sockets are iroh bi-streams; no
  localhost listener exists.
- Background notifications follow the foreground order (iroh first) inside
  the existing `AgentNotificationService` foreground service. Each poll opens
  an iroh connection, makes its request and closes the connection, trading a
  handshake per poll for no long-lived background connection. This lands as
  its own slice after the foreground one, checked on a real device.
- The iroh transport sits behind a Kotlin interface, so the fallback state
  machine, pairing parsing and key-storage tests run on the JVM without
  loading the native library.
- Native library: the `computer.iroh:iroh-android` Maven artifact, pinned to
  a 1.x minor and bumped deliberately. It ships x86_64 as well as arm64, so
  emulator UI tests can load it. Building with `cargo-ndk` stays the fallback
  if a needed version is not published.

## Testing

- Bridge: hermetic tokio test with two in-process iroh endpoints — an
  unknown EndpointId is rejected on `cappuccino/1` but can pair on
  `cappuccino-pair/1`, pairing consumes the token exactly once, revocation
  closes a live connection, an allowlisted dial pipes to a stub TCP server.
  No network needed.
- App: unit tests for key storage, pairing payload parsing and the fallback
  state machine (order, stickiness, re-probe on network change); the existing
  UI journeys stay green because tailnet machines are unchanged.
- One task-owned integration pass: phone on mobile data ↔ office machine.

## Risks

- iroh-ffi exposes fewer knobs than iroh core: no GSO toggle, keep-alive or
  idle-timeout settings. An open upstream issue reports cellular uploads
  falling back to relay unless GSO is disabled. The spike measures this
  before slice 3.
- Android network-change handling through iroh-ffi is unverified at runtime;
  the spike covers it.
- Mobile paths flap (carrier NAT rebinding, IPv4/IPv6 switching); relayed
  periods add latency. Hold-down on the badge and cadence keeps that from
  thrashing the UI.
- Two transports now exist, with automatic fallback between them. The
  transport badge and the re-probe rules keep that debuggable.
- Background iroh use is the riskiest app code (lifetime, battery); the
  per-poll connection keeps it short, and it ships last among the app slices.

## Alternatives considered

- **Tailscale Funnel + bearer auth.** Ships fastest, but it publishes the
  bridge to the hostile internet behind one token; the protocol was designed
  assuming a private network. Rejected as the primary path; the Phase-0
  hardening items remain worth doing under any transport.
- **Cloudflare Tunnel.** Moves TLS termination and policy to a third party
  and needs a public hostname — the opposite of the no-ingress goal. (A
  tunnel in front of a self-hosted iroh relay is different: it carries only
  end-to-end-encrypted relay traffic; see Relays.)
- **Native QUIC protocol rewrite** (drop HTTP, speak the bridge protocol over
  iroh streams directly). Cleanest long-term, largest churn; revisit only if
  the tunnel proxy shows real overhead.

## Implementation slices (each independently shippable)

0. Spike (throwaway, real phone): iroh-android dial to a bridge stub;
   switch Wi-Fi ⇄ cellular and record reconnection, the network-change hook,
   GSO behaviour on cellular and direct/relayed flapping. Results set the
   hold-down values and confirm or change slice 3.
1. Bridge: iroh endpoint behind `iroh.enabled` (default off), allowlist hook on
   `cappuccino/1`, pairing on `cappuccino-pair/1`, stream→TCP pipe, `pair`
   subcommand with QR and one-time token, hermetic tests.
2. App dependency: pin `computer.iroh:iroh-android`, check APK size.
3. App foreground: iroh pairing (QR scan), `no_backup` key file, OkHttp
   iroh `SocketFactory`, network-change forwarding, iroh-first auto
   fallback, transport badge with hold-down, relayed-path poll back-off.
4. App background: per-poll iroh connection in `AgentNotificationService`,
   checked on a device.
5. Hardening batch: rate limits, body caps, audit log, `pair --revoke` (also
   closing live connections).
6. Relay spike: measure direct-path rates on n0 versus `iroh-relay` on
   harus-k3s behind a Cloudflare Tunnel; adopt only if the numbers hold
   (cross-repo with harus-k3s).
