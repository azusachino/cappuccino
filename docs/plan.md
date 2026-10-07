# Native skeleton and attachment feasibility

[Intent](intent.md) is confirmed. The owner selected existing-session bridge feasibility → connected iPhone → native Android → Mac-specific UX. These connected slices are separate from the owner-approved disconnected Android/iOS/macOS scaffold. Live execution belongs in Asobi; detailed connected acceptance and task decomposition belong in the owning parent/task issues after the framework discussion.

## 1. Repository skeleton

Replace the stale Java/Gradle sample, retaining Git history and the existing GPL-3.0 license. Use Swift 6, a dependency-free SwiftPM core and shared SwiftUI shell for Apple, and Kotlin/Compose in one native Android app module. XcodeGen's pinned declarative project generates iOS and macOS app targets; generated projects/build output stay ignored. Make owns local/CI gates, mise pins non-Xcode tools, swift-format owns Swift style, and rumdl owns Markdown.

Acceptance:

- Native entrypoints live in `apps/android`, `apps/ios` and `apps/macos`; `packages/apple` contains shared Apple core/UI/tests. Android keeps pure logic, UI and platform I/O separate within its app module. No speculative bridge/service abstraction or fake networking.
- Chats, Attention and Machines have honest empty states. Sending is disabled until attachment exists; the Nudge/Follow-up distinction is visible.
- A session identifier is machine-scoped. Hermetic tests cover that identity separation and the message-delivery choices; no live sessions or credentials are read.
- `make validate-apple` and `make validate-android` pass, as do explicitly device-selected `make ui-test` and `make ui-test-android`. UI smoke launches each phone shell, checks disconnected states/disabled messaging and explicit delivery choices, and visits Attention and Machines. Android activity recreation preserves selection.
- README, CONTRIBUTING and AGENTS name the actual commands and implementation limits. CI runs the same structural/test/build gates without credentials or deployment.

Deployment targets are iOS 17 and macOS 26; the owner raised the Mac minimum after the initial skeleton checkpoint. Xcode 26+ supplies the Swift 6.2 package toolchain and swift-format. These are build requirements, not a claim of hardware acceptance across every supported OS. [Development and quality](development.md) records the native-first framework choice, platform-specific Mac work and CI/behavior gates.

The owner conventions used here are Make/mise, two-space indentation, pure logic separate from I/O, hermetic checks, small conventional commits, and comments explaining non-obvious constraints rather than narrating code. There was no existing owned Swift project/style configuration in this checkout; use Apple's formatter rather than inventing a framework.

### Optional debugging instrument

The owner suggested vphone-cli; [source assessment](discovery.md#optional-debugging-vphone-cli) records its capabilities and host risks. Keep it read-only and optional. Do not add a dependency or replace the Simulator gate. A later guest experiment requires a separately approved host/security/storage plan, task-owned VM and iPhoneOS build/install path; patched-guest results do not establish ordinary device signing or remote-agent continuity.

## 2. Disposable-session attachment spike

Use a task-owned Herdr/Pi session, already running before any client attaches. Record machine, Herdr version, process identity, native session ID/path and active branch. Never probe an owner's working agent or install a global extension automatically. Read Pi's installed extension/session contracts before implementing this slice.

**(Historical, superseded — kept as the slice-A record.)** The owner selected the companion-daemon architecture on 2026-10-05: a small user-space Mac daemon binds to tailnet/localhost only; phones connect over HTTPS/WSS with a manual one-time pairing token. The daemon consumes the Herdr socket for discovery and streams, and receives a separate outbound WebSocket from a narrowly scoped Pi extension for typed tool gating and grants. There is no public ingress and no cloud relay. The [behavior spec](behavior-spec.md) and its fixtures are the cross-platform contract; the wire format stays provisional.

**Historical architecture pivot (2026-10-06, superseded by the plugin pivot below).** The owner superseded the per-machine resident daemon for the product path: phones reach machines over tailnet SSH and drive the existing `herdr` CLI directly (`agent list --json`, `pane read` polling; approvals later via the Pi extension plus a watched decision file). No per-machine daemon ships. `services/daemon` (merged from slice A, PR #14) is retained as a reference implementation, and its pairing and reconciliation logic migrates into the phone client as the SSH transport lands. Client logic above the transport is unaffected: the iOS client already consumes the daemon through a transport protocol, and the same interface is what the SSH-exec implementation replaces.

**Plugin pivot (2026-10-06, supersedes the SSH-exec direction).** The owner selected a Herdr plugin bridge as the product phone transport (issue #16): `services/bridge`, a Rust/Tokio/axum executable linked through Herdr's manifest. Herdr invokes its one-shot startup hook and actions; the executable manages a separate auth-free loopback HTTP/WebSocket server. It is not loaded into Herdr's process and does not own agent runtime. It exposes selected Herdr socket data (agent catalog, validated Pi transcript paths, read-only pane-line stream) to native clients. The iOS client consumes it through the `DaemonServing` seam (`BridgeClient` over URLSession); `services/daemon` stays frozen reference work. Herdr's terminal `agent.prompt` API does not select Pi's `steer` versus `follow_up` queue and its PTY write acknowledgement is not a delivery receipt; issue #8 remains unimplemented pending reconciled owner acceptance. `herdr-web-ui` was studied as design prior art only; the bridge is our own implementation.

The original disposable attachment spike's A/B/C acceptance outline below is historical; its daemon/pairing premises were superseded by the plugin pivot. Current issue slices are:

- **#16 plugin bridge:** Herdr-linked standalone Rust phone-transport facade; read-only agent catalog, transcript lookup and pane-line stream.
- **#7 transcript UI:** shipped as a bounded scripted UI slice. Canonical live Pi transcript/WebSocket behavior remains unverified.
- **#8 delivery:** not implemented. Herdr terminal prompt writes do not select Pi Nudge/Follow-up queues or provide correlated receipts; reconcile acceptance before implementation.
- **#9 approvals:** planned after delivery; no Pi extension or approval gate is implemented.

No paired daemon or pairing-token design is current. Any future runtime acceptance uses a task-owned disposable Herdr/Pi session and does not claim more than the evidence proves.

## 3. Connected iPhone slice

The first private usable prototype is the **read-only journey**: pair one machine → list existing agents → open one and watch its active-branch history live, with code and expandable tool details. Delivery, approvals, grants and alerts are later slices (B/C above and section 4), each gated on the previous acceptance.

After the spike, connect one paired machine to the iPhone app and prove that journey end to end. Broaden to multiple machines with machine-scoped identity and per-machine failure states. Keep unsupported actions visibly unavailable.

Telegram is a later, separately configured slice: generic attention only, no code/prompt payload, no remote approval buttons, and no credentials in source/tests. Long-term installation remains a separate decision from free on-device prototyping.

## 4. Native Android client

The disconnected Kotlin/Compose shell can be prepared independently. After the connected iPhone acceptance checkpoint, follow [the Android plan](android-plan.md): paired machine and existing-agent list → active-branch transcript → explicit delivery → typed approvals/grants → lifecycle and real-device acceptance. Reuse proven bridge behavior and synthetic compatibility fixtures, not the SwiftUI implementation. Transport/authentication and wire-format decisions still depend on section 2; no shared protocol is published by this plan.

## 5. Mac-specific UX

Keep the existing macOS 26 build target throughout earlier slices. After Android acceptance, develop the sidebar/detail, keyboard/menu, selection and window behavior described in [development](development.md#mac-preparation), with an actual Mac runtime journey. A shared shell build is not Mac UX acceptance.

## Verification and authority

Skeleton checks prove source/build/shell behavior, not remote-agent control. Physical iPhone installation, seven-day reprovisioning, background Telegram delivery, real approvals and live connectivity remain untested until their owning slices run.

Use fresh independent verification at a stable acceptance checkpoint under workstation tier-1 policy. Source/gates must be green before that review; startup failures keep verification open rather than substituting self-review. Repo setup does not authorize server changes, real-machine integration installation, pushes to the workstation's main branch, releases or deployment.
