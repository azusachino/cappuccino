# Public exposure via iroh — design proposal

Status: **proposed** (needs owner approval before implementation). Additive to
the existing tailscale-serve path; nothing in this doc removes it.

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
iroh transport whose remote EndpointId is on the bridge's allowlist and whose
pairing token matched.**

## Decision

Add **iroh** (n0-computer) as a second transport, coexisting with
tailscale-serve. iroh gives dial-by-EndpointId (ed25519 public key), QUIC
with TLS 1.3, NAT hole-punching with encrypted relay fallback, and
first-party Kotlin bindings for the phone. Android aarch64/armv7 are
supported by the bindings (currently built from source — CI owns that job).
Swift bindings are official, so a future iPhone app reuses the same design.

The bridge keeps speaking its existing HTTP/WS protocol on loopback. iroh is
a **tunnel**, not a protocol rewrite:

```text
phone (Kotlin)
  OkHttp → 127.0.0.1:<local port>
  local iroh endpoint (Kotlin FFI) ── QUIC stream, ALPN cappuccino/1 ──┐
                                                                       ▼
bridge iroh endpoint (Rust, in cappuccino-bridge)
  per-connection auth (allowlist + pairing token, constant-time)
  bi-stream ⇄ TCP pipe → 127.0.0.1:7392 (existing axum, unchanged)
```

`BridgeClient`, the bridge routes, and the behavior spec do not change. The
transport changes; the contract does not.

## Security model

In order of enforcement:

1. **Node allowlist.** The bridge persists the EndpointIds (ed25519 public
   keys) of paired phones and rejects every other dial in the accept hook —
   before any application bytes flow. Pairing adds one id.
2. **Pairing token.** A 32-byte random token, printed by the bridge at pair
   time and stored by the app in Android Keystore. The client proves it in
   the first stream after connect (challenge-less: token sent once, compared
   constant-time bridge-side). A leaked token without the phone's private
   key is unusable; a cloned private key without the token fails the hook.
3. **Transport identity.** iroh's QUIC TLS already authenticates the remote
   EndpointId, so a man-in-the-middle cannot sit between phone and bridge
   without the bridge's private key.
4. **Relays.** Public iroh relays may relay encrypted packets for traversal;
   they hold no keys and see no plaintext. A self-hosted relay with
   `access.allowlist` is the documented option for owners who want zero
   third-party involvement.
5. **Loopback only.** The axum server keeps binding 127.0.0.1. The iroh
   tunnel is the only path in; disabling iroh (`bridge.transport = "tailscale"`)
   returns the system to today's boundary — the kill switch.

Remaining hardening checklist (applies regardless of transport, tracked as
bridge issues): per-connection rate limit and body-size caps at the axum
layer, structured audit log of pairing and rejected dials, no tokens or keys
in logs, token rotation command (`cappuccino-bridge pair --revoke`).

## App changes

- New machine type "iroh": fields EndpointId (copy from bridge start output)
  - pairing token. Existing tailnet machines keep working untouched.
- One Kotlin iroh endpoint per app process (iroh's own recommendation),
  started on demand — when a machine list refresh or a connection needs it —
  and closed when idle; the private key persists in app-private storage.
- A small localhost listener bridges OkHttp ⇄ iroh bi-streams. OkHttp keeps
  its existing base-URL-per-machine model; an iroh machine's URL is the
  local listener.
- Android native library (aarch64/armv7) is built from source in CI
  (`cargo-ndk`) and shipped in the APK; pinned iroh version, bumped
  deliberately.

## Testing

- Bridge: hermetic tokio test with two in-process iroh endpoints — dial,
  auth-hook rejects unknown NodeId, accepts allowlisted with token, stream
  pipes to a stub TCP server. No network needed.
- App: unit tests for pairing storage and machine-type mapping; the existing
  UI journeys stay green because OkHttp targets localhost either way.
- One task-owned integration pass: phone on mobile data ↔ office machine.

## Risks

- iroh 0.9x is a canary series; 1.0 is announced as coming. We pin the
  current minor and re-evaluate at 1.0 before calling this stable.
- Android bindings build from source today — a CI job and a pinned
  toolchain, not a research project.
- Relay fallback adds latency when hole-punching fails; direct connections
  are the common case on real networks, and the status row already surfaces
  connection quality honestly.
- Two transports now exist. The machines list already shows per-machine
  connection state; each machine records which transport it uses.

## Alternatives considered

- **Tailscale Funnel + bearer auth.** Ships fastest, but it publishes the
  bridge to the hostile internet behind one token; the protocol was designed
  assuming a private network. Rejected as the primary path; the Phase-0
  hardening items remain worth doing under any transport.
- **Cloudflare Tunnel.** Moves TLS termination and policy to a third party
  and needs a public hostname — the opposite of the no-ingress goal.
- **Native QUIC protocol rewrite** (drop HTTP, speak the bridge protocol over
  iroh streams directly). Cleanest long-term, largest churn; revisit only if
  the tunnel proxy shows real overhead.

## Implementation slices (each independently shippable)

1. Bridge: iroh endpoint behind `bridge.transport` config, auth hook,
   stream→TCP pipe, pairing subcommand, hermetic tests.
2. CI: Android native library build job (`cargo-ndk`), artifact attached to
   the debug APK.
3. App: iroh machine type, pairing flow, local proxy listener, Keystore
   storage.
4. Hardening batch: rate limits, audit log, `pair --revoke`.
