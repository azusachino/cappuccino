# Native development and quality

## Platform and framework choices

The owner selected macOS 26 as the Mac minimum; iOS remains 17+. SwiftPM uses tools 6.2 and Xcode 26+ supplies the required toolchain. Share pure models and reusable views, not every navigation or lifecycle decision.

Use SwiftUI and standard platform APIs first. Primitive view state stays in `@State`; introduce Observation models when the connected flow actually needs shared state. Keep UI state main-actor isolated; use structured concurrency and actor isolation for genuinely shared mutable connection state. An actor does not itself provide server-side approval atomicity or durable receipts.

No TCA, dependency container, persistence framework or transport dependency is adopted by the skeleton. Evaluate a maintained library only against a demonstrated need, its supported platforms, maintenance/license, concurrency behavior and testability. Native Foundation networking applies if HTTP/WebSocket is selected; it is not an SSH client. Do not invent cryptography or rely on tailnet membership as authorization.

## Android planning

The owner chose connected iPhone first, native Android next, then Mac-specific UX. [Android planning](android-plan.md) proposes Kotlin/Jetpack Compose, lifecycle-aware state and small pure-logic/I/O/UI boundaries. It does not replace the Apple implementation with Flutter, React Native or Kotlin Multiplatform, add Android dependencies, select an SDK minimum or freeze an unproven transport contract. Android build/test targets are future work; the commands and CI below currently cover Apple only.

## Mac preparation

The separate Mac target shares the core and currently compiles the same empty shell. A native Mac slice still needs sidebar/detail presentation, keyboard/menu commands, text selection, appropriate window state and actual Mac UI tests. Do not treat a successful build as desktop interaction acceptance or force the phone's tab layout onto the finished Mac product.

## CI and dependencies

The owner selected released GitHub Action version tags rather than commit SHAs. `.github/workflows/ci.yml` owns the exact runner/Xcode/runtime selections; do not silently fall back when a configured environment disappears. Weekly Dependabot PRs review action major upgrades; no automatic merge or repository protection change is configured. Mise CLI/tool versions are separately selected and need normal compatibility review.

CI runs on a macOS 26 host so the macOS 26 core tests can execute. It selects stable Xcode 26.6 through `DEVELOPER_DIR`, runs `make validate`, creates one iPhone 17 Pro/iOS 26.5 Simulator, runs the disconnected XCTest journey, and deletes only that created device. PRs and pushes to main are checked without duplicate feature-push jobs. Compiler warnings are errors for SwiftPM tests and generated app/test targets; Swift 6 language mode provides data-race checks. Do not add suppression annotations or relax gates to accommodate a failure.

The job token has read-only contents permission. Checkout does not persist credentials, and mise-action does not export its token to subsequent commands. XCTest bundles, including synthetic shell screenshots, are uploaded from the narrow test-results path for seven days on success or failure. No owner session, real transcript, pairing token or signing credential is an acceptable fixture or artifact.

## Behavior and security acceptance

Formatting and compilation are prerequisites, not proofs of safe remote control. The attachment slice must test active-branch history, cancellation, ordered reconciliation, ambiguous delivery/duplicate prevention, stale actions, local/remote approval races, pending/denied non-execution and exact-grant invalidation. Keep these checks hermetic where possible, followed by an explicitly task-owned runtime journey.

Before calling the connected UI usable, verify VoiceOver, Dynamic Type, keyboard interaction, reduced motion and long transcript/tool-detail performance. Add Mac runtime evidence with Mac-specific behavior. Do not invent a coverage percentage before those meaningful behaviors exist; do not skip critical-path tests to obtain a number.

## Sources

- [Apple SwiftUI model data](https://developer.apple.com/documentation/swiftui/managing-model-data-in-your-app) and [Mac app tutorial](https://developer.apple.com/tutorials/swiftui/creating-a-macos-app).
- [Swift 6 data-race safety](https://www.swift.org/migration/documentation/swift-6-concurrency-migration-guide/dataracesafety/).
- [Hosted macOS 26 image](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-Readme.md): runner selections must be checked against the actual job, not inferred from this moving document.
- [mise-action v5.1.1](https://github.com/jdx/mise-action/releases/tag/v5.1.1): token persistence is opt-in; retain the safer default.
