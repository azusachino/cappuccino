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
iroh transport whose remote EndpointId is on the bridge's allowlist**, and an
EndpointId reaches the allowlist only by presenting a one-time pairing token.

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
  OkHttp with an iroh SocketFactory (each Socket = one iroh bi-stream)
  local iroh endpoint (Kotlin FFI) ── QUIC stream, ALPN cappuccino/1 ──┐
                                                                       ▼
bridge iroh endpoint (Rust, in cappuccino-bridge)
  accept hook: remote EndpointId must be on the allowlist
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
   keys) of paired phones and rejects every other dial in the accept hook —
   before any application bytes flow. Pairing adds one id.
2. **One-time pairing token.** `cappuccino-bridge pair` prints a QR code
   (copy-paste fallback; the app clears the clipboard after use) carrying the
   bridge EndpointId and a 32-byte random token. The phone presents the token
   once, compared constant-time; the bridge then adds the phone's EndpointId
   to the allowlist and discards the token. After pairing, trust rests on the
   EndpointId alone — no per-connection token.
3. **Transport identity.** iroh's QUIC TLS already authenticates the remote
   EndpointId, so a man-in-the-middle cannot sit between phone and bridge
   without the bridge's private key.
4. **Relays.** Ship on n0's public relays: they hold no keys and see no
   plaintext, only metadata (IPs, EndpointIds, timing, volume). Self-hosting
   comes later as its own slice: `iroh-relay` on harus-k3s behind a
   Cloudflare Tunnel (outbound only, WebSocket relay; no QUIC address
   discovery because the tunnel carries no UDP). That swaps n0 for Cloudflare
   as the metadata observer and gives us control of relay uptime and version.
5. **Loopback only.** The axum server keeps binding 127.0.0.1. The iroh
   tunnel is the only path in; disabling iroh (`bridge.transport = "tailscale"`)
   returns the system to today's boundary — the kill switch.

Remaining hardening checklist (applies regardless of transport, tracked as
bridge issues): per-connection rate limit and body-size caps at the axum
layer, structured audit log of pairing and rejected dials, no tokens or keys
in logs.

**Revocation.** `cappuccino-bridge pair --revoke <EndpointId>` removes a phone
from the allowlist. Any paired phone may also revoke any other paired phone
(not itself) through a bridge control call; every revocation is audit-logged.
A misused phone can at worst lock others out, which the bridge CLI undoes by
re-pairing.

**Versioning.** ALPN `cappuccino/1`, bumped on breaking protocol changes;
bridge and app upgrade in lockstep. A mismatch fails the iroh dial cleanly and
the app falls back to tailnet.

## App changes

- A machine can carry both a tailnet URL and an iroh pairing (EndpointId).
  **Auto fallback: iroh first, then tailnet.** The transport that worked is
  kept until Android reports a network change or a request fails, then the
  order is re-probed. Existing tailnet-only machines keep working untouched.
- A small badge per machine shows the active transport: tailnet, iroh direct
  or iroh relayed.
- Poll cadence: 5 s on a direct path, backing off to 30 s while iroh reports
  the path as relayed. Push from bridge to phone is a separate future design.
- One Kotlin iroh endpoint per app process (iroh's own recommendation),
  started on demand and closed when idle. The ed25519 secret key is a plain
  file in the app-private `no_backup` directory (iroh-ffi needs raw bytes, so
  Keystore cannot hold it non-exportably).
- OkHttp gets a custom `SocketFactory` whose sockets are iroh bi-streams; no
  localhost listener exists.
- Background notifications follow the foreground order (iroh first), which
  keeps an FFI endpoint alive from the background worker. This lands as its
  own slice after the foreground one, with a battery/lifetime check on a real
  device.
- Android native library (aarch64 only) is built from source in CI
  (`cargo-ndk`) and shipped in the APK; pinned iroh minor, bumped
  deliberately.

## Testing

- Bridge: hermetic tokio test with two in-process iroh endpoints — dial,
  accept hook rejects an unknown EndpointId, pairing consumes the token
  exactly once, an allowlisted dial pipes to a stub TCP server. No network
  needed.
- App: unit tests for key storage, pairing payload parsing and the fallback
  state machine (order, stickiness, re-probe on network change); the existing
  UI journeys stay green because tailnet machines are unchanged.
- One task-owned integration pass: phone on mobile data ↔ office machine.

## Risks

- iroh 0.9x is a canary series; 1.0 is announced as coming. We pin the
  current minor and re-evaluate at 1.0 before calling this stable.
- Android bindings build from source today — a CI job and a pinned
  toolchain, not a research project.
- Relay fallback adds latency when hole-punching fails; direct connections
  are the common case on real networks, and the status row already surfaces
  connection quality honestly.
- Two transports now exist, with automatic fallback between them. The
  transport badge and the re-probe rules keep that debuggable.
- The background worker holding an iroh endpoint is the riskiest code
  (lifetime, battery); it ships last among the app slices for that reason.

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

1. Bridge: iroh endpoint behind `bridge.transport` config, allowlist accept
   hook, stream→TCP pipe, `pair` subcommand with QR and one-time token,
   hermetic tests.
2. CI: Android native library build job (`cargo-ndk`, aarch64), artifact
   attached to the debug APK.
3. App foreground: iroh pairing (QR scan), `no_backup` key file, OkHttp
   iroh `SocketFactory`, iroh-first auto fallback, transport badge,
   relayed-path poll back-off.
4. App background: notifications over iroh, verified on a device for
   lifetime and battery.
5. Hardening batch: rate limits, body caps, audit log, `pair --revoke`, and
   revocation from another paired phone.
6. Self-hosted relay: `iroh-relay` on harus-k3s behind a Cloudflare Tunnel
   (cross-repo with harus-k3s).
