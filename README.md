# Cappuccino

A personal native companion for conversations and attention from agents already running on your Herdr machines. The connected-feature order is bridge feasibility, connected iPhone, Android, then Mac-specific UX.

**Current state:** native clients and hermetic core tests, plus iPhone machine/agent and transcript UI slices speaking HTTP/WebSocket to the Herdr plugin bridge (`services/bridge`) or the frozen reference daemon (`services/daemon`) through `DaemonServing`. The bridge is a standalone Rust phone-transport facade launched by Herdr's plugin commands; it is not loaded into Herdr and does not own agent runtime. It reads selected Herdr APIs over the local socket. It ships **no auth** by owner decision: the tailnet/loopback boundary is the security model, the binary refuses non-loopback binds, and `tailscale serve` is the supported exposure. Prompt delivery and tool approval are not implemented, and canonical live Pi transcript/stream behavior remains unverified. There is no agent launcher or terminal emulator.

Read [intent](docs/intent.md), [plan](docs/plan.md), [architecture](docs/architecture.md), [Android plan](docs/android-plan.md), [user stories](docs/user-stories.md) and [source research](docs/discovery.md). Existing remote agents keep their process/session lifetime; future clients attach and detach.

## Getting started (MVP)

**Phone (S7).** Build and install from this repo with Xcode: `make setup`,
open the generated `Cappuccino.xcodeproj`, select the `Cappuccino` iOS scheme
with your device as destination, and run (free provisioning; re-sign weekly).
No store, no TestFlight in the MVP.

**Add a machine and browse agents.** On each Herdr machine, install the bridge
binary before linking the plugin. From this repository's root, run
`cargo install --path services/bridge --locked`; ensure Cargo's install `bin`
directory is on Herdr's `PATH`, then run
`herdr plugin link <path-to-services/bridge>` and verify with
`herdr plugin action invoke status --plugin azusachino.cappuccino-bridge`. The
plugin does not build or install the binary. Follow the
[bridge setup guide](services/bridge/README.md) to expose its loopback listener
over a private tailnet using `tailscale serve`. The bridge has no authentication:
do not expose it to public ingress. In the app, open Machines and add the
machine's base URL (`http://127.0.0.1:7392` locally or your private tailnet
HTTPS address). Agent listing and read-only transcript streaming are available;
prompt delivery and approvals are not implemented.

Selecting an agent never starts, stops or replaces it. Prompt delivery and
approvals are not implemented. The issue #7 transcript UI slice is present,
but canonical live Pi transcript/stream behavior has not been verified.

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

The Android conversation reader renders native CommonMark prose/code, keeps tool
and thought details collapsed until expanded, and formats timestamps in the
device timezone as `yyyy-MM-dd HH:mm:ss`. It follows the newest displayed
content unless you scroll back; incoming content then offers an explicit jump
to latest. Canonical Pi identity still requires an authoritative Herdr session
report; see [Pi identity](services/bridge/README.md#pi-transcript-identity).

Create a new, uniquely named AVD from an already available image; do not reuse
or overwrite an owner's AVD. Local evidence uses an API 35 ARM emulator; CI
selects API 35 x86_64 on its disposable Linux runner. Instrumentation covers
navigation/recreation, machine and foreground recovery, connection failures,
conversation scrolling, disclosures, Markdown and live timezone changes.
Reports and additional synthetic PNGs remain under `apps/android/app/build/`;
the three baseline screenshots export to `.build/android/screens/`.

Release keys and physical-phone installation are separate work. Debug APK
assembly uses development signing only. Android declares network access for
bridge connections; legacy and modern backup/transfer rules exclude app data.
Those rules are not a credential-storage implementation.

## Gates

```sh
make fmt
make validate
```

These aggregate commands require both toolchains. Use `fmt-apple`/`check-apple`/`validate-apple` or their Android counterparts for platform-focused iteration. `make md-check` checks all Markdown. Formatting, core tests, lint and compilation are prerequisites; both platform UI journeys are explicit commands, not implicit acceptance of remote behavior.

CI defines separate Apple and Android jobs using released action tags and read-only contents permissions. It preserves narrow synthetic UI evidence for seven days and uses only task-owned simulators/emulators. Delivered-head hosted runs succeeded for both platforms (run 37325757158); the hosted Android capture shows an unrelated system launcher ANR dialog from emulator load, recorded in [verification](docs/verification.md#delivered-head-hosted-checkpoint). See [development and quality](docs/development.md) for toolchain details and [verification](docs/verification.md) for actual accepted runs and limits.

## Limitations

The bridge currently supports machine reachability, agent listing and read-only
transcript/stream routes. Prompt delivery and approvals are not implemented.
The bridge is intentionally unauthenticated and must remain on loopback/private
tailnet ingress. See [verification](docs/verification.md) for tested behavior
and known limits, and [the plan](docs/plan.md) for future work.

See [CONTRIBUTING](CONTRIBUTING.md) and [AGENTS](AGENTS.md) for style, gates and ownership. The existing [GPL-3.0 license](LICENSE) remains unchanged.
