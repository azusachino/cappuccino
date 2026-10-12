# iOS client: Android parity in Liquid Glass

Status: planned 2026-10-13, superseding the earlier parity plan. Branch `feat/ios-liquid-glass`, cut from `feat/pi-pocket-rework` (PR #34). Execution is delegated slice by slice; the lead reviews and pushes.

## Outcome

The iPhone app does what the Android app does today, in native SwiftUI with Liquid Glass on iOS 26 and later:

- Machines, Settings and connection details for several Herdr bridges at once.
- The conversation page: user prompts, Markdown replies, the activity timeline, response status, the live status line, implicit follow and the jump button.
- The composer: Send, Stop while working, a Commands button with New session, the model and effort chip, and the context/cache line.
- Prompt cards: tool approvals and screen choice menus, Claude's AskUserQuestion form one question at a time, and its review card.
- Owner actions report the bridge's reason on failure without marking the connection failed.

The behaviour, wording and bridge protocol match Android. Only the chrome is Apple's.

## Owner decisions this plan records

- **2026-10-13: no macOS app.** Herdr covers the desktop. The `CappuccinoMac` target and `apps/macos/` are removed; `docs/intent.md`, `AGENTS.md`, `README.md`, the Makefile and CI follow. The Swift package keeps `.macOS(.v26)` only so `swift test` can run the core tests on the Mac host.
- **Liquid Glass.** Native glass on iOS 26 and later. The deployment target stays iOS 17, from the earlier plan: every glass API sits behind `if #available(iOS 26, *)` with a material fallback.
- **Out of scope:**
  - Background notifications: iOS needs push (APNs), which the intent excludes.
  - Offline saved conversations, native code highlighting, and the five-palette chooser. The app ships Catppuccin Latte/Mocha with System/Light/Dark.
  - Any change to the bridge.

## Sources of truth

Port behaviour, not widgets. When Android and this plan disagree, Android wins; note the difference in the slice's commit.

| Concern | Android reference (`apps/android/app/src/main/java/com/azusachino/cappuccino/`) | Bridge contract |
| --- | --- | --- |
| Wire parsing: agents, stream events, prompt cards, questions, conversation parts | `core/BridgeProtocol.kt`, `io/BridgeDetails.kt` | `docs/bridge-v2-contract.md` |
| HTTP calls: verify, agents, conversation and stats, prompt, answers, control | `io/BridgeClient.kt` | §6.1, `services/bridge/src/routes.rs` |
| State: profiles, per-machine jobs, selection, polling, stream, actions, `actionError` | `io/ConnectedViewModel.kt`, `io/ProfileStore.kt` | |
| Activity timeline: segments, summary, step words, outcomes, durations | `core/TranscriptActivity.kt`, `core/ToolActivityState.kt` | |
| Recent history bounds and follow content | `core/RecentHistory.kt` | |
| Agent identity and icons | `core/AgentIdentity.kt` | |
| Conversation page, composer, header, machines tree | `ui/CappuccinoShell.kt` | |
| Transcript rows: prompt, activity group/step, live status, jump | `ui/TranscriptRows.kt`, `ui/MessageBody.kt`, `ui/ResponseStatusRow.kt` | |
| Question form, prompt card | `ui/QuestionForm.kt`, `PromptCardView` in `ui/CappuccinoShell.kt` | §6.1 |
| Session status: model, effort, context, cache | `SessionStatusLine`, `shortModel`, `formatTokens` in `ui/CappuccinoShell.kt` | `stats` in the conversation response |
| Settings and appearance | `ui/SettingsScreen.kt`, `ui/AppAppearanceSettings.kt`, `ui/Theme.kt` | |

Android's unit and UI tests (`app/src/test`, `app/src/androidTest`) are the behaviour checklist: each ported rule gets the equivalent Swift test.

## What the Apple code has today

- **Core** (`packages/apple/Sources/CappuccinoCore`): `BridgeClient` pairs, verifies, lists agents, fetches the conversation as an untyped `[String: Any]`, submits prompts and answers screen prompts. `BridgeStream` parses stream events. `BridgeProfile`, `MachineConnections` and `AgentIdentity` are gated and stay.
- **Missing from the core:** typed conversation turns and parts, `stats`, `controlAgent`, `answerQuestions`, prompt-card `questions`, machine names and capabilities in UI state, and the error-envelope message.
- **Prototype leftovers** that the new UI must stop using: `DaemonClient`, `DaemonWire`, `DemoDaemonClient`, `ReferenceDaemonAdapter`, `TokenStore`, `MessageDelivery`, `TranscriptModel`/`TranscriptStreaming`. Delete each once nothing references it, together with its tests.
- **UI** (`CappuccinoUI`): `ContentView` (old tabs and demo clients), `MachinesView`, `TranscriptView`. These are replaced.

## Liquid Glass rules

Glass is for controls and navigation that float over content. Message text, the transcript and prompt content stay on opaque, readable surfaces.

- Navigation comes from the SDK: `TabView` (Machines, Settings), `NavigationStack` and toolbars get glass automatically. Add `.tabBarMinimizeBehavior(.onScrollDown)` behind the availability check.
- The composer is one floating glass panel at the bottom: `.safeAreaBar(edge: .bottom)` on iOS 26, `.safeAreaInset(edge: .bottom)` before. Inside a `GlassEffectContainer`:
  - the text field on `.glassEffect(.regular, in: .rect(cornerRadius: 22))`
  - Commands (⊞) with `.buttonStyle(.glass)`
  - Stop with `.buttonStyle(.glass)` tinted red
  - Send with `.buttonStyle(.glassProminent)`
  - the model and effort text, and under it the context/cache line, inside the same panel
- The jump-to-latest button is a circle with `.glassEffect(.regular.interactive(), in: .circle)`, at the bottom trailing edge above the composer, marked when there is new output.
- Prompt cards and the question form are sheets with `.presentationDetents([.medium, .large])`; iOS 26 gives sheets glass. The content inside stays opaque.
- Before iOS 26: `.regularMaterial` backgrounds and `.bordered`/`.borderedProminent` buttons, same layout.
- Accessibility: Dynamic Type, VoiceOver labels identical to Android's content descriptions ("Send", "Stop", "Commands", "Jump to latest", "Jump to new output", "Conversation actions"). Status is never shown by colour alone. Reduce Transparency and Reduce Motion are left to the system.
- Confirm every API in the installed SDK before using it: the glass APIs are in `SwiftUICore.framework`'s `arm64e-apple-ios.swiftinterface`; `safeAreaBar`, `tabViewBottomAccessory` and `tabBarMinimizeBehavior` are in `SwiftUI.framework`'s.

## Slices

Each slice is one commit (or a few) on `feat/ios-liquid-glass`. It ends green on its gates and leaves the app building.

0. **Retire macOS.**
   - Remove the `CappuccinoMac` target and scheme from `project.yml`, `apps/macos/`, `apps/macos` from `SWIFT_PATHS`, `build-macos` and its use in `validate-apple`, and the CI path filter and job name text.
   - Update `docs/intent.md` (record the 2026-10-13 decision), `AGENTS.md` and `README.md`.
   - Gates: `make check-apple`, `make build-ios`.
1. **Typed core.**
   - Swift ports of the Android wire models: `ConversationTurn`, `ConversationPart` (text, thinking, tool call with started/finished, response status), `ToolActivityState`, `PromptCard` with `questions`, `PromptQuestion`, `QuestionAnswer`, `SessionStats`, `BridgeDetails` (version, protocol, capabilities), `machine_name`.
   - Parsers fail closed exactly like `BridgeProtocol.kt`. Canonical conversation identity must start with the agent's provider.
   - Tests mirror `BridgeProtocolTest`, `BridgeDetailsTest` and `CanonicalConversationIdTest`.
2. **Bridge client calls.**
   - `conversationSnapshot` (typed, with `stats`), `controlAgent(session, action)`, `answerQuestions(session, promptID, answers)`. Errors use the bridge envelope's `message`.
   - Fix path-component encoding for locators like `w1:p3K`.
   - Tests mirror `BridgeClientTest`, with a `URLProtocol` stub.
3. **Activity logic.** Ports of `transcriptSegments`, `activitySummary`, `stepWords`, `stepOutcome`, `activityDuration`/`compactDuration`, `toolKind`, the bounded recent history and follow content, `shortModel`, `formatTokens`, `answerComplete`. Tests mirror `TranscriptActivityTest`, `RecentHistoryTest`, `SessionStatusTest` and `QuestionFormTest`.
4. **App state.**
   - An `@Observable` model mirroring `ConnectedViewModel`: profiles; per-machine connection jobs with epochs; selection that is bounded to its machine; conversation polling with session stats; the stream (status, pending prompt, entries); and actions.
   - Actions are send, `controlAgent`, `answerPrompt` and `answerQuestions`. They go through one helper that sets `actionError` and never touches the connection, and only when the machine's verified details advertise the capability.
   - Swift tests port `ConnectedViewModelTest`'s cases: a stale epoch, switching machines, and controls with a fresh client per request.
5. **Machines and Settings.**
   - `TabView` with Machines and Settings. Machines: a profile per machine, its connection state, agents under it (icon, label, status square and word, cwd · branch), Add (address, optional label), Refresh, Disconnect, and a menu with Connection details, Rename and Remove.
   - Settings: Theme (System/Light/Dark) with Catppuccin Latte/Mocha, Connections, About.
   - UI test: a fresh app shows "No machines configured"; Add machine shows "Bridge address" and "Connect".
6. **Conversation page.**
   - Header: agent icon and label, a status square, and the status word · machine · cwd folder · branch. The toolbar ⋮ opens Connection details.
   - Transcript:
     - your prompts as `› You HH:mm`, with the full local time as the accessibility label
     - assistant Markdown (`AttributedString(markdown:)`, headings at body size and bold), with "View full message" past the limit
     - the activity group: a collapsed summary with its duration, open in the newest turn, a step per part with glyph, label, subject and outcome, and an expandable body
     - the response status row
     - the live status line while working or blocked
     - "Older messages aren't loaded."
   - Follow: near the bottom (60 pt slack) means following; a drag stops it; the jump button returns. Tool-only updates never move the reader.
   - UI tests mirror `CappuccinoShellTest` and `ConversationActivityStyleTest`.
7. **Composer and status.**
   - The glass composer panel described above. Stop only while working. Commands only when `agent_control` is advertised; its New session asks "Start a new session?" and is disabled while working or blocked.
   - The model and effort chip, and the context meter: percent and `tokens / window` when the window is known, tokens alone otherwise, plus cache %.
   - UI test mirrors `stopWhileWorkingAndNewSessionOnlyWhenIdle`, including Stop and Send at the trailing edge.
8. **Prompts and questions.**
   - The pending prompt opens a sheet. A screen prompt shows numbered options and Cancel (`answerPrompt`). An AskUserQuestion card shows "Question k of N", options with descriptions, radio or checkbox rows, an Other line, Submit (enabled by `answerComplete`) and Cancel. A review card shows the chosen answers with Submit answers / Cancel.
   - `actionError` shows on the page in the error colour.
   - UI tests mirror `QuestionFormUiTest`.
9. **Remove prototype leftovers** listed above, and their tests and launch arguments. UI tests use one fixture switch (`-cappuccino-fixture`) that injects a fake bridge transport, as Android's tests inject fake actions.
10. **Acceptance.**
    - `make validate-apple`; `make ui-test` on a task-owned simulator.
    - A live read-only check against the Mac bridge from the simulator (`http://127.0.0.1:7392`, with an ATS exception for localhost in debug builds only): Machines lists the agents, the conversation and its status line load, and the stream shows working/idle.
    - Screenshots of each page in light and dark, stored under `docs/evidence/ios-liquid-glass/`.

## Gates and environment

- All Apple commands run with the README's scoped Xcode, without changing the global selection: `env DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer make <target>`.
- Every slice: `make check-apple` and `make build-ios`. Slice 10 adds `make validate-apple` and `make ui-test DESTINATION="platform=iOS Simulator,id=<id>"`.
- A task-owned simulator comes from `xcrun simctl create cappuccino-ios-task <device type from simctl list devicetypes> com.apple.CoreSimulator.SimRuntime.iOS-27-0`. Shut it down and delete it at the end; never boot or erase any other device.
- Edit `project.yml`, not the generated project. No new dependencies, no warning suppression, no skipped or weakened tests. Swift 6 strict concurrency, two-space indentation, `make fmt-apple` before each commit.
- Commit per slice with conventional messages ending in the session's attribution line. Never push, open PRs or write to GitHub.
- Live checks are read-only `GET`s and stream reads. Never send prompts, answers, Stop or New session to real agents, never touch harus-mini, and never install on a physical device.
- Stop and report instead of guessing:
  - a gate fails twice for an unclear reason
  - an API the plan names is missing from the SDK
  - Android's behaviour is ambiguous
  - a slice would need a bridge change

## Progress

Record each slice's commit and gate results here, one line per slice.
