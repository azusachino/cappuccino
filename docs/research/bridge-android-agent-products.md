# Bridge and Android reliability: lessons from agent products

Date: 2026-10-20. Status: source-backed assessment, not runtime acceptance.

## Scope and evidence

The owner requested an assessment of Cappuccino's bridge and Android client,
including lessons from Pi, Pi Durable, DeepSeek Harness and other agent products,
then requested this research and a feasible multi-step plan be saved.

Cappuccino was inspected on `main` at
`7a5691e86c5366e11afb921350560abe7a0f46a8`, with a clean worktree. No agent input,
reload, integration installation, device installation or deployment was performed.
No build, performance measurement or device journey was run for the assessment.
The [verification limits](../verification.md) remain in force. Implementation is
proposed in the [reliability plan](../design/bridge-android-reliability-plan.md),
not authorized or delivered by this research.

Primary local references were inspected at these revisions, without refreshing:

| Reference | Revision | Relevant evidence |
| --- | --- | --- |
| Pi | `33e203354391ba26937cd1e8a210b4db10e82c66` | Session tree and active-leaf projection; durable storage and watch contracts |
| DeepSeek Harness | `639ed015397290b3745d163aafe02ffee4aa3f84` | Session projections and API gateway |
| HAPI | `5153ff23c196c7b84818752f7a631420ef0eb25b` | Native companion architecture and permission routes |
| Pi Pocket | `ed50991eb9c9b92e717f819073de36633bf83425` | Shared room projections, peek subscriptions and runtime ownership |

Installed Pi 1.3.0 RPC, RPC-command and extension-UI documentation was also read.
Public documentation linked below was fetched during the assessment; those moving
pages are not guarantees for an installed runtime. Pi Durable's local README
emphasizes storage while the public announcement describes a broader runtime.
Do not treat those surfaces as one version-pinned adoption contract.

## Recommendation

Keep Cappuccino a native companion for existing agents. Improve the reliability of
history, input, session binding and attention before expanding its feature set.
Do not replace Herdr/Pi lifetime ownership or migrate the Rust bridge to Pi Durable.

Existing strengths worth preserving: native Compose/SwiftUI, explicit canonical
versus scrollback history, machine identity checks, bounded read-only offline
snapshots, command confirmation, and stale/reused screen-answer rejection.

## Findings in Cappuccino

Paths below are relative to the inspected Cappuccino revision.

### R1: Pi history is not an active-branch projection

`services/bridge/src/conversation.rs`, `parse_pi_transcript`, walks `message`
records in append order and generates `t-001`-style IDs. It does not traverse
`parentId` from an authoritative current leaf. `read_canonical_tail` normally
reads a 2 MiB tail for Pi. By contrast, Pi's
[`session-manager.ts`](https://github.com/earendil-works/pi/blob/33e203354391ba26937cd1e8a210b4db10e82c66/packages/coding-agent/src/core/session-manager.ts)
projects a path through the session tree.

Abandoned branches can therefore be flattened into one conversation, and row IDs
can change meaning when the tail window advances. A byte tail cannot establish
complete ancestry outside that window. The Git branch used by stream resets is
not the Pi conversation branch. Full active-branch history remains unproven.

Retain native entry/tool IDs, establish the current leaf from an authoritative
source, and expose coverage/truncation. Do not infer authoritative live branch
selection solely from the last line of a file: navigation can move the active
leaf before another message is appended.

### R2: Current input transport cannot establish native delivery semantics

`services/bridge/src/routes.rs` forwards prompt text through Herdr and returns
`prompt_sent`. It exposes no explicit Pi queue selector or native admission
receipt. `prompt.rs` and `questions.rs` reconstruct supported dialogs from screen
text, and routes answer them by typing keys. The current nonce/claim checks are
valuable but cannot make screen-read plus key-write atomic with terminal input.

Use a scoped same-process Pi adapter for typed operations, if feasible. A new
`pi --mode rpc` process is not attachment to the existing interactive Pi. See the
[existing capability audit](../design/herdr-pi-capability-audit.md). Keep screen
compatibility honestly labeled; do not describe it as a typed permission gate.

### R3: Locator identity is weaker than mutation ownership

Prompt and control bodies do not carry the expected native conversation identity.
Android `ConnectedViewModel.restoreSelection` matches machine plus locator, while
`openNotificationAgent` additionally matches pane and native session reference.
A locator can resolve to a different occupant after session replacement.

Apply stronger identity checks consistently, including at the mutation authority.
A client-side check alone cannot close a replacement race between checking and
sending. Reconnect is not permission to act on a replacement session.

### R4: Android clears drafts before send outcome is known

In `ConnectedViewModel.submitPrompt`, `setDraft(..., "")` occurs before the network
operation. Failure changes connection state and does not restore that draft.
Drafts in `ConnectedUiState` are in-memory; process-death durability is not
established by that map.

Preserve a recoverable pending message and distinguish rejection from ambiguous
transport failure. Never automatically resend ambiguous PTY input. An HTTP
success still means only the documented transport-level outcome, not Pi admission.

### R5: Stream and upstream failures need explicit recovery

`BridgeClient.stream` ignores the result of `trySend`; a full callback-flow buffer
can discard an event without forcing resynchronization. `routes.rs` polls each
WebSocket every 400 ms, reads the pane and agent, and synchronously checks Git
branch metadata. Failed pane reads continue silently; missing agent status can
fall back to idle. `herdr.rs::request` has no explicit end-to-end deadline or
response-line size cap.

Handle overflow with visible recovery, add bounded upstream I/O, and represent
unknown/stale state honestly. Share observation by agent rather than multiplying
work by viewers. Android reloads canonical snapshots on open/entries/reset/status;
coalesce unchanged work before adding a more ambitious incremental protocol.
These are source observations, not measured bottlenecks or leak findings.

### R6: Attention is tied to the selected stream

`ConnectedUiState.stream.pendingPrompt` and the shell's prompt card represent the
selected agent. Machine catalog monitoring can discover blocked agents, but is
not a reconciled cross-machine pending-request store.

Build independent attention summaries before enabling remote typed answers from
an inbox. Opening a cached or stale summary must revalidate the current request;
it must not authorize execution.

### R7: Android monitoring is intentionally bounded

`AgentNotificationService` polls active agents every three seconds and idle agents
every ten seconds. It stops after five minutes without activity, on task removal,
and uses `START_NOT_STICKY`. Notifications therefore do not establish always-on
coverage while the phone is away. Sequence-based identity and private lock-screen
content are useful foundations.

First expose watching/paused/stopped/unreachable state. Decide the intended
monitoring lifetime before changing battery behavior. Generic server-side Telegram
alerts are an existing later product slice, not a reason to add public ingress.

## Transferable patterns

### Pi: separate admission, settlement and UI correlation

[RPC documentation](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/rpc.md)
and [commands](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/rpc-commands.md)
distinguish started/queued/handled disposition from later execution, expose
steer/follow-up and stable entry cursors, and distinguish `agent_end` from
`agent_settled`. [Extension UI](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/rpc-extension-ui.md)
uses correlated dialogs and documents unsupported custom terminal components.

Borrow these semantic distinctions. Discover capabilities on the actual attached
runtime; do not assume all RPC commands are TUI slash commands or every Pi version
supports the installed 1.3.0 documentation. Stop also needs explicit queue policy:
Pi's documented abort can continue queued messages unless they are cleared.

### Pi Durable: durable admission does not make PTY effects exactly-once

The [announcement](https://earendil.com/posts/pi-durable/) describes request-ID
submission deduplication, durable decisions and safe versus unsafe replay. The
[pinned design](https://github.com/earendil-works/pi/blob/33e203354391ba26937cd1e8a210b4db10e82c66/packages/durable/docs/pico-v5.md)
specifies admission separate from settlement and atomic snapshot-plus-watch.

Borrow those contracts, not the runtime dependency. A bridge crash after typing
but before saving success remains ambiguous unless the agent-side authority can
reconcile it. Persisting a bridge receipt cannot retroactively make a terminal
side effect exactly-once. The API is experimental and requires fresh evaluation
before any adoption.

### DeepSeek Harness: serve consistent read models

[Session projections](https://github.com/deepseek-ai/deepseek-harness/blob/639ed015397290b3745d163aafe02ffee4aa3f84/docs/subsystems/session-projection.md)
carry a shared `asOfSeq` over host-computed values. The
[API gateway](https://github.com/deepseek-ai/deepseek-harness/blob/639ed015397290b3745d163aafe02ffee4aa3f84/docs/api-gateway.md)
separates explicit validated operations from incremental data protocols.

Borrow host normalization, consistent watermarks and explicit operation exposure.
Do not import Cordis, generated TypeScript clients, or automatic cold-session
resume: Cappuccino's existing-session ownership differs.

### Pi Pocket: compact live views and scoped peeks

The [README](https://github.com/TannerMidd/pi-pocket/blob/ed50991eb9c9b92e717f819073de36633bf83425/README.md)
and [room implementation](https://github.com/TannerMidd/pi-pocket/blob/ed50991eb9c9b92e717f819073de36633bf83425/src/server/room.ts)
show committed-state projections, changed-field tracking and lightweight visible
peek subscriptions. Borrow compact updates and attention summaries. Pi Pocket
owns its durable runtime; its coordinator, browser, schedules and multiplayer
features do not belong in this companion plan.

### HAPI, Happier and Claude Remote Control: attachment has prerequisites

[HAPI's permission routes](https://github.com/tiann/hapi/blob/5153ff23c196c7b84818752f7a631420ef0eb25b/hub/src/web/routes/permissions.ts)
validate session-scoped pending request IDs and reject missing or raced requests.
Its [README](https://github.com/tiann/hapi/blob/5153ff23c196c7b84818752f7a631420ef0eb25b/README.md)
describes native clients over an agent-wrapper/hub architecture. Borrow typed
pending requests and native-client fixtures, not assumed arbitrary attachment.

[Happier's Codex documentation](https://docs.happier.dev/agents/codex) explicitly
warns that resuming a thread in another process cannot join its active writer.
It documents capability-dependent controls and strict resume failures.
[Codex App Server](https://developers.openai.com/codex/app-server/) offers typed
threads, turns, approvals and streamed events, with experimental transport limits.
These are comparison patterns, not new supported Cappuccino backends.

[Claude Remote Control](https://code.claude.com/docs/en/remote-control) illustrates
existing-session continuity and explicit disconnect reasons. Its cloud routing,
credentials and transcript storage are different from the approved tailnet-only
Cappuccino boundary; do not copy that deployment model.

The earlier [Paseo source study](paseo-source-study.md) supplies complementary
lessons: epoch/sequence catch-up, cached-but-unsynchronized replicas, native code
rendering bounds, and stable reading position. That study's pinned evidence was
not freshly rerun here.

## B1 follow-up: live leaf authority

The authorized pi-subagents investigation used
`zai-coding-cn/glm-5.3-flash` at low thinking against the same Pi reference.
Parent source inspection confirmed `SessionManager.branch()` and `resetLeaf()`
only change the in-memory leaf; they do not append a record. Two live states can
therefore have identical file bytes but different selected conversation branches.
`ReadonlySessionManager` exposes `getLeafId` and `getBranch` to extensions; that is
a candidate same-process read surface, not an installed transport endpoint.

B1 is source-feasible only with an explicit authority boundary: native IDs and
file-tree indexing can improve stability without a new integration, but the
last-appended entry cannot prove the current live branch. Full B2 active-branch
acceptance depends on D1 exposing a consistent leaf/history observation from the
attached process. Until then, label live-branch authority unknown; never describe
last-append ancestry as proven current. Complete ancestry also cannot be inferred
from a truncated byte tail.

The scout suggested applying compaction's context cut to the reader. That advice
is not adopted: the product requires full active-branch conversation history,
including pre-compaction messages. Model context projection and human history are
different views. Preserve full proven ancestry, using summaries as explicit
records rather than silently deleting history. Partial/malformed tails need
coverage reporting; a valid JSON record lacking a final newline is not itself
proof of an incomplete record.

This follow-up ran no agent, integration reload or runtime branch journey. B1's
source investigation is complete; B2 runtime correctness remains unverified.

## Priority and limits

1. Correct conversation identity/history and recoverable sending.
2. Bound upstream work and make stream recovery explicit.
3. Prove same-process typed attachment before promising controls or receipts.
4. Reconcile typed pending requests, then exact grants and cross-machine answers.
5. Clarify monitoring coverage; add approved generic Telegram attention later.

Retain the no-auth, loopback/private-tailnet decision. No public ingress, new
agent lifetime ownership, cross-platform runtime, file/Git browser, coordinator,
or broad feature-parity promise is recommended. Approval gates are not sandboxes.

Issue #8 and #9 were read when saving this report. Both remain open; their older
pairing/daemon terminology and delivery/review requirements need reconciliation
with current intent before implementation. No issue was changed. See the plan for
small slices, stop conditions and the exact acceptance boundary.
