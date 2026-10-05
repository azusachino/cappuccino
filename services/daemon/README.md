# Companion daemon (slice A spike)

A small user-space macOS daemon that lets a paired client pair → list existing
agents → follow one agent's active-branch transcript read-only. This is the
slice A spike from [plan §2](../../docs/plan.md); the wire format below is
provisional v0 and owned by this directory.

## Layout

```
services/daemon/
├── Package.swift
├── Sources/DaemonCore/        # pure, unit-tested library
│   ├── JSONValue.swift        # loose JSON model for both wire protocols
│   ├── HerdrClient.swift      # NDJSON over Herdr Unix socket (one-shot requests)
│   ├── Agents.swift           # agent list + machine identity + active branch
│   ├── Transcript.swift       # entry model + stable content ids (sha256)
│   ├── Stream.swift           # line confirmation, reconciliation, ring buffer
│   └── Pairing.swift          # manual one-time token (constant-time compare)
├── Sources/cappuccino-daemon/ # executable: TCP listener + poll loop
├── Sources/capctl/            # CLI test client
└── Tests/DaemonCoreTests/     # pairing + reconciliation (incl. shared fixtures)
```

Location rationale: this is neither an app nor shared client code, so it does
not live under `apps/` or `packages/apple`; a follow-up slice can promote it to
a launchd service without moving the library.

## Proposed wire format v0

Newline-delimited JSON over TCP, bound to `127.0.0.1` only (a tailnet address
is a configuration change for a later slice). The pairing token must accompany
every request; a rejected token returns a visible `unauthorized` error and
closes the connection — never a silent retry.

```
client → daemon                                   daemon → client
{"op":"pair","token":"…"}                         {"event":"paired","machine_id":"…","protocol":1}
{"op":"list","token":"…"}                         {"event":"agents","machine_id":"…","agents":[…]}
{"op":"stream","token":"…",                       {"event":"stream_open","session_id":"…","generation":N}
 "session_id":"cap-spike-agent",                  {"event":"stream_reset","generation":N}   // branch changed
 "after_seq":-1}                                  {"event":"entries","entries":[…]}         // replay + live
{"op":"ping","token":"…"}                         {"event":"error","code":"unauthorized|…","message":"…"}
```

Entries follow `fixtures/history-active-branch.json` shapes: `{seq, id, kind,
text, branch, complete}`. `id` is the sha256 of branch + line text, the
idempotency key across reconnects; `seq` is daemon-assigned and monotonic. A
trailing pane line is only confirmed complete when a later line follows it or
the agent is idle, so a growing line is never rendered complete. An active-
branch change bumps `generation` and clients reset instead of concatenating an
abandoned branch. Reconnect with `after_seq` replays from the daemon ring; lost
prefixes surface as explicit `gap` entries (`seq: null` on the wire).

## Known limits (slice A)

- Herdr 0.9.3 exposes raw pane text, not normalized transcripts; entries are
  lines, not semantic messages, and entries are not durable across daemon
  restarts (in-memory ring only).
- Polling every 400 ms via `pane.read`; no `events.subscribe` (read-only spike,
  no replay guarantees anyway).
- `session_id` is the pane's reported agent name; `active_branch` comes from a
  read-only `git branch --show-current` in the agent cwd.
- No TLS: localhost bind only; tailnet exposure needs a real transport decision
  (HTTPS/WSS) before anything beyond this spike.

## Run

```
xcrun swift build --package-path services/daemon
# token: $CAPP_SPIKE_TOKEN or ~/Library/Application Support/cappuccino-spike/pairing-token
.build/debug/cappuccino-daemon --port 7391
.build/debug/capctl pair   --port 7391 --token <token>
.build/debug/capctl list   --port 7391 --token <token>
.build/debug/capctl stream --port 7391 --token <token> --session cap-spike-agent --duration 30
```

Tests: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun swift
test --package-path services/daemon` (XCTest needs the Xcode toolchain; the
CommandLineTools SDK has no XCTest). The reconciliation cases read the shared
read-only `fixtures/reconciliation-cases.json`.
