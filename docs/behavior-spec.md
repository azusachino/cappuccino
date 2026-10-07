# Behavior specification (provisional v0)

Shared cross-platform behavior contract for Cappuccino clients (iOS, Android). The companion-daemon and SSH-exec directions are superseded; `services/daemon` remains frozen reference work. The current client transport is the standalone Herdr plugin bridge (`services/bridge`), a loopback HTTP/WebSocket facade over selected Herdr APIs; it is not loaded into Herdr or an agent. Both platform test suites consume the same [fixtures](../fixtures/); implementations stay native. This file records intended behavior, not a published wire protocol. Changes here require updating both platforms' conformance fixtures in the same commit.

Authority: [intent](intent.md). Process/session lifetime belongs to Herdr/Pi; a phone never starts, replaces or terminates an agent.

## Identities

- `machine_id`: stable UUID for one host (Herdr machine identity).
- `session_id`: one Pi session on that machine; machine-scoped, never globally unique alone.
- `agent_ref`: `{machine_id, session_id}` selecting one existing agent process.
- `branch`: the session's active branch; abandoned branches are never concatenated into history.
- `request_id`: one typed approval request; `action_id`: one delivery attempt; `grant_id`: one exact grant.

**Current identity limit:** the read-only bridge catalog uses a Herdr agent name for named rows and a pane ID for unnamed rows as its current `session_id` locator. That value is not established as Pi's native session UUID or an occupant epoch. `agent_ref` is therefore only a current display/lookup locator; delivery must not target it as an immutable session identity. An approved delivery path must pin the native Pi session and Herdr terminal/pane incarnation at acceptance time.

## Delivery (Nudge / Follow-up)

**Status:** this remains an intended contract, not a shipped capability. The owner-selected acceptance is that `confirmed` means Pi accepted the requested queue operation; an ambiguous outcome is final `unresolved` and that action is not replayed. These are acceptance semantics, not runtime capabilities proven through Herdr. The current bridge has no delivery endpoint. Herdr 0.9.3's `agent.prompt` writes text followed by Enter to the agent PTY and reports PTY submission completion; its optional wait observes lifecycle state and does not identify a Pi queue disposition. `agent.read/get/wait` observe terminal output and lifecycle; `agent.send-keys` writes validated terminal keys. None selects Nudge versus Follow-up or proves Pi queue acceptance. The installed Pi RPC docs separately describe `steer` and `follow_up`; their semantics are not exposed by Herdr's prompt schema. See [architecture](architecture.md#herdr-input-api-and-delivery-boundary).

Do not map Herdr's prompt acknowledgement to `sent` or `confirmed`, and do not claim exactly-once delivery from bridge-side state. Reconcile owner acceptance before implementing a transport or Pi integration.

- Both target the same existing `{machine_id, session_id}`; they differ in boundary semantics (Nudge interrupts at the next safe boundary; Follow-up queues for completion).
- Every send produces a `DeliveryReceipt{action_id, state}` with states `pending → sent → confirmed`, or `unresolved` on ambiguous disconnect. `confirmed` means Pi accepted the requested queue operation. `unresolved` is final for that `action_id` and must not be replayed; no blind or automatic retry. A later explicit user action, if offered, is a new attempt with a new `action_id`.
- Receipts are idempotent per `action_id`; duplicate delivery of one action is a defect.
- A stale composer targeting a replaced session occupant fails visibly and never delivers.

## Approvals and grants

- Shell, file-write and unknown tools require an explicit typed decision; pending or denied requests never execute; offline phones never cause default approval.
- Local terminal and any phone compete for one `request_id`: first valid decision wins; losing, stale or duplicate decisions fail visibly.
- A `Grant{tool, args, cwd, session_id}` authorizes exactly that invocation. Reconnect never broadens it. Revocation, integration reload, branch change or session replacement invalidates it immediately.
- The daemon/integration arbitrates races; cached client state is never an approval authority.

## History and reconciliation

**Status:** this is the desired client contract. The issue #7 UI was accepted against scripted fixtures; a canonical live Pi transcript/WebSocket journey has not been verified. The current bridge fails closed when Herdr exposes no usable Pi session reference and otherwise reads pane output; do not treat scripted UI tests as live-history proof.

- Initial load returns the session's durable active-branch history; streaming appends only confirmed-complete entries.
- Reconciliation handles duplicates (idempotent by entry id), gaps (visible placeholder, never silent), out-of-order delivery (stable order by sequence), and session replacement (fresh stream; old pending actions/grants invalidated).
- Disconnect shows `unresolved` state for in-flight actions; foreground recovery reconciles without duplicate sends or decisions.

## Client lifecycle boundaries

- Activity recreation, tab switching and short backgrounding preserve selection and never re-send.
- Process death recovers from server state, not client memory; a dead app stores no grants and holds no approval authority.
- Background sockets, foreground services and FCM are out of scope for the first prototype; generic Telegram alerts come later without previews or approval buttons.

## Failure display

Every unavailable capability stays visibly unavailable (disabled actions plus explanation), matching the disconnected shells. Pairing failures, untrusted hosts and stale targets are explicit errors, never silent retries.
