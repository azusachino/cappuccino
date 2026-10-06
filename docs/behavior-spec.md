# Behavior specification (provisional v0)

Shared cross-platform contract for Cappuccino clients (iOS, Android) against the companion daemon. Both platform test suites consume the same [fixtures](../fixtures/); implementations stay native. This file records behavior, not a wire protocol: per the 2026-10-06 architecture pivots the production transport is the herdr plugin bridge (`services/bridge`, HTTP/WS loopback API over the herdr socket), which supersedes the SSH-exec direction; the slice-A daemon's wire v0 (`services/daemon/README.md`) remains a reference implementation only. Changes here require updating both platforms' conformance fixtures in the same commit.

Authority: [intent](intent.md). Process/session lifetime belongs to Herdr/Pi; a phone never starts, replaces or terminates an agent.

## Identities

- `machine_id`: stable UUID for one host (Herdr machine identity).
- `session_id`: one Pi session on that machine; machine-scoped, never globally unique alone.
- `agent_ref`: `{machine_id, session_id}` selecting one existing agent process.
- `branch`: the session's active branch; abandoned branches are never concatenated into history.
- `request_id`: one typed approval request; `action_id`: one delivery attempt; `grant_id`: one exact grant.

## Delivery (Nudge / Follow-up)

- Both target the same existing `{machine_id, session_id}`; they differ in boundary semantics (Nudge interrupts at the next safe boundary; Follow-up queues for completion).
- Every send produces a `DeliveryReceipt{action_id, state}` with states `pending → sent → confirmed`, or `unresolved` on ambiguous disconnect. `unresolved` is final for that `action_id`; a retry mints a new `action_id`. No blind retries.
- Receipts are idempotent per `action_id`; duplicate delivery of one action is a defect.
- A stale composer targeting a replaced session occupant fails visibly and never delivers.

## Approvals and grants

- Shell, file-write and unknown tools require an explicit typed decision; pending or denied requests never execute; offline phones never cause default approval.
- Local terminal and any phone compete for one `request_id`: first valid decision wins; losing, stale or duplicate decisions fail visibly.
- A `Grant{tool, args, cwd, session_id}` authorizes exactly that invocation. Reconnect never broadens it. Revocation, integration reload, branch change or session replacement invalidates it immediately.
- The daemon/integration arbitrates races; cached client state is never an approval authority.

## History and reconciliation

- Initial load returns the session's durable active-branch history; streaming appends only confirmed-complete entries.
- Reconciliation handles duplicates (idempotent by entry id), gaps (visible placeholder, never silent), out-of-order delivery (stable order by sequence), and session replacement (fresh stream; old pending actions/grants invalidated).
- Disconnect shows `unresolved` state for in-flight actions; foreground recovery reconciles without duplicate sends or decisions.

## Client lifecycle boundaries

- Activity recreation, tab switching and short backgrounding preserve selection and never re-send.
- Process death recovers from server state, not client memory; a dead app stores no grants and holds no approval authority.
- Background sockets, foreground services and FCM are out of scope for the first prototype; generic Telegram alerts come later without previews or approval buttons.

## Failure display

Every unavailable capability stays visibly unavailable (disabled actions plus explanation), matching the disconnected shells. Pairing failures, untrusted hosts and stale targets are explicit errors, never silent retries.
