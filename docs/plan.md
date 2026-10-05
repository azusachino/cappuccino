# Native skeleton and attachment feasibility

[Intent](intent.md) is confirmed. The owner selected existing-session bridge feasibility → connected iPhone → native Android → Mac-specific UX. This plan separates those slices from the delivered Apple skeleton. Live execution belongs in Asobi, not a duplicated Markdown task board; Android implementation is not part of this planning change.

## 1. Repository skeleton

Replace the stale Java/Gradle sample, retaining Git history and the existing GPL-3.0 license. Use Swift 6, a dependency-free SwiftPM core and shared SwiftUI shell. XcodeGen's pinned declarative project generates iOS and macOS app targets; generated projects/build output stay ignored. Make owns local/CI gates, mise pins non-Xcode tools, swift-format owns Swift style, and rumdl owns Markdown.

Acceptance:

- Source is split only into core, app and tests; no speculative bridge/service abstraction or fake networking.
- Chats, Attention and Machines have honest empty states. Sending is disabled until attachment exists; the Nudge/Follow-up distinction is visible.
- A session identifier is machine-scoped. Hermetic tests cover that identity separation and the message-delivery choices; no live sessions or credentials are read.
- `make check`, `make build-ios`, `make build-macos` and `make ui-test` pass. UI smoke launches the shell, checks its disconnected state/disabled send, and visits Attention and Machines.
- README, CONTRIBUTING and AGENTS name the actual commands and implementation limits. CI runs the same structural/test/build gates without credentials or deployment.

Deployment targets are iOS 17 and macOS 26; the owner raised the Mac minimum after the initial skeleton checkpoint. Xcode 26+ supplies the Swift 6.2 package toolchain and swift-format. These are build requirements, not a claim of hardware acceptance across every supported OS. [Development and quality](development.md) records the native-first framework choice, platform-specific Mac work and CI/behavior gates.

The owner conventions used here are Make/mise, two-space indentation, pure logic separate from I/O, hermetic checks, small conventional commits, and comments explaining non-obvious constraints rather than narrating code. There was no existing owned Swift project/style configuration in this checkout; use Apple's formatter rather than inventing a framework.

### Optional debugging instrument

The owner suggested vphone-cli; [source assessment](discovery.md#optional-debugging-vphone-cli) records its capabilities and host risks. Keep it read-only and optional. Do not add a dependency or replace the Simulator gate. A later guest experiment requires a separately approved host/security/storage plan, task-owned VM and iPhoneOS build/install path; patched-guest results do not establish ordinary device signing or remote-agent continuity.

## 2. Disposable-session attachment spike

Use a task-owned Herdr/Pi session, already running before any client attaches. Record machine, Herdr version, process identity, native session ID/path and active branch. Never probe an owner's working agent or install a global extension automatically. Read Pi's installed extension/session contracts before implementing this slice.

Prove, in order:

1. Install a narrowly scoped trusted integration for the disposable session and reload at a safe idle boundary. Process and native session identities remain unchanged; context/history is retained.
2. Read structured active-branch history and stream updates without concatenating abandoned branches or accepting partial trailing JSONL as an entry. Distinguish durable history from in-flight output.
3. Send one Nudge and one Follow-up to that same process through a typed integration. Delivery receipts identify the action; ambiguous transport failures do not cause blind retries.
4. Gate one operation with a specific request/session identity and exact arguments. Resolve locally and remotely, reject the losing/stale decision, and prove denied/pending requests do not execute.
5. Exercise an exact invocation grant, manual revocation and invalidation on reload/branch/session change. A reconnect cannot broaden permission.
6. Disconnect and reattach. The remote process survives; history/pending state recovers without duplicate sends or approvals. A replacement occupant cannot inherit old pending actions or grants.

Failure at any prerequisite stops dependent claims. Idle extension reload is supported by inspected Pi source, but a working authenticated companion is not proven yet. Decide the smallest transport and pairing mechanism only with evidence from this spike; do not publish a protocol or bind public ingress as part of scaffolding.

## 3. Connected iPhone slice

After the spike, connect one paired machine to the iPhone app and prove the actual journey: existing-agent list → active transcript → reply or approval → detach/reconnect. Broaden to multiple machines with machine-scoped identity and per-machine failure states. Keep unsupported actions visibly unavailable.

Telegram is a later, separately configured slice: generic attention only, no code/prompt payload, no remote approval buttons, and no credentials in source/tests. Long-term installation remains a separate decision from free on-device prototyping.

## 4. Native Android client

After the connected iPhone acceptance checkpoint, follow [the Android plan](android-plan.md): honest Kotlin/Compose shell → paired machine and existing-agent list → active-branch transcript → explicit delivery → typed approvals/grants → lifecycle and real-device acceptance. Reuse proven bridge behavior and synthetic compatibility fixtures, not the SwiftUI implementation. Transport/authentication and wire-format decisions still depend on section 2; no shared protocol is published by this plan.

## 5. Mac-specific UX

Keep the existing macOS 26 build target throughout earlier slices. After Android acceptance, develop the sidebar/detail, keyboard/menu, selection and window behavior described in [development](development.md#mac-preparation), with an actual Mac runtime journey. A shared shell build is not Mac UX acceptance.

## Verification and authority

Skeleton checks prove source/build/shell behavior, not remote-agent control. Physical iPhone installation, seven-day reprovisioning, background Telegram delivery, real approvals and live connectivity remain untested until their owning slices run.

Use fresh independent verification at a stable acceptance checkpoint under workstation tier-1 policy. Source/gates must be green before that review; startup failures keep verification open rather than substituting self-review. Repo setup does not authorize server changes, real-machine integration installation, pushes to the workstation's main branch, releases or deployment.
