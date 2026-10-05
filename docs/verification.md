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
