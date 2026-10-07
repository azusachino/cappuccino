# Native Android client plan

The owner now selected **Android-first connected iteration with debug APKs**, deferring additional iOS distribution work. The detailed current [read-only implementation plan](../tasks/plan.md) and [checkpoint checklist](../tasks/todo.md) supersede the earlier platform ordering and daemon/token prerequisites for that slice. It consumes the accepted Rust bridge, with no authentication by owner decision. Sending and approvals remain unavailable; the current stream is recent pane output, not proven canonical Pi history.

The sections below preserve the earlier architecture roadmap and broader, still-undelivered acceptance capabilities. They are not a grant to restore daemon pairing, implement delivery/approvals, or require iPhone delivery before this read-only slice. Keep the delivered Apple code and native gates. [Intent](intent.md) remains the shared product/safety authority; live task ownership belongs in Asobi.

## Architecture direction

Use Kotlin and Jetpack Compose for native Android presentation, with coroutines/Flow and lifecycle-aware state collection. Prefer one app module with pure logic, I/O and UI separated by packages; split modules only for a demonstrated build or testing boundary. Keep owner conventions: two-space indentation, Make/mise entry points, hermetic tests and small conventional commits. Use a Gradle wrapper for Android, not the removed Java/Spring sample.

Android and Apple attach to the same existing-agent integration. Share the proven behavior specification and synthetic conformance fixtures, not Swift code or a universal UI framework. Do not rewrite SwiftUI, introduce Kotlin Multiplatform, or create a shared generated SDK merely to accommodate Android. Represent machine/session/branch/request identities explicitly; the integration, not either phone's cached state, arbitrates approval decisions and grant lifetime.

The bridge spike must settle transport, pairing and reconciliation before Android implements them. Foundation networking is not an SSH implementation; neither is choosing Compose. Select a maintained Android transport library only for the proven transport, supported SDK range, license, security and testability. No transport, cryptography or protocol version is selected here.

## Before implementation

- Accept the disposable existing-session spike and one connected iPhone journey before connected Android implementation. The independently buildable disconnected scaffold does not remove these prerequisites.
- Scaffold minimum is API 26, compile/target API 37. Record the owner's actual target phone, OS and distribution needs before accepting device compatibility; these SDK choices are not physical-device evidence.
- The scaffold pins JDK 21/Gradle 9.8.0/AGP 9.4.1/Kotlin and Compose compiler 2.4.20, Compose BOM 2026.09.00 and Activity 1.13.0. AGP built-in Kotlin stays enabled; the explicit compiler upgrade follows its documented mechanism. Wrapper distribution and JAR checksums are checked against upstream. See [development](development.md) for upstream compatibility limits and actual gates.
- `apps/android/` is the native Gradle root; separate `apps/ios/`, `apps/macos/` and `packages/apple/` retain Apple's native builds. [README](../README.md) documents actual Make entry points.

## Ordered acceptance slices

Each slice keeps unsupported actions visibly unavailable. Use synthetic fixtures and task-owned integration sessions only, never an owner's working agent or credentials. Prerequisite gates must pass before dependent emulator/device work.

### 1. Disconnected shell

- Chats, Attention and Machines show honest disconnected states; Send and approval actions cannot operate, while Nudge/Follow-up choices are visible.
- Pure Kotlin tests cover machine-scoped session identity and delivery choices without network or signing credentials.
- A pinned-wrapper build, Android lint and Compose instrumentation smoke pass; smoke visits all three destinations and checks disabled actions. Save synthetic screenshots/results as scoped evidence.

### 2. Pair one machine and list existing agents

- Manual private-LAN/tailnet pairing authenticates the integration; reachability alone is not authorization. Unpaired/untrusted connections fail visibly without weakening host verification.
- Credentials use an assessed platform-secure storage design, considering Android Keystore-backed keys, backup/export exclusions and revocation. Keystore hardware support is device-dependent; it is not a general secret-value database or an authorization substitute.
- The list identifies existing agents by machine/session; selecting, detaching or closing the app never starts, replaces or terminates their processes. Verify this against recorded task-owned process/session identities.

### 3. Active-branch conversation

- Show the selected session's active-branch history with code and expandable tool details; do not concatenate abandoned branches or commit partial streamed entries as durable history.
- Reconcile initial history and live updates with the integration's proven ordering/gap recovery; test duplicate, missing and out-of-order fixtures and replacement-session identities.
- The Compose journey opens a selected agent, renders long synthetic history and tool details, and returns to the list without introducing a file browser or terminal emulator.

### 4. Explicit delivery

- Nudge and Follow-up retain their distinct boundary/completion semantics and target the same existing process/session.
- Delivery receipts correlate each action; an ambiguous disconnect is shown as unresolved, not blindly retried. Test duplicate prevention and wrong/stale session targets.
- A task-owned runtime journey sends one of each, proves the intended delivery and preserves process/session/branch continuity; stale composer state cannot target a replacement occupant.

### 5. Typed approvals and exact grants

- Shell, writes and unknown tools require the shared fail-closed gate. Pending or denied requests never execute; offline phones never cause default approval.
- Local terminal and app decisions compete for the same request: first valid decision wins; losing, stale or conflicting actions fail visibly. If both clients can connect concurrently, test iPhone/Android contention too.
- Grants match exact tool, arguments and cwd in one session. Ordinary reconnect cannot broaden them; manual revocation, integration reload, branch change and session replacement invalidate them. Verify these cases in hermetic tests and the task-owned runtime journey.

### 6. Lifecycle and device acceptance

- Exercise rotation/recreation, background/foreground, Android process death and network/tailnet loss. The remote agent survives, and foreground recovery reconciles history, pending requests and receipts without duplicate sends or decisions. A dead app is never a grant store or approval authority.
- Prove TalkBack, font scaling, back navigation, keyboard/composer behavior and long-history responsiveness. Install a scoped prototype on the owner's actual target phone and record its build/OS identity; emulator screenshots alone do not accept hardware behavior.
- Keep generic Telegram alerts initially, without content previews or Telegram approval controls. FCM, continuous background sockets, foreground services and periodic WorkManager polling are not adopted to imitate an always-running phone client. Background scheduling is a separate measured need, not a guarantee of realtime attention.

## Build, CI and distribution boundary

Android Make entry points wrap Gradle format/static checks, hermetic unit tests, APK assembly and Compose instrumentation. Tools are pinned through the wrapper/version catalog; root mise supplies Apple/Markdown tooling. Use current released action tags, least-privilege job permissions, task-owned emulators and bounded synthetic test artifacts, matching the owner's CI preference. Preserve Apple gates; do not substitute Android checks for them. Treat project Kotlin compiler warnings as errors; do not silence compiler/lint diagnostics or skip critical-path tests to obtain green CI. Emulator acceleration/runner support and exact versions must be validated in the eventual hosted job.

Debug APK assembly can use disposable development signing; release keys, production pairing data and real transcripts never enter source, CI logs or test artifacts. Android signing is distinct from Apple's seven-day Personal Team provisioning, but installation/update/signing-key handling and device policy still require actual-phone acceptance. No store listing, production signing-key creation, device-setting changes, FCM setup or deployment is authorized by this document.

Before each implementation slice is accepted, run its actual logic/static/build gates and consuming UI/runtime journey, followed by fresh independent verification under workstation policy. Record exact source, fixture, toolchain and device identities. A plan review or passing shell test is not evidence of safe attachment.

## Open decisions

Actual target phone/OS acceptance; prototype installation/signing-key lifecycle; credential-storage details; and bridge transport/authentication/wire/reconciliation details remain unresolved. The scaffold's SDK/toolchain/image choices are explicit, but do not prove physical-device or connected behavior. These block their owning implementation slices, not this planning checkpoint. Scope excludes agent orchestration, terminal emulation, public ingress/cloud relay and automatic discovery, as on iPhone.

## Primary sources

Inspected 2026-10-05 for architecture direction, not as a dependency lockfile:

- [Jetpack Compose](https://developer.android.com/compose): native Android UI toolkit.
- [Compose state](https://developer.android.com/develop/ui/compose/state): lifecycle-aware Flow collection with `collectAsStateWithLifecycle`.
- [Compose testing](https://developer.android.com/develop/ui/compose/testing): UI semantics and instrumentation testing.
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore): key isolation and device-dependent hardware protection.
- [Background task overview](https://developer.android.com/develop/background-work/background-tasks): lifecycle and scheduling restrictions; no promised permanent foreground connection after app backgrounding.
