# iroh setup and operations

How to turn on the iroh transport for the bridge, pair a phone, run the
self-hosted relay, and debug it. Design and threat model:
[public exposure via iroh](design/public-exposure-iroh.md). Bridge reference:
[services/bridge/README.md](../services/bridge/README.md).

## Overview

iroh adds a second way to reach the bridge. The phone dials the bridge by its
EndpointId (an ed25519 public key), over QUIC with TLS 1.3. The bridge keeps its
HTTP and WebSocket API on loopback; iroh carries those streams unchanged. The
bridge has no public ingress port.

The tailnet path stays. The app tries iroh first and falls back to the tailnet
URL. A machine can carry both. iroh is off by default and changes nothing until
`iroh.enabled` is `true`.

## Enable on the bridge

The bridge reads one configuration file (`config.toml`). Its path is `CAPP_BRIDGE_CONFIG` when set, else
`$XDG_CONFIG_HOME/cappuccino-bridge/config.toml`, else
`~/.config/cappuccino-bridge/config.toml`. Relative `XDG_*` values are ignored.

```toml
[iroh]
enabled = true

[serve]
auto_apply = false
```

- `iroh.enabled`: strict boolean (`true`/`false`). Any other value stops startup.
- `iroh.relay_url`: optional absolute `https` URL for a custom relay. Omit it to use
  n0's default public relays (such as `aps1` in Tokyo), which provide full UDP NAT
  traversal and require no manual allowlists. See [Custom relay](#custom-relay).
- `serve.auto_apply`: `false` stops the bridge from touching Tailscale Serve.
  Use it when you do not want `start` or `status` to change Serve.

Parsing is strict in some places and lenient in others:

- Unknown keys inside `auth` are errors. The retired `auth.pair_token_ttl_secs`
  fails startup; remove it.
- Unknown keys at the top level and inside `iroh` are ignored, so a typo such as
  `relay` for `relay_url` does nothing silently. Check the spelling.

Runtime state lives in `data_dir`, default `$XDG_STATE_HOME/cappuccino-bridge`,
else `~/.local/state/cappuccino-bridge`. Set `data_dir` in the file or
`CAPP_BRIDGE_DATA_DIR` (the environment wins) to move it. The directory holds:

| File | Purpose | Mode |
| --- | --- | --- |
| `iroh-secret.key` | bridge EndpointId key | 0600 |
| `iroh-allowlist.json` | paired EndpointIds | 0600 |
| `iroh-admin.sock` | CLI control socket (`pair`) | owner-only |
| `bridge.log` | audit and bridge log | owner-only |

The admin socket path must stay under macOS's 104-byte limit. The bridge refuses
to start with a "set data_dir to a shorter path" error otherwise.

Nothing is migrated from the older macOS path
`~/Library/Application Support/cappuccino-bridge`. To keep using it, set
`"data_dir"` to that absolute path. Otherwise the bridge starts with a fresh
EndpointId and every phone must pair again.

## Install and restart

Build and install the executable that Herdr runs. Herdr runs the `cappuccino-bridge`
on its `PATH`, normally the `~/.cargo/bin` copy, not a checkout's `target/`
artifact.

```sh
cd /Users/azusachino/Projects/project-github/harus-workstation/vendor/cappuccino
cargo build --release --manifest-path services/bridge/Cargo.toml
cargo install --path services/bridge --locked
command -v cappuccino-bridge
```

Restart the bridge through the Herdr plugin actions. Stop, then start:

```sh
herdr plugin action invoke stop --plugin cappuccino-bridge
herdr plugin action invoke start --plugin cappuccino-bridge
herdr plugin action invoke status --plugin cappuccino-bridge
```

Run the plugin actions from a normal shell, never from a sandboxed one. A sandbox
blocks `~/Library`, and the Herdr-launched child inherits that restriction. The
bridge then starts without the state it expects, or fails on the same paths.
Linking the plugin or its startup hook does not build or install the binary.

Restarting the bridge keeps pairings. The key and allowlist persist in
`data_dir`. The pairing code is in memory only, so a restart invalidates the
code currently shown, nothing else.

Logs and status:

```sh
cappuccino-bridge status
cappuccino-bridge logs
herdr plugin log list --plugin cappuccino-bridge --limit 10
```

`status` and `logs` read the Herdr plugin state directory
(`$XDG_STATE_HOME/herdr/plugins/cappuccino-bridge`, else
`~/.local/state/herdr/plugins/cappuccino-bridge`), so a bare `cappuccino-bridge`
command addresses the bridge that Herdr started.

## Pairing

Pairing needs a running bridge with `iroh.enabled` set. `pair` asks that bridge
over `iroh-admin.sock`; it does not create a code of its own.

### Terminal QR

```sh
cappuccino-bridge pair
```

The command prints a compact QR, the same payload as text, and how long the code
lasts. Rules:

- The code rotates every 60 seconds. The previous code is accepted for one more
  window, so a code is usable for up to about two minutes.
- A successful pairing uses up that window's code. One code pairs one phone.
  Once burned, the code is refused until its window passes.
- A wrong guess burns nothing. Each EndpointId gets 5 pairing attempts per 60
  seconds.
- The payload is a pairing secret. Do not paste it into issues, logs or chat.

The QR is about 55 to 59 columns by 28 to 30 rows. If the terminal is narrower
than the QR, the command prints a hint. Widen the terminal, or use `/pair`.

### Pairing page

Open `https://<tailnet-host>/pair` from a tailnet device. It is served through
Tailscale Serve, shows the current code as a square QR, and reloads itself at
each window boundary. Use it when the terminal stretches the QR. The page is
side-effect-free: loading it creates no code and writes no audit line.

### Phone

In the Android app, open the add-machine menu (`+`) and choose **Pair over
iroh**. Then either:

- **Scan QR**: scan the bridge's QR (the app uses the Google code scanner, no
  camera permission), or
- **Paste**: paste the payload text. The app clears the clipboard after the
  paste.

The app stores the bridge's EndpointAddr, never the token. Use **Add tailnet
URL** on an iroh-only machine to add the tailnet fallback later. The app verifies
the bridge before saving either.

### When to pair again

- The phone was revoked, or it is a new phone.
- `relay_url` changed. The stored pairing carries the old relay URL, so the phone
  must scan a new payload.
- `data_dir` changed, or the bridge started from a fresh state directory (new
  EndpointId).
- The code expired or was already used.

A plain bridge restart does not require a new pairing.

## Custom relay

By default, the bridge and phone use n0's public relays (automatically selecting
the nearest node, e.g. Tokyo `aps1`). Because iroh traffic is end-to-end encrypted
with ed25519 keys and TLS 1.3 / QUIC, public relays only forward opaque ciphertexts
and never have access to session data, tokens, or agent payloads.

`iroh.relay_url` configures the bridge to use a custom relay instead. Note that
setting `relay_url` completely clears n0's default relay pool.

Important considerations for self-hosted relays:

- **Avoid HTTP tunnels / reverse proxies**: Running an iroh relay behind Cloudflare
  Tunnel or standard HTTP reverse proxies drops UDP traffic. This disables STUN
  and direct QUIC hole-punching (`enable_quic_addr_discovery = false`), forcing
  all traffic into high-latency WebSocket relay streams.
- **Avoid restrictive allowlists**: Restricting access with an EndpointId allowlist
  breaks mobile clients because the app generates a new keypair whenever
  reinstalled or state is cleared.
- A self-hosted relay should be deployed with native dual-stack UDP and direct
  network exposure.

## Transports and badges

Order: iroh first, then tailnet. The transport that last worked is sticky. The
app uses it alone until a request on it fails or the Android default network
changes, then probes the order again. On a default-network change the app cancels
an in-flight iroh dial and redials at once. After three consecutive iroh failures
it rebuilds the iroh endpoint.

Mutating requests (prompts, control actions, answers) are never re-sent on
another transport once they may have reached the bridge. A mid-request failure
shows an outcome-unknown state instead.

Badge on each machine row:

| Badge | Meaning |
| --- | --- |
| `tailnet` | last request went over the tailnet URL |
| `iroh direct` | iroh path is direct |
| `iroh relayed` | iroh path goes through the relay |

The badge changes only between direct and relayed, and only after a 10 second
hold-down. Switching between direct addresses (LAN, public) is normal and not
shown. A switch to or from tailnet applies at once.

Poll cadence follows the badge: 5 seconds on a direct path, 30 seconds while
relayed. Background notifications poll iroh-only and dual machines iroh-first.

## Revoking a phone

The bridge keeps the allowlist in `iroh-allowlist.json` in `data_dir`. Read it
and the audit lines in the log:

```sh
jq . "$HOME/.local/state/cappuccino-bridge/iroh-allowlist.json"
cappuccino-bridge logs | grep 'audit pair_ok'
```

Adjust the path if `data_dir` is set elsewhere. Then revoke:

```sh
cappuccino-bridge pair --revoke <EndpointId>
```

The command prints `revoked <id>`, or `<id> was not paired`. Revocation removes
the id from the allowlist and closes its live tunnel connections. The phone gets
no notice; its next dial is refused. Every revocation is audit-logged. To pair the
phone again, run `cappuccino-bridge pair` and scan a new code.

## Troubleshooting

**Office or network blocks n0.** Set `iroh.relay_url` to a custom relay
(see [Custom relay](#custom-relay)), restart the bridge, and pair again.
Without that, the app falls back to the tailnet URL when the machine has one.

**The QR is stretched or wrapped.** Widen the terminal. If it still stretches,
open `https://<tailnet-host>/pair` on a tailnet device and scan the square QR.

**`cappuccino-bridge pair` says the bridge is not running.** Start it with the
Herdr plugin action (see [Install and restart](#install-and-restart)).
`pair` also refuses when `iroh.enabled` is not `true`.

**The bridge will not start.**

1. `cappuccino-bridge status` and `cappuccino-bridge logs` show the reason.
2. Check the config for a retired key (`auth.pair_token_ttl_secs`) or a
   non-boolean `iroh.enabled`.
3. Check `relay_url`: it must be an absolute `https` URL with a host and no
   surrounding whitespace.
4. If the error is about the socket path, shorten `data_dir`.

**`data_dir` is unreadable or points at an old Library path.** Startup fails when
the key or allowlist cannot be read. The bridge never falls back to
`~/Library/Application Support/cappuccino-bridge`. Fix the permissions, or point
`data_dir` at a readable directory and accept re-pairing.

**Relay checks.** For custom relays, the health endpoint is `GET /healthz`:

```sh
curl -s https://relay.example.com/healthz
```

Expect `{"status":"ok",...}`. The relay's WebSocket endpoint is `/relay`. Clients
must offer the `iroh-relay-v2` subprotocol. The server accepts
`iroh-relay-v2,iroh-relay-v1` (iroh-relay 1.4.1, `src/server/http_server.rs`), so
an upgrade check should send the header:

```sh
curl -si \
  -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Version: 13' \
  -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
  -H 'Sec-WebSocket-Protocol: iroh-relay-v2' \
  https://relay.example.com/relay
```

A `101 Switching Protocols` reply means the upgrade worked. Without the
subprotocol header, the relay does not negotiate iroh-relay-v2.
