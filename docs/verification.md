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
