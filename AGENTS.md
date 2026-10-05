# Cappuccino

Native Android/Apple companion for existing, long-running Herdr/Pi agents. Read [intent](docs/intent.md) before changing behavior and [plan](docs/plan.md) before the attachment spike. [Discovery](docs/discovery.md) contains source-backed research, not runtime guarantees.

## Work

- Current source is a disconnected app skeleton. Keep unavailable actions explicit; sample data never represents a real attachment.
- Herdr/Pi own agent lifetime. Attach/detach preserves the remote process and session. Setup reload is explicit and idle-only.
- Apple shared code and hermetic tests live in `packages/apple/`; iPhone entry/UI tests in `apps/ios/`, Mac entry/platform UI in `apps/macos/`. The core target stays independent of the shared SwiftUI target.
- Android is one Gradle app module at `apps/android/`; keep pure Kotlin logic in `core`, platform I/O in the activity/adapters, and Compose presentation in `ui`. No cross-platform runtime or generated SDK is adopted.
- Use Swift 6/Kotlin, two-space indentation and platform `make fmt-*` targets. Comments explain non-obvious constraints; names explain behavior. Prefer standard SwiftUI/Compose APIs and concrete types to speculative services or factories.
- Edit `project.yml`, not generated `Cappuccino.xcodeproj`. Xcode supplies Swift; `.mise.toml` pins the tools Make invokes.

## Verify

Run the focused `make check-apple` or `make check-android` during iteration. Before acceptance, run the changed platform's `validate-*` and consuming UI journey: `make ui-test DESTINATION="platform=iOS Simulator,id=<task-owned simulator>"` or `ANDROID_SERIAL=<task-owned emulator> make ui-test-android`. `make validate` aggregates both toolchains. Preserve both platform gates for shared/build changes. README covers scoped toolchain selection and task-owned devices. Compiler/lint warnings are errors; no diagnostic suppression, lint baseline or skipped critical tests to obtain green gates. Structural/package tests do not prove attachment, approvals or notifications.

Tests use no owner sessions, network credentials or real approvals. A live spike uses explicitly task-owned processes and scoped integration files; preserve its source/runtime identity evidence. Approval/transport changes need fail-closed, stale-action and reconnect tests.

## Records and Git

Live tasks/claims/handoffs live in Asobi; accepted requirements and evidence stay in the owning docs/issue/PR. Follow the workstation's tier and fresh-verification rules when working in its checkout.

Use focused feature branches and conventional commits. Run owning gates before committing. Keep signing credentials, pairing tokens, Telegram secrets, real transcripts and private machine addresses out of source and review artifacts. Preserve the existing license. Deployment and changes to an owner's long-running agents require separate approval.
