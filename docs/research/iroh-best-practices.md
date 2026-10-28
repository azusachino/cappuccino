# iroh best practices, pitfalls and real-world use

Source research from 2026-10-28 that stress-tests the [iroh public-exposure design](../design/public-exposure-iroh.md). Findings are source, documentation and issue-tracker reading plus one inspection of a published Android artifact; nothing here is runtime verification of iroh on a device or against our bridge. Anything I could not confirm is marked **unverified**.

## Versions and revisions inspected

| Item | Version / state | Source |
| --- | --- | --- |
| iroh (core) | 1.4.1, released 2026-10-22 (latest at time of reading) | [iroh releases](https://github.com/n0-computer/iroh/releases) |
| iroh-relay | 1.4.1 | [docs.rs iroh-relay](https://docs.rs/iroh-relay/latest/iroh_relay/) |
| iroh-ffi | 1.2.0, tagged 2026-10-22; `Cargo.lock` on `main` resolves iroh 1.4.1, noq 1.4.0, netwatch 0.19.3 | [iroh-ffi releases](https://github.com/n0-computer/iroh-ffi/releases), [Cargo.lock](https://raw.githubusercontent.com/n0-computer/iroh-ffi/main/Cargo.lock) |
| Kotlin artifacts on Maven Central | `computer.iroh:iroh` (JVM) and `computer.iroh:iroh-android` 1.1.0 and 1.2.0 | [maven-metadata.xml](https://repo1.maven.org/maven2/computer/iroh/iroh-android/maven-metadata.xml) |
| dumbpipe | 0.40.1, 2026-10-21 | [dumbpipe releases](https://github.com/n0-computer/dumbpipe/releases) |
| iroh-proxy-utils | work in progress, "API subject to change" | [README](https://github.com/n0-computer/iroh-proxy-utils) |

Source files were read from the `main` branches on 2026-10-28; commit SHAs were not captured, so refreshing later does not update these conclusions. Issue statuses are as of the same day.

## 1. Recommended patterns

### Endpoint lifecycle

- The `Endpoint` docs say "It is recommended to only create a single instance per application", so that all connections share the same peer-to-peer connections ([endpoint.rs](https://docs.rs/iroh/latest/src/iroh/endpoint.rs.html)). The design's "one endpoint per process" is consistent with this.
- `close()` closes the QUIC endpoint and socket. The docs "highly recommend" awaiting it; skipping the wait makes still-open connections time out on the remote end. The UDP sockets only close once every clone of the `Endpoint` is dropped ([Endpoint::close](https://docs.rs/iroh/latest/iroh/endpoint/struct.Endpoint.html)).
- Do not store an `Endpoint` inside a hook type: it creates reference cycles and blocks clean shutdown ([endpoint hooks](https://docs.iroh.computer/connecting/endpoint-hooks)).
- `online()` waits for at least one relay handshake and "pends forever" if no relays are configured ([Endpoint docs](https://docs.rs/iroh/latest/iroh/endpoint/struct.Endpoint.html)).

### Connections, streams, reuse

- Streams are cheap: opening one needs no round trip, and one connection can carry many request/response exchanges on separate bi-directional streams, as in HTTP/3 ([using QUIC](https://docs.iroh.computer/protocols/using-quic.md)).
- The docs advise keeping a connection open while the user is active, to avoid repeated handshakes and hole punching, and monitoring `closed()` ([using QUIC](https://docs.iroh.computer/protocols/using-quic.md)).
- A stream "will only be accepted once the initiating peer has sent some data on it" ([endpoint.rs](https://docs.rs/iroh/latest/src/iroh/endpoint.rs.html)). The acceptor's `accept_bi` does not return for a stream the dialer opened but has not written to.
- `finish()` signals end of stream after pending bytes; `reset(code)` discards unsent data; `stop(code)` tells the sender the reader is gone. A bi-directional stream can only be reset or stopped as a unit ([using QUIC](https://docs.iroh.computer/protocols/using-quic.md)).
- If one side never closes the connection, the other waits, "usually after 30s" ([using QUIC](https://docs.iroh.computer/protocols/using-quic.md)). Exact keep-alive and idle-timeout defaults are **unverified**: the `QuicTransportConfig` docs name `default_path_keep_alive_interval` and `default_path_max_idle_timeout` without values ([docs.rs](https://docs.rs/iroh/latest/iroh/endpoint/struct.QuicTransportConfig.html)).
- Server-side 0.5-RTT data can be replayed; treat it as potentially non-idempotent ([using QUIC](https://docs.iroh.computer/protocols/using-quic.md)). Relevant if the bridge ever acts on a request before the handshake finishes; a bi-stream into an HTTP proxy that forwards immediately is such a case.

### ALPN and versioning

- Protocols identify themselves by ALPN; a dialer can offer several (for example `/my-protocol/2` then `/my-protocol/1`) and the acceptor picks one it supports ([protocols](https://docs.iroh.computer/concepts/protocols.md)).
- The 1.0 post says each protocol negotiates its version through ALPN and "each ALPN gets its own QUIC connection" ([road to 1.0](https://www.iroh.computer/blog/the-road-to-iroh-1-0)). So a pairing ALPN and an application ALPN are separate connections.
- `Endpoint::set_alpns` replaces the accepted list; order matters for negotiation ([Endpoint docs](https://docs.rs/iroh/latest/iroh/endpoint/struct.Endpoint.html)).

### Accept-side authorisation

- iroh 0.96+ has `EndpointHooks` with `before_connect` and `after_handshake`. `after_handshake` runs for incoming and outgoing connections after the TLS handshake, with remote EndpointId and ALPN available and no application data yet. It returns `Accept` or `Reject { error_code, reason }`; hooks run in registration order and the first rejection wins ([endpoint hooks](https://docs.iroh.computer/connecting/endpoint-hooks), [0.96 post](https://www.iroh.computer/blog/iroh-0-96-0-the-quic-multipaths-to-1-0)).
- Hooks observe or reject only. Do not clone the `Connection` inside `after_handshake`; use `conn.weak_handle()`. Use `conn.path_events()` to track paths ([endpoint hooks](https://docs.iroh.computer/connecting/endpoint-hooks)).
- The upstream `auth-hook.rs` example uses a **separate auth ALPN for pre-authentication**: outgoing connections run that step first and incoming connections from unauthenticated peers are rejected on other ALPNs ([endpoint hooks](https://docs.iroh.computer/connecting/endpoint-hooks)).
- A hook runs once at handshake. It does not evict a connection already accepted; revoking a live peer needs an explicit `Connection::close` (inference from the hook API; no upstream revocation example read).
- `Router` plus `ProtocolHandler` dispatches by ALPN per connection ([protocols](https://docs.iroh.computer/concepts/protocols.md)). `iroh-proxy-utils` implements its upstream proxy as a `ProtocolHandler` with a pluggable `AuthHandler` ([README](https://github.com/n0-computer/iroh-proxy-utils)).

## 2. Mobile and iroh-ffi

### Status and packaging

- iroh-ffi is maintained by n0 and tracks the stabilised 1.0 surface for Kotlin, Swift, Python and Node; blobs, docs and gossip are out of scope ([iroh-ffi README](https://github.com/n0-computer/iroh-ffi), [DEVELOPERS.md](https://raw.githubusercontent.com/n0-computer/iroh-ffi/main/DEVELOPERS.md)). Tag 1.2.0 landed the same day as iroh 1.4.1.
- The Kotlin docs page says Android "requires building from source" ([Kotlin docs](https://docs.iroh.computer/languages/kotlin.md)). That is stale: `computer.iroh:iroh-android` 1.2.0 is on Maven Central. I downloaded the AAR and listed it: it contains `jni/{arm64-v8a,armeabi-v7a,x86,x86_64}/libiroh_ffi.so`, with `arm64-v8a` at about 12.8 MB ([artifact](https://repo1.maven.org/maven2/computer/iroh/iroh-android/1.2.0/iroh-android-1.2.0.aar), [POM](https://repo1.maven.org/maven2/computer/iroh/iroh-android/1.2.0/iroh-android-1.2.0.pom)). Its `minSdk` is 24 ([build.gradle.kts](https://raw.githubusercontent.com/n0-computer/iroh-ffi/main/kotlin/android/build.gradle.kts)). The repo also has an on-device smoke test and an emulator CI job ([IrohSmokeTest.kt](https://raw.githubusercontent.com/n0-computer/iroh-ffi/main/kotlin/android-smoke/src/androidTest/kotlin/computer/iroh/smoke/IrohSmokeTest.kt), [ci_kotlin.yml](https://raw.githubusercontent.com/n0-computer/iroh-ffi/main/.github/workflows/ci_kotlin.yml)). Not run by me.

### Android initialisation (required)

- iroh's default `DnsResolver` reads system DNS through JNI. Apps must publish a JVM context before constructing an endpoint; if not, iroh relies on panic unwinding to fall back to nameservers, and with `panic = "abort"` the app panics ([endpoint.rs "Usage on Android"](https://docs.rs/iroh/latest/src/iroh/endpoint.rs.html)).
- iroh-ffi exposes `IrohAndroid.installAndroidContext(context)`, idempotent, to be called once at startup before any `Endpoint` ([IrohAndroid.kt](https://raw.githubusercontent.com/n0-computer/iroh-ffi/main/kotlin/android/src/main/kotlin/computer/iroh/IrohAndroid.kt), [android_init.rs](https://raw.githubusercontent.com/n0-computer/iroh-ffi/main/src/android_init.rs)). A failed first attempt is logged and not retried.
- Issue [#4502](https://github.com/n0-computer/iroh/issues/4502) (closed 2026-09-03): without a working JNI context, DNS silently fell back to Google 8.8.8.8, a privacy leak and wrong on split-horizon/VPN DNS. Maintainer: PR #4419 makes the fallback configurable. Which release carries it is **unverified**.

### Network change handling

- `Endpoint::network_change()` is `pub async fn`, and its doc says iroh detects changes itself on many systems but "some systems like android do not expose this functionality to native code", so the app should notify iroh from Java code; extra calls are harmless ([endpoint.rs](https://raw.githubusercontent.com/n0-computer/iroh/main/iroh/src/endpoint.rs)).
- iroh-ffi has **no** plain notify method. `Endpoint.watch_network_change(callback)` spawns a loop that awaits `endpoint.network_change()` and then calls the callback, repeatedly ([watch.rs](https://raw.githubusercontent.com/n0-computer/iroh-ffi/main/src/watch.rs), [endpoint.rs](https://raw.githubusercontent.com/n0-computer/iroh-ffi/main/src/endpoint.rs)). Whether that loop behaves as a notifier, a wait, or a busy loop at runtime is **unverified**; it must be tested on a device before the design relies on it. Whether an `ConnectivityManager` callback can reach iroh through iroh-ffi at all is therefore open.
- Rebinds on change: iroh rebinds its UDP socket on a network change because sockets can break permanently after suspend on mobile OSes. Issue [#4289](https://github.com/n0-computer/iroh/issues/4289) (open, v1.0.0 milestone): a failed rebind is tried once; the endpoint can die silently (`accept()` returns `None`, `is_closed()` stays `false`). With a relay configured the endpoint survives. Reproduced on Linux only.
- [#4588](https://github.com/n0-computer/iroh/issues/4588) (closed 2026-10-19 via PR #4605, shipped in 1.4.0 per the [1.4.0 post](https://www.iroh.computer/blog/iroh-1-4-0)): moving between networks without internet left the old address advertised for 20 to 26 seconds, but only with relays disabled.
- [#1464](https://github.com/n0-computer/iroh/issues/1464) (Wi-Fi to cellular breaks connections) is closed from 2023; it is historical and predates the current stack.

### Background, doze, battery

- Kotlin docs: on backgrounding call `shutdown()` (the Kotlin object has `close()` in the source I read; the docs page says `shutdown`, treat the name as unverified), persist the secret key, and rebind with the saved key on foreground. To stay live in the background use an Android foreground service; "Without one, Android can kill the sockets" ([Kotlin docs](https://docs.iroh.computer/languages/kotlin.md)).
- Key persistence: the docs suggest durable storage such as `EncryptedSharedPreferences` for `ep.secretKey().toBytes()` ([Kotlin docs](https://docs.iroh.computer/languages/kotlin.md)). The design's raw `no_backup` file is the same trust level as app-sandbox storage; the choice is the owner's.
- Battery: no battery measurements found in primary sources. Related cost signals: [#4598](https://github.com/n0-computer/iroh/issues/4598) (open) says each idle direct connection triggers a NAT-traversal round every 60 s, about 160 B/s per idle connection, because minimum RTT above 10 ms counts as "not good enough"; [#4589](https://github.com/n0-computer/iroh/issues/4589) asks for pausing periodic net-reports when no connection is open (open). The thread does not discuss battery.

### What iroh-ffi does not expose

From reading `endpoint.rs` on `main`: no hooks, no `DnsResolver` option, no GSO toggle, no keep-alive or idle-timeout settings, no standalone `shutdown` (it is `close()`). Exposed: `Preset` (N0, minimal, N0 without relay), `relay_mode`, `secret_key`, `alpns`, `bind_addr`, `protocols`, `connect`, `accept_next`, `Incoming.refuse/ignore/retry`, `Connection.remote_id/paths/watch_paths/watch_path_events/close`, stream `write_all/finish/reset/stopped/read/read_exact/stop` ([endpoint.rs](https://raw.githubusercontent.com/n0-computer/iroh-ffi/main/src/endpoint.rs), [accept.rs](https://raw.githubusercontent.com/n0-computer/iroh-ffi/main/src/accept.rs), [path.rs](https://raw.githubusercontent.com/n0-computer/iroh-ffi/main/src/path.rs)). Issue [#289](https://github.com/n0-computer/iroh-ffi/issues/289) asks for resolver-only discovery in Swift/FFI. Kotlin Multiplatform is [#272](https://github.com/n0-computer/iroh-ffi/issues/272). Streams read in chunks (`read(size_limit)`), which a blocking `Socket` wrapper must adapt itself.

## 3. Tunnelling TCP/HTTP over iroh

| Project | Pattern | Lesson |
| --- | --- | --- |
| [dumbpipe](https://raw.githubusercontent.com/n0-computer/dumbpipe/main/src/main.rs) `connect-tcp` / `listen-tcp` | One **new QUIC connection and one bi-stream per accepted TCP connection**. Listener calls `accept_bi()` once, then connects to the target. | Simple, but contradicts the "reuse the connection" guidance; no pooling or explicit connection close. Fine for ad-hoc pipes, not for a many-short-requests client. |
| dumbpipe copy loop | Two one-way copies. After a direction ends: `send.finish()` then `send.stopped().await`, "to avoid dropping in-flight data". On error: `send.reset(0)` and `recv.stop(0)`. A finished direction does not cancel the other. | Half-close is supported by running two independent copies. The code comment about aborting both sides when either finishes is inaccurate. |
| dumbpipe handshake | Dialer writes a fixed handshake first (only without a custom ALPN); listener verifies. | This also forces the dialer to send data, which is what makes the stream appear on the acceptor. |
| [iroh-proxy-utils](https://github.com/n0-computer/iroh-proxy-utils) | ALPN `iroh-http-proxy/1`; **each bi-stream carries one HTTP/1.1 connection** (CONNECT tunnel or absolute-form request); one iroh connection carries any number of streams; pluggable `AuthHandler`. | Closest n0 prior art to our design. Work in progress, API unstable. Half-close not documented. |
| [iroh-ssh](https://github.com/rustonbsd/iroh-ssh) and [iroh-ssh-android](https://github.com/futpib/iroh-ssh-android) | Per incoming stream, the server opens TCP to local sshd and proxies; client side uses a local TCP listener or ProxyCommand. Android client uses a Flutter-to-Rust bridge and an Android foreground service for sessions. | Third-party; relevant only as evidence that a local-listener design is the common mobile shape, and a foreground service is how they keep sessions alive. Their stream and half-close handling was not read. |

Pitfalls drawn from the above and the stream docs:

- The acceptor does not see a stream until the dialer writes. A `Socket.connect()` that opens a stream and waits for the server to speak first will hang; an HTTP client writes first, so OkHttp is safe.
- Always propagate both directions' end: `finish()` the sink when the source hits EOF, and `reset`/`stop` on error. Await `stopped()` before dropping the connection or closing the endpoint, otherwise in-flight bytes are lost (dumbpipe's explicit comment).
- Backpressure comes from QUIC flow control through awaited `write_all`; no separate mechanism was documented. `Connection.set_max_concurrent_bi_streams` exists in iroh-ffi for limiting streams ([endpoint.rs](https://raw.githubusercontent.com/n0-computer/iroh-ffi/main/src/endpoint.rs)).
- Known tunnelling bugs specific to stream copying: none found in the issue search. **Unverified** beyond that search.

## 4. Relays, paths and detection

### Observing path type

- 0.96 removed `Endpoint::conn_type`; use `Connection::paths()` and look at the selected path's remote address: relay vs IP ([0.96 post](https://www.iroh.computer/blog/iroh-0-96-0-the-quic-multipaths-to-1-0)).
- In iroh-ffi, `Connection.paths()` returns snapshots with `is_ip`, `is_relay`, `is_selected`, `rtt_ms`, `remote_addr`; `watch_paths` and `watch_path_events` deliver `Opened`, `Closed`, `Selected`, `Lagged` ([path.rs](https://raw.githubusercontent.com/n0-computer/iroh-ffi/main/src/path.rs)). Unknown upstream variants are mapped to `Lagged { missed: 0 }`.
- A connection is multipath: QUIC picks the sending path, so "direct vs relayed" can change during one connection.

### Direct paths on mobile networks (open issues)

- [#4424](https://github.com/n0-computer/iroh/issues/4424) (open): behind T-Mobile CGNAT, an iOS client's direct path drops to relay every 50 to 75 s as the carrier rebinds the external mapping mid-session, then re-punches in 1 to 26 s. Detection takes up to 15 s (`PATH_MAX_IDLE_TIMEOUT`). Relay fallback kept the connection alive. Whole-connection drops seen were client-side OS suspension.
- [#4644](https://github.com/n0-computer/iroh/issues/4644) (open, bug, iroh 1.4.0): under load on Android phones over 4G/5G, the selected direct path flips between IPv4 and IPv6 about every 60 s; maintainer: "a serious problem with iroh's current mechanism", no fix.
- [#4647](https://github.com/n0-computer/iroh/issues/4647) (open, iroh 1.4.1): on Android over cellular, a bulk **upload** from the phone loses the direct path and falls to relay (about 3 Mbps) when UDP GSO is on; `enable_segmentation_offload(false)` fixed it in the reporter's tests. Not triaged. The setting is not reachable from iroh-ffi.
- [#4600](https://github.com/n0-computer/iroh/issues/4600) (open, ready-for-pr): while an old connection to a peer is open, a **new** `connect()` sends its handshake only down the old selected path. If the peer was killed without close frames and returned elsewhere with the same key, the new connect times out even with the new relay URL in the `EndpointAddr`. Reporter's workaround: do an address lookup first and pass relay URLs from earlier successes, and drop the old connection before redialling.
- [#4319](https://github.com/n0-computer/iroh/issues/4319) (open): with self-hosted relays and a failed home relay, the endpoint is unreachable for about 30 s while the home relay switches, and established connections can die.

### Relay selection, auth and operations

- n0's public relays: "do not use … for production workloads", rate-limited, no uptime guarantee ([add a relay](https://docs.iroh.computer/add-a-relay.md), [relays](https://docs.iroh.computer/concepts/relays.md)). The 1.0 post says relayed traffic is rate-limited and limits can change, and that public relays serve 1.x clients ([iroh 1.0](https://www.iroh.computer/blog/v1)).
- Clients try multiple relays and fail over; relays are stateless ([add a relay](https://docs.iroh.computer/add-a-relay.md)).
- Self-hosting: `iroh-relay` binary or Docker image `n0computer/iroh-relay`, TOML config; `access` can be `everyone` (default), `allowlist` of endpoint IDs, `denylist`, `shared_token`, or an `http` callback; `enable_quic_addr_discovery` needs TLS certificates ([iroh-relay README](https://raw.githubusercontent.com/n0-computer/iroh/main/iroh-relay/README.md), [self-hosted guide](https://docs.iroh.computer/iroh-services/relays/self-hosted.md), community-maintained and "may not reflect the most up-to-date" config). The relay client sends `X-Iroh-Endpoint-Id` since 1.4.0; `X-Iroh-NodeId` is deprecated ([1.4.0 post](https://www.iroh.computer/blog/iroh-1-4-0)).
- Network requirements: relay connections start as HTTPS on TCP 443 and upgrade to WebSocket; UDP 7842 to the relay is "recommended" for public-address learning; blocked UDP means everything is relayed; iroh does not use an HTTP proxy by default, and proxy support (HTTP CONNECT) carries only relay traffic; firewalls that block WebSocket upgrades stop iroh; iroh uses its own CA list, so TLS-inspecting proxies fail ([configuring networks](https://docs.iroh.computer/configuring-networks.md)).
- Zedra, a mobile remote-coding-agent app on iroh, self-hosts the unmodified upstream `iroh-relay` ([search summary of zedra's README](https://github.com/tanlethanh/zedra); the README itself was not fetched, so treat as **unverified**).

### What breaks without QUIC address discovery (QAD)

- Maintainer, [#4623](https://github.com/n0-computer/iroh/issues/4623): "There is currently no way to perform the holepunching in iroh without having a relay transport", and QAD-only servers are deliberately out of scope. A QAD-learned address is what ends up in tickets.
- The enterprise-network page says the UDP 7842 path lets a device "learn its public address, the role STUN plays in VoIP" ([configuring networks](https://docs.iroh.computer/configuring-networks.md)).
- **Inference, not stated by any source I read:** a relay reachable only over WebSocket through Cloudflare Tunnel gives no reflexive address, so hole punching has only local and port-mapped candidates and most phone-on-cellular to Mac paths will stay relayed. Whether iroh still exchanges addresses through such a relay and whether Cloudflare Tunnel's WebSocket handling is acceptable for the relay protocol is **unverified**; needs a test.

## 5. Discovery and what to put in the QR

- `presets::N0` publishes and resolves through n0's DNS/pkarr server (`dns.iroh.link`). Dialing with only an EndpointId resolves a signed packet containing the home relay URL and, optionally, direct addresses ([address lookup](https://docs.iroh.computer/concepts/address-lookup.md), [DNS](https://docs.iroh.computer/connecting/dns-address-lookup.md)). The publisher sends only the relay URL by default; direct addresses need `AddrFilter::unfiltered()`. In #4424 the reporter found explicitly configured external addresses stripped by the default `relay_only()` filter.
- Both sides must use the same lookup server. For a self-hosted setup, start from `presets::Minimal` and add your own `PkarrPublisher` and `DnsAddressLookup` ([DNS](https://docs.iroh.computer/connecting/dns-address-lookup.md)). iroh-ffi lacks a resolver option (issue #289), and presets are the Kotlin lever; custom `Preset` implementations are possible in the foreign language ([endpoint.rs](https://raw.githubusercontent.com/n0-computer/iroh-ffi/main/src/endpoint.rs)). Whether n0's DNS accepts a pkarr packet naming a **non-n0 relay URL** is **unverified**.
- Tickets: an endpoint address (id, relay URL, direct addresses) plus app data, `postcard` plus base32. Docs: use tickets "when you have no other way to coordinate", such as QR codes; tickets embed your current IPs, are reusable, and go stale; cache EndpointIds for long-lived connections ([tickets](https://docs.iroh.computer/concepts/tickets.md)).
- Iroh-ffi `EndpointAddr.new(id, relay_url, addresses)` builds a dialing address from id plus relay URL ([net.rs](https://raw.githubusercontent.com/n0-computer/iroh-ffi/main/src/net.rs)).

## 6. Stability and versioning

| Event | Date | Relevance |
| --- | --- | --- |
| 0.35 long-term stable line | 2025-05-13 | Receives no further releases; public relay support ends 2026-12-31 ([iroh 1.0](https://www.iroh.computer/blog/v1)). |
| 0.96, "one of the last major wire-breaking changes" | 2026-01-27 | Renamed Discovery to AddressLookup, removed `conn_type`, added hooks ([0.96 post](https://www.iroh.computer/blog/iroh-0-96-0-the-quic-multipaths-to-1-0)). |
| 1.0.0-rc.0 / rc.1 | 2026-05-11 / 05-27 | rc.1 changed `AccessConfig` to an `AccessControl` trait in iroh-relay ([releases](https://github.com/n0-computer/iroh/releases)). |
| **1.0.0** | **2026-06-15** | Wire and API stable; "a v1 endpoint can talk to any other v1 endpoint, whatever its minor version or language"; wire-affecting changes only in major releases ([iroh 1.0](https://www.iroh.computer/blog/v1)). |
| 1.0.1 to 1.4.1 | 2026-06-29 to 10-22 | About monthly minors. One note: 1.1.0 changed `CustomAddr` serialization; 1.0.2 and 1.1.0 and 1.4.0 carry relay or TLS security fixes ([releases](https://github.com/n0-computer/iroh/releases)). |

- Public relays: v0.9x and v1.0.0-rc builds lose public relay support on **2026-09-30**, which has passed ([iroh 1.0](https://www.iroh.computer/blog/v1)).
- Names settled: `EndpointId`, `EndpointAddr`, `AddressLookup`, `QuicTransportConfig`. `CaRootsConfig` became `CaTlsConfig` at 1.0.
- Implication for separate pinning: the wire contract is major-version-scoped, so bridge and app can sit on different 1.x minors. Relay and DNS-server operators must rebuild when security releases land (1.4.0 notes). Whether iroh-ffi tags track iroh patch versions one-to-one is **unverified**: ffi 1.2.0 declares `iroh = "1.0.0"` in `Cargo.toml` but its lockfile resolves 1.4.1.

## 7. Known issues relevant to the design

| Issue | State on 2026-10-28 | Why it matters here |
| --- | --- | --- |
| [#4647](https://github.com/n0-computer/iroh/issues/4647) GSO breaks direct path on Android cellular upload | Open, untriaged | Phone-to-bridge requests are small, but large uploads may fall to relay; FFI cannot toggle GSO. |
| [#4644](https://github.com/n0-computer/iroh/issues/4644) IPv4/IPv6 path flapping under load on cellular | Open, bug | Badge and poll cadence may flap. |
| [#4424](https://github.com/n0-computer/iroh/issues/4424) CGNAT mid-session rebinding drops direct path every minute or so | Open | Same. Fallback to relay keeps the connection alive. |
| [#4600](https://github.com/n0-computer/iroh/issues/4600) new connect pinned to another connection's selected path | Open, ready-for-pr | Bridge restart (same key, new relay/port) while a phone connection lingers can fail redial. |
| [#4319](https://github.com/n0-computer/iroh/issues/4319) 30 s outage on home relay failure | Open | Relevant to a single self-hosted relay. |
| [#4289](https://github.com/n0-computer/iroh/issues/4289) failed UDP rebind can silently kill the endpoint | Open | Android network switches; mitigation is detecting `is_closed()`/no accept and rebinding. |
| [#4598](https://github.com/n0-computer/iroh/issues/4598) idle connections re-holepunch every 60 s | Open | Argues for closing idle connections (design does). |
| [#4502](https://github.com/n0-computer/iroh/issues/4502) Android DNS JNI failure and Google DNS fallback | Closed | Call `installAndroidContext`; fallback release **unverified**. |
| [#3408](https://github.com/n0-computer/iroh/issues/3408) `accept()` returned `None` after hours | Closed 2025-07 | Old; the bridge accept loop should still log and restart rather than exit. |

I found no open issue specific to half-close, connection leaks or stream resets in the search results read. The searches covered titles only for several results, so absence is not proof.

## 8. Real-world use

- n0 states over 200 million endpoints were created on public relays in 30 days, with uses in video streaming, LLM training, games and file transfer ([iroh 1.0](https://www.iroh.computer/blog/v1)). Paycode links Kotlin Android point-of-sale devices to .NET terminals ([iroh payments](https://www.iroh.computer/solutions/pos), via search summary; **unverified** beyond that).
- Closest analogues for Cappuccino, from [awesome-iroh](https://github.com/n0-computer/awesome-iroh): Zedra (mobile client to a desktop daemon, QR pairing, `zedra start`), Codux ("encrypted p2p control across desktop, mobile, and headless hosts" for coding agents), iroh-ssh-android, koh (a Mosh-style shell that survives network changes), iroh-http (HTTP-style request/response over iroh streams). I read only their listings and, for iroh-ssh-android, its README; no first-hand lessons-learned write-ups were found.
- Lessons that can be read off the issue tracker rather than from reports: mobile carriers rebind mappings, relay fallback keeps sessions alive, and users report path churn on cellular. Those are issues, not endorsements of any design.

## Implications for the Cappuccino design

Each row cites the section of the [design doc](../design/public-exposure-iroh.md).

| Design decision | Verdict | Finding |
| --- | --- | --- |
| Risks: "iroh 0.9x is a canary series; 1.0 is announced as coming; pin the current minor" | **Change** | iroh 1.0 shipped 2026-06-15 and 1.4.1 is current. 0.9x lost public relay support on 2026-09-30, so a 0.9x pin would not work on the n0 relays the design ships on. Pin 1.x; wire is stable across 1.x. Update the Risks and Decision text. |
| Decision: "Android bindings supported (currently built from source — CI owns that job)"; slice 2 and App changes: build with `cargo-ndk` in CI | **Change** | `computer.iroh:iroh-android` 1.2.0 on Maven Central ships all four ABIs. Slice 2 can shrink to a Gradle dependency; keep a source-build job only if we need an unreleased fix or a feature iroh-ffi does not expose. Re-verify the AAR on a device. |
| App changes: "iroh's own recommendation" for one endpoint per process | **Keep** | Docs say one instance per application. |
| App changes: endpoint "closed when idle" | **Keep with care** | Idle teardown avoids #4598, but every restart costs rebind plus address lookup plus hole punching. Android may kill sockets anyway; Kotlin docs say shut down on background and use a foreground service to stay live. Persist the key and rebind; treat `is_closed()` or a dead accept as a signal to rebuild. |
| App changes: key as raw file in `no_backup` | **Keep** | Matches iroh-ffi's need for raw bytes. Kotlin docs suggest `EncryptedSharedPreferences`; both are app-sandbox storage. Owner's choice. |
| App changes: Android network-change behaviour, "sticky until Android reports a network change" | **Risk** | iroh cannot detect changes on Android by itself; the app must notify it. iroh-ffi exposes no notify call, only a watcher whose behaviour is unverified. Spike this first. Also call `IrohAndroid.installAndroidContext` at startup. |
| Security model 1 and 2: accept hook rejects every EndpointId not on the allowlist, "before any application bytes flow", while pairing needs an unknown phone to present a token | **Change (bug)** | An unpaired phone cannot be on the allowlist yet. Use a separate pairing ALPN (for example `cappuccino-pair/1`) that the hook admits for unknown ids and which accepts one token-bearing request, as upstream `auth-hook.rs` does with its auth ALPN. Admit unknown ids only on that ALPN; enforce the allowlist on `cappuccino/1`. |
| Security model: revocation | **Change** | The hook runs at handshake only. `pair --revoke` must also close live connections from that id. Allowlist state in the hook must be shared and interior-mutable; never store the `Endpoint` in the hook. |
| Versioning: ALPN `cappuccino/1`, mismatch fails the dial cleanly and the app falls back | **Keep** | ALPN mismatch fails negotiation. Consider offering `cappuccino/2` then `cappuccino/1` when the time comes; iroh supports multiple ALPNs. |
| Diagram: bi-stream to TCP pipe into loopback axum | **Keep** | Matches `iroh-proxy-utils` (one bi-stream per HTTP/1.1 connection, many streams per connection) and the dumbpipe copy pattern. Use two independent copies; `finish()` then await `stopped()` before dropping; `reset`/`stop` on error. Prefer one iroh connection reused for many streams, not dumbpipe's one connection per TCP connection. Consider `Router` plus `ProtocolHandler` on the bridge. |
| App changes: OkHttp `SocketFactory`, each socket = one bi-stream | **Risk** | iroh-ffi streams are chunked suspend calls; the factory needs blocking adapters. A stream is invisible to the bridge until the phone writes, so do not wait for server-first bytes on connect (HTTP is client-first, fine). OkHttp's connection pool will hold sockets open across polls; make sure closing the socket finishes and awaits `stopped()`. |
| App changes: transport badge, "iroh direct or iroh relayed"; poll 5 s direct, 30 s relayed | **Keep with hysteresis** | `Connection.paths()` and `watch_path_events` give the selected path type. Direct/relay flips are common on cellular (#4424, #4644), so debounce the state before changing poll cadence and badge. |
| Auto fallback: sticky until a network change or failure | **Risk** | After a failure close the old iroh connection before redialling (#4600 pins new connects to the old selected path), and re-pass the full `EndpointAddr` from pairing rather than relying on cached state. |
| Pairing: QR carries the bridge EndpointId and token | **Change (small)** | Docs recommend tickets (EndpointAddr) for QR sharing and IDs for long-lived use. Put the home relay URL in the QR (an `EndpointAddr` with relay) so first dial works with a self-hosted relay and without DNS lookup; drop direct IPs because tickets are reusable and leak the Mac's IPs. Re-resolve by EndpointId afterwards. |
| Security model 4: n0 public relays now | **Risk (accepted)** | n0 says do not use them for production; rate-limited, no SLA. Acceptable for one owner, but expect throttled relayed throughput and treat as a stopgap. Keep the self-hosted slice. |
| Security model 4 and slice 6: self-hosted relay on k3s behind Cloudflare Tunnel, WebSocket only, no QAD | **Risk (high)** | Maintainer: no hole punching without a relay transport; QAD (UDP 7842) is how endpoints learn their public address. Without UDP, expect most paths between phone on mobile data and Mac to stay relayed (inference, **unverified**). Test before committing. Relay `access.allowlist` of EndpointIds is available and should be enabled so strangers cannot use the relay. Single relay = 30 s outage on failure (#4319). Whether Cloudflare Tunnel is compatible with the relay's WebSocket upgrade is **unverified**. |
| Self-hosted relay discovery | **Risk** | iroh-ffi cannot configure a `DnsResolver` or publisher directly (#289). With custom relays, carry the relay URL in the pairing payload and set `relay_mode` custom; do not rely on n0 DNS carrying a non-n0 relay (unverified). |
| App changes: background notifications use iroh | **Risk (confirmed)** | A plain WorkManager worker is not a foreground service; docs say Android can kill sockets without one. Slice 4 must pick: bind per run and accept the cold-start cost, or run a foreground service with its notification and battery cost. Measure on a device as already planned. |
| Testing: hermetic in-process tests with two endpoints | **Keep** | iroh provides `test_utils::run_relay_server()` for in-process relays ([iroh-relay README](https://raw.githubusercontent.com/n0-computer/iroh/main/iroh-relay/README.md)); useful for relay-path tests. |
| Risks: "relay fallback adds latency when hole-punching fails; direct is the common case" | **Keep, refine** | n0 claims about 9 in 10 networks allow direct ([NAT traversal](https://docs.iroh.computer/concepts/nat-traversal.md)), but cellular CGNAT is the weak case and has open path-stability bugs. Do not assume direct on mobile data. |
| FFI limits | **Risk** | No GSO toggle (#4647 workaround), no idle/keep-alive tuning, no DNS resolver option, no hooks. If we need any of these on the phone, we would carry a fork or a small custom FFI crate; weigh against the AAR. |
