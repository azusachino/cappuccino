# Native skeleton verification

The first checkpoint below covers the original skeleton. Its fingerprints identify that earlier scope. The later macOS 26 minimum and workflow changes have a separate [CI-quality checkpoint](#ci-quality-and-macos-26-checkpoint).

Verified 2026-10-05 against [plan section 1](plan.md#1-repository-skeleton). All five scoped criteria passed; the independent reviewer reported no actionable findings. This is acceptance of the disconnected skeleton, not the remote attachment spike.

## Independent review

Fresh local Herdr peer `cappuccino-verifier`, task-created pane `w1:p5N`, used `openai-codex/gpt-6-luna`. The owner explicitly selected this alternative after Anthropic readiness returned `not_ready`. Startup argv specified `--thinking medium`; the visible runtime footer also showed medium. `PI_EFFORT` was empty, so the effort evidence is the argv/footer, not that environment variable. The substantive report established model usability; startup readiness alone did not.

The peer received read/search/gate authority only, without the implementation conversation. It confirmed the staged fingerprints and reported no unexpected source/index changes:

| Owning Git | Branch and base commit | Reviewed staged binary diff SHA-256 |
| --- | --- | --- |
| Cappuccino | `feat/native-skeleton`, `84aba5575459208e2e8eacec79c90e9af94f1850` | `46c9c9391a4e940370e13b962f6b15d2537e8a6feb9270bdb15d34d06434cddf` |
| Workstation | `main`, `bcb9569874d7f57171afb6725ae66673904adca3` | `c0e8a23ca59b96fdec4ddab84a0376a8d48774beac4485698e6efdcbe5e05627` |

These identify the reviewed working diffs before this evidence record was added. The record changes no Swift, test, build configuration or acceptance contract inputs.

## Performed checks

All commands below were independently performed with exit 0; no earlier lead gate was reused in their place. Xcode/Swift commands used the per-command `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` override, leaving machine-global developer selection unchanged.

| Command | Evidence |
| --- | --- |
| Native `make validate` | Swift style, Markdown, 3 hermetic core tests, generated project, iOS Simulator app build and shared macOS shell build passed. |
| Native `make ui-test DESTINATION="platform=iOS Simulator,id=131B54FD-065E-4611-BCEA-7716C632768B"` | 1 XCTest, 0 failures: Chats disconnected state, disabled Send, Nudge/Follow-up controls, Attention and Machines empty states; three screenshot attachments. |
| Workstation `make validate` | Root check (79 tests), generated-catalog freshness, status, live Asobi read and documentation build passed. |
| Workstation `make build-docs` | Documentation build passed. |

Environment: Xcode 27.0, Swift 6.4, task-owned iPhone 18 Pro Simulator with iOS 27.0. Independent XCTest result: `.build/xcode/Logs/Test/Test-Cappuccino-2026.10.05_18-05-28-+0900.xcresult` in Cappuccino. Build/test bundles are ignored local evidence, not portable release artifacts.

## Criterion results

1. **Met:** dependency-free SwiftPM core and minimal shared SwiftUI shell replace Java/Gradle; Git history and GPL-3.0 unchanged; no fabricated connection or speculative bridge.
2. **Met:** honest Chats/Attention/Machines states, unavailable sending, explicit delivery choices and machine-scoped identity tests; the disconnected iPhone journey executed successfully.
3. **Met:** Make/mise, pinned non-Xcode tools, two-space style, pure-core separation, declarative Xcode generation, generated-output exclusions and truthful docs/CI.
4. **Met within scope:** structural/core/build gates and the actual iPhone Simulator shell journey pass. Mac acceptance is build-only.
5. **Met:** workstation catalog integration is fresh and consistent; the optional vphone source assessment records its revision, host-policy/storage risks, installation caveats and separate approval boundary.

## Limits and warnings

Xcode emitted the no-AppIntents-dependency metadata warning. The iOS beta runtime emitted duplicate Accessibility-class and debugger-lookup diagnostics. No checks were suppressed; builds and tests passed.

Not tested or implemented: authenticated remote bridge, existing-agent/session/active-branch continuity, real prompts or approvals, grants/reconnect behavior, Telegram/APNs/background delivery, physical-iPhone signing/provisioning, minimum-OS coverage, live Mac UI or vphone guest execution. The next bounded work is [the disposable-session attachment spike](plan.md#2-disposable-session-attachment-spike); no deployment or host-security change is authorized by this verification.

## Daemon spike slice A checkpoint

Local acceptance verified 2026-10-06 for [issue #5](https://github.com/azusachino/cappuccino/issues/5) at branch `feat/daemon-spike-a`. Scope: companion-daemon slice A only — manual pairing, existing-agent list, read-only active-branch streaming to a CLI client against one task-owned disposable session. No delivery, approvals, or tailnet exposure.

Writer: `cap-spike-glm` (zai-coding-cn/glm-5.3-flash low, sole checkout writer). Independent verifier: fresh `cap-spike-verifier` pane (openai-codex/gpt-6-luna, **medium**), round 1 BLOCKED, round 2 **PASSED** (code-only; lead-owned checkpoint condition satisfied by this section). Lead held acceptance, integration and sole prompting rights on the disposable agent.

Reviewed revisions: skeleton `8fb0fe2`, live-append fixes `2224a5c`/`4c87c5a`, crash fix `c1021b5`, parity/branch-honesty fix `f79c520` (verification HEAD).

### Round 1 (initial verification) — BLOCKED

MET: pairing (external 0600 token, visible unauthorized rejection, no secret in tree), live-append acceptance (journey log `final-journey2.log`: 4 entries events, `ACK-MARK-D` captured mid-stream, 74/74 unique ids; reconnect `reconnect-dedupe.log`: zero new/dupe ids), crash hardening (`c1021b5` materialized LCS windows; 18 tests incl. adversarial shapes), gates (Swift tests green, scoped `make validate` exit 0), honest limits docs.

BLOCKING findings: (1) `Agents.swift` dropped unnamed agents, breaking exact Herdr list parity; (2) `active_branch` inherited the enclosing workstation repo for a scratch-cwd agent, misleading clients; (3) no durable checkpoint existed here. Follow-ups recorded: Herdr-name→Pi-session-UUID mapping before slice B/C reliance; plaintext-token TCP is localhost-spike-only and needs an approved authenticated transport before any tailnet/public exposure; rerun live acceptance after any stream-reconciliation change.

### Round 2 (re-verification at `f79c520`) — PASSED

Parity: `capctl list` ≡ `herdr agent list` as sets including unnamed `w1:p43` (pane_id fallback identity, no fabricated names; unit-tested). Branch honesty: `active_branch: null`, `branch_source: none` unless Git toplevel == agent cwd (device/inode compare; unit-tested for repo-root, nested, no-repo, empty-cwd). No regressions: 20 Swift tests green; scoped `make validate` exit 0. Daemon bound 127.0.0.1 only; disposable session identity unchanged throughout (pane `w1:p5Y`, pid 69909).

### Known limits (accepted for slice A)

Entries are pane lines, not semantic messages; in-memory ring (no restart durability); 400 ms polling; `session_id` is the Herdr agent name or pane-id fallback, not a Pi session UUID; NDJSON/TCP on localhost only, no TLS. Live-append robustness required three fix iterations — the churn/repaint/window regression tests are mandatory-run guards for future stream work.

Physical devices, background/process-death reconciliation, delivery receipts, approvals/grants and transport hardening remain unproven and belong to slices B/C and later (issues #6–#10).

## iPhone pairing slice checkpoint

Local acceptance verified 2026-10-06 for [issue #6](https://github.com/azusachino/cappuccino/issues/6) at branch `feat/iphone-pair-list` (stacked on the daemon branch; PR #14 base still unmerged). Scope: Machines-tab pairing sheet with device-Keychain token storage, agent list with fixture/live equivalence, visible failure states; delivery/approvals remain disabled. This is a historical pre-plugin checkpoint: its pairing token and SSH-exec notes are superseded by the owner's later plugin/no-auth-tailnet decision and are not current bridge instructions.

Writer: `cap-spike-glm` (glm-5.3-flash low, sole checkout writer). Fresh independent verifier `cap-sliceb-verifier` (openai-codex/gpt-6-luna, **medium**): round 1 BLOCKED on one documentation finding — top-level README still claimed "no machine connection" and omitted the pivot; all functional criteria met. Fix `2c67e05` (README current-state + pivot framing, docs-only); round 2 **PASSED** with all round-1 verdicts standing.

Verified HEAD `2c67e05`. Key evidence: `DaemonServing` transport seam with NWConnection reference transport and `DemoDaemonClient` reachable only via `-cappuccino-demo` launch argument; Keychain production store (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`) with in-memory test double; `DaemonWireTests` fixture conformance (null branch, unnamed pane) plus live-vs-fixture equivalence; verifier-rerun gates — 17 Swift tests, scoped `make validate` exit 0, `make ui-test` 3/3 on task-owned simulator `140A8D6F-A532-428E-9387-CDEC83CAB619` (iPhone 17 Pro, local iOS 27.0 runtime; CI remains the 26.5 acceptance runtime) with xcresult `.build/xcode/Logs/Test/Test-Cappuccino-2026.10.06_08-30-21-+0900.xcresult`.

Accepted limitations (with follow-ups): UI-test success path uses the scripted demo client because `SecItemAdd` is unavailable to unsigned simulator apps — real Keychain path is unit-covered, and live production pairing is not yet claimed; demo selection is a launch-argument hook available at runtime (build/test-time gating is a follow-up); SSHClient remains a design sketch implementing the seam after #6. Physical-device pairing, SSH transport, transcripts (issue #7), delivery (#8) and approvals (#9) remain unproven.

## Plugin bridge skeleton checkpoint

Local acceptance verified 2026-10-06 for [issue #16](https://github.com/azusachino/cappuccino/issues/16) at branch `feat/plugin-bridge` HEAD `cc31de3` (base `main` post-#14/#15 merges). Scope: the Cappuccino **herdr plugin bridge** — Rust/tokio/axum plugin serving `GET /api/session`, `/api/agents` (Herdr socket RPC parity incl. unnamed panes), `GET /api/transcript` (pi `agent_session` → canonical store containment), `WS /api/stream` (content-diff reconciler port) — plus the Swift `BridgeClient`/`BridgeStream` client behind `DaemonServing`, plugin manifest, user stories S1–S8 and `docs/architecture.md`.

Architecture context (owner decisions, 2026-10-06): Cappuccino ships **its own herdr plugin** (herdr-web-ui = design prior art, studied not used — assessment: harus-workstation KB `docs/runbooks/research/2026-10/2026-10-06-herdr-web-ui-assessment.md`); no auth by default (tailnet/loopback is the boundary; public exposure a non-goal); extensive-yet-configurable (config layer, reserved auth section, middleware hook, composable modules); dependency policy = no herdr/tailscale crates (schema-fixture conformance + tailscale CLI only). `services/daemon` stays merged as frozen reference; the SSH-exec pivot is superseded.

Writer: `cap-spike-glm` (glm-5.3-flash low). Independent verifier: `cap-bridge-verifier` (openai-codex/gpt-6-luna, medium) — round 1 BLOCKED (stale token/auth docs + invented layout; missing WS stream client; live journey unverifiable without a safe route), round 2 BLOCKED (two pinpoint defects: WS scheme downgrade to insecure `ws://`; knob-contract mismatch across shell/Rust), round 3 **PASSED** at `cc31de3`.

Round-3 evidence at historical HEAD `cc31de3`: scheme mapping http→ws / https→wss with typed rejection (`WebSocketSchemeTests`); unified knob contract (`StrictBool` plus the then-present `bridge.sh`; `knob_contract.sh` covered accepted spellings); schema conformance against committed `fixtures/herdr-api.schema.json` (Herdr 0.9.3, protocol 22); gates rerun — cargo 31 (24+7), Swift 27, scoped `make validate` 0, `md-check` 0, knob contract 0. Round-2 live route (executed exactly: `CAPP_BRIDGE_SERVE_AUTO_APPLY=0 CAPP_BRIDGE_PORT=7991`, zero Tailscale mutation, exact pane-set parity, fail-closed transcript) remains evidence only for the runtime paths unchanged since then. The shell lifecycle described by this checkpoint is historical and is not evidence for the replacement Rust lifecycle below.

Accepted limitations/follow-ups: live WebSocket reconnect journey not yet exercised (scripted frames cover initial→append→duplicate→disconnect; reconnect policy is a later slice); two non-fatal Rust warnings in `config.rs` (cleanup + a warnings-as-errors decision for the bridge are follow-ups); branch history contains since-deleted `target/` artifacts from the first skeleton commit (HEAD tree is clean); physical devices, delivery (#8), approvals (#9), transcripts UI (#7) remain unproven. No owner agent was prompted; `cap-spike-agent`'s externally closed pane is recorded rather than hidden.

Hosted addendum (2026-10-06, same day): the first hosted run on this branch ([37404336604](https://github.com/azusachino/cappuccino/actions/runs/37404336604)) failed `BridgeStreamTests.initialEntriesThenAppendThenDuplicateSuppressed` on macOS 26 while the local macOS 27 run passed — the stream body closed the socket in a detached task, so the consumer observed the error before close ran. Fix `2359605` (synchronous `closeImmediately()` before any error propagates, all paths; regression assertion kept) went green on the authoritative runner ([37406629241](https://github.com/azusachino/cappuccino/actions/runs/37406629241) successor at `2359605`, both jobs success). Recorded as workstation pitfall "local-green is not hosted proof for ordering-sensitive stream tests"; the assertion is retained as the permanent regression.

## CI-quality and macOS 26 checkpoint

Verified 2026-10-05. The owner selected action version tags rather than SHA pins and a macOS 26 minimum; iOS remains 17+. This checkpoint covers CI, deployment minimums, compiler gates and native-first development guidance, not new application behavior.

Fresh task-created Herdr peer `cappuccino-quality-verifier` in `w1:p5P` used the owner-selected `openai-codex/gpt-6-luna` alternative at medium effort. Startup argv, visible footer and peer `PI_REASONING_LEVEL` agreed; the peer confirmed `HERDR_ENV=1` and its pane identity. It corrected an initial report sentence that incorrectly described itself as not a Herdr peer. Authority was read/search/gate execution, not source/index or remote writes.

| Owning Git | Reviewed commit | Binary diff SHA-256 from preceding checkpoint |
| --- | --- | --- |
| Cappuccino | `56a1ccfecb978264d639c7ea11e697903c1ca6cf` | `29d38fbf911b85de74e3503d6f203b7bac0c09e28cf99b7c05c215deaefe36b5` from `a46f644944db2c048a819b60e2dca9516082c410` |
| Workstation | `85cbe4716ef94b9d3176e444488c6a826cb1ef48` | `edbd2a134199459fcbe286e6bf4e9fb1d175bc5f78ecae8719d61ff8e71db3b5` from `d983d4b42c9261326b5880a3c4834fbe74a953a1` |

The peer independently performed native `make validate`, UUID-selected `make ui-test` and root `make validate`, all exit 0: 3 core tests, both app builds, 1 UI test with three attachments, root 79 tests, catalog/status/Asobi checks and docs build. Local environment was macOS 27.0.1, Xcode 27.0 and Swift 6.4. It used only task-owned simulator `A88308C8-5034-4067-8E1E-C22684FBA504` (iPhone 18 Pro/iOS 27); result `.build/xcode/Logs/Test/Test-Cappuccino-2026.10.05_19-32-05-+0900.xcresult`.

The peer then inspected [hosted run 37297617473](https://github.com/azusachino/cappuccino/actions/runs/37297617473), job `111722465955`, at the exact reviewed native commit. All required steps succeeded on macOS 26.6.2, Xcode 26.6 (`17F113`) and Swift 6.3.3:

- `make validate`: style/Markdown, 3 core tests with warnings-as-errors, iOS and macOS builds in Swift 6 mode.
- iPhone 17 Pro/iOS 26.5 smoke: 1 test, 0 failures or skips; disconnected states, disabled Send, delivery choices and three screenshot attachments.
- Artifact `iphone-shell-37297617473-1`, ID `11340193738`, 349,478 bytes, uploaded successfully with expiry 2026-10-12. Bundle: `Test-Cappuccino-2026.10.05_10-38-25-+0000.xcresult`.
- Created, tested and successfully deleted the same simulator UUID `AACE79EA-35ED-439C-90F5-9D1EDCE7F833`.

The requested tags resolved to checkout v7 `3d3c42e5aac5ba805825da76410c181273ba90b1`, mise-action v5 `2d8d4cafcbd33be2ea37d2b6f5ad595363d1f1ca` and upload-artifact v7 `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a`. These are observed provenance, not workflow SHA pins. The peer also confirmed root [run 37297616160](https://github.com/azusachino/harus-workstation/actions/runs/37297616160) and [run 37297620123](https://github.com/azusachino/harus-workstation/actions/runs/37297620123) succeeded at the reviewed root commit. Hosted evidence inspection did not repeat local gates.

Criterion disposition: tagged-action/security and Dependabot configuration met; consistent platform/compiler gates met; simulator/artifact/cleanup configuration and successful runtime path met; bounded native/Mac guidance met; exact-head hosted execution met. No blocking source or local-runtime findings. Cleanup's `always()` failure path was source-reviewed, not exercised by a deliberately failing run. The lead additionally downloaded the hosted bundle, confirmed its device/result summary and inspected all three exported screenshots.

This evidence-only record delta changes no Swift, tests, workflow, deployment settings, contracts or generated inputs; runtime evidence originates at `56a1ccf`. The remote-bridge, physical-device, minimum-iOS-runtime, live Mac UI and vphone limits above still apply.

## Multi-app scaffold checkpoint

Local acceptance verified 2026-10-05; hosted acceptance is pending. This scope adds disconnected Android alongside relocated iOS/macOS entrypoints and the shared Apple package. It includes the Android plan formerly proposed in PR #2, for consolidation into [PR #1](https://github.com/azusachino/cappuccino/pull/1). No connected feature or release is accepted here.

Source/configuration commit: `a3311ab62333265ae015373bbdb0fea7cffddbe2`, including preceding Apple relocation `e45c1f1f0922943207356c429fc20764acca8d2e` and Android plan `73504a113ee23ac101e56217823da743956f031e`. Independent review covered the working tree at `e45c1f1` before the source commit; the committed tree preserves those reviewed inputs.

| Compared scope | Binary diff SHA-256 |
| --- | --- |
| Full scaffold from PR #1 head `c66403b`, with `--no-renames` | `c7e0bf0386222233e5f48f36322a7d2caaf1dbf65a60be48dea9243f9cc48478` |
| Same full scaffold with default rename detection | `1d473aa7c577c5412be5b589707f731c2e04437ad39868a4b7ce1d495cc5a124` |
| Working delta from `e45c1f1` | `664efc7df73a7f72a14d5608894626bbb7807a9e22f76134e1514eda737d1033` |

The first two hashes differ only in Git's representation of the Apple moves; both encodings were reproduced against the same source. They must not be compared using different rename flags.

### Independent local evidence

Task-created real Herdr peer `cap-scaffold-luna`, pane `w1:p5V`, independently reviewed source and executed the owning gates. The owner selected `openai-codex/gpt-6-luna` at **low** effort; lead-observed startup argv and visible runtime footer confirm that route/effort, and substantive responses prove model access. The peer did not claim provider-level attestation. Task-created peer `cap-scaffold-glm`, pane `w1:p5W`, used owner-selected `zai-coding-cn/glm-5.3-flash` at low for separate read-only build/CI and artifact review. Both had no source/index/remote-write authority.

The requested `antigravity/gemini-3.8-flash` low peer returned a roughly 40-hour quota error rather than a usable response. The owner explicitly approved proceeding with Luna/GLM; no model was silently substituted. Only the failed task-owned Gemini pane `w1:p5X` was closed.

| Independently performed owning command | Result |
| --- | --- |
| `make -C <cappuccino> validate` with scoped Xcode/SDK | Exit 0: Swift/Kotlin style, Markdown, 3 Swift and 3 Kotlin core tests, Android lint zero issues, iOS/macOS builds and both Android debug APKs. |
| `make -C <cappuccino> ui-test-android` with explicit serial | Exit 0: 1 instrumentation test, 0 failures/errors/skips, activity recreation and saved tab/delivery selection, disabled messaging, three decoded PNGs at 1344×2992. |
| `make -C <cappuccino> ui-test DESTINATION=…` with explicit UUID | Exit 0: 1 XCTest, 0 failures/skips; three screenshot attachments exported and visually inspected. |

Environment: macOS 27.0.1, Xcode 27.0/Swift 6.4, existing JDK 21.0.11/Android SDK, Gradle 9.8.0, AGP 9.4.1 and Kotlin/Compose compiler 2.4.20. Android used only newly task-created `Cappuccino_multi_app_20261005`, API 35 ARM, serial `emulator-5560`; iOS used task-created UUID `BA94D231-CB0A-4383-B3F4-94F449175D58`, iPhone 18 Pro/iOS 27. The independent XCTest bundle is `.build/xcode/Logs/Test/Test-Cappuccino-2026.10.05_23-02-48-+0900.xcresult`.

Local criteria: native layout/consuming builds met; honest disconnected UI/delivery choices met; identity/pins/license/no runtime integration met; strict native gates met; both phone journeys/images met; truthful instructions and scope met. CI configuration was reviewed, but **delivered-head hosted execution remains unverifiable until it runs**.

### Failures retained and corrected

- Strict lint caught stale AGP/Kotlin patch examples, missing launcher icon and missing modern backup/transfer exclusions. Actual repository releases and real resources fixed them without a baseline or suppression.
- Compiler warnings-as-errors rejected the legacy Compose test rule. The test migrated to the documented `junit4.v2` API rather than weakening the compiler gate.
- A passing first Android journey exported 51-byte error text as `.png`: UTP uninstalled the app before post-test `adb run-as`. Source-only reviews had wrongly assumed that export was valid. Actual image inspection disproved it. Capture now uses AndroidX platform test storage, collected before uninstall; Make requires a unique file and PNG MIME type. Missing, ambiguous and non-PNG negative fixtures are rejected. These recipe-isolation checks are not runtime gate substitutes.
- The first independent invocation mistakenly ran workstation Make. Its root exit 0 is not app evidence; the missing Android target failed. Explicit absolute `make -C <cappuccino>` commands then passed the owning gates.
- The original emulator service reached its harness 1800-second limit during independent reproduction. That was infrastructure timeout, not an app crash. The same task-owned AVD/serial was restarted and the missing Android journey rerun; existing equivalent Apple/build evidence was retained.

Gradle's configuration deprecation was traced independently to upstream AGP/ktfmt `Configuration.setVisible` calls, not project scripts. It remains visible; no project/compiler/lint warnings were suppressed. Kotlin's fully-tested compatibility-window caveat stays explicit in [development](development.md).

Physical-device/minimum-OS coverage, assisted accessibility, process death/background/network recovery, authenticated bridge, real history/delivery/approvals/grants/alerts, credential storage, signing/update distribution and vphone remain unproven. The first eventual release target is a private usable prototype. This evidence record changes no source, tests, build/workflow settings or product contracts; runtime evidence belongs to the reviewed source commit.

### Delivered-head hosted checkpoint

Hosted acceptance verified 2026-10-05 at PR #1 head `75694c3` (run [37325757158](https://github.com/azusachino/cappuccino/actions/runs/37325757158), jobs `android` and `check`, both success).

The first delivered-head run `37324470442` failed before any test: the hosted runner's AVD catalog has no `pixel_8_pro` device (`Error: No device found matching --device pixel_8_pro`), so AVD creation never produced an emulator and the artifact step correctly found no results. The optional `profile:` input was removed in `75694c3`; every other job input, gate and artifact path is unchanged. This was workflow-infrastructure only; no app source changed after `a3311ab`.

At `75694c3`, both required jobs succeeded on their hosted runners:

| Job | Evidence |
| --- | --- |
| `check` (macOS) | `make validate-apple`, one task-created simulator, iPhone shell 1 test/0 failures/0 skips, XCTest bundle `Test-Cappuccino-2026.10.05_14-36-56-+0000.xcresult` (artifact `iphone-shell-37325757158-1`, 348,273 bytes), simulator deleted. |
| `android` (Ubuntu) | `make validate-android`, API 35 x86_64 Google APIs emulator with KVM rule, instrumentation `disconnectedJourneyAndRecreation` 1/0/0/0 (JUnit XML in artifact), three collected PNGs (artifact `android-shell-37325757158-1`, 113,034 bytes), emulator shutdown owned by the runner action. |

The lead downloaded both artifacts. The Android JUnit XML reports 1 test, 0 failures/errors/skips on `emulator-5554`; the exported screenshots are real PNGs. The iPhone bundle summary reports 1 passed, 0 failed, 0 skipped. Hosted Android screenshots are 320×640 low-resolution; the Chats capture shows the app's correct disconnected state **behind a system `Pixel Launcher isn't responding` ANR dialog** caused by hosted-emulator load. The journey's semantic assertions passed independently of that dialog. This is recorded honestly as a hosted-environment artifact, not suppressed; a later CI tuning slice may raise emulator resources, and physical-device evidence remains a separate requirement.

Cancelled/stale runs (`37324470442` and superseded watchers) are retained in history as failure evidence; the merged PR #2 and this PR's checks all conclude at `75694c3`. Delivered-head hosted acceptance is complete for this skeleton scope; the limits in the local checkpoint above still apply.

## iPhone transcript UI — issue #7 local checkpoint

Independent verification passed on 2026-10-06 at `2c96ae76861cb92771471be663dad6b47bb4a914`, branch `feat/transcript-ui`, stacked on PR #17 (`feat/plugin-bridge`). GLM implemented the initial slice and first fixes; after its quota limit the owner authorized Luna as sole writer. A separate Luna medium verifier reviewed all three rounds.

Delivered: transcript bubbles, authored-order prose/code segments, expandable tool arguments/details, sequence-ordered backfill and duplicate suppression, visible gap markers, manual reconnect/failure banner, foreground-only stream lifecycle, and reset recovery that preserves prior history on durable-reload failure. Regression tests cover stopped reloads, failed reset preservation, out-of-order 1→3→2 delivery, and fresh independent 1k/4k merge workloads. Temporary debug UI/logging was removed.

Independent commands at the reviewed source revision:

- `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun swift test --package-path packages/apple`: exit 0, 39 tests in 11 suites.
- `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer make validate`: exit 0, complete Apple/Android validation.
- `make md-check`: exit 0.
- Scoped `make ui-test DESTINATION="platform=iOS Simulator,id=140A8D6F-A532-428E-9387-CDEC83CAB619"`: exit 0, 5/5 journeys, including scripted live transcript and 1k-entry scroll. xcresult: `Test-Cappuccino-2026.10.06_16-50-49-+0900.xcresult`.

Earlier blockers: reset captured history after clearing it; live tool payloads were dropped; prose/code order was flattened; ordering/scaling coverage was insufficient; debug artifacts remained. These were fixed rather than suppressing tests. Round-1 gate failures used unscoped CommandLineTools; subsequent independent gates used the scoped Xcode toolchain.

Limits: UI evidence uses scripted transcripts, not a canonical live Pi transcript. Read-only bridge probes returned HTTP 200 and honestly unavailable canonical history; no live WebSocket transcript journey or Herdr parity comparison was performed in this verification. Accessibility was source/journey checked, not exercised with VoiceOver or Dynamic Type stress. Reassembler append is amortized O(1), but published Swift Array snapshots may incur copy-on-write; backfill is O(n). The unchanged timing-ratio test is a coarse regression signal, not a complexity proof. A separate newer-generation stale-result test remains follow-up; stop-during-reload is covered. Hosted acceptance for this source is pending; local PASS is not hosted proof.

## Herdr API boundary and Rust lifecycle — local checkpoint

The owner-selected issue #8 receipt acceptance is `confirmed` only after Pi accepts the requested queue operation; ambiguous outcome is final `unresolved` and that action is not replayed. This remains intended acceptance, not a capability observed through Herdr. Herdr 0.9.3 `agent.prompt` acknowledges PTY submission and optional lifecycle wait; the API does not select Pi `steer`/`follow_up` or provide a correlated Pi queue receipt. The bridge remains a standalone phone-transport facade, not an agent runtime. No delivery route or Pi extension was added. Full source/runtime limits and the recovered task-owned synthetic probe evidence are recorded in the workstation scratch report.

The Herdr manifest invokes the Rust executable directly for start/stop/status/logs; both bridge shell scripts remain deleted. The lifecycle now stores only a random control token in a verified owner-only `bridge.control` record and uses an owner-only Unix-domain `bridge.sock` for token-checked status and graceful shutdown. The record PID path is no longer signaled; legacy `bridge.pid`, malformed records and unsafe filesystem paths fail closed without cleanup. State directories/files are verified against owner, type and exact modes; file opens use directory-relative `O_NOFOLLOW` operations. The managed server sets a restrictive umask before Tokio starts and verifies the resulting socket mode.

New lifecycle coverage includes concurrent start serialization, malformed and stale/legacy records with an unrelated child process kept alive, unsafe custom directories and symlink/nonregular state paths, startup bind failure leaving an unrelated TCP listener available, an owned-child readiness timeout, stop timeout preserving state/listeners, private ownership/mode assertions, and a `tailscale` sentinel executable proving disabled auto-apply never invokes it. The checked-in integration suite exercises the actual CLI on macOS; the later S1/S2 journey below records separate task-owned Linux runtime evidence.

Local gates for this change:

- `cargo fmt --check` in `services/bridge`: exit 0.
- `cargo test` in `services/bridge`: exit 0, 27 unit tests, 6 lifecycle integration tests and 7 schema-conformance tests.
- `cargo build --release` in `services/bridge`: exit 0.
- Scoped `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer make validate`: exit 0. Swift Testing reports 39 passed; Apple build targets succeeded; Android formatting/unit/lint checks and debug app/instrumentation APK assembly succeeded.
- `make md-check` after the final acceptance wording: exit 0, 15 files.
- `git diff --check`: exit 0. Static TOML parsing confirmed every manifest startup/action command calls the binary directly, with no shell launcher.

Native UI files were unchanged, so no simulator journey was rerun.

Independent Luna-medium re-verification passed at `f1abb933b41e728c08bb11fe16fc592691639316`. The verifier independently reran all gates above and resolved the prior blockers: persisted PID signaling was replaced by a private per-instance Unix control endpoint; owner/private modes and descriptor-relative no-follow state handling were checked; failure-path integration tests preserved unrelated processes/listeners. A fresh isolated release lifecycle on macOS verified start/status/logs/HTTP readiness/stop, directory mode `0700`, file/socket modes `0600`, endpoint closure, and a PATH sentinel proving disabled Serve invoked no Tailscale command. All task-owned process/state artifacts were cleaned up; no retained bridge or Tailscale configuration was changed.

The initial manual `/tmp` path was rejected because it is a macOS symlink; the canonical `/private/tmp` run passed. One cleanup attempt found an empty task-owned HOME directory, which was inspected and removed. At this historical independent-verification checkpoint, Linux runtime remained unverified; the later task-owned S1/S2 journey below supplies Linux runtime evidence, not independent verification. Neither result certifies native queue-kind selection, Pi-confirmed delivery, or a live transcript journey. Hosted checks for this branch remain separate from these local independent results.

### Installed plugin and Linux acceptance

Independent Luna-medium verification passed at `2a4fa1dd2be55ba247c6ac0ad0ebe1c6a5e9c810`. The plugin now invokes installed `cappuccino-bridge` from Herdr's inherited `PATH`; it has no build hook or checkout-artifact dependency. Both READMEs document installation before plugin linking, supported behavior, configuration/security limits, and development commands. The direct `libc` dependency supplies Unix locking and descriptor-relative no-follow filesystem operations; HTTP routes and WebSocket `/api/stream` share the `/api` prefix.

The verifier independently used ephemeral Podman Linux/arm64 containers with only bridge source mounted read-only. Image: `docker.io/library/rust:1.98.0-slim-bookworm@sha256:1469a27c125cb5a3aebfa4f4e4665d935b02fb72cc093b2c974b3d740e43f157`, Debian 12, Rust/Cargo 1.98. `cargo fmt --check`, `cargo test --locked` (27 unit + 6 lifecycle + 7 schema tests), release build, and isolated `cargo install --locked` all passed. A separate installed-binary PATH journey verified start/status/logs, HTTP readiness, private state modes, stop, endpoint closure, and control-state cleanup. Disabled-Serve sentinel assertions proved no Tailscale command ran. At this earlier checkpoint, live Linux Herdr socket integration remained untested; the later S1/S2 journey below now supplies that task-owned runtime evidence.

Scoped full local `make validate` independently passed with `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` and `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`: Swift 39 tests, Apple builds, Android unit/lint/build gates. Markdown, manifest assertions, and diff checks passed. An initial container login shell omitted Cargo's PATH and exited 127; the non-login shell retry passed. Ephemeral containers were removed; the existing VM was left running. No retained bridge, owner agent, or Tailscale configuration was changed. Hosted checks for the updated PR head remain pending.

## S1/S2 installed Herdr plugin journey

Task-owned macOS and Linux/arm64 journeys passed in the final source tree based on `8f192f9a0ec032b956800cbda98a30d65a828b24`; the final commit is recorded in the linked task report. This checkpoint is runtime evidence, not a physical-device or independent-review claim.

Using Herdr `0.9.3` (protocol `22`) and an isolated HOME/XDG config/state, the actual bridge manifest registered as `azusachino.cappuccino-bridge`. `cargo install --path vendor/cappuccino/services/bridge --locked --root <task-owned-cargo-root>` succeeded. With Cargo's bin removed from inherited PATH, the actual startup hook and `status` action failed to spawn the missing `cappuccino-bridge` (`No such file or directory`). This was observed once; the isolated server was stopped.

With the installed executable on the actual Herdr server's inherited PATH, the final `[[startup]]` hook succeeded and started bridge PID 61333 at `127.0.0.1:51714`. `GET /api/session` returned HTTP 200. Actual Herdr CLI actions `start` twice, `status`, `logs`, and `stop` all completed successfully; repeated start reported the same PID as already running. After stop, the HTTP listener refused connections, PID 61333 was absent, `bridge.control`/`bridge.sock` were absent, the state leaf was mode `0700`, and lock/log files were mode `0600`. `CAPP_BRIDGE_SERVE_AUTO_APPLY=0`; the task-owned Tailscale sentinel was not invoked. Both task-owned Herdr sessions ended `not_running`; no existing server/session or Serve configuration was touched.

The actual startup exposed two product integration issues that were corrected: Herdr creates the per-plugin state root as `0755`, so fresh bridge state now lives in a private `state` child while legacy lifecycle files keep their prior location; the shorter child also keeps the Unix control-socket path within macOS `SUN_LEN`. Existing no-follow and owner-only validation remains unchanged. The bridge gates pass on macOS (28 unit, 6 lifecycle, 7 schema tests), and the user stories/READMEs now state that installation precedes linking and linking does not build the binary.

Final local gates at this source state: `cargo fmt --check`, `cargo test --locked` (41 total Rust tests), and `cargo build --locked --release` all exited 0; `make -C vendor/cappuccino md-check` checked 15 Markdown files and exited 0; `git diff --check` exited 0. Scoped `env ANDROID_HOME=/opt/homebrew/share/android-commandlinetools DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer make -C vendor/cappuccino validate` exited 0. Gradle printed deprecation warnings; none were suppressed. These source gates were completed before the final verification-doc wording update; Markdown and diff checks were rerun after that documentation-only change.

Linux/arm64 acceptance passed in task `bf46c1885` using disposable `docker.io/library/rust:1.98.0-slim-bookworm@sha256:1469a27c125cb5a3aebfa4f4e4665d935b02fb72cc093b2c974b3d740e43f157` (Debian 12, Rust/Cargo 1.98). Herdr source revision `e35f3937b0efe40ec0dab675709c68e1d8e8c9e6` identifies the official v0.9.3 Linux/aarch64 artifact URL and digest in `distribution/latest.json`; the container verified SHA-256 `4de7aa3e25678812e92960de64f7c2aaa1bca1f0f80a3c5e559837e231e1f5c0`, and `herdr --version` reported 0.9.3. Bridge source and the artifact manifest were read-only mounts. No host HOME, config, socket, credentials or secrets were mounted.

The real bridge manifest linked and listed as `azusachino.cappuccino-bridge`. Isolated Herdr status reported version `0.9.3`, protocol `22`, and `compatible:true`. Startup log `plugin-log-1` succeeded and started bridge PID 1419 on `127.0.0.1:51715`. `curl --fail --silent --show-error http://127.0.0.1:51715/api/session` exited 0 and returned `event: paired` JSON. Real Herdr CLI actions `start` twice, `status`, `logs`, and `stop` all completed with exit 0 (`plugin-log-2` through `plugin-log-6`); repeated start reported the same PID. After stop, the endpoint refused connections (curl exit 7), PID 1419 was absent, `bridge.control`/`bridge.sock` were absent, state mode was `0700`, and lock/log files were `0600`. Herdr reported `not_running`. With Serve auto-apply disabled, the task-owned Tailscale sentinel was untouched. The container used `--rm`; no owner process, session, or Tailscale state was used or changed.

Earlier Linux attempts are retained in `.tmp/cappuccino-connected/spike-a/s1-s2-plugin-report.md`: `b45dcd5a3` lacked rustfmt; `bcf7d456c` passed Linux bridge format/test/release gates (28 unit + 6 lifecycle + 7 schema tests) but Herdr source compilation required Zig 0.16.0; `b4eff10c9` verified and started the official artifact but exited 127 before health/actions because a PATH override hid `rustc`. No result from those attempts is counted as the completed S1/S2 journey.

Independent Luna-medium S1/S2 verification passed at `d8a60f8222213fc056b3c77e4461415b6eb2f8c2`. Fresh isolated macOS and Linux/arm64 Herdr 0.9.3 journeys reproduced installed-PATH binary setup, plugin link/list, actual startup hook, repeated idempotent start, status/logs/stop, HTTP readiness and listener closure. Active state directories were `0700`; lock/control/log/socket files were `0600`. Tailscale sentinels were untouched. All task-owned bridge/server processes, sockets and temporary roots were stopped/removed; ephemeral Linux containers used `--rm`, while the existing Podman VM remained running.

Independent source gates passed: Rust 41 tests, format/release, scoped full Apple/Android validation, Markdown and diff checks. Harness-only failures (incorrect log filename, missing artifact directory, incorrect server-status JSON selector) were corrected sequentially with task-owned cleanup; their outputs were not counted as successful journeys. This acceptance covers S1/S2 plugin lifecycle before device introduction, not live-agent delivery, physical-device connectivity, or the forthcoming headless-client performance/leak benchmarks.

## Headless bridge client — independent acceptance

Independent Luna-medium verification passed on `feat/bridge-test-client` at
`b2f139a21e883d9619e28d6791cc11ab18fdc904` plus the four-file fixture diff below.
The source-first review resolved connection-registration races, cancellation
ownership, panic publication, and child/temp cleanup failure paths before the
full gates ran. The final commit and hosted results are separate checkpoints.

| Reviewed file under `services/bridge/tests/` | SHA256 |
| --- | --- |
| `client_contract.rs` | `493c4e4e3f82e58f6b082fac2d1146e584e1b8f7b9bda8c0f7d2eafbc546ffd2` |
| `resource_soak.rs` | `62d60392855bc2a235f17539379b5772680258e5fd21d7941e606b4d27f6627d` |
| `common/task_scope.rs` | `10473b135a03e55a813d0fde7367e85b42974fab70e115a91ccef1b8ccbd0689` |
| `common/resource_scope.rs` | `a43569ef58f638f4f5ab9a64d57797b0591531db613a0ed61e5ddb7a1d8f1af7` |

### Gates and failure coverage

- macOS `make check-bridge`: exit 0; 90 normal tests (29 unit, 39 client,
  6 lifecycle, 9 resource, 7 schema), all four mandatory ignored resource
  cases, formatting, and release example build passed.
- Linux/arm64 equivalent `make check-bridge`: exit 0 for the gate; the same
  normal tests and all four ignored cases passed. Two additional complete
  39-test client runs and child/temp ownership fault tests passed on each OS.
- Both platforms independently repeated the four resource cases with visible
  telemetry; each run passed in approximately 350 seconds.
- Release bridge binaries were explicitly rebuilt before external benchmarks;
  an existing release file alone was not matching-input evidence.
- Scoped full `make validate` passed: Swift 39 tests, iOS Simulator/macOS
  builds, Android formatting/unit/lint and app/test APK assembly, and Markdown
  (15 files). No UI, emulator/device, or live connectivity journey is implied.
  Gradle deprecation notices remain unsuppressed.

Linux used Debian 12 on aarch64 and Rust/Cargo 1.98.0 in disposable containers
from `rust:1.98.0-slim-bookworm` at digest
`sha256:1469a27c125cb5a3aebfa4f4e4665d935b02fb72cc093b2c974b3d740e43f157`.
Public source was read-only; separate Cargo/build/temp mounts contained no host
HOME, credentials, sockets, or unrelated repositories. macOS used Darwin 27
arm64 and Rust/Cargo 1.98.1. The Xcode and Android SDK selection was scoped,
not changed globally.

Initial container attempts lacked rustfmt or git and failed; these were not
counted as passing gates. The successful Linux gate runner later failed on a
scratch benchmark-script path after its gate/repeats/builds had passed. The
corrected separate benchmark completed successfully. Both failed containers
and subsequent task containers were removed.

The fixtures now own unspawned connection futures through their listener.
Shutdown closes registration before spawning can escape, aborts all tracked
children before joining, and reports panics only after sibling cleanup.
Caller cancellation or a missed deadline retains a live cleanup owner rather
than equating `abort()` with termination. Injected child kill/reap and temp
metadata/remove failures exercise normal Drop and existing panic unwinding:
failed cleanup remains identifiable and owned for retry; tests observe that
ownership, release a readiness gate, and require terminal cleanup and worker
join. Persistent host failures remain explicitly unsuccessful/unresolved and
require runner reconciliation; arbitrary non-yielding work is not forcibly
terminable by Tokio.

### Representative benchmark and recovery observations

Each platform ran the checked-in `bench-bridge-client` target against a fresh,
task-owned bridge and synthetic Herdr session: 300 HTTP iterations, 20/20 WS
cycles, and 40 consumed entries. Strict H2 and HTTP/1-only fallback were also
run separately. HTTP observed `http2`; WS used classic HTTP/1.1 Upgrade.

| H2-preferred Make benchmark | macOS arm64 | Linux arm64 |
| --- | ---: | ---: |
| Cold p50 / p95 (µs) | 129 / 242 | 854 / 51,042 |
| Warm p50 / p95 (µs) | 36 / 43 | 164 / 291 |
| Warm requests/s | 26,490.6 | 5,693.5 |
| CPU delta (ms) | 77 | 230 |
| Current RSS start → end (KiB) | 3,728 → 4,912 | 3,800 → 4,140 |

Separate strict-H2 external current-RSS samples plateaued at 4,896 KiB on
macOS (116 samples) and 4,104 KiB on Linux (247 samples). These are single-run
observations, not cross-platform comparisons or ratified performance budgets.
Peak RSS is separate: the Make run reported 5,029,888 **bytes** on macOS and
41,944 **KiB** on Linux; neither is substituted for current residency.

Success-soak phase-matched FD teardown recovered exactly: macOS 11→11,
Linux 15→15. Services-up batch counts remained 12/12/12 and 16/16/16,
respectively. Current-RSS batch drift was 208 KiB and 260 KiB. After full
teardown the reported current-RSS deltas were +560 KiB and +176 KiB.
Refusal/disconnect reported FD deficits from runtime teardown, not unexplained
growth. Twenty observed in-flight cancellations joined on each OS; worst join
latency was approximately 197 µs / 245 µs. This is bounded regression/recovery
evidence, not a claim of universal leak freedom.

All verification jobs terminated; task benchmark children/listeners, sockets,
temp roots, and containers were reconciled. Build caches and measurement logs
were retained as scratch evidence, not live resources. Preexisting bridges,
owner agents, Tailscale, and global toolchains were untouched. The task-started
Podman VM restoration is a separate closeout action.

Limits: synthetic loopback evidence does not prove canonical live Pi transcript
parity, physical-device connectivity, tailnet HTTPS/ALPN, or WS-over-H2.
Pi queue-kind selection/confirmed delivery remains unimplemented. Hosted
Apple/Android checks remain separate from local Rust/Linux evidence.

## Android read-only connected client — independent acceptance

Independent verification passed on `feat/android-connected` at
`30a3a7259d392e5494d611f370a896e91eb6df23` with the 32 regular changed/untracked
files cataloged below. The source-first independent review (`cap-android-glm-review`,
`zai-coding-cn/glm-5.3-flash` LOW) verified all independent criteria, confirmed zero
source defects, and verified that all production/test diffs match the accepted scope.

| Metric / Artifact | Recorded value |
| --- | --- |
| Base HEAD | `30a3a7259d392e5494d611f370a896e91eb6df23` |
| Tracked diff SHA-256 | `86231e6a1812dddf8936437ac524908db01710aecc2ed859496be87755d32b0f` |
| 32-file manifest SHA-256 | `d79acc831c0b75de862d57be70c294518a9988354b793d561cf1251cec16ab5a` |
| Debug APK path | `apps/android/app/build/outputs/apk/debug/app-debug.apk` |
| Debug APK size | 13,047,614 bytes (~12 MiB) |
| Debug APK SHA-256 | `036c269173d957733ecb33b1ba835f0a1cefeafa613e7609de68d8836909ace8` |

### Independent gates and evidence

- `mise exec -- make check-android`: exit 0; ktfmt, lintDebug, and testDebugUnitTest executed clean.
- `mise exec -- apps/android/gradlew --no-daemon -p apps/android :app:testDebugUnitTest --rerun-tasks`:
  exit 0; 26/26 tasks executed forced; 44 tests (3 identity, 14 ConnectedViewModel, 4 raw close peer,
  2 theme, 12 protocol, 9 bridge client), 0 failures, 0 errors, 0 skipped.
- `mise exec -- apps/android/gradlew --no-daemon -p apps/android :app:compileDebugAndroidTestKotlin --rerun-tasks`:
  exit 0; 29/29 tasks executed forced.
- `mise exec -- make validate-android`: exit 0; 71 actionable tasks executed/up-to-date; debug APK and
  androidTest APK assembled.
- `mise exec -- make md-check`: exit 0; rumdl check passed across all Markdown records.
- `git diff --check`: exit 0.

### Scope boundaries and limits

Source and JVM clearance only: wire models, concrete OkHttp/WS transport, lifecycle controller,
Material 3 / dynamic theme, and raw socket close fixture proof are verified. Broad Android runtime,
OS process death under platform pressure, physical phone Tailscale HTTPS/WSS connectivity, and
canonical Pi history parity remain separately unverified/blocked for device trial stage E.

## Pi transcript identity — conversation-polish checkpoint

Verified 2026-10-08 on `fix/conversation-polish`, base
`183460b78e6cd71e5684d77b380cc1f03d6a9fae`, with only
`services/bridge/src/transcript.rs` and `services/bridge/README.md` in the
reviewed bridge diff. Transcript source SHA-256:
`c8387d537a40b885ae5f40ab4316e8d772ac390ae9bc0832dcce8442fe6ee92f`.

Fresh independent Herdr peer `cap-polish-review` used the owner-selected
`zai-coding-cn/glm-5.3-flash` at low effort. All six criteria passed: exact
path-kind session reports, both default Luna/Pi stores, exclusive override
precedence, no cwd/title/newest-file guessing, canonical containment, and
accurate documentation. Nine focused transcript cases include the known-Pi
pane guard against an `agy` word in its title.

The lead and reviewer each completed `make check-bridge`: 104 normal tests
(41 unit, 41 client, 6 lifecycle, 9 resource, 7 schema), all four mandatory
ignored resource cases, formatting and the release example build passed.
The independent resource run took 349.30 seconds. Both completed `make
md-check` with exit 0. This evidence-only record changes no runtime inputs;
Markdown is checked again before its commit.

Earlier reviewer attempts lost an exit result or timed out. They are not
successful gate evidence; the complete independent rerun supersedes them.
The outer Herdr wait also timed out before the peer's final response; the
lead retrieved the completed report and actual exit-bearing logs afterward.
The peer reconciled its interrupted attempt's stale socket/temp root and
left pre-session, non-owned processes untouched. No owner bridge or agent
was restarted, and no Tailscale configuration changed.

Limits: this proves resolver/source and hermetic bridge behavior, not
canonical live Pi/active-branch parity, a runtime deployment, physical-device
connectivity or global issue #12 acceptance. Missing authoritative Pi
identity still fails closed. Android presentation acceptance is separate.

## Android conversation reader — conversation-polish checkpoint

Independent local acceptance passed 2026-10-08 on `fix/conversation-polish`,
base `c3113bc6d05a2c404ce7aebda51fc348586ad4e7` plus 14 working files:
13 Android source/build/test files and README Android notes. Tracked binary
patch SHA-256 was
`fcc2a0ba3d7919628608590fdcef9546682cfb063d9449f1e4d518b18f3f2c77`.
All six untracked feature/test files were fingerprinted before and after gates:

| Untracked file (under Android app source) | SHA-256 |
| --- | --- |
| `androidTest/java/com/azusachino/cappuccino/ConversationPresentationTest.kt` | `dbe5299d8480fd57b8004b64f6c8920c6586cde3295f714a65b43451cac3c7eb` |
| `main/java/com/azusachino/cappuccino/ui/ConversationDetails.kt` | `b71e55bde101599415e34947c7f1da867f0111a75372d16b956827a8393eeaa5` |
| `main/java/com/azusachino/cappuccino/ui/ConversationTimestamp.kt` | `7f1beb8fc3183ab1e932bf12e64dc07ca691f2dd62351f9a633262ad692ed2c9` |
| `main/java/com/azusachino/cappuccino/ui/MarkdownText.kt` | `60306dea576d37d293f7bfeb8ad1f294b7ae9d1e3c7cf983f3ced68e2f443345` |
| `test/java/com/azusachino/cappuccino/ui/ConversationTimestampTest.kt` | `e4f707201afefb61339b299fd48c9d0ede85b16973598096ac1c7e2afaec0403` |
| `test/java/com/azusachino/cappuccino/ui/MarkdownTextTest.kt` | `a8bffb6d0529865c400d81165fdd96ffd86c290c732dbd7e13c77e32553a55e9` |

The lead initially implemented the slice, then the owner directed TL-only
coordination. Worker `cap-polish-android-worker` completed the compilation
repair and owning gates, inspected feature PNGs and released writer ownership.
Separate fresh verifier `cap-polish-android-verifier` used the owner-selected
`zai-coding-cn/glm-5.3-flash` at low effort; startup argv and visible runtime
confirmed that route. Worker evidence was not substituted for its review.

All six criteria passed: native passive CommonMark presentation, accessible
collapsed tool/thought details, device-local `yyyy-MM-dd HH:mm:ss` timestamps
and foreground timezone changes, true-bottom following with manual reading
and explicit jump, profile/machine/session state isolation, and owned,
coalesced, stale-safe conversation refresh. The narrow-screen metadata banner
was changed to a vertical stack after screenshot review caught crowding.

Independent commands all exited 0: `make validate-android`, forced
`:app:testDebugUnitTest --rerun-tasks` (63 tests), explicit-serial
`make ui-test-android` (13 tests across six classes), `make md-check` and
`git diff --check`. No failures, errors or skips; no diagnostics or assertions
were weakened. Five actual PNGs were decoded: collapsed/expanded details,
Markdown, latest tall conversation and device timezone change. The OS-zone
journey used only the task-owned API-35 AVD and restored its original setting.
Debug APK SHA-256 after independent gates:
`c1cdd68d5b51444be9ffc6652537e4d21c63661d677ae6560502a4cb674a81d4`.

Failures retained: earlier Markdown expectation and stream-open fixture errors,
and the missing `TextOverflow` import after the banner edit. They were fixed
and required gates rerun. The verifier initially double-counted parent/child
JUnit totals as 26; direct XML reconciliation establishes 13, not 26. It also
corrected its inspected-PNG count to five and completed post-gate untracked
hash comparison; source/index inputs were unchanged. This record is docs-only,
so matching runtime evidence remains valid; Markdown is rerun before commit.

Limits: synthetic UI/controller fixtures on API 35 only, not live canonical
Pi/active-branch parity, physical devices, minimum-OS coverage or deployment.
Owner phone, Pixel emulator, running bridge/agents and Tailscale were unchanged.
Hosted CI and global issue #12 acceptance remain separate.

## Native Material 3 conversation slice checkpoint

Accepted 2026-10-08 for the [native Material 3 conversation slice](design/android-material3.md)
([issue #12](https://github.com/azusachino/cappuccino/issues/12) scope only) on branch
`feat/android-material3-redesign`, base `36f3afe` (merged PR #28), verified content
committed unchanged as `cc898ef`. The owner explicitly approved the 7-capture
preview set of real screenshots — **the conversation slice's app appearance only**;
no broader redesign, merge, deployment or live/physical acceptance is granted.

Slice delivered: stock M3 color schemes replacing the hand-mixed palette, tonal
cardless reader rows and disclosures, native app bar / navigation / composer
components, `primaryContainer` user bubble, and the follow-latest extended FAB —
with all pre-existing behavior preserved. An independent review found two real
deviations (jump FAB rendered at the top of the column instead of bottom-end above
the composer; dark in-app theme drew dark status icons on the near-black surface
under a light system). Both were repaired surgically (in-flow FAB placement with
bounds-based geometry assertions; theme→system-bar appearance sync through the
production composition, `enableEdgeToEdge()` still the single enabler) with no
assertion weakened.

A fresh independent reviewer (GLM flash, low reasoning, no implementation context)
rechecked the frozen tree: tracked working diff SHA-256
`d930c1f97b4e96da9e0094fb63f2b96528098367d2c46ff56ed2b1d1631cb7f8` at start, end and
after gates, with a whole-tree 12-path manifest (7 tracked-modified + 5 untracked)
identical before and after. Note: the working-patch hash covers tracked files only;
the untracked files of the reviewed state are pinned by the manifest itself, and the
state was committed unchanged as `cc898ef` afterwards. All gates exit 0 at the exact
final source: `make validate-android`, forced `:app:testDebugUnitTest --rerun-tasks`
(63 tests), full `make ui-test-android` (21 tests) — zero failures, errors, skips —
plus `make md-check` and `git diff --check`. Both findings verified FIXED on source,
tests and the reviewer's own runtime captures. A low-severity bookkeeping defect in
the implementation writer's scratch after-manifest (10 of 12 paths listed) was
corrected against the stable reviewed record; the source was never affected.

Accepted evidence: 7 synthetic full-frame captures (1080 × 2340, byte-identical
originals) in [`evidence/android-material3/`](evidence/android-material3/README.md),
covering 393/320 dp light/dark, collapsed and expanded disclosures, markdown +
user bubble, bottom-end FAB, a genuine visible-Gboard IME frame at 320 dp, and the
production Settings status path (incidental shared-palette evidence only, not a
Settings redesign). Privacy: synthetic fixtures throughout; no owner sessions,
tokens or private addresses.

Limits: emulator fixtures on API 35 only — no live Pi/active-branch parity, no
physical device, no deployment, no merge; some compact IME frames show insets
asserted without a visible keyboard and are excluded (the included 320 dp frame is
the genuine IME evidence); static screenshots do not replace dynamic tests; other
screens changed color only via the shared tokens, their layouts are not restyled.
No public PR exists at this checkpoint; broader rollout remains an owner decision.

## CI freshness repair checkpoint — compose compiler 2.4.21 (locally verified; hosted re-run pending)

Hosted CI run 37756325936 on `feat/android-material3-redesign` (PR #29) failed
`:app:lintDebug` — this is the recorded historical trigger of this repair, not a
standing status; the branch's current hosted truth is PR #29's checks
(<https://github.com/azusachino/cappuccino/pull/29>/checks), which the next
delivery push must turn green.
(`NewerVersionAvailable`: org.jetbrains.kotlin.plugin.compose 2.4.20 → 2.4.21).
Repair: single catalog line `kotlin = "2.4.20"` → `"2.4.21"` in
`apps/android/gradle/libs.versions.toml` (feeds both `kotlin-gradle-plugin` and the
compose compiler plugin, which are version-locked). No other source, test, UI, gate
or workflow change. Version availability verified against official artifacts:
Gradle Plugin Portal marker POM
`org.jetbrains.kotlin.plugin.compose/org.jetbrains.kotlin.plugin.compose.gradle.plugin/2.4.21`
and Maven Central
`org.jetbrains.kotlin/compose-compiler-gradle-plugin/2.4.21` both resolve (HTTP 200).

Local gates at this checkpoint (working tree, uncommitted): pre-patch fresh
`:app:lintDebug --rerun-tasks --refresh-dependencies` exit 0 (cached-green, CI red
remains the authoritative failure signal); post-patch fresh lint exit 0, 29/29
executed; `make check-android` exit 0; `make validate-android` exit 0 (71 tasks);
forced `:app:testDebugUnitTest --rerun-tasks` exit 0, 63 tests / 0 failures /
0 errors / 0 skips; full `make ui-test-android` (`ANDROID_SERIAL=emulator-5560`)
exit 0, 21 tests / 0 failures / 0 errors / 0 skips on the new task-owned AVD
`cappuccino-material3-ci-api35` (API 35 google_apis arm64-v8a, serial
emulator-5560, launcher PID recorded in task scratch). Fresh runtime captures at
compose compiler 2.4.21 (12 PNGs, normal 448 dp + compact 320 dp density override,
restored and verified) show no material appearance change versus the accepted
7-image set; the accepted PNGs remain historical 2.4.20 evidence and are not
replaced. Capture-width reconciliation: the first compact attempt (density 540)
rendered 398 dp frames, not contract compact; a corrective density-672 run
produced the true-320 dp set (1344 px ÷ (672/160) = 320 dp). Both overrides were
reset and verified; only the 672 run is compact-contract evidence.

Fresh independent verification (GLM Flash, LOW reasoning, separate session,
read-only, source frozen) PASSED the local repair: independently reran fresh
lint (exit 0, 29/29), `make validate-android` (exit 0, 71 tasks), forced JVM
tests 63/0/0/0, full UI suite 21/0/0/0, plus a focused true-320 dp preview run
5/0/0/0 — and confirmed no material appearance change at 320 dp (bottom-end FAB,
genuine in-frame Gboard, identical tokens), so no owner re-approval is needed.
The 2.4.21 catalog line (`f06fd72b…`) and all 7 approved PNG assets were
whole-file SHA-256 stable before and after verification, unchanged from HEAD.

Status: the local repair is accepted at this checkpoint; hosted CI has not yet
re-run. The historical run 37756325936 remains FAILED as a record; subsequent
delivery (lead commit + push) must produce a green hosted run on PR #29 before
any DONE/merge claim — no hosted-green claim is made here.
