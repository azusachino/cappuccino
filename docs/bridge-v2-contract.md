# Cappuccino Bridge & Interceptor Architecture Specification

**Status**: Proposal / Draft Contract  
**Target Repositories**: `vendor/cappuccino/services/bridge`, `refs/coding-agents/herdr-web-ui`  
**Related Documents**: [Architecture](architecture.md), [Herdr WebUI Research](herdr-webui-design-research.md), [Behavior Spec](behavior-spec.md)

---

## 1. Principles: Thin Client, Strong Bridge

Clients (Android, iOS/macOS, WebUI) should not act as VT100 terminal emulators or parse unstructured ANSI text to guess prompt boundaries, tool calls, and ephemeral status indicators.

- **Bridge owns normalization**: Connects to the local Herdr IPC socket, inspects agent processes and session files, parses live TUI prompts/approvals, and normalizes ANSI text into structured domain events.
- **Clients remain thin**: Consume typed HTTP/WebSocket endpoints to render native views (Jetpack Compose, SwiftUI, or Web DOM).
- **Separation of transient state vs durable history**: Ephemeral status (spinners, `working · ...`, resource meters) is maintained in a discrete status slot, never appended into historical transcript turns.

---

## 2. Herdr API Surface & Extension Model

Based on installed Herdr 0.9.3 and reference implementations in `refs/coding-agents/herdr-web-ui/server/herdr/client.ts`:

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

- **Pi**: `~/.pi/agent/sessions/<cwd-slug>/<session>.jsonl`
- **Claude Code**: `~/.claude/projects/<project-slug>/<session>.jsonl`
- **Codex**: rollout jsonl files.
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

## 4. Cappuccino Bridge V2 API Contract

### 4.1 HTTP REST Endpoints

#### `GET /api/session`

Returns machine identity, protocol version, and bridge capabilities.

```json
{
  "event": "paired",
  "machine_id": "ced25706-f528-456e-b340-4fad8a6c8c88",
  "protocol": 2,
  "capabilities": ["stream", "turns", "prompts", "approvals"]
}
```

#### `GET /api/agents`

Returns active agents, their execution status, git branch, and pending interactive prompts.

```json
{
  "agents": [
    {
      "session_id": "cap-lead",
      "pane_id": "w1:p3K",
      "agent": "agy",
      "label": "agy --dangerously-sk",
      "cwd": "/Users/azusachino/Projects/project-github/harus-workstation",
      "status": "working",
      "active_branch": "main",
      "pending_prompt": null
    }
  ]
}
```

#### `GET /api/agents/:sessionId/conversation`

Returns paginated, structured conversation turns (markdown-ready).

```json
{
  "session_id": "cap-lead",
  "source": "canonical_log",
  "turns": [
    {
      "id": "t-001",
      "role": "user",
      "timestamp": "2026-10-07T23:30:00Z",
      "text": "Check git status"
    },
    {
      "id": "t-002",
      "role": "agent",
      "timestamp": "2026-10-07T23:30:05Z",
      "parts": [
        {
          "type": "tool_call",
          "name": "run_command",
          "input": {"CommandLine": "git status -s"},
          "output": "M docs/architecture.md"
        },
        {
          "type": "text",
          "text": "The working tree has modifications in `docs/architecture.md`."
        }
      ]
    }
  ]
}
```

#### `POST /api/agents/:sessionId/prompt`

Submits a user prompt or answers an interactive prompt card.

```json
// Prompt submission
{
  "type": "prompt",
  "text": "run make check"
}

// Or answering an approval card
{
  "type": "answer_prompt",
  "prompt_id": "prompt-hash-123",
  "action": "select_option",
  "option_index": 0
}
```

---

### 4.2 WebSocket Stream (`WS /api/stream?session=:sessionId`)

Clients open a single persistent WebSocket to stream live execution.

#### Message Types Sent by Bridge

1. **`stream_open`**: Confirms connection and current state generation.
2. **`agent_status`**: Replaces the single status slot on the client (does NOT touch history).

   ```json
   {
     "event": "agent_status",
     "state": "working",
     "detail": "Running make check..."
   }
   ```

3. **`turn_chunk`**: Incremental text/markdown for the active assistant turn.

   ```json
   {
     "event": "turn_chunk",
     "turn_id": "t-003",
     "role": "agent",
     "delta": "All tests passed successfully."
   }
   ```

4. **`prompt_request`**: Dispatched when agent hits an interactive question or tool approval.

   ```json
   {
     "event": "prompt_request",
     "prompt": {
       "id": "p-987",
       "kind": "confirm",
       "title": "Permission Required",
       "options": ["Allow", "Deny"]
     }
   }
   ```

5. **`prompt_resolved`**: Closes the pending prompt card once answered or dismissed.

---

---

## 5. Antigravity (`agy`) Session Model & Interaction Protocol

Antigravity (`agy`) is the agent actively powering this workstation environment and running across Herdr panes.

### 5.1 Storage & Session File Resolution

- **Base Directory**: `~/.gemini/antigravity-cli/`
- **Session Identification**: A session has a unique UUID (e.g. `691371cf-798b-47d6-ae99-ff7e3a0a23a5`).
- **Transcript Logs**:
  - Located at `~/.gemini/antigravity-cli/brain/<session-id>/.system_generated/logs/transcript.jsonl`.
  - Compact NDJSON containing incremental entries. Each line represents a step:
    - `"type": "USER_INPUT"`, `"source": "USER_EXPLICIT"`
    - `"type": "PLANNER_RESPONSE"`, `"source": "MODEL"` with `"tool_calls": [...]` and `"content": "..."`
- **Resolution in Bridge**:
  - Herdr reports `session_id` via `agent.get`.
  - The bridge locates `~/.gemini/antigravity-cli/brain/<session-id>/.system_generated/logs/transcript.jsonl`.
  - Parses structured turns directly without any terminal screen guessing.

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

### 6.1 Flow 1: `ask_user_question` / `ask_question`

When an agent (like `agy` or `omo`) pauses execution to elicit user clarification:

- **Detection**:
  - *From session log*: Assistant tool call with `name: "ask_question"` or `"ask_user_question"` containing `questions: [{ question, options, is_multi_select }]`, with no corresponding `toolResult` yet.
  - *From screen*: Live TUI form showing question title, radio/checkbox options, and hint `enter select / tab next`.
- **Card Schema**:

  ```json
  {
    "type": "ask_question",
    "prompt_id": "step-2640-ask",
    "questions": [
      {
        "id": "q1",
        "question": "Which backend framework should we use?",
        "is_multi_select": false,
        "options": ["Axum (Recommended)", "Actix-web"]
      }
    ]
  }
  ```

- **Resolution**:
  - Client answers via `POST /api/agents/:sessionId/prompt`:
    `{"type": "answer_question", "prompt_id": "step-2640-ask", "answers": {"q1": "Axum (Recommended)"}}`
  - Bridge sends the response back to the waiting agent via `pane.send-keys` (selecting the options) or via the agent's interactive stdin.

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
   - Implement `services/bridge/src/modules/prompt.rs` porting regex signatures from `refs/coding-agents/herdr-web-ui/server/prompt.ts`.
   - Expose `POST /api/agents/:sessionId/prompt` for card answering.
   - *Verification*: Synthetic ANSI screen test fixtures.

4. **Task 4: Thin Client Consumer Refactor (Android / Apple)**
   - Android: Remove legacy regexes from `TranscriptParser.kt`. Connect to `GET /conversation` and bind `agent_status` WS event directly to the header status badge.
   - Render clean `PromptCard` components when `prompt_request` arrives.
   - *Verification*: `make check-android && make check`.
