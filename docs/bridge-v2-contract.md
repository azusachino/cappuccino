# Cappuccino Bridge Protocol v2

**Status**: Draft for freeze. The 0.2.0 bridge and apps speak a pre-freeze draft that this document replaces. The freeze tag is the first stable v2. Until then, shapes here may still change.
**Clients**: native Android and iOS apps. The browser WebUI is not a v2 client.
**Related**: [Architecture](architecture.md), [Behavior Spec](behavior-spec.md), [Intent](intent.md).

---

## 1. Scope and principles

- **Thin client, strong bridge.** The bridge owns normalization: it reads Herdr and each agent's session file, parses prompts on screen, and returns typed data. Clients never parse terminal output.
- **The bridge never starts, stops or kills an agent.** Its only control actions are `interrupt` (Esc) and `new_session` (typing `/new` into an idle session).
- **Transient state is not history.** Spinners, footers and status lines travel as `agent_status` events. They never become conversation turns.
- **Typed input is not delivery.** The bridge reports that text was typed into the pane (`prompt_typed`). Herdr does not confirm acceptance, so no receipt is claimed.

## 2. Herdr surface used by the bridge

The bridge talks to Herdr over its local Unix socket, one line-delimited JSON-RPC request per connection. Methods used:

- `agent.list`, `agent.get { session_id }`: agent descriptors (`cwd`, `agent_status` as `idle` or `working`, `agent_session` as the reported native session path or id).
- `pane.read { pane_id, source, lines }`: visible screen or recent scrollback.
- `pane.send_keys { pane_id, keys }`: logical keystrokes.
- `pane.send_text { pane_id, text }`: literal text.

## 3. Bridge pipelines

- **Canonical history.** Turns come from each agent's session file (§5, §7). Never from screen text.
- **Prompt detection.** Permission dialogs and question forms are read from the visible screen and become prompt cards (§6). A card is answered only if the same prompt is still on screen.
- **Live status.** The stream emits `agent_status` changes and live pane rows. Spinners and footer meters are filtered out.

---

## 4. Wire contract

### 4.1 Versioning and freeze

- The protocol number is `2`. It is carried in the `X-Cappuccino-Protocol: 2` header on every call after the handshake.
- The freeze tag marks v2 stable. After it, v2 changes are additive only (§4.7). A breaking change needs v3, a new protocol number and a new path prefix.
- The 0.2.0 draft is not stable, so clients built against it are not supported after the freeze.

### 4.2 Connection and handshake

1. The client calls `POST /api/v2/handshake` with its protocol range and capabilities. Nothing else is called before this succeeds.
2. The client checks that `machine_id` in the response is the machine it registered. A different id ends the connection.
3. Every later call carries `X-Cappuccino-Protocol: 2`. A missing or different value returns `400 protocol_unsupported`.
4. The bridge keeps no record of an agreement. The client offers only the actions whose capability is in `agreed_capabilities`.

Request:

```json
{
  "protocol_min": 2,
  "protocol_max": 2,
  "offered_capabilities": {
    "live_output": {"version": 1},
    "agent_control": {"version": 1},
    "prompt_input": {"version": 1}
  },
  "required_capabilities": ["live_output"]
}
```

- `offered_capabilities` maps each capability name the client speaks to an object whose `version` is the highest version it speaks. An absent name means the client does not speak it. Unknown names are ignored.
- `required_capabilities` lists capabilities the client cannot run without.

Response (HTTP 200):

```json
{
  "event": "handshake_accepted",
  "machine_id": "ced25706-f528-456e-b340-4fad8a6c8c88",
  "machine_name": "MacMini",
  "bridge_version": "0.2.0",
  "protocol_version": 2,
  "offered_capabilities": {
    "agent_discovery": {"version": 1},
    "conversation_sync": {"version": 1},
    "live_output": {"version": 1},
    "prompt_input": {"version": 1},
    "prompt_answers": {"version": 1},
    "agent_control": {"version": 1},
    "completion_signals": {"version": 1}
  },
  "agreed_capabilities": {
    "live_output": {"version": 1},
    "agent_control": {"version": 1}
  },
  "telegram": {"route": "enabled", "events": ["completion", "needs_answer", "route_failure"]}
}
```

- `agreed_capabilities` holds each capability both sides offer, at the lower of the two versions.
- `telegram` is advisory status. A client treats an absent block as unknown.
- Refusals: `400 protocol_unsupported` when the range excludes 2, with `supported_protocol_min: 2` and `supported_protocol_max: 2`. `409 capability_missing` when a required capability is not offered, with `missing_capabilities: ["<name>", ...]`.

### 4.3 Capability registry

Every capability is at version `1` in this contract. A capability's version changes only when its payloads change in a breaking way, which needs v3 (§4.1).

| Name | Promises |
| --- | --- |
| `agent_discovery` | `GET /api/v2/agents` lists agents with `session_id`, name, `cwd`, status and branch where known. |
| `conversation_sync` | The sync endpoint in §7 returns upserts and tokens. |
| `live_output` | The stream endpoint in §4.4 delivers live pane rows and status events. |
| `prompt_input` | `POST .../prompt` with `type: "prompt"` types text into the pane. |
| `prompt_answers` | `POST .../prompt` with `type: "answer_question"` or `type: "answer_prompt"` answers a prompt card. |
| `agent_control` | `POST .../control` with `interrupt` or `new_session`. |
| `completion_signals` | The stream sends `agent_status` changes that show a session has finished working or is waiting for input. |

The capability list is advisory. The bridge does not refuse a call because the client did not agree a capability. A client must not call an endpoint whose capability is missing from `agreed_capabilities`.

### 4.4 Endpoints

Every session-scoped path uses `{sessionId}`, the id from `agent_discovery`. Mutating calls (`prompt`, `control`) require `expected_session` (§4.8).

| Method | Path | Capability | Notes |
| --- | --- | --- | --- |
| `POST` | `/api/v2/handshake` | none | §4.2 |
| `GET` | `/api/v2/agents` | `agent_discovery` | |
| `GET` | `/api/v2/agents/{sessionId}/conversation/sync` | `conversation_sync` | §7 |
| `POST` | `/api/v2/agents/{sessionId}/prompt` | `prompt_input`, `prompt_answers` | Body `type` selects the action. |
| `POST` | `/api/v2/agents/{sessionId}/control` | `agent_control` | Body `action` selects the action. |
| `GET` (WebSocket) | `/api/v2/agents/{sessionId}/stream` | `live_output` | §6.5 |

The transcript endpoint `/api/v2/transcript` is removed. No client calls it.

Pairing and transport sit outside `/api/`, each with its own version:

| Surface | Version | Role |
| --- | --- | --- |
| `GET /pair` | unversioned page | Owner-facing pairing page, served only when iroh is enabled. Not part of the phone API. |
| ALPN `cappuccino-pair/1` | ALPN | Pairing-code exchange. |
| ALPN `cappuccino/1` | ALPN | Tunnel that carries this HTTP contract unchanged. Transport framing, not the API version. |

### 4.5 Field naming rule

Every request and response field must say what it means on its own.

- No bare numbers or positional arrays as values. A version, count or choice carries a key that names it.
- An array is allowed only when its key names the items, for example `options`, `events` or `missing_capabilities`.
- An item is identified by its id, never by its position in a list.
- A new field follows this rule. A field that breaks it needs v3 (§4.1).

### 4.6 Errors

Every error response has this envelope:

```json
{"event": "error", "code": "stale_prompt", "message": "the question is no longer shown", "on_screen_prompt_id": null}
```

- `code` is the stable identifier. Clients branch on it.
- `message` is for display only. Its wording may change.
- Extra fields are listed in the table, and only for the codes that define them.

| HTTP | Code | When | Extra fields |
| --- | --- | --- | --- |
| 400 | `protocol_unsupported` | Missing or different `X-Cappuccino-Protocol`, or a handshake range that excludes 2 | `supported_protocol_min`, `supported_protocol_max` (handshake only) |
| 400 | `bad_request` | The body does not match the request shape, or an unknown `type` or `action` | |
| 400 | `session_binding_required` | A mutating call has no `expected_session` | |
| 400 | `bad_session_binding` | `expected_session` is malformed | |
| 400 | `bad_answer` | An answer does not fit the question (for example two options for a single-select) | |
| 404 | `not_found` | No agent has this `sessionId` | |
| 409 | `capability_missing` | Handshake: a required capability is not offered | `missing_capabilities` |
| 409 | `confirm_command` | A slash command needs `confirmed: true` | `command` |
| 409 | `agent_busy` | `new_session` while the agent is working or blocked | |
| 409 | `stale_prompt` | The prompt id is spent or no longer on screen | `on_screen_prompt_id` (null if none) |
| 413 | `payload_too_large` | The request body exceeds `max_request_body_bytes` | |
| 502 | `upstream_unavailable` | Herdr cannot be reached, or it returned an error | |
| 502 | `outcome_unknown` | A keystroke sequence may have been partly typed (§4.8) | |
| 500 | `internal_error` | Unexpected bridge fault | |

A client that receives an unknown code uses the HTTP status family to decide what to show.

### 4.7 Forward compatibility

- Clients ignore unknown fields, unknown events and unknown error codes.
- A prompt card with an unknown `type` renders as "answer in the terminal".
- A payload changes only through a capability version bump or v3. Additive fields are the only change allowed in v2.

### 4.8 Retries and timeouts

- The handshake and all `GET` calls are safe to retry.
- `prompt` and `control` are never retried automatically. A send that fails is shown to the user, who decides.
- `outcome_unknown` is final. The bridge never replays keystrokes, and neither does a client.
- Every mutating call carries `expected_session: {machine_id, pane_id, native_session_ref}` from the agent's last `agent_discovery` result. The bridge refuses the call if the binding no longer matches.
- Answers are protected by spent prompt ids: an id is used once, and a repeat gets `stale_prompt`.
- A multi-step answer is bounded by a server time budget (`[tuning.prompt] answer_budget_secs`, proposed default 10). If the budget runs out, the response is `outcome_unknown`.
- A client re-runs the handshake after every reconnect.

---

## 5. Agent session stores

Each agent writes its own session file on the machine that runs it. The bridge reads that file, maps it to turns, and returns turns to the phone. Turn ids are opaque strings, unique within a session. Interaction flows are in §6.

### 5.1 Pi

- **Store:** `~/.pi/agent/sessions/<cwd-slug>/<session>.jsonl`. `[pi] agent_dir` in the bridge config, when set to an absolute path, replaces the store with `<agent_dir>/sessions`. `PI_CODING_AGENT_DIR` in the bridge's own environment is a fallback only.
- **Session id:** the session header's `id`. Each entry carries `id` and `parentId`, which form a tree.
- **Turn id:** the entry `id`. Entry ids are 8 hex characters, so a duplicate within one read gets the entry `id` plus `#` and a digest of its record. A collision between two reads is not caught (section 8).
- **Resolution:** the path Herdr reports must sit inside a configured store.
- **Active branch (P1):** the path from the file's last entry up to the root through `parentId`. This matches Pi's own load rule, because the leaf is not stored in the file. Records off the branch are dropped before parsing, so their tool results and edits cannot reach shown turns. The index is in section 7.1.
- **Compaction (P2):** a `compaction` entry is a `notice` turn with one `divider` part (label `compaction`, the summary). Earlier entries stay visible.
- **Context edits (P3):** a `context_edit` with `replacement: null` leaves its target visible, with a `context_omitted` part on the turn that owns it. A tool result belongs to the assistant turn that called the tool, so the marker goes there. When the edit falls in a page but its turn does not, that turn is sent again with its tool results. Only edits on the active branch count.
- **Custom messages (P4):** a `custom_message` is a `notice` turn only when its `display` flag is true.
- **Not shown:** `custom`, `label`, `session_info` and `usage` entries.

### 5.2 Claude Code

- **Store:** `~/.claude/projects/<project-slug>/<session>.jsonl`, under `CLAUDE_CONFIG_DIR` when that is set.
- **Session id:** the session id from the file name.
- **Turn id:** the record's `uuid`.
- **Skipped:** records marked `isSidechain` or `isMeta`, and harness notices.
- **Tool results** attach to the turn that holds the `tool_use` with the same id. When the result is in a later record than the call, that turn is re-sent as an upsert (§7.4).
- **Compaction** was observed as appended records. No file rewrite has been observed.

### 5.3 Agy

- **Store:** `~/.gemini/antigravity-cli/brain/<session-id>/.system_generated/logs/transcript.jsonl`.
- **Session id:** the UUID that Herdr reports through `agent.get` as `session_id`.
- **Turn id:** `step-<step_index>`, for example `step-4`. Each line is one step.
- **Step types:** `USER_INPUT` (source `USER_EXPLICIT`) and `PLANNER_RESPONSE` (source `MODEL`, with `tool_calls` and `content`).
- **Resolution:** the bridge parses structured turns from this file. It never guesses turns from the terminal screen.

---

## 6. Interaction flows

### 6.1 Claude Code questions (`ask_user_question`)

Claude Code writes a question's tool call to its transcript only after it is answered, so the visible form is the only source while it waits.

- **Detection:** the pane shows the form (footer `Enter to select`, a `Chat about this` row). The bridge reads the question text, its options with their descriptions, checkboxes for multi-select, and the tab row. The form's own "Type something" and "Chat about this" rows are not options.
- **Card (one per question):**

  ```json
  {
    "type": "ask_user_question",
    "prompt_id": "p-918ff1b95c4e",
    "title": "Pick a color?",
    "tool_name": "AskUserQuestion",
    "question_number": 1,
    "question_count": 2,
    "questions": [
      {
        "id": "q-1",
        "header": "Color",
        "question": "Pick a color?",
        "multi_select": false,
        "highlighted_option_id": "opt-0",
        "options": [{"id": "opt-0", "label": "Red", "description": "warm"}]
      }
    ]
  }
  ```

- **Review card:** after the last question the form shows its review. The bridge sends `type: "ask_user_question_review"`, with one display-only `answer` per answered question, and two options. `question_id` is the id the question card had (a hash of the question text). A wrapped question line is joined with a space, as the form joins it. The review's answers are shown, not sent back, so the phone never submits them by id:

  ```json
  {
    "type": "ask_user_question_review",
    "prompt_id": "p-7c2e0a91",
    "answers": [{"question_id": "q-abb508ec5cb9", "answer": "Blue"}],
    "options": [{"id": "submit", "label": "Submit answers"}, {"id": "cancel", "label": "Cancel"}],
    "default_option_id": "submit"
  }
  ```

- **Answer a question:** `POST .../prompt` with `type: "answer_question"`:

  ```json
  {
    "type": "answer_question",
    "prompt_id": "p-918ff1b95c4e",
    "expected_session": {"machine_id": "...", "pane_id": "...", "native_session_ref": "..."},
    "answers": [{"question_id": "q-1", "selected_option_ids": ["opt-0", "opt-2"], "free_text": "a bird"}]
  }
  ```

  A single-select takes exactly one of `selected_option_ids` (one id) or `free_text`. A multi-select takes at least one id. An answer that does not fit returns `400 bad_answer`.
- **Before typing:** the bridge re-reads the screen. The same question must still show, with nothing ticked in the terminal. Otherwise it returns `409 stale_prompt`, and the owner finishes the form in the terminal.
- **Keys:** sent with a short pause between steps, checked against Claude Code 2.1. Single-select sends the option digit, or the "Type something" digit then the text and Enter. Multi-select toggles digits, types free text on its row, then moves to Submit and presses Enter.
- **Answer the review:** `POST .../prompt` with `type: "answer_prompt"` and `option_id` (`submit` or `cancel`). Submit presses `1`, and cancel presses `2`.

### 6.2 Tool approval (`tool_approval`)

When an agent without `--dangerously-skip-permissions` attempts a high-stakes command:

- **Detection:** the viewport matches a permission dialog, and the options are `Yes / Allow` and `No / Reject`.
- **Card:**

  ```json
  {
    "type": "tool_approval",
    "prompt_id": "approval-hash-456",
    "tool_name": "run_command",
    "command": "git push origin main",
    "options": [
      {"id": "allow_once", "label": "Allow once"},
      {"id": "reject", "label": "Reject"}
    ],
    "default_option_id": "allow_once"
  }
  ```

- **Always-allow is not offered.** The agent's persistent "always allow" option is hidden, and the bridge never selects it, until grants are designed (intent: narrow, revocable grants only).
- **Answer:** `POST .../prompt` with `type: "answer_prompt"`, `prompt_id` and `option_id`. The bridge checks that the prompt is still on screen, then sends `1` or `2` and Enter.
- **Cancel** (`action: "cancel"` in place of `option_id`) sends Esc.

### 6.3 Prompt answer responses

The answer response is `{"event": "prompt_answered", "prompt_id": "...", "outcome": "option"}` or `"outcome": "cancelled"`. A question submission returns `"outcome": "answered"`. A question submission returns `"outcome": "answered"`. The field `outcome` replaces the earlier `action` field, so one word means one thing.

### 6.4 Prompt delivery

- The client sends `POST .../prompt` with `{"type": "prompt", "text": "...", "expected_session": {...}}`. Add `"confirmed": true` only after the user has confirmed a slash command.
- A slash command such as `/model` or `/new` without `confirmed: true` returns `409 confirm_command`.
- On success the bridge types the text and Enter into the pane and returns `{"event": "prompt_typed", "session_id": "..."}`. The client shows the text as sent by the user, but makes no claim about delivery.

### 6.5 Stream

`GET /api/v2/agents/{sessionId}/stream` is a WebSocket. Its events:

- `stream_open` (first frame): `{"event": "stream_open", "session_id": "...", "stream_generation": 3}`. It also carries the current `agent_status`, so a reconnecting client has a snapshot.
- `agent_status`: `{"event": "agent_status", "session_id": "...", "state": "working", "detail": "...", "elapsed_seconds": 14}`. `state` is `idle`, `working` or `exited`.
- `entries`: live pane rows, for the live view only: `{"event": "entries", "stream_generation": 3, "entries": [{"id": "...", "seq": 412, "text": "..."}]}`. Pane rows are not turns.
- `stream_reset`: the live window restarted. The client drops its live rows. The next `entries` event carries the new `stream_generation`.
- `prompt_request`: `{"event": "prompt_request", "session_id": "...", "prompt": {card}}`.
- `prompt_resolved`: `{"event": "prompt_resolved", "session_id": "...", "prompt_id": "..."}`, sent when the card leaves the screen.
- `conversation_changed`: `{"event": "conversation_changed", "session_id": "..."}`, a hint to run a sync request. It carries no turns.
- `error`: the envelope from §4.6. Pane read failures arrive as `upstream_unavailable`.

Turns never arrive on the stream. Turns come only from the sync endpoint (§7).

---

## 7. Conversation sync

### 7.1 Model

- The phone keeps a replicated log per session. The offline cache is that log.
- The bridge keeps no per-client state. Each request reads the agent's session file. For Pi it also keeps one index per session file (the entry tree and the context edits), derived only from the file's bytes and extended as the file grows (section 5.1).
- A token is opaque to the phone. It is hex-encoded JSON of `{file, offset, record}`:
  - `file` is the session file's identity: its file stem for Pi and Claude Code, and the conversation directory name for Agy, whose file is always `transcript.jsonl`.
  - `offset` is the byte offset of the record a page starts from. Every page starts at the record that opens a turn.
  - `record` is that record's turn id.
- A token is trusted only when `file` matches the session's current file and the record at `offset` still has the id `record`. Otherwise the response is a resync (7.5).

### 7.2 Requests

- `GET /api/v2/agents/{sessionId}/conversation/sync` with no query returns the newest page, with both tokens. Use it for a first sync.
- `?after=<token>` returns the records after the phone's forward position. This is the catch-up path.
- `?before=<token>` returns the turns before the phone's earliest turn. The phone uses it to backfill in the background.
- Sending both `after` and `before` is `400 bad_request`.

### 7.3 Response

```json
{
  "mode": "append",
  "upserts": [
    {"id": "a1b2", "role": "agent", "timestamp": "2026-11-04T09:00:00Z", "parts": [{"type": "text", "text": "..."}]}
  ],
  "after_token": "<token>",
  "before_token": "<token>",
  "caught_up": false,
  "conversation_id": "pi:<digest>",
  "stats": {}
}
```

- `mode` is `append`, `resync` or `scrollback`.
- `upserts` holds whole turns. The phone replaces a turn with the same `id`, and adds a turn with a new id.
- Which fields are present depends on the request:

| Request | `after_token` | `before_token` | `caught_up` | `conversation_id`, `stats` |
| --- | --- | --- | --- | --- |
| no query | yes | yes (`null` at the start of the file) | yes | yes (when known) |
| `after` | yes | no | yes | no |
| `before` | no | yes (`null` at the start of the file) | no | no |
| resync | yes | yes | yes | yes |

- `caught_up` is true when the page reached the end of the complete records in the file.
- `conversation_id` is present only when the canonical id can be established. `stats` is the composer's session stats (model, context, rate limits).

### 7.4 Turn boundaries and late changes

- A page holds whole turns, and it always contains at least two turn openers, so it makes progress. Every turn of the page is sent in full. The forward token points at the earliest turn still waiting for a tool result, or else at the last turn of the page. The first turn of a page is never held, so each page moves on. The next request sends the held turn again, complete, and an upsert replaces it.
- A turn is complete once a later turn has opened. The bridge never sends a record past the end of the file's complete lines, so a record still being written is not served.
- A tool result that arrives later is sent with its turn, because the token is held at that turn until the result comes back.

### 7.5 Invalid token

- `mode: "resync"` is returned when a token names a different session file, an offset past the end of the complete records, a record id that no longer matches, or an undecodable token. The response is the newest page, with fresh tokens. The phone discards its local log for that session and applies the page.
- For Pi, a token whose `record`, or whose `last` turn, is off the active branch resyncs. `last` is the page's last turn and is set on forward pages.

### 7.6 Scrollback

- A session with no canonical log answers `mode: "scrollback"`, with the pane's recent lines as turns. Its tokens are `null`, and `caught_up` is true. Scrollback turns have positional ids, which are not stable across reads.

### 7.7 Budgets

- Sync page: at most `[tuning.sync] page_bytes` (default 1 MiB) of records per response, except that a single turn is never split. A page holding one turn larger than that holds only that turn.
- Stream message: at most `[tuning.stream] message_bytes` (default 256 KiB) per `entries` event. Entries are split across events and none is dropped. A single row larger than the budget is sent alone.

## 8. Decisions on the open items

These close the open items from the design review. Each is implemented unless it says otherwise.

1. **Duplicate Pi entry ids.** Entry ids are short, so a session file can contain the same id twice. The bridge does not reject the session. Each turn named by a duplicated id is named `<id>#<byte offset of its record>`. Offsets are stable because session files are append-only, so the names stay unique.
2. **Session file identity (section 7.1).** Pi and Claude Code: the file stem. Agy: the conversation directory. A new file for the same session gets a new identity, so its tokens resync.
3. **Largest turn.** Each text field of a turn is capped at 64 KiB. A longer field is cut and the turn carries `truncated: true`. Tool inputs are not capped, which is a known limit: a very large tool input can still exceed a page.
4. **Claude Code `/compact`.** No rewrite of a session file has been observed, so no special handling is added. A rewrite that moved an offset would fail the token check and resync, which is the intended outcome.
5. **Defaults.** `answer_budget_secs` = 10, enforced between the steps of a multi-step answer; when it runs out, the response is `outcome_unknown`. `page_bytes` = 1 MiB. `message_bytes` = 256 KiB. The stream budget only sets where a message splits, so it is safe at any value; it is not tuned by measurement.

---

## 9. Decision record

Decisions made in design review, with their current status. Earlier numbering (Q1–Q16, P1–P4, S-series in review notes) is kept for traceability.

| Decision | Outcome |
| --- | --- |
| Approval keystrokes fail closed when the prompt region's signature changes (Q1) | Kept (§6.2) |
| Multi-choice answers are typed from the current selection (Q3) | Kept (§6.1) |
| Stream opens with a status snapshot (Q4) | Kept (§6.5) |
| Turn ids come from the record that opened the turn (Q14) | Kept, with opaque string ids (§5) |
| Watermark-based sync with client-held tokens; no server cursor (Q7, superseded) | Kept (§7) |
| Invalid token returns `resync` (Q8) | Kept (§7.5) |
| Settled boundary; incomplete turns re-sent as upserts (Q9, Q15) | Kept (§7.4) |
| Fresh phone syncs the newest page first, then backfills (Q10) | Kept (§7.2) |
| Pages under a byte budget, nothing lost (Q11) | Kept (§7.6) |
| Live output is a pane view and stays separate from sync (Q12) | Kept (§6.5) |
| Prompt card ids are nonce-spent (Q10 in the earlier round) | Kept (§4.8) |
| Interrupt is Esc; the bridge never kills an agent (Q11 earlier round) | Kept, forced kill removed (§1) |
| Turn events `turn_chunk`, `turn_complete`, `turn_commit` | Removed. Sync upserts are the only turn path. |
| `allow_always` offered to the phone | Hidden until grants are designed (§6.2) |
| Global broadcast of prompt cards to every client | Deferred. Not part of the freeze. |
| Native clients only; WebUI is not a v2 client | Decided |
