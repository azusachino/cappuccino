# Native development and quality

## Platform and framework choices

The owner selected macOS 26 as the Mac minimum; iOS remains 17+. SwiftPM uses tools 6.2 and Xcode 26+ supplies the required toolchain. Share pure models and reusable views, not every navigation or lifecycle decision.

Use SwiftUI and standard platform APIs first. Primitive view state stays in `@State`; introduce Observation models when the connected flow actually needs shared state. Keep UI state main-actor isolated; use structured concurrency and actor isolation for genuinely shared mutable connection state. An actor does not itself provide server-side approval atomicity or durable receipts.

No TCA, dependency container, persistence framework or transport dependency is adopted by the skeleton. Evaluate a maintained library only against a demonstrated need, its supported platforms, maintenance/license, concurrency behavior and testability. Native Foundation networking applies if HTTP/WebSocket is selected; it is not an SSH client. Do not invent cryptography or rely on tailnet membership as authorization.

## SSH transport design sketch (slice-B follow-through)

Per the 2026-10-06 pivot, the next transport is `SSHClient: DaemonServing` beneath the same seam the daemon client uses today: one reused authenticated SSH connection with an exec channel per call, driving only fixed herdr subcommands with validated arguments (never shell strings from the UI). Herdr 0.9.3 emits JSON envelopes by default for `agent list`/`agent get` (no `--json` — that exits 2) and plain text for `pane read`, so the client parses envelopes as-is and never passes `--json`. Ed25519 private keys live in the Keychain, host keys are pinned per machine, and foreground refresh polls adaptively at 1–2s. Errors map to the existing typed triple — unreachable (connect/timeout/nonzero exit), unauthorized (auth failure), protocol (malformed or truncated output) — and a dropped connection reconnects lazily on the next call, never retrying silently. Identity stays fail-closed: the installed CLI exposes no stable Pi session UUID (`agent_session` is absent), so attach and approvals require that UUID before they can proceed, and name/pane locators remain display-only.

## Android scaffold and connected plan

The owner chose connected iPhone first, native Android next, then Mac-specific UX, and separately approved buildable disconnected skeletons now. `apps/android/` uses Kotlin/Compose in one native Gradle app module, with pure identity/delivery types separate from activity I/O and UI. Saved tab/delivery selection survives activity recreation; this is not process-death/network reconciliation or a grant store. No lifecycle Flow, transport or ViewModel abstraction is needed for empty states. [Android planning](android-plan.md) retains connected prerequisites and later lifecycle-aware state collection.

Use JDK 21, Gradle 9.8.0, AGP 9.4.1, matching Kotlin/Compose compiler 2.4.20, Compose BOM 2026.09.00 and Activity 1.13.0. The catalog and wrapper are authoritative; SDK minimum is 26, compile/target 37, with API 35 emulator acceptance separate from actual-phone acceptance. AGP built-in Kotlin remains enabled; its documented explicit KGP upgrade supplies the selected compiler. Ktfmt uses two-space Google style. Kotlin compiler and Android lint warnings are errors; freshness checks are not disabled.

Kotlin's published fully-tested compatibility table currently ends at Gradle 9.7.0/AGP 9.3.1 for KGP 2.4.20; it explicitly permits later releases with possible deprecations/features limitations. Therefore the selected current releases need actual consuming gates, not a claim that every pair is covered by that table. Attribute any upstream Gradle warning to its plugin; do not hide project/compiler/lint diagnostics. No Flutter, React Native, Kotlin Multiplatform or generated SDK is adopted, and no wire protocol is frozen.

## Mac preparation

The separate Mac target shares the core and currently compiles the same empty shell. A native Mac slice still needs sidebar/detail presentation, keyboard/menu commands, text selection, appropriate window state and actual Mac UI tests. Do not treat a successful build as desktop interaction acceptance or force the phone's tab layout onto the finished Mac product.

## CI and dependencies

The owner selected released GitHub Action version tags rather than commit SHAs. `.github/workflows/ci.yml` owns the exact runner/Xcode/runtime selections; do not silently fall back when a configured environment disappears. Weekly Dependabot PRs review action major upgrades; no automatic merge or repository protection change is configured. Mise CLI/tool versions are separately selected and need normal compatibility review.

CI runs on a macOS 26 host so the macOS 26 core tests can execute. It selects stable Xcode 26.6 through `DEVELOPER_DIR`, runs `make validate-apple`, creates one iPhone 17 Pro/iOS 26.5 Simulator, runs the disconnected XCTest journey, and deletes only that created device. PRs and pushes to main are checked without duplicate feature-push jobs. Compiler warnings are errors for SwiftPM tests and generated app/test targets; Swift 6 language mode provides data-race checks. Do not add suppression annotations or relax gates to accommodate a failure.

The Android job uses Ubuntu 24.04/JDK 21, `make validate-android`, and an explicitly named API 35 x86_64 Google APIs emulator. KVM permissions are configured only inside that disposable hosted runner, never on an owner's host. Emulator-runner owns emulator shutdown. The UI journey visits all three empty states, changes delivery selection, verifies disabled messaging and activity recreation, and exports three synthetic screenshots. Capture uses AndroidX platform test storage so Gradle collects images before uninstalling the app; Make rejects missing, ambiguous or non-PNG exports instead of trusting `adb exec-out` exit status. Hosted execution, not the presence of this workflow, establishes runner compatibility.

The job token has read-only contents permission. Checkout does not persist credentials, and mise-action does not export its token to subsequent commands. XCTest bundles and Android instrumentation reports/screenshots are uploaded from narrow synthetic-evidence paths for seven days on success or failure. No owner session, real transcript, pairing token or signing credential is an acceptable fixture or artifact.

## Behavior and security acceptance

Formatting and compilation are prerequisites, not proofs of safe remote control. The attachment slice must test active-branch history, cancellation, ordered reconciliation, ambiguous delivery/duplicate prevention, stale actions, local/remote approval races, pending/denied non-execution and exact-grant invalidation. Keep these checks hermetic where possible, followed by an explicitly task-owned runtime journey.

Before calling the connected UI usable, verify VoiceOver, Dynamic Type, keyboard interaction, reduced motion and long transcript/tool-detail performance. Add Mac runtime evidence with Mac-specific behavior. Do not invent a coverage percentage before those meaningful behaviors exist; do not skip critical-path tests to obtain a number.

## Sources

- [AGP built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin), [Kotlin Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html), and [Compose compiler](https://developer.android.com/develop/ui/compose/compiler).
- [AndroidX platform test storage](https://developer.android.com/reference/androidx/test/platform/io/PlatformTestStorageRegistry): AGP 8+ default output collection; app-private screenshots cannot be pulled after test cleanup uninstalls the app.
- [Android backup/transfer rules](https://developer.android.com/about/versions/12/backup-restore#xml-changes): modern device transfer needs explicit exclusions; `allowBackup=false` alone is insufficient across devices.
- [Gradle wrapper checksums](https://gradle.org/release-checksums/) and [Android emulator runner](https://github.com/ReactiveCircus/android-emulator-runner/tree/v2.38.0).
- [Apple SwiftUI model data](https://developer.apple.com/documentation/swiftui/managing-model-data-in-your-app) and [Mac app tutorial](https://developer.apple.com/tutorials/swiftui/creating-a-macos-app).
- [Swift 6 data-race safety](https://www.swift.org/migration/documentation/swift-6-concurrency-migration-guide/dataracesafety/).
- [Hosted macOS 26 image](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-Readme.md): runner selections must be checked against the actual job, not inferred from this moving document.
- [mise-action v5.1.1](https://github.com/jdx/mise-action/releases/tag/v5.1.1): token persistence is opt-in; retain the safer default.
