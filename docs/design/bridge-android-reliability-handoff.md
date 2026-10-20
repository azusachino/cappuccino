# Bridge and Android reliability: implementation handoff

Date: 2026-10-20. Status: A1/A2, C1 and native stream-recovery checkpoints
verified; C2 history-before-actions and the overall plan remain incomplete.

## Owning checkpoint

Repository: Cappuccino. Branch: `feat/bridge-android-reliability`.
Implementation base: `e9e897e74e6a3e7989eb58a5b34f3373b05b7b22`.
The commit containing this handoff records the first verified implementation slice;
use owning Git history for its full revision before resuming. No push, PR,
deployment, integration installation/reload or owner-session action was performed.

The owner authorized pi-subagents with `openai-codex/gpt-6-luna` and
`zai-coding-cn/glm-5.3-flash`, both low thinking. After two unsuccessful shell
repair passes, the owner explicitly selected parent-led repair. Parent source
inspection and runtime verification are self-verification, not independent agent
review. No child is writing this checkout at this checkpoint. The approved D1 disposable
runtime attempt ended without proving live observation; its owned process was
terminated. Both task-owned native devices are stopped after verification; see
the continuing checkpoint below.

## Delivered slice

A1 recoverable sending now retains the draft while a request is pending, prevents
repeated taps, preserves newer edits on older completion, scopes pending/outcome
state by profile/machine/locator, and distinguishes bridge 4xx rejection from
ambiguous transport/5xx outcomes without automatic retry. Successful bridge input
is explicitly not a claim of Pi admission. Native-session binding and process-death
persistence remain future work, not proven properties of A1.

Luna implemented the transport/ViewModel/composer seam and regression tests. GLM
repaired the new composer instrumentation test to use one composition with
observable pending state, preserving text-retention, disabled-action and confirmed
slash-command assertions.

The acceptance journey also exposed baseline defects. Parent repair preserves
structured `parts.Text` messages and activity rows, compares prose/outcome content
for following, and uses prose size changes rather than whole tool-row size changes
as layout follow signals. Real aborted outcomes still affect the signature;
hidden provider failures and tool-only changes do not. Context/cache statistics
are visible again. The existing New session owner control is restored through the
existing bridge route, with confirmation, known-agent/capability checks and
idle-only enablement; no new lifecycle API or process ownership is introduced.

## Gate evidence

All connected runs used a newly created task-owned API 35 ARM emulator named
`cappuccino-reliability-20261020`, serial `emulator-5580`. No owner's AVD was used.
Private SDK/device paths are environment details, not committed configuration.

The final parent-run chain passed in order on the unchanged source checkpoint:

1. `mise exec -- make fmt-android`
2. `mise exec -- make check-android`
3. `mise exec -- make validate-android`
4. `ANDROID_SERIAL=emulator-5580 mise exec -- make ui-test-android`

The connected test XML reports 50 tests, zero failures/errors/skips, timestamp
`2026-10-20T11:36:04`. Screenshots were exported by the owning Make target.
Tests use synthetic state and transport; this is not live agent admission,
canonical parity, real approval or physical-phone tailnet acceptance. Apple/bridge
code and wire contracts were unchanged; their platform gates were not substituted
for the Android journey. Full `make validate` remains required before a PR.

Reports are ignored build artifacts under
`apps/android/app/build/outputs/androidTest-results/connected/` and
`apps/android/app/build/reports/androidTests/connected/debug/`.
Run `make md-check` after documentation-only updates; those do not change the
source/runtime inputs above. Re-run affected runtime checks after source changes.

## A2 native-session restoration checkpoint

A2 builds on committed A1 `69e8219dd8fb4b5c718cd767a72ca21def2584ca`.
GLM Flash low bound saved selections to the expected native session reference,
rejected locator reuse with a different reference, and required explicit
reselection for legacy saved selections lacking native identity. Draft/send keys
now include the native reference. No bridge/Apple wire fields changed.

Parent integration also scoped composer saveable state and pre-canonical reader
identity to the native reference, cancelled obsolete selected-stream work on
catalog replacement, and prevented an old send failure from appearing on the
replacement's conversation. New tests cover late-send isolation and a replacement
not inheriting the previous editor or slash-command confirmation. The latter
initially failed only because an empty editor's placeholder participated in a
text assertion; the final assertion checks actual `EditableText` instead.

The same parent-run format/check/validate/UI chain listed above passed again on
the A2 source checkpoint. Connected XML: 51 tests, zero failures/errors/skips,
timestamp `2026-10-20T11:54:41`. This is synthetic Android verification, not atomic
server-side fencing. For old bridges emitting no native reference, live catalog
refresh still cannot distinguish two null references; restored selections fail
closed, while a manually selected live view retains that compatibility limit.
Native references are opaque reported-reference hashes, not canonical IDs or
proof that a provider reused neither a file nor its contents. A3 remains required
for mutation ownership at the authority.

## C1a bounded IPC checkpoint

C1a builds on `f25c81694de20dbaeba5a6c4131c4295f01bceb9`. Only bridge
`herdr.rs` behavior changes: one shared connect/write/read timeout, a bounded
LF-framed response reader, explicit truncated/oversized/invalid errors, and
validation of response IDs when present. Conservative safety ceilings are ten
seconds and 4 MiB payload excluding LF, not measured latency/capacity promises.
Timeouts are conservatively ambiguous and never trigger automatic retries.
No HTTP/WS schema or native-client behavior changed.

The first GLM attempt deadlocked its test helper by awaiting accept before
returning the address. Parent inspected the open command, interrupted that exact
child, confirmed pause, and resumed the same model/protocol. Parent inspection
also caught a final-newline cap bypass, stacked deadline budgets, removed ANSI
regressions and an off-by-one exact-limit test. These were fixed; original ANSI
coverage is retained, and a deterministic split-buffer limit test was added.

Parent ran `cargo fmt --manifest-path services/bridge/Cargo.toml` and
`make check-bridge`, both passing. Suites report 90 + 44 + 6 + 9 + 7 passing tests,
zero failures, plus the unchanged four opt-in ignored resource-soak cases. The
locked release `test-client` example build passed. No ignored case was added to
obtain green; the opt-in soak was not executed, and no release is authorized.
C1 is still partial: C1b unavailable/stale stream state and C2 client recovery are
not delivered by bounded I/O alone.

## Earlier failures and disposition

Parent's initial candidate run had 50 tests and three failures: repeated
`setContent` in the new test, tool-only viewport movement, and duplicate model-label
selection. The untouched baseline in a separate detached worktree reproduced the
latter two (49 tests, two failures). Baseline failure was diagnostic, never a gate
waiver. GLM fixed the introduced composition error.

The next Luna shell repair filtered out valid structured prose and omitted expected
activity rows. Its report said two failures, while the XML showed three. Parent
rejected it, restored transcript content, fixed the follow mechanism and restored
missing footer/control behavior. The final 50-test run retained all assertions;
no critical test was skipped or deleted to obtain green. The model label now has
an explicit composer tag plus an exact-text assertion rather than arbitrary
first-match selection. No warnings were suppressed or baselines weakened.

## B1 finding and next work

B1 source investigation is in the
[research follow-up](../research/bridge-android-agent-products.md#b1-follow-up-live-leaf-authority).
The live Pi leaf can move without appending to its file. Full B2 active-branch
acceptance therefore depends on D1 same-process observation. Preserve human
pre-compaction history; never label last-append ancestry as proven current.
No runtime leaf journey or integration was executed.

At that checkpoint, next work was C1b unavailable/stale stream handling and C2
client recovery from the [plan](bridge-android-reliability-plan.md). See the newer
recovery checkpoint below. A3, B2, full C2 and later feature slices remain open. The owner approved only a disposable D1
spike with synthetic data/scoped files, no owner session/global integration or
credentials, and retained native Pi admission as #8's receipt bar. The isolated
launch initially lacked Node; the same-protocol corrected version probe returned
Pi 1.3.0, but the bounded TUI attempt produced no observation marker/session file
and its own process was terminated. No live getter/branch/teardown proof exists.
The research records evidence and uncertainty; no owner session was touched.
B2 live-branch acceptance and D2–E3 stay blocked by their prerequisites. Do not
downgrade admission to adapter dispatch. Typed receipts, stop/queue semantics,
approval gates, monitoring lifetime and optional Telegram retain their gates.

At resume, compare owning branch/full commit, inspect current issue acceptance
and revalidate any changed source. One writer owns a checkout and task device at
a time. No commit is permission to publish or deploy. Root workstation changes
belong to other work.

The diagnostic baseline worktree is workstation scratch at
`.tmp/cappuccino-reliability/baseline`, detached at the implementation base. Remove
it only after confirming no owned source changes. Retain the task emulator only
while continuing this work; when stopping, terminate only its owned process and
preserve evidence before deleting only that AVD.

## C1b/C2 recovery checkpoint

Implementation base: `7c0b8d6104e93eaaae250ddd29047b42c75240e4`. C1b changes in
`services/bridge/src/routes.rs` emit the existing protocol-1 `error` with code
`upstream_unavailable` and a generic message, then close the socket when pane or
agent-status observation fails. Missing/non-string status is not idle. Parent
added real-bridge/mock-Herdr regressions in `tests/client_contract.rs`: pane/get
failure after a visible prompt, initial get failure, missing/non-string status,
visible error followed by termination, no false idle/resolution, no leaked source
error text. Parent `make check-bridge` passed: 90 unit, 46 contract, 6 lifecycle,
9 active resource and 7 schema tests; four existing opt-in soak cases remain
ignored. The release test-client build passed. This is component verification,
not full C2 acceptance. The commit containing this record checkpoints the owned
bridge and native recovery changes; use owning Git history for its full revision.

GLM C2 run `e3a9e4e5-670d-4ad0-8007-c38350ac8bf7` is paused after parent
interruption; it must not resume writing while parent owns integration. Its
candidate is unaccepted. The unit gate hung in the new overflow test: `onStart`
delayed starting the transport, not consuming events, so no overflow was forced
and collection waited indefinitely. Parent replaced this with bounded explicit
first-event/consumer-release/peer-close synchronization. Parent also restored
manual legacy-view compatibility while refusing automatic recovery with missing
identity, rechecked binding after suspending catalog reads, invalidated stale
prompts/status on terminal failures and exhausted retries, and guarded fresh
status against missing stream-open. Send fixtures now establish a fresh status
before exercising writes; all A1 send assertions remain. Parent format/check
passed with 205 unit tests, zero failures/errors/skips. The first full Android
validation passed; its UI suite had 51 tests, two failures and zero skips. The
expanded stale-card/recovery/draft journey passed. The two remaining fixtures
needed a stable synthetic native reference for the five-attempt retry test and
an explicit fresh live baseline for the keyboard screenshot test; their original
assertions remain. The final parent format/validate/UI chain passed. Connected
XML: 51 tests, zero failures/errors/skips, timestamp `2026-10-20T13:38:49`.
Android C2 component verification is green, not whole-plan or shared acceptance.

Preserve the A2 distinction above: manual legacy views retain their stated
compatibility limit; missing native identity is not strong proof for restoration
or automatic recovery. Do not invent a stricter first-attachment policy as a test
convenience.

The task-owned Android emulator was stopped after the final green UI chain.
Android verifies bounded retries, source-loss invalidation, fresh-state action
gating, native-binding revalidation and unchanged send-outcome behavior. Manual
legacy selection remains compatible; automatic legacy recovery fails closed.
This is not atomic mutation fencing. Canonical-load coalescing was not redesigned.
Apple compatibility/source-loss handling and shared gates remain outstanding.
Luna Apple run `5d3e5ccc-6c9b-4cd3-8c14-2b3bebccb8df` returned an incomplete,
unaccepted seven-file candidate: its check failed, new regressions were absent,
and its binding experiment constructed an uninjected client. Parent now owns
integration, removed that experiment from `MachineConnections`, added an injected
session-serving verification seam backed by real machine/catalog requests, and
implemented bounded overflow/cancellation, finite retry budgets, stale-state
invalidation and controlled live-stream tests. Optional pane/native references
are parsed from existing wire fields. Parent Apple format/check/validate passed
with 109 core tests and an unsigned simulator build. The task-owned simulator
journey passed four UI tests; the existing fifth test is opt-in live-bridge
coverage and was skipped, not simulated or waived as live acceptance. The app's
background/foreground wiring now invalidates session state and requires verified
native recovery; manual legacy views require reselection after a pause.
The task-owned simulator was shut down after this journey.

Additional regression tests exercise the existing history-request coalescers:
a blocked first request plus a burst creates only one final follow-up; that final
history update must not hide a source-failure warning or re-enable live actions.
The parent shared `make validate` passed after these test additions: 110 Swift
core tests, unsigned simulator build, 206 Android unit tests, lint and both APK
builds. Native UI source and bridge source are unchanged from their successful
journeys/gates. Source/native evidence is synthetic, not physical-phone tailnet,
real approval or live Pi admission proof. The live-bridge UI skip remains an
explicit verification limit.

**Remaining C2 criterion:** actions currently require fresh live status after
binding revalidation, but canonical refresh runs asynchronously. They are not yet
held until that refresh settles. Do not label the full C2 history-before-actions
requirement complete from the passing recovery tests. The next implementation
slice must bind refresh completion to the current attachment (not a late previous
request), handle unavailable history explicitly, and test the ordering. Existing
coalescing needs evidence, not an unnecessary replacement. B2 branch authority,
A3 mutation fencing, physical-phone/live parity and native admission remain
unproven as described above. No new frame type, native admission proof,
publication or deployment is claimed.
