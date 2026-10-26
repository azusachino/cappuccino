# Bridge and Android reliability: implementation handoff

Created: 2026-10-20. Updated: 2026-10-26. Status: A1/A2 Android, C1 and C2
native recovery checkpoints verified; the overall plan remains incomplete.

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

**C2 gap at the `7f95208` checkpoint:** fresh status gated actions, but canonical
refresh was asynchronous. The following checkpoint resolves that ordering gap;
passing status-recovery tests alone did not establish history-before-actions.
Existing coalescing needed evidence, not an unnecessary replacement. B2 branch authority,
A3 mutation fencing, physical-phone/live parity and native admission remain
unproven as described above. No new frame type, native admission proof,
publication or deployment is claimed.

## C2 canonical-history gate checkpoint

Base: `7f952082b95f53b8fc0549e5887731e571ebf9a8`. The owner explicitly chose
**block writes until canonical history refresh succeeds after reconnect**, not an
automatically degraded raw-output mode. A fresh native binding/status alone must
not unlock recovery. Initial explicit manual legacy attachment retains its
existing compatibility limits; automatic legacy recovery still fails closed.

The verified follow-up serializes recovery: verify binding, cancel obsolete
coalesced history work, fetch/validate/apply canonical history, then open the
stream and wait for fresh status. History must carry the expected machine, a
valid provider-specific canonical ID and any already-known conversation ID.
Unavailable, missing or changed identity keeps actions blocked; a read-only
explicit retry can attempt recovery again and never replays input/answers/control.
Cancelled old requests check their own task/job cancellation before publishing,
so a transport ignoring cancellation cannot overwrite the recovered view or
unlock it. Apple now exposes the read-only retry in its error UI.

New model regressions cover blocked history, late cancelled reads, source/identity
failure and successful explicit retry without replay. Native fixture metadata is
explicitly canonical for tests that assert recovery. Focused checks passed:
113 Swift and 209 Android unit tests. Shared validation passed, including builds
and lint. Selected disconnected views now show Unknown instead of a cached
Idle/Working/Done label.

The first Android native run had 51 tests and one failure: the foreground fixture
still lacked canonical identity. The repaired fixture retains its verification,
catalog and resubscription assertions, and now additionally holds recovery
history behind a signal, proves actions and reattachment stay blocked, releases
history, then asserts fresh live readiness without replay. Android validation
and all 51 native tests then passed; its task AVD was shut down.

The Apple UI gate failed before executing tests: Xcode reported the host path as
`Debug-iphonesimulator/Cappuccino` (missing `.app`), while the actual `.app` exists
with the correct identifier/executable. Diagnostic collection also reported
missing `simctl`, although explicit scoped tool lookup succeeds. The unchanged
owning UI gate reproduced the same failure with fresh task-scoped derived data
(`.build/xcode-history-readiness`), ruling out a fix by simply replacing the
project's derived-data directory. The local package/development record documents
an earlier Xcode app/package target-name collision. No generated project edit,
global developer selection/service restart, easier gate or test bypass was used.
The owner approved a project-local test-host repair, keeping the xtool product
contract and all owner/global toolchain settings unchanged. Parent inspected
build settings: the UI host is looked up by `TEST_TARGET_NAME=Cappuccino`, which
also names a package product. Renaming only the Xcode app target to
`CappuccinoPhone`, retaining `PRODUCT_NAME=Cappuccino`, scheme `Cappuccino`, bundle
identifier and the untouched package/xtool product, resolved host lookup. The
unchanged full `make validate` and default Apple UI gate passed after this
project-local disambiguation. The public development record documents the naming
contract; no global fix is claimed for the secondary diagnostic-tool lookup.

Final evidence on the checkpoint source:

- Bridge: unchanged from the verified 158-test C1b gate; four pre-existing opt-in
  soak cases remain ignored.
- Shared `make validate`: 113 Swift core tests; unsigned simulator app build;
  209 Android unit tests; Android lint, app APK and instrumentation APK builds.
- Android `ui-test-android`: 51 passed, zero failures/errors/skips; XML timestamp
  `2026-10-26T04:03:11`. Includes the strict foreground/history barrier journey.
- Apple `ui-test`: four passed, zero failures; one pre-existing live-bridge opt-in
  test skipped. This is synthetic fixture acceptance, not live agent parity.
- Both task devices shut down cleanly. No owner sessions/devices, global Xcode
  selection, installs/reloads to long-running agents, pushes or PRs were used.

Run `make md-check` and `git diff --check` after final documentation updates.
C1/C2 are ready for owner checkpoint review; A3/B2 and later native-authority and
admission-dependent work remain open/blocked. Do not interpret these gates as
proof of live Pi admission, atomic mutation fencing or active-branch history.

## A3 contract-first continuation

The owner requested continuing A3 in steps after the C1/C2 checkpoint. Owning Git
was rechecked clean on `feat/bridge-android-reliability` at
`5cf12114edf26c89187297e7f9a1b70f8af030e3` before edits. The bridge
implementation now enforces binding guards and passes its owning gate;
Android/Apple adapters remain pending.
The [accepted A3 contract](mutation-session-binding.md) records the
expected-session object, new capability, strict old/new-client behavior, typed
stale and partial-outcome errors, test matrix and unavoidable PTY race.

Inspection found prompt submission resolving a pane but then writing through
the original alias, and multi-step answers lacking intervening native checks.
The captured Herdr schema does not offer atomic expected-native-session writes.
A3 must use one identity algorithm and fresh pre-dispatch checks, without calling
this atomic fencing or changing native-admission acceptance.

Read-only issue searches found no matching binding issue. Existing #10 requires
broader approval/grant work; it must not be closed by A3. The owner accepted the
exact A3 wire and strict compatibility contract, recorded
in [issue #45](https://github.com/azusachino/cappuccino/issues/45) before production
implementation. The no-push/no-PR/deploy and no-owner-runtime restrictions remain.
The bridge candidate shares the existing reference hash, validates required
binding, checks fresh catalog metadata before each write, targets the verified
pane, and stops partial multi-step input with an unknown outcome. Shared fixtures
and real-bridge/mock-Herdr cases cover all mutation variants, legacy/malformed/
stale input, unavailable metadata, nonce preservation, partial replacement and the
remaining post-final-check race. This is bridge-only evidence, not native-client
acceptance or a native fencing/admission guarantee.

The initial focused `cargo test mutation::tests` passed three tests. The first
full `make check-bridge` stopped at a new test's invalid `StatusCode as u64` cast;
it was corrected to `u64::from(status.as_u16())`. The identical full gate is being
rerun before proceeding. That rerun passed 94 unit tests and 49/51 contract
tests; two new race fixtures failed before bridge startup because their long
temporary Unix-socket paths exceeded macOS `SUN_LEN`. Their labels were shortened
to `a3-nonce` and `a3-pty`. The next full `make check-bridge` passed: 94 unit,
51 contract, six lifecycle, nine active resource and seven schema tests (167
passing), plus release test-client build. Four pre-existing opt-in soak ignores
remain. The forced post-final-check replacement test observes the replacement
receiving input, explicitly preserving that known limitation.
No prior assertion was removed, ignored or weakened; missing-target mutation
expectation intentionally changes from 404 to the
accepted 409 `stale_session` contract. Native gates have not run for A3.

Bridge checkpoint: `3b2ccd3` (`fix: require native binding before bridge mutations`).

Android candidates now require an immutable selected binding on all transport
writes, reverify capability/machine before POST, serialize binding through JSON
builders, and disable OkHttp POST retry/redirect behavior. Model/UI gates require
both live readiness and advertised binding support. Typed stale/partial-unknown
responses invalidate source/action state while preserving original-native draft
and outcome keys; late errors are epoch-fenced. New focused transport/binding
tests passed (20 transport plus two binding tests). Added direct-call capability,
shared typed-error, escaped-field and injected-client no-POST-retry regressions,
plus all-action source invalidation and late typed-error isolation tests. The
full Android format/unit/lint gate passed after fixing a compiler unnecessary
safe-call warning, a MockWebServer 5 immutable-ByteString helper assumption, and
unstable chained `runCatching` formatting; no checks were suppressed.

Click-time UI checks now compare the rendered profile/native binding with the
current model, preventing a stale callback from borrowing a replacement's valid
binding. Positive UI fixtures explicitly advertise the new capability/native
reference; added frozen-old-render and read-only legacy/incomplete-binding
journeys. Foreground recovery also asserts actual mutation readiness after the
history barrier, rather than transport readiness alone. The first native build
needed a missing `assertNull` import. Its UI run then passed 51/53; the new frozen
render's fake controller lacked draft storage, and the IME preview still used
legacy binding advertisements. Corrected those fixtures without changing draft,
IME-inset, action-isolation or positive-readiness assertions.

Final Android `mise exec -- make validate-android` and
`ANDROID_SERIAL=emulator-5580 mise exec -- make ui-test-android` passed on unchanged
source: 218 unit and 53 UI tests, zero failures/errors/skips, lint and both APKs.
Native UI XML timestamp: `2026-10-26T10:23:12`. The task-owned API35 emulator on
port 5580 was shut
down with its scoped `adb emu kill` command after acceptance. Apple remains
unchanged and pending. These are Android/bridge checkpoints, not full A3 closure.

Android checkpoint: `99edbd5` (`fix: bind Android actions to the rendered native
session`), with 218 unit and 53 UI tests passing. No agent reviewer was used.

## Owner-directed Android-only continuation

The owner explicitly paused all iOS updates and chose saving the unfinished iOS
candidate as a verified local patch. Its four core files were restored exactly
to the committed baseline; no iOS implementation is accepted or left active in
the checkout. Apple fixtures, UI task-selection capture, conformers and tests had
not been updated, and no Apple gate ran for that incomplete candidate.

The untracked workstation-root artifact is
`.tmp/cappuccino-reliability/ios-a3-paused/changes.patch`, with adjacent `README.md`.
SHA-256:
`f61699eebf84eeea09b4bd2c8e2ba2b1ade4f9b77b52376503eea10464715a9c`.
Reverse apply-check passed before parking and forward apply-check passed after
restoration at `99edbd5`; the owning checkout was clean. This is local scratch,
not published implementation or a substitute for these owning records. Resuming
iOS requires renewed owner authorization and rechecking owning Git/hash first.

Current active scope is Android-only. Bridge and Android checkpoints are verified;
full cross-platform A3 and issue #45 remain open. Do not infer native Pi admission,
atomic PTY fencing, active-branch authority, real approval or physical-phone parity
from synthetic evidence. No push, PR, deployment or owner-runtime action occurred.
Both task-owned native devices were stopped at that checkpoint.

## Android actual-bridge continuation

The owner approved an Android-to-actual-bridge integration journey after the
`8df8921` clean checkpoint. iOS remains explicitly paused. This is tier-1 test
infrastructure work, not another wire contract, release or deployment.

`make ui-test-android` now uses
`services/bridge/tests/android_journey.py` to launch the locked Rust bridge with
an isolated temporary HOME, synthetic Pi-format history and a private fake Herdr
Unix socket. Fixture control and bridge HTTP bind loopback only; host probes
ignore proxies/redirects and the runner adds/removes only its two owned adb
reverse mappings on the explicitly selected emulator. No provider/Pi process or
owner store is used. The existing full UI suite is retained, not filtered.

`ActualBridgeJourneyTest` uses real Android `BridgeClient` HTTP/WebSocket and
`ConnectedViewModel`: stale prompt/control/answer must yield zero terminal RPCs;
a frozen old rendered screen must not borrow B's binding; a positive B send
proves writes work; then a pending write loses its acknowledgement, canonical
history becomes unavailable, and read-only recovery must never replay the input.
The peer journal is a synthetic terminal-RPC oracle, not native Pi admission.

Host fixture self-test and `make md-check`/`git diff --check` passed. Initial
self-checks incorrectly expected `available: false` and then an explicit null ID;
the actual labeled scrollback contract omits the optional canonical ID. The
correct check requires scrollback with no canonical identity. Android
`make validate-android` passed after replacing a deprecated Compose test-rule
import with the project's v2 API; warnings remain errors. The complete 54-test UI
suite initially passed the existing 53 tests but exposed the rejected draft
correctly retained by A1: text input appended to it in the new frozen-screen
scenario. Changed that scenario to explicit text replacement, preserving the
exact retained-text and zero-write assertions.

Final unchanged-source acceptance passed:

- `mise exec -- make validate-android`: 218 unit tests, lint, debug and test APKs.
- `ANDROID_SERIAL=emulator-5580 mise exec -- make ui-test-android`: all 54 UI tests,
  zero failures/errors/skips, including the actual-bridge journey (7.808 seconds;
  XML timestamp `2026-10-26T12:55:33`). Screenshot export also passed.
- Host fixture self-test, `make md-check`, and `git diff --check` passed. Rust
  source, Cargo manifest and lockfile are unchanged from `3b2ccd3`; its 167-test
  bridge gate evidence remains applicable, while the actual bridge binary was
  freshly built/run by both the self-test and Android runner.

After completion, scoped `adb reverse --list` was empty, the runner had reaped its
bridge and removed temporary state, and the task-owned emulator was shut down.
These results prove the synthetic Android/actual-bridge contract, not native Pi
admission, atomic PTY fencing, real approval, live branch or tailnet-phone parity.
iOS stays paused; issue #45/full cross-platform A3 remain open. No push, PR,
deployment, global integration or owner-session action was performed.
