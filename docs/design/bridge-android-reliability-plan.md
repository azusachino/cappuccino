# Bridge and Android reliability plan

Date: 2026-10-20. Status: proposed implementation sequence; documentation only.
Baseline: `7a5691e86c5366e11afb921350560abe7a0f46a8`.

## Outcome and boundaries

Make the existing conversation and Send action trustworthy, then add explicit
same-session Pi control and independent attention. The
[research](../research/bridge-android-agent-products.md) owns findings R1–R7 and
external sources; this document owns the proposed sequence and acceptance.
[Intent](../intent.md) remains authoritative. Nothing here declares implementation
complete, approves deployment, or changes the bridge's no-auth tailnet boundary.

Use the existing [plan](../plan.md),
[delivery issue #8](https://github.com/azusachino/cappuccino/issues/8) and
[approvals issue #9](https://github.com/azusachino/cappuccino/issues/9). Do not
create another task store or duplicate live status in this document. Asobi and
agent reviewers are not activated by this plan. Each slice is intended for one
focused session; split it again if discovery exceeds roughly five implementation
files, excluding fixtures. No calendar estimate is a delivery promise.

### Reconcile before changing contracts

The inspected #8 still describes a paired daemon, pending/sent/confirmed/unresolved
receipts and duplicate rejection; #9 requires fresh independent verification.
Current intent instead selects a no-auth Herdr plugin and requires owner agreement
on receipt semantics. Preserve issue acceptance until the owner reconciles it;
do not silently replace confirmed with HTTP success or deduplication with replay.
The owner must explicitly authorize any required agent reviewer. Record agreed
changes in the owning issues before implementing the typed write path.

Public/shared wire changes are tier 2 when implemented: follow the workstation's
intent-to-verified-result procedure, write prior acceptance in the owning issue,
and preserve Apple compatibility. Saving this proposed plan is tier 1, not a
published contract change.

## Approach

- Keep Rust bridge and Kotlin/Compose. Add no runtime dependency by default.
- Native session, pane locator, Pi conversation branch and Git branch are distinct.
- Fix visible correctness before performance redesign; measure before selecting
  polling, memory or throughput budgets.
- New fields are additive and capability-gated where compatible. Reject unsupported
  typed actions; never silently degrade them into PTY input.
- Keep screen support labeled as compatibility behavior. Typed approvals resolve
  at the agent-side gate, not at a bridge screen parser.
- Speculative features stay deferred. No coordinator, agent spawning, public
  relay, file browser, session navigation UI or Pi Durable migration.

## Dependency map

```text
A1 recoverable sends ──────────────────────────────┐
A2 restored identity ──> A3 mutation fencing ──────┤
B1 branch feasibility ──> B2 correct history ──────┤ checkpoint 1
C1 bounded upstream ──> C2 stream recovery ────────┘
                                      │
                      C3 shared observation (measured need)
                                      │
D1 same-process feasibility + owner receipt decision
    └─> D2 typed send + receipt ──> D3 queue-aware stop
                                  └─> D4 model/thinking
                         checkpoint 2
D2 ──> E1 typed gate ──> E2 reconciled Attention ──> E3 exact grants
F1 monitoring coverage (independent) ──> F2 optional Telegram
                         checkpoint 3
```

A/B/C are independently useful even if typed attachment proves infeasible. B1 and
D1 are bounded investigations, not permission to install into owner sessions.
C3 is not a prerequisite for D1. F1 may be delivered without typed controls.

## Check and evidence policy

Commands run from the repository root, using the scoped toolchains in
[development](../development.md):

| Label | Required checks |
| --- | --- |
| Docs | `make md-check` |
| Bridge | `make check-bridge` |
| Android | `mise exec -- make check-android` during iteration; `mise exec -- make validate-android` and `ANDROID_SERIAL=<task-owned emulator> mise exec -- make ui-test-android` at acceptance |
| Shared | Bridge gate plus `make validate` and both native UI journeys; select a task-owned iOS simulator with `make ui-test DESTINATION="platform=iOS Simulator,id=<id>"` |
| Runtime | Explicitly task-owned disposable Herdr/Pi processes and synthetic content; installation/reload or physical-device use needs the separately scoped approval |

Preserve both native platform gates for shared/build changes. `make validate` is
required before a PR; docs-only CI normally checks Markdown, but that is not a
waiver of the documented local PR gate. A failed or unavailable required gate
blocks that acceptance/publication action; do not substitute CI or a narrower
check. No agent reviewer is dispatched without owner authorization.

Record source commit, tool versions, fixture/runtime identity, commands and
results with the owning PR/issue. Synthetic unit/UI evidence does not prove live
attachment. Physical Android tailnet acceptance remains separately pending until
approved and exercised. Never retain private transcripts, URLs or credentials.

## Checkpoint 1: trust history, identity and sending

### A1. Preserve unsent and ambiguous prompts

**Depends on:** none. **Size:** medium. **Evidence:** R4.

Likely paths: Android `io/ConnectedViewModel.kt`, `ui/CappuccinoShell.kt`, existing
view-model and composer tests.

- Retain editable text until a known send outcome; separate action failure from
  connection failure. Preserve newer edits when an older send fails.
- Show pending, rejected or delivery-unknown appropriately; prevent repeated taps
  while pending. Never automatically resend ambiguous input.
- Keep current PTY success wording honest; do not claim agent admission.

**Verify:** Android gate/journey with rejection, lost response after server write,
rapid repeated tap, navigation and activity recreation. Add saved-state draft
restoration as a separate small follow-up if private persistence exceeds scope;
process-death durability must not be claimed until that test passes.

### A2. Bind restored selections and drafts to native identity

**Depends on:** none. **Size:** medium. **Evidence:** R3.

Likely paths: Android `io/ConnectedViewModel.kt`, `core/AgentIdentity.kt`, identity
and foreground-recovery tests.

- Save expected native session reference with machine and locator; reject a reused
  locator and show a visible missing/replaced state.
- Clear obsolete stream/prompt state before attaching a new occupant; never
  transfer a draft automatically into the replacement conversation.
- Handle older saved selections without native identity as requiring explicit
  reselection, not as permission to guess.

**Verify:** Android gate/journey for same-session reconnect, changed machine,
reused pane, session replacement and old saved-state migration.

### A3. Fence mutations against session replacement

**Depends on:** A2 and prior wire acceptance. **Size:** medium. **Evidence:** R3.

Likely paths: bridge routes/request types, Android transport, shared fixtures and
client-contract tests. Keep Apple consumption compatible or include its adapter.

- Specify expected identity and a typed stale-session error; capable clients send
  the identity on prompt/control/answer operations.
- Check the binding before dispatch. Document that PTY check-then-type still has
  a race; do not call it atomic fencing without enforcement at the authority.
- Fail closed on missing/stale identity for the new capability; do not silently
  enable typed actions on older bridges.

**Verify:** Shared gates/journeys with occupant replacement before dispatch and
between validation and side effect. Any race the PTY boundary cannot close stays
explicitly unresolved until D2/E1, not marked safe by a passing preflight test.

### B1. Prove an authoritative active-branch read strategy

**Depends on:** none. **Size:** small investigation. **Evidence:** R1.

Likely paths: existing Pi reader design and synthetic bridge branch fixtures.

- Establish where the attached process exposes its current leaf, including tree
  navigation with no subsequent append. Inspect pinned session-format semantics.
- Select a bounded ancestry strategy: indexed canonical log plus authoritative
  leaf, or a same-process read-only projection. Do not assume a byte tail is enough.
- Specify incomplete/unknown coverage and a stop condition if leaf authority is
  unavailable. Do not manufacture a complete active branch from file order.

**Verify:** synthetic fork, abandoned branch, navigation-without-append, compaction,
ancestry outside 2 MiB and partial trailing JSON. Record feasibility before B2.
If runtime evidence is necessary, obtain scoped disposable-session approval first.

### B2. Deliver stable, correctly scoped conversation rows

**Depends on:** B1; typed leaf support from D1 if B1 requires it. **Size:** medium.

Likely paths: bridge `conversation.rs`, response fixtures, Android protocol and
reader tests; Apple compatibility fixtures as required.

- Use native entry IDs and tool IDs; render only proven active ancestry.
- Expose available coverage and distinguish omitted UI rows from missing source
  ancestry. Unknown coverage must be visible, not described as full history.
- Preserve selected row/expansion identity across tail movement and reconnect.

**Verify:** Shared gates/journeys with B1 cases and a reader scrolled away from
latest. Older-page loading is a later slice: do not implement a pagination engine
just to fix IDs. Runtime parity must use the actual active branch of a disposable
session before the full-history claim can be accepted.

### C1. Bound Herdr I/O and report stale state

**Depends on:** none. **Size:** medium. **Evidence:** R5.

Likely paths: bridge `herdr.rs`, `routes.rs`, client-contract tests.

- Add end-to-end request deadlines and bounded response-line reading with typed
  errors. A side-effect timeout remains ambiguous and is never retried blindly.
- Do not turn unavailable metadata into authoritative idle/completed state.
- Emit a recoverable unavailable/stale condition and require fresh observation
  before client actions re-enable.

**Verify:** Bridge gate and synthetic never-answer, oversized-line, disconnect,
missing-agent and recovery tests. Shared gates if wire events change.

### C2. Recover explicitly from client stream loss

**Depends on:** C1 for upstream recovery cases. **Size:** medium. **Evidence:** R5.

Implementation checkpoint verified: bounded overflow, source-loss invalidation,
finite retries, native recovery binding, canonical history before reattachment
and fresh-status action guards. The owner chose to block recovery writes when
canonical history is unavailable; no automatic degraded mode is enabled. See the
[implementation handoff](bridge-android-reliability-handoff.md#c2-canonical-history-gate-checkpoint)
for native gate evidence and the remaining overall-plan limits.

Likely paths: Android `io/BridgeClient.kt`, `io/ConnectedViewModel.kt`, transport
and recovery tests.

- Detect callback-flow overflow and close into recovery rather than dropping an
  event silently; keep buffering bounded.
- After reconnect, refresh canonical identity/history and pending-state baseline
  before enabling actions. Do not restore an old approval from cache.
- Coalesce redundant canonical refresh requests without delaying the final settled
  update or hiding a source failure.

**Verify:** Android gate/journey with burst events, slow consumer, bridge restart,
background/foreground and replacement session. No arbitrary unbounded buffer.

### Checkpoint 1 acceptance

A1/A2, the feasible B2 scope, C1/C2 and accepted A3 are individually verified.
Document remaining PTY races and unavailable branch authority. Run the applicable
shared/native gates and disposable runtime parity journey. Owner reviews the
trust fixes before larger protocol work; blocked criteria remain blocked.

## Checkpoint 2: typed control without replacing the agent

### C3. Share observations only after measuring

**Depends on:** C1/C2. **Size:** medium, optional before D1. **Evidence:** R5.

Likely paths: bridge stream ownership/routes, resource/client-contract tests.

- Measure one and multiple viewers; record RPC rate, branch subprocesses and
  CPU/RSS, without inventing a performance budget from a short sample.
- If warranted, share one bounded observer per agent and cache branch metadata;
  release it after the last subscriber detaches.
- Preserve per-viewer ordering and isolate a slow viewer; avoid duplicate offline
  writes for unchanged Android snapshots in a separate client slice if needed.

**Verify:** Bridge gate, existing benchmark against task-owned data and resource
soak for observation-lifecycle changes. Shared gates for protocol changes. Keep
baseline and comparison inputs identical; no claim of zero leaks.

### D1. Prove same-process Pi attachment and settle delivery acceptance

**Depends on:** owner reconciliation of #8; may start before C3. **Size:** bounded
spike, no production controls. **Evidence:** R2/R3.

Likely paths: a project-owned adapter design, synthetic adapter fixtures and the
owning issue. Select the integration location only after confirming ownership.

- Prove an existing interactive Pi can expose identity, current leaf, supported
  operations and correlated request handling without a replacement process.
- Specify a private machine-local channel, lifecycle cleanup, runtime-version
  checks and capability discovery. Bridge network exposure remains unchanged.
- Agree admission versus settlement, action-ID scope, duplicate policy, retention,
  ambiguous crash windows and queue-aware stop behavior in #8. No exactly-once
  claim unless enforced and recoverable at the agent-side authority.

**Verify:** permission-scoped disposable same-process journey; record unchanged
PID/session and branch continuity, idle-only setup reload and adapter removal.
If unsupported, stop D2–E3, retain honest PTY compatibility, and return a decision
rather than building another runtime. Cross-repo integration work needs a separately
scoped owning plan and gates.

### D2. Deliver explicit Nudge/Follow-up with correlated outcomes

**Depends on:** D1, A3 and agreed #8 acceptance. **Size:** split into contract,
adapter/bridge, then native-consumer sessions; one vertical acceptance journey.

Likely paths: same-process adapter, bridge prompt routes, behavior fixtures and
native transport/composer implementations. Do not land this as one large patch.

- Preserve explicit mode and action identity across recreation; show authoritative
  admission disposition separately from execution completion.
- Reconcile retries/crashes with the authority; expose unresolved delivery instead
  of duplicating input. Unsupported typed modes fail visibly, never PTY-fallback.
- Prove one Nudge and one Follow-up reach the same disposable process/session in
  the intended order. Keep #8's existing criteria unless explicitly reconciled.

**Verify:** Shared gates/journeys; duplicate IDs, disconnect before/after admission,
bridge/adapter restart, conflicting payload, session replacement and busy-mode
fixtures. Perform the task-owned runtime journey. Required independent review
remains blocked until the owner authorizes the reviewer.

### D3. Make Stop and queues explicit

**Depends on:** D2. **Size:** medium per vertical slice.

Likely paths: adapter queue/abort handling, bridge controls, native composer and
control tests. Split queue display from queue withdrawal if scope grows.

- Show authoritative pending steering/follow-up state with no guessed queue count.
- Apply the owner-agreed Stop policy: current-run interruption and queue withdrawal
  are distinct; do not accidentally continue queued work after Stop.
- Display acknowledged idle only when reported by the attached process.

**Verify:** Shared gates/journeys; abort with both queue types, idle abort,
reconnect, concurrent local queue changes and stale session. Runtime identity stays
unchanged. No agent termination or session replacement.

### D4. Add capability-driven model/thinking controls

**Depends on:** D2. **Size:** medium; split model and thinking if needed.

Likely paths: adapter discovery/set operations, bridge routes, native controls and
fixtures.

- Offer only values advertised by the attached runtime, not a hard-coded catalog.
- Confirm cache-affecting changes, preserve draft/mode and apply the acknowledged
  selection. Define busy-session behavior before implementation.
- Show unsupported or failed changes without mutating local display optimistically
  into a false confirmed state.

**Verify:** Shared gates/journeys with unsupported model, model-specific reasoning
levels, runtime version mismatch, busy rejection and lost acknowledgement.

### Checkpoint 2 acceptance

Owner-reconciled #8 is met with same-process runtime evidence, not just mocked
HTTP success. Preserve compatibility for both apps. Typed gate work starts only
after delivery acceptance; independent verification requirements remain explicit.

## Checkpoint 3: typed approvals and independent attention

### E1. Gate one risky tool path end to end

**Depends on:** accepted D2/#8 and #9 acceptance. **Size:** several small slices:
request contract, agent gate, then native answer consumption.

Likely paths: Pi adapter/tool gate, bridge request routes, shared fixtures and
native approval UI. Retain screen compatibility separately.

- Correlate request ID, exact tool/args/cwd and native session/branch scope at the
  agent authority; wait before executing shell/write/unknown tools.
- Offline, pending, denied, unknown or expired requests never default to allow.
  Local and phone decisions race at the same authority; first valid wins.
- Reconcile pending/resolved requests on reconnect and adapter restart. If safe
  resumption cannot be established, keep execution blocked and require re-request.

**Verify:** Shared gates/journeys and task-owned approved/denied calls; competing
terminal/phone answers, duplicates, replacement, restart and no-phone cases.
Demonstrate denied/pending never executes. No reusable grants in this slice.

### E2. Build a reconciled cross-machine Attention inbox

**Depends on:** E1 for actionable typed requests; a read-only blocked-agent list
can be a smaller earlier slice after C2. **Size:** medium per vertical slice.

Likely paths: bridge attention summary/feed, Android per-agent state and Attention
UI, shared fixtures; Apple compatibility consumers as needed.

- Maintain attention by machine + native session + request/completion identity,
  independent of the currently selected conversation.
- Revalidate before answers; remove resolved requests consistently after local or
  remote resolution. Unreachable machines remain visibly unknown, not all-clear.
- Use lightweight summaries, not a transcript stream per agent. Opening an old
  notification cannot answer a new request.

**Verify:** Shared gates/journeys; two machines, two simultaneous requests,
unselected-agent completion, one unreachable machine, stale notification and
terminal resolution during navigation.

### E3. Add exact reusable grants

**Depends on:** E1/E2 and explicit #9 grant acceptance. **Size:** split issuance/use
from revocation/invalidation if necessary.

Likely paths: agent-side grant matcher/store, bridge grant routes, native grant
management and exact-match fixtures.

- Bind exact tool, canonical arguments, cwd, session and branch scope; define
  argument equality before implementation. Unknown matches require a fresh answer.
- Ordinary reconnect cannot broaden a grant; manual revoke, integration reload,
  active-branch change and session replacement invalidate it as intent requires.
- Make grant use visible and separate from approving an already-pending request.

**Verify:** Shared gates/journeys; one-field argument changes, cwd changes,
revocation races, reload, branch/session replacement and reconnect. Finish #9's
disposable grant-revoke-re-request journey and its authorized review.

### F1. Expose monitoring coverage before changing lifetime

**Depends on:** none; C2 helps recovery. **Size:** medium. **Evidence:** R7.

Likely paths: Android notification service/preferences/settings and service tests.

- Show watching/paused/stopped/unreachable state and the actual five-minute idle,
  task-removal and OS lifecycle limits. Do not promise background delivery when off.
- Preserve sequence-based deduplication and private lock-screen content.
- Obtain the owner's intended lifetime/battery policy before implementing longer
  monitoring. Do not assume WorkManager or an immortal service guarantees alerts.

**Verify:** Android gates/journey with permission denied/revoked, idle timeout,
task removal, restart, machine outage and notification tap into a replaced session.
Document emulator versus approved physical-phone evidence separately.

### F2. Add generic Telegram attention only if selected

**Depends on:** F1, stable attention identity and separate alert/security acceptance.
**Size:** proposed later slice; split source observation from Telegram delivery.

Likely paths: bridge completion/attention observer, secret-free configuration,
notification tests and setup documentation.

- Use generic approval/failure/completion notices without code, prompt payload or
  approval buttons; store no credentials in source or fixtures.
- Define durable event deduplication, restart/retry semantics and outage reporting;
  Telegram unavailability must not affect agent lifetime or unblock approvals.
- Keep no public ingress; configuration and deployment require separate approval.

**Verify:** Bridge gate with a fake Telegram endpoint and restart/outage fixtures;
shared gates if capabilities change. Actual Telegram delivery is a separately
approved runtime check, not implied by a fake endpoint passing.

### Checkpoint 3 acceptance

Issue #9 criteria are reconciled and verified, Attention survives navigation/reconnect,
and monitoring coverage is honest. Grants and Telegram remain deferred unless
explicitly selected and separately accepted. Update verification limits only from
fresh source/runtime evidence, never from this plan's existence.

## Continuing handoff

Start with A1 and A2; perform B1 before promising B2. No implementation task has
started. At resume, compare the actual checkout with the baseline above, inspect
owning issue changes and revalidate source findings. Persist each implemented
slice's branch/full commit, gates, open limitations and next action in its owning
PR/issue. This document is the proposed sequence, not a live task ledger.
