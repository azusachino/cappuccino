# Cappuccino

A personal native companion for conversations and attention from agents already running on your Herdr machines. The connected-feature order is bridge feasibility, connected iPhone, Android, then Mac-specific UX.

**Current state:** disconnected native shells and hermetic core tests, plus an iPhone Machines pairing/agent-list flow against the local reference daemon (TCP wire v0, `services/daemon/`) behind the client's `DaemonServing` transport seam. Per the 2026-10-06 architecture pivot, SSH-exec driving the existing `herdr` CLI is the product transport and is not implemented yet; the daemon is a reference transport only, so nothing here is production remote access. No prompt delivery, tool approval, live transcript or tailnet exposure is implemented; the pairing token stays in the device Keychain. There is no agent launcher or terminal emulator.

Read [intent](docs/intent.md), [plan](docs/plan.md), [Android plan](docs/android-plan.md) and [source research](docs/discovery.md). Existing remote agents keep their process/session lifetime; future clients attach and detach.

## Layout

```text
apps/android/    Native Gradle/Kotlin/Compose app and its tests
apps/ios/        iOS app entrypoint and XCTest UI journey
apps/macos/      macOS app entrypoint
packages/apple/  Local SwiftPM core, reusable SwiftUI and core tests
```

`packages/apple` shares Swift code between iOS and macOS only. Android keeps Kotlin logic and UI in its app module; it shares product behavior, not Swift code. Root Make commands wrap the native build systems. No monorepo framework or generated cross-platform SDK is needed.

## Apple development

Requires macOS 26+, Xcode 26+ with Swift 6.2+, and mise. No API keys, Herdr runtime or Apple Developer membership is needed for tests or unsigned Simulator builds.

```sh
make setup
make check-apple
make generate
open Cappuccino.xcodeproj
```

Choose `Cappuccino` for iPhone/iPad Simulator or `CappuccinoMac` for the shared Mac shell. Edit root `project.yml`, not the ignored generated project. iOS remains 17+; macOS is 26+.

If Command Line Tools are selected globally, use a scoped override:

```sh
env DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer make validate-apple
env DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer make ui-test DESTINATION="platform=iOS Simulator,id=<task-owned simulator-id>"
```

Discover available device types/runtimes with `xcrun simctl list devicetypes` and `xcrun simctl list runtimes`. Create a dedicated simulator with `xcrun simctl create <name> <type-id> <runtime-id>`, use its returned UUID, and shut down/delete only that task-created device after preserving evidence. UI results and synthetic screenshot attachments live in `.build/xcode/Logs/Test/`.

## Android development

Requires JDK 21 and an Android SDK containing API 37/build tools 36.0.0. Set `ANDROID_HOME` to that SDK; use `JAVA_HOME` only when selecting an existing JDK. The app minimum is API 26; compile/target is API 37. The checked-in, checksum-verified Gradle wrapper owns Gradle; `gradle/libs.versions.toml` owns Android/Kotlin/Compose versions. Open `apps/android/` in Android Studio.

```sh
make fmt-android
make validate-android
ANDROID_SERIAL=<task-owned emulator serial> make ui-test-android
```

Create a new, uniquely named AVD from an already available image; do not reuse or overwrite an owner's AVD. Local evidence uses an API 35 ARM emulator; CI selects API 35 x86_64 on its disposable Linux runner. Instrumentation visits Chats, Attention and Machines, checks disabled messaging and explicit delivery choices, and exercises activity recreation. Reports remain under `apps/android/app/build/`; three synthetic screenshots export to `.build/android/screens/`.

Release keys and physical-phone installation are separate work. Debug APK assembly uses development signing only. The skeleton declares no network permission; legacy and modern backup/transfer rules exclude app data. Those rules are not a credential-storage implementation.

## Gates

```sh
make fmt
make validate
```

These aggregate commands require both toolchains. Use `fmt-apple`/`check-apple`/`validate-apple` or their Android counterparts for platform-focused iteration. `make md-check` checks all Markdown. Formatting, core tests, lint and compilation are prerequisites; both platform UI journeys are explicit commands, not implicit acceptance of remote behavior.

CI defines separate Apple and Android jobs using released action tags and read-only contents permissions. It preserves narrow synthetic UI evidence for seven days and uses only task-owned simulators/emulators. Delivered-head hosted runs succeeded for both platforms (run 37325757158); the hosted Android capture shows an unrelated system launcher ANR dialog from emulator load, recorded in [verification](docs/verification.md#delivered-head-hosted-checkpoint). See [development and quality](docs/development.md) for toolchain details and [verification](docs/verification.md) for actual accepted runs and limits.

## Optional vphone debugging

[vphone-cli assessment](docs/discovery.md#optional-debugging-vphone-cli) covers the owner-suggested virtual iPhone tool. It is a read-only research reference, not a dependency or replacement for XCTest/Simulator. Running it needs a separately approved physical Mac host and security/storage setup, plus an iPhoneOS build. No VM was installed or started.

## Prototype constraints

Private LAN/tailnet machines, manual pairing, Pi-first integration, active-branch history and typed approvals are planned, not implemented. Generic Telegram alerts replace native APNs/FCM for now. Free Personal Team builds on a physical iPhone need periodic reprovisioning; long-term distribution and actual-device acceptance remain open.

See [CONTRIBUTING](CONTRIBUTING.md) and [AGENTS](AGENTS.md) for style, gates and ownership. The existing [GPL-3.0 license](LICENSE) remains unchanged.
