# Cappuccino Bridge & Interceptor Architecture Specification

**Status**: Proposal / Draft Contract  
**Target Repositories**: `services/bridge`, and the herdr-web-ui reference (read-only, not vendored)  
**Related Documents**: [Architecture](architecture.md), [Herdr WebUI Research](research/herdr-webui-design-research.md), [Behavior Spec](behavior-spec.md)

---

## 1. Principles: Thin Client, Strong Bridge

Clients (Android, iOS/macOS, WebUI) should not act as VT100 terminal emulators or parse unstructured ANSI text to guess prompt boundaries, tool calls, and ephemeral status indicators.

- **Bridge owns normalization**: Connects to the local Herdr IPC socket, inspects agent processes and session files, parses live TUI prompts/approvals, and normalizes ANSI text into structured domain events.
- **Clients remain thin**: Consume typed HTTP/WebSocket endpoints to render native views (Jetpack Compose, SwiftUI, or Web DOM).
- **Separation of transient state vs durable history**: Ephemeral status (spinners, `working · ...`, resource meters) is maintained in a discrete status slot, never appended into historical transcript turns.

---

## 2. Herdr API Surface & Extension Model

Based on installed Herdr 0.9.3 and reference implementations in herdr-web-ui `server/herdr/client.ts`:

### 2.1 Transport & Lifecycle

- **IPC Protocol**: Unix Domain Socket (`~/.config/herdr/herdr.sock`), line-delimited JSON-RPC 2.0. One request per connection (Herdr server closes connection after single response).
- **Hosting**: Herdr Plugin manifest (`herdr-plugin.toml`). Herdr lifecycle hooks spawn `cappuccino-bridge` as a managed process.

### 2.2 Key Herdr RPC Methods

1. `ping`: returns server version, protocol level, and capabilities.
2. `session.snapshot`: returns entire workspace tree (workspaces, tabs, panes, active focus).
3. `agent.list`: returns all running agent descriptors across panes.
4. `agent.get { session_id }`: returns detailed agent metadata:
   - `cwd`: working directory.
   - `agent_status`: `idle` vs `working`.
   - `agent_session`: native session file path / id (for Pi, Claude, Codex).
5. `pane.read { pane_id, source, format, strip_ansi, lines }`: reads visible viewport or recent scrollback (`recent` / `recent_unwrapped`).
6. `agent.prompt { pane_id, text, wait }`: writes text + Enter to the agent PTY.
7. `pane.send-keys { pane_id, keys }`: sends logical keystrokes (`up`, `down`, `enter`, `escape`, `space`, `tab`).

---

## 3. The "Interceptor" Layer Architecture

The Interceptor sits inside `cappuccino-bridge` between Herdr's low-level pane/PTY streams and the client-facing APIs. It consists of three decoupled pipelines:

```text
                  ┌────────────────────────┐
                  │    Herdr RPC Socket    │
                  └───────────┬────────────┘
                              │
  ┌───────────────────────────┴───────────────────────────┐
  │                   Interceptor Layer                   │
  │                                                       │
  │  ┌─────────────────┐ ┌────────────────┐ ┌──────────┐  │
  │  │ Session File    │ │ Screen-State   │ │ Terminal │  │
  │  │ Watcher         │ │ Prompt Engine  │ │ Stripper │  │
  │  │ (Canonical Log) │ │ (Approval Card)│ │ & Status │  │
  │  └────────┬────────┘ └───────┬────────┘ └────┬─────┘  │
  └───────────┼──────────────────┼───────────────┼────────┘
              │                  │               │
  ┌───────────▼──────────────────▼───────────────▼────────┐
  │              Cappuccino Bridge HTTP / WS              │
  │        (Structured Events, Typed Turns, Status Slot)  │
  └───────────────────────────┬───────────────────────────┘
                              │
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
    Android App           Apple App            WebUI
  (Jetpack Compose)       (SwiftUI)        (Browser DOM)
```

### 3.1 Pipeline A: Canonical Transcript Parser (Durable History)

Instead of guessing turns from screen dumps, read canonical session files when available:

- Per-agent stores, identities and record rules are in §5.
- **Fallback (Generic shell/agent)**: Strip ANSI sequences, split on prompt echo (`^[❯>▸]`), and group output into user and agent turns.

### 3.2 Pipeline B: Screen-State Prompt & Approval Engine (Interactive Interceptor)

Learned from `herdr-web-ui/server/prompt.ts`:

- Monitors the active viewport of working agents via `pane.read(source: "visible", stripAnsi: true)`.
- Detects interactive prompt states using regex signatures:
  - Claude confirmation menus (`enter to confirm`, `esc to cancel`).
  - Tool execution permission requests (`Allow ... to run`, `Approve tool call`).
  - Multi-choice option selectors (`1. Yes, 2. No`, `❯ option`).
- Synthesizes an interactive `PromptCard`:

  ```json
  {
    "id": "prompt-hash-123",
    "kind": "confirm" | "choice" | "input",
    "title": "Bash Command Approval",
    "message": "Allow agent to execute: rm -rf .tmp/test?",
    "options": ["Allow once", "Allow always", "Reject"],
    "selected_index": 0
  }
  ```

- Handles responses safely: verifies prompt screen signature still matches before sending keystrokes via `pane.send-keys` or `agent.prompt`.

### 3.3 Pipeline C: Ephemeral Status Extractor

- Extracts current spinner, timer, active model, and tool state from the footer or `agent.get`.
- Emits discrete `agent_status` events:

  ```json
  {
    "event": "agent_status",
    "session_id": "cap-lead",
    "state": "working",
    "detail": "Reading services/bridge/src/main.rs...",
    "elapsed_seconds": 14
  }
  ```

- **Rule**: Never append status lines to transcript message history.

---

## 4. Cappuccino Bridge v2 contract

Status: shipped in 0.2.0. The bridge serves v2 only; the v1 paths listed in §4.3 are removed.

### 4.0 What v2 changes

v2 changes three things and nothing else:

- **How a client connects.** A client handshakes before any other call (§4.1).
- **Which paths it calls.** Endpoints move under `/api/v2/` (§4.3).
- **How versions are checked.** Each v2 call carries the agreed protocol version (§4.1).

The payloads of the endpoints do not change. Their shapes are the shipped ones in
[services/bridge/README.md](../services/bridge/README.md).

### 4.1 Workflow

1. The phone calls `POST /api/v2/handshake` with its protocol range and the capabilities it
   implements. Nothing else is called before this succeeds.
2. The phone checks that `machine_id` in the response is the paired machine. A different id
   stops the connection.
3. Every later v2 request carries `X-Cappuccino-Protocol: 2`. A request without it, or with
   another value, gets HTTP 426.
4. The bridge keeps no record of an agreement. The client offers only the actions whose
   capability is in `agreed`.

### 4.2 Handshake

Request:

```json
{
  "protocol_min": 1,
  "protocol_max": 2,
  "capabilities": {"live_output": 1, "agent_control": 1, "prompt_input": 1},
  "required": ["live_output"]
}
```

- `capabilities` maps each name to the highest version the client speaks. A value of 0 or an
  absent key means unsupported. Unknown keys are ignored.
- `required` lists capabilities the client cannot run without.

Response (HTTP 200):

```json
{
  "event": "paired",
  "machine_id": "ced25706-f528-456e-b340-4fad8a6c8c88",
  "machine_name": "MacMini",
  "plugin": "cappuccino-bridge",
  "bridge_version": "0.2.0",
  "protocol": 2,
  "capabilities": {"agent_discovery": 1, "live_output": 1, "agent_control": 1},
  "agreed": {"live_output": 1, "agent_control": 1},
  "telegram": {"route": "enabled", "events": ["completion", "needs_answer", "route_failure"]}
}
```

- `agreed` holds each capability both sides speak, at the lower version.
- `telegram` is advisory status. A client treats an absent block as unknown.

Refusals, all with the envelope `{"event": "error", "code": ...}`:

| HTTP | Code | When | Extra fields |
| --- | --- | --- | --- |
| 426 | `protocol` | The range does not include version 2 | `"supported_protocol_min": 2, "supported_protocol_max": 2` |
| 409 | `capability_missing` | A `required` capability is not offered | `"capabilities": ["<name>", ...]` |
| 400 | `bad_request` | The body does not match the request shape | |

### 4.3 Endpoint map

| Method | Path (v2) | Capability | v1 path retired at cutover |
| --- | --- | --- | --- |
| `POST` | `/api/v2/handshake` | none | `GET /api/session` |
| `GET` | `/api/v2/agents` | `agent_discovery` | `GET /api/agents` |
| `GET` | `/api/v2/agents/{sessionId}/conversation` | `conversation_history` | `GET /api/agents/{sessionId}/conversation` |
| `GET` | `/api/v2/transcript?session=` | `conversation_history` | `GET /api/transcript` |
| `POST` | `/api/v2/agents/{sessionId}/prompt` | `prompt_input` | `POST /api/agents/{sessionId}/prompt` |
| `POST` | `/api/v2/agents/{sessionId}/control` | `agent_control` | `POST /api/agents/{sessionId}/control` |
| `GET` (WebSocket) | `/api/v2/stream?session=` | `live_output` | `GET /api/stream` |

Pairing and transport sit outside `/api/`, and each has its own version:

| Surface | Version | Role |
| --- | --- | --- |
| `GET /pair` | unversioned page | Owner-facing pairing page, served only when iroh is enabled. It shows the QR payload and is not part of the phone API. |
| ALPN `cappuccino-pair/1` | ALPN | Pairing-code exchange between a phone and a bridge. Unchanged in 0.2.0, so existing pairings keep working. |
| ALPN `cappuccino/1` | ALPN | Tunnel that carries the HTTP contract as-is, v2 paths included. This is transport framing, not the API version, so it is unchanged. |

### 4.4 Versioning rules

- **Additive within v2.** Fields may be added, never repurposed or removed.
- **Breaking changes need v3.** A change of meaning or a removal gets a new protocol number and a
  new path prefix.
- **Retired paths return 404.** After cutover, unversioned `/api/...` paths are removed, not
  redirected.

## 5. Agent session stores

Each agent writes its own session file on the machine that runs it. The bridge reads that file, maps it to turns, and returns turns to the phone. This section records, per agent, where the file is, how records and sessions are identified, and how records become turns. Interaction flows (prompting, approvals, questions) are in §6.

### 5.1 Pi

- **Store:** `~/.pi/agent/sessions/<cwd-slug>/<session>.jsonl`. `[pi] agent_dir` in the bridge config, when set to an absolute path, replaces the store with `<agent_dir>/sessions`. `PI_CODING_AGENT_DIR` in the bridge's own environment is a fallback only.
- **Identity:** the session header carries `id`. Each entry carries `id` and `parentId`, which form a tree.
- **Resolution:** the path Herdr reports must sit inside a configured store.
- **Active branch (P1):** the path from the file's last entry up to the root through `parentId`. This matches Pi's own load rule, because the leaf is not stored in the file.
- **Compaction (P2):** a `compaction` entry shows as a divider with its summary collapsed. Earlier entries stay visible.
- **Context edits (P3):** a `context_edit` with `replacement: null` leaves its target visible, with an "omitted from model context" marker.
- **Custom messages (P4):** a `custom_message` shows only when its `display` flag is true.
- **Not shown:** `custom`, `label`, `session_info` and `usage` entries.

### 5.2 Claude Code

- **Store:** `~/.claude/projects/<project-slug>/<session>.jsonl`, under `CLAUDE_CONFIG_DIR` when that is set.
- **Identity:** every record carries a `uuid`. The session id comes from the file name.
- **Skipped:** records marked `isSidechain` or `isMeta`, and harness notices.
- **Tool results** attach to the `tool_use` with the same id, within the same read. A result whose call falls outside the read is dropped.
- **Compaction** was observed as appended records. No file rewrite has been observed.

### 5.3 Agy

- **Store:** `~/.gemini/antigravity-cli/brain/<session-id>/.system_generated/logs/transcript.jsonl`.
- **Identity:** the session id is a UUID that Herdr reports through `agent.get` as `session_id`. Each line is one step, keyed by `step_index`.
- **Step types:** `USER_INPUT` (source `USER_EXPLICIT`) and `PLANNER_RESPONSE` (source `MODEL`, with `tool_calls` and `content`).
- **Resolution:** the bridge parses structured turns from this file. It never guesses turns from the terminal screen.

### 5.4 Other agents

- Codex rollout files appeared in an earlier draft of §3.1. No Codex reader is implemented, so none is specified here.

---

## 6. Actual Interactive Prompt Behaviors: Detailed Contract

Before low-effort sub-agents implement these features, the exact behavior across the 4 primary interaction flows must be specified:

```text
┌────────────────────────────────────────────────────────────────────────┐
│                        INTERACTIVE FLOW MATRIX                         │
├───────────────────┬──────────────────────────────┬─────────────────────┤
│ Interaction Type  │ Trigger / Detection          │ Resolution Action   │
├───────────────────┼──────────────────────────────┼─────────────────────┤
│ 1. Ask User Q     │ ask_question tool call       │ Submit answers JSON │
│ 2. Tool Approval  │ TUI permission prompt screen │ Allow / Deny key    │
│ 3. Agent Response │ Streaming token/markdown     │ Render markdown     │
│ 4. Prompt Deliver │ User initiates message       │ PTY text + Enter    │
└───────────────────┴──────────────────────────────┴─────────────────────┘
```

### 6.1 Flow 1: Claude Code `AskUserQuestion` (`ask_user_question`)

When Claude Code asks the owner a question through its `AskUserQuestion` tool. Claude Code writes the tool call to its transcript only after it is answered, so the visible form is the only source while it waits.

- **Detection**: the pane shows the form (footer `Enter to select`, a `Chat about this` row). The bridge reads the question on screen: its text, the options with their descriptions (the form's own "Type something" and "Chat about this" rows are not options), checkboxes for a multi-select, and the tab row's headers with their answered marks. One card is one question; as the form moves on, the next question becomes a new card. The final review becomes an `ask_user_question_review` card. Other choice menus keep the screen-read `ask_question` card.
- **Card Schema** (`prompt_id` is a hash of what is on screen plus a per-showing nonce; see Q10):

  ```json
  {
    "type": "ask_user_question",
    "prompt_id": "p-918ff1b95c4e",
    "title": "Pick a color?",
    "message": "Question 1 of 2",
    "tool_name": "AskUserQuestion",
    "options": [],
    "selected_index": 0,
    "questions": [
      {
        "header": "Color",
        "question": "Pick a color?",
        "multi_select": false,
        "options": [{"id": "opt-0", "label": "Red", "description": "warm"}]
      }
    ]
  }
  ```

  The review card has `type: "ask_user_question_review"`, the chosen answers as its `message`, and the options `Submit answers` and `Cancel`.
- **Resolution**:
  - `POST /api/agents/:sessionId/prompt` with the one question's answer: option indexes and/or one line of free text (the form's "Type something"):
    `{"type": "answer_question", "prompt_id": "p-918ff1b95c4e", "answers": [{"selected": [0, 2], "other": "a bird"}]}`
  - A single-select takes exactly one of an index or text; a multi-select takes at least one. Answers that do not fit are `400 bad_answer`.
  - The bridge re-reads the screen first: the same question must still show, with nothing ticked in the terminal; otherwise `409 stale_prompt`, and the owner finishes it in the terminal.
  - Keys, with a short pause between steps (checked against Claude Code 2.1 in a Herdr pane): single-select digit, or the "Type something" digit, the text and enter; multi-select digits toggle, free text goes on its row (reached from the `❯` cursor), then the Submit row and enter.
  - The review is answered with `answer_prompt`: `select_option` presses its digit alone (`1` submits, `2` cancels).
  - Cancel is `answer_prompt` with `action: "cancel"`: Esc on a question, `2` on the review.

### 6.2 Flow 2: Tool Permission Approval (`tool_approval`)

When an agent without `--dangerously-skip-permissions` attempts a high-stakes command (`run_command`, writing files):

- **Detection**:
  - Viewport matches permission dialog signatures:
    `/(?:Approve (?:this )?tool call|Allow .* to run|Execute command\?)/i`
  - Options present: `1. Yes / Allow`, `2. Always allow`, `3. No / Reject`.
- **Card Schema**:

  ```json
  {
    "type": "tool_approval",
    "prompt_id": "approval-hash-456",
    "tool_name": "run_command",
    "command": "git push origin main",
    "options": [
      {"id": "allow_once", "label": "Allow Once"},
      {"id": "allow_always", "label": "Allow Always"},
      {"id": "reject", "label": "Reject"}
    ]
  }
  ```

- **Resolution**:
  - Client sends selection: `{"type": "answer_prompt", "prompt_id": "approval-hash-456", "option_id": "allow_once"}`.
  - Bridge verifies the prompt is still active on screen, then sends keystroke `1` + `Enter` via Herdr `pane.send-keys`.

### 6.3 Flow 3: Streaming Agent Response

- **Behavior**:
  - While agent status is `working`, the bridge streams incremental markdown chunks (`event: "turn_chunk"`).
  - TUI spinner characters and footer meters are filtered out at the bridge.
  - When the step completes, bridge emits `event: "turn_complete", "turn_id": "t-..."`.
- **Client Rendering**:
  - Appends chunk text to the active turn bubble.
  - Formats markdown continuously using commonmark parser (fenced code, lists, bold).

### 6.4 Flow 4: User Prompt Delivery

- **Behavior**:
  - User types in the input field and hits Send.
  - Client sends `POST /api/agents/:sessionId/prompt` with `{"type": "prompt", "text": "continue with step 2"}`.
  - Bridge calls Herdr `agent.prompt(pane_id, text, wait=false)`.
  - PTY accepts submission, client creates an optimistic `user` turn bubble, and bridge emits `agent_status: "working"`.

---

## 7. Migration & Sub-Agent Task Breakdown

To enable low-effort, focused execution by delegated agents:

1. **Task 1: Status Slot Decoupling in Bridge (Rust)**
   - Filter transient status lines (`working · ...`, spinners) out of `reconcile.rs`.
   - Add discrete `agent_status` WebSocket event to `routes.rs`.
   - *Verification*: `cargo test -p cappuccino-bridge`.

2. **Task 2: Native Transcript Ingestion for `agy` and `pi` (Rust)**
   - In `services/bridge/src/modules/transcript.rs`, add reader for `~/.gemini/antigravity-cli/brain/<session>/.../transcript.jsonl` and `~/.pi/agent/sessions/...`.
   - Expose `GET /api/agents/:sessionId/conversation`.
   - *Verification*: Unit tests with real session JSONL samples.

3. **Task 3: Interactive Prompt Detection Engine (Rust)**
   - Implement `services/bridge/src/modules/prompt.rs` porting regex signatures from herdr-web-ui `server/prompt.ts`.
   - Expose `POST /api/agents/:sessionId/prompt` for card answering.
   - *Verification*: Synthetic ANSI screen test fixtures.

4. **Task 4: Thin Client Consumer Refactor (Android / Apple)**
   - Android: Remove legacy regexes from `TranscriptParser.kt`. Connect to `GET /conversation` and bind `agent_status` WS event directly to the header status badge.
   - Render clean `PromptCard` components when `prompt_request` arrives.
   - *Verification*: `make check-android && make check`.

---

## 8. Grilling Decisions & Edge-Case Contracts (Q1–Q11)

The following 11 mechanical decisions were established during grilling review to close all unseen failure modes and security gaps:

### Round 1: Safety & Core Foundations

1. **Approval Keystroke Safety (Q1)**:
   - Before typing keystrokes for an approval, the bridge computes a SHA256 of the normalized prompt bounding box lines from `pane.read(source: "visible")`.
   - If the signature has drifted, the bridge aborts fail-closed and refuses to type into the PTY.
2. **Hybrid Stream Synchronization (Q2)**:
   - Historical turns are served from canonical session logs (`transcript.jsonl`).
   - In-flight turns stream live PTY deltas from the bridge until the step completes, then seamlessly swap in the canonical markdown turn.
3. **Multi-Choice Form Emulation (Q3)**:
   - For `ask_question`, the bridge computes the delta from the currently selected index and simulates exact arrow/space/enter key sequences via `pane.send-keys`, with fallback to numeric text submission.
4. **Stateful Reconnect Snapshot (Q4)**:
   - The opening frame of the WebSocket (`stream_open`) immediately delivers the authoritative `agent_status` snapshot (`working`, `idle`, `exited`), preventing race conditions on phone wake.

### Round 2: Drift, Buffer & Multi-Device Coordination

5. **Transient Turn ID Binding (Q5)**:
   - In-flight turns are assigned synthetic IDs (`in_flight:<step_index>`). On step completion, `turn_commit` atomically replaces the transient entry with the canonical markdown turn without UI flicker.
6. **Semantic Anchor Hashing (Q6)**:
   - Viewport hash checks isolate the prompt bounding box lines via title and divider regexes, ignoring unstable background scrollback or footer clock tickers.
7. **Tail Window & Cursor Paging (Q7)** — superseded by section 9 (client-held watermark, no server cursor):
   - To protect mobile memory, the bridge reads transcripts backwards starting from the latest 2MB window (20–30 turns). Clients prepend earlier history via `cursor=offset:<bytes>`.
8. **Global Multi-Device Broadcast (Q8)**:
   - Prompt requests and approvals are broadcast across all active client connections. Answering a card on a phone immediately broadcasts `prompt_resolved` to dismiss it on the tablet and Mac.

### Round 3: Crash Recovery, Security & Control

9. **Stateless Re-hydration on Boot (Q9)**:
   - On bridge restart, it inspects Herdr's live pane state via `session.snapshot` and `agent.list`, re-emitting active prompts without requiring persistent disk state.
10. **One-Time Nonce Ephemeral Prompt IDs (Q10)**:
    - A card id is a hash of what the screen shows plus a nonce. The nonce stays while the card stays on screen, is renewed when the card leaves and comes back (the same command asked again is a new prompt), and is spent as soon as an answer or cancel is typed. A spent or stale id gets `409 stale_prompt` with the id on screen now.
11. **Graceful Cancellation Contract (Q11)**:
    - User interruption from mobile sends `ctrl+c` / `agent.stop` via Herdr RPC. Bridge updates status to `"interrupted"` and emits `turn_complete`. If the agent fails to yield within 3 seconds, a forceful kill option is presented.

## 9. Conversation sync (proposed)

Status: proposed. The decisions below come from the design review; none is implemented yet. Implementation follows the contract in reviewed slices.

### 9.1 Model

- The phone keeps a replicated log per session. The offline cache becomes that log.
- The bridge is stateless. It serves records from the agent's session file and holds no per-client state.
- The phone owns a watermark: where it stopped reading. It is an opaque token, `{file_id, offset}`, where `offset` is the byte position just past the last settled record applied.
- `file_id` is the agent's own session id (Pi header `id`, Claude session id, Agy conversation id). Session files are append-only; a rewrite is detected through `resync` (9.4).

### 9.2 Requests

- `GET /api/v2/agents/{sessionId}/conversation/sync?after=<token>` returns records after the watermark, forward. This is the catch-up path.
- `GET /api/v2/agents/{sessionId}/conversation/sync?before=<token>` returns an earlier page. The phone uses it to backfill history in the background, after the latest turns are shown (decision Q10).
- Both tokens are client-held. The bridge keeps no cursor state.
- Response:

```json
{
  "mode": "append",
  "upserts": [ { "id": "...", "role": "agent", "parts": [] } ],
  "watermark": "<token>",
  "more": true
}
```

- `upserts` holds whole turns, keyed by `id`. A turn is sent again whenever a later record changes it, such as a tool result or an omission marker.
- `more: true` means the phone should request the next page with the new token.

### 9.3 Settled boundary

- A record is settled when it belongs to a complete turn. The bridge never sends the watermark past an incomplete turn (decision Q9).
- An incomplete turn is still sent as an upsert, and the phone replaces it later (decision Q15).

### 9.4 Invalid watermark

- If the token names a different `file_id`, an offset past the end of the file, or a point that no longer matches the record there, the response is `"mode": "resync"`. The response then carries the first page of a fresh snapshot.
- The phone discards its local log for that session and applies the snapshot (decision Q8).

### 9.5 Turn identity

- A turn's `id` is the id of the record that opened it: Claude `uuid`, Agy `step_index`, Pi entry `id` (decision Q14).
- Positional ids (`t-001`) are not used on the wire.
- Open risk for Pi: entry ids are 8 hex characters. Ids are 32-bit random values, so by the birthday estimate a session of about 7,000 entries has roughly a 0.6% chance of a collision. The parser must check uniqueness per session, or the turn key must include the byte offset of the opening record.

### 9.6 Record-to-turn rules

- Per-agent record rules are in §5.1 to §5.3.

### 9.7 Budgets

- Sync page budget: 1 MiB per response, under the phone's 2 MiB ceiling. Proposed `[tuning.sync] page_bytes`.
- Stream message budget: 256 KB per WebSocket message, under the phone's 1 MiB ceiling. Proposed `[tuning.stream] message_bytes`. Entries are split across messages and none is dropped (decision Q3). The worst-case row width is unmeasured, so this number is provisional.
- The phone's own ceilings stay as they are (decision Q4).

### 9.8 Open items before implementation

1. Pi entry-id uniqueness check, or switch the turn key to include the opening record's byte offset (9.5).
2. Confirm the `file_id` source for each agent, and the behaviour when a session id is reused.
3. Measure the stream's worst-case row width on a real pane and set `message_bytes` from it.
4. Decide whether Claude's `/compact` ever rewrites a file. No rewrite has been observed. `resync` covers it either way.
