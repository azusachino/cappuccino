# Development and quality

Contributor reference: layout, toolchains, make targets and gates, device
selection, and the design rules behind the native apps and the bridge. Style
and contribution rules are in [CONTRIBUTING.md](../CONTRIBUTING.md).

## Repository layout

```text
apps/android/    Native Gradle/Kotlin/Compose app module and its tests
apps/ios/        SwiftPM core/SwiftUI package and core tests; App/ entrypoint, UITests/ journey
services/bridge/ Rust Herdr plugin bridge
docs/            Intent, plan, architecture, behavior spec, design and research notes
```

`apps/ios` holds the Swift core, the SwiftUI shell and the iPhone entrypoint. The
Swift package keeps `.macOS(.v26)` only so `swift test` runs on the Mac host; there
is no macOS app. Android keeps pure Kotlin logic in `core`, platform I/O in the
activity and adapters, and Compose presentation in `ui`. The two platforms share
product behavior through fixtures, not code. No monorepo framework, cross-platform
runtime or generated SDK is used.

## Toolchains and pins

- **mise** (`.mise.toml`) pins the non-Xcode tools that Make invokes, such as
  `xcodegen`, `rumdl` and xtool. `make setup` installs them. xtool is pinned with
  `os = ["macos"]`, so the Linux CI job skips it. The Apple SDK and the JDK are
  prerequisites, not mise-managed.
- **Apple:** macOS 26 or later, Xcode 26 or later with Swift 6.2 or later, and
  swift-format through `xcrun`. The deployment target is iOS 17; glass APIs are
  used on iOS 26 and later only. Edit `project.yml`, not the generated, ignored
  `Cappuccino.xcodeproj`.
- **Android:** JDK 21 and an Android SDK with platform API 37 and build-tools
  36.0.0. Set `ANDROID_HOME` to that SDK. The app minimum is API 26 and the compile
  and target level is API 37. The checked-in, checksum-verified Gradle wrapper
  owns Gradle (9.8.0 at the time of writing), and `apps/android/gradle/libs.versions.toml`
  owns the Android Gradle Plugin, Kotlin, Compose and library versions. AGP's
  built-in Kotlin stays enabled. ktfmt uses two-space Google style.
- **Bridge:** Rust (Cargo) with the crate's own `Cargo.lock`. The bridge is built
  with `--locked`.
- **Markdown:** rumdl through mise. `make md-check` is the gate.

Kotlin's published compatibility table can lag the pinned Gradle and AGP pair. Use
the pinned versions and consuming gates as the evidence, not the table. Attribute
an upstream Gradle warning to its plugin. Do not hide project, compiler or lint
diagnostics.

## Local environment and scoped selection

`.mise.toml` loads the tracked `.env` defaults, then the optional, ignored
`.env.local`, through mise's `_.file` setting. Relative paths resolve from the
config root, even when a command starts in a subdirectory. Keep `.env` portable and
free of secrets. Put machine-specific `ANDROID_HOME`, an optional `DEVELOPER_DIR`,
and an existing JDK selection in `.env.local`, or inherit them from the environment.
Do not put private bridge endpoints or credentials in either file. `.env.local` may
be absent on clean checkouts and in CI. Do not print `mise env` output, because it
includes the actual values.

Run native gates through mise so that the declared tools and the local SDK
selection both apply:

```sh
mise exec -- make check-android
mise exec -- make validate-android
ANDROID_SERIAL=<task-owned emulator serial> mise exec -- make ui-test-android
```

If the global Command Line Tools are selected, scope the Xcode override to a single
command instead of changing the global selection:

```sh
env DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer make validate-apple
env DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer make ui-test DESTINATION="platform=iOS Simulator,id=<task-owned simulator-id>"
```

CI has no `.env` and inherits its runner's SDK and toolchain environment.

## Task-owned devices

Never reuse an owner's simulator or emulator. Create one for the task and remove
only that device afterwards, after its evidence is preserved.

- **iOS Simulator:** list types and runtimes with `xcrun simctl list devicetypes`
  and `xcrun simctl list runtimes`. Create a device with
  `xcrun simctl create <name> <type-id> <runtime-id>` and use the UUID it returns.
  Shut down and delete only that device. XCTest results and screenshot attachments
  are in `.build/xcode/Logs/Test/`.
- **Android emulator:** create a new, uniquely named AVD from an image that is
  already installed. Do not reuse or overwrite an owner's AVD. Local evidence uses
  an API 35 ARM emulator. CI uses API 35 x86_64 on a disposable Linux runner. Pass
  its serial as `ANDROID_SERIAL`. Reports and extra screenshots are under
  `apps/android/app/build/`. The three baseline screenshots are copied to
  `.build/android/screens/`.

## Make targets and gates

`make help` lists every target. The gates a contributor uses most:

| Target | What it runs |
| --- | --- |
| `make setup` | Installs the pinned mise tools (SDKs and JDK are prerequisites) |
| `make generate` | Generates the ignored Xcode project from `project.yml` |
| `make fmt` | Formats Markdown, Swift and Kotlin |
| `make fmt-check` | Checks Swift and Kotlin style |
| `make md-format` / `make md-check` | Formats or checks all Markdown with rumdl |
| `make test` | Runs both hermetic core suites (Swift and Kotlin unit tests) |
| `make check-apple` | Swift style, Markdown check and Swift core tests, warnings as errors |
| `make check-android` | ktfmt check, unit tests and lint in one Gradle run |
| `make check` | Both focused platform gates; needs both toolchains |
| `make build-ios` | Unsigned iOS Simulator app |
| `make build-android` | Debug app and instrumentation APKs |
| `make validate-apple` | `check-apple` and `build-ios` |
| `make validate-android` | ktfmt, unit tests, lint and both debug APKs |
| `make validate` | Default Android validation, bridge gate and Markdown check; no Xcode required |
| `make ui-test DESTINATION=…` | iPhone shell journey on the named simulator |
| `make ui-test-android` | Android shell journey on `ANDROID_SERIAL`; exports three screenshots |
| `make check-bridge` | Bridge gate: `cargo fmt --check`, all hermetic tests, release example build |
| `make bench-bridge-client` | Benchmark against a running bridge (`BASE_URL`, `SESSION`, `STREAM_CYCLES`) |
| `make resource-bridge-client` | Leak and lifecycle soak, about six minutes; run before a version release |

Use the platform-focused targets while iterating. The owner-selected default
acceptance and pre-PR gate is `make validate`: Android, bridge and Markdown only.
iOS implementation is deferred; Xcode is not required by this default gate.
`make validate-apple` remains the explicit Apple source/test/build gate and must
accompany resumed Apple work and its simulator journey. Existing Apple CI remains
enabled. Run the owning gates before committing; `make check-bridge` covers bridge
changes and `make md-check` covers Markdown. The default gate does not establish
Apple acceptance or claim the deferred iOS mutation-binding work is complete.

Formatting, compilation and core tests are prerequisites, not proof of safe remote
control. Both platform UI journeys are explicit commands. Nothing in the gates
establishes attachment, approvals or notifications on a live agent.

Compiler and lint warnings are errors. Do not add suppressions, lint baselines or
skipped tests to get a green gate. Swift 6 language mode provides data-race checks.

## Apple development

```sh
make setup
make check-apple
make generate
open Cappuccino.xcodeproj
```

Choose the `Cappuccino` scheme for an iPhone or iPad Simulator. Use a
task-owned simulator for `make ui-test`. No API keys, Herdr runtime or Apple
Developer membership is needed for tests or unsigned Simulator builds.

### Unsigned iPhone IPA

`make ipa` runs `xtool dev build --configuration release --ipa` in `apps/ios` and
writes `apps/ios/xtool/Cappuccino.ipa`. The app is unsigned, so SideStore or AltStore
re-signs it with your own Apple ID when you install it. Nothing is signed or
uploaded by the build. `make dev` builds, signs and installs on a connected iPhone
with xtool. It needs a one-time `xtool setup`, your Apple ID and the phone. Install
routes are in [install-iphone.md](install-iphone.md).

### xtool builds and installs; Xcode tests

xtool builds and installs the iPhone app (`make ipa`, `make dev`). XcodeGen and
xcodebuild still own `make build-ios`, `make ui-test` and the validate gates. The
split follows what each tool can do:

- xtool cannot run tests or the simulator test journey
  ([xtool#177](https://github.com/xtool-org/xtool/issues/177)).
- `xtool launch` is broken on iOS 17 and later
  ([xtool#44](https://github.com/xtool-org/xtool/issues/44)), so debugging stays in
  Xcode.
- The app is the `Cappuccino` package product, built from target `CappuccinoApp` and
  sources `apps/ios/App`. xtool and Xcode compile the same sources. `apps/ios/xtool.yml`
  and `apps/ios/App/Info.plist` define the xtool bundle. Keep its entry target
  `CappuccinoApp` distinct from the Xcode app target.
- `project.yml` names the Xcode application target `CappuccinoPhone` so UI-test
  host lookup does not confuse it with the `Cappuccino` package product. The
  Xcode product name and scheme remain `Cappuccino`, with the same bundle ID and
  `Cappuccino.app` artifact. This does not rename the package/xtool product.
  Edit `project.yml` rather than the generated Xcode project.

## Android development

```sh
make fmt-android
make validate-android
ANDROID_SERIAL=<task-owned emulator serial> make ui-test-android
```

Open `apps/android/` in Android Studio to work interactively. Android declares
network access for bridge connections. Its legacy and modern backup and transfer
rules exclude app data. Those rules are not a credential-storage implementation.

The conversation reader renders CommonMark prose and code natively. Tool and
thought details stay collapsed until expanded. Timestamps use the device timezone
in `yyyy-MM-dd HH:mm:ss`. The reader follows the newest content unless the user
scrolls back. Incoming content then offers an explicit jump to the latest. The
Pi identity rules that it depends on are in
[the bridge README](../services/bridge/README.md#pi-transcript-identity).

Release signing and physical-phone installation are separate work. Debug APK
assembly uses development signing only. Instrumentation covers navigation and
activity recreation, machine and foreground recovery, connection failures,
conversation scrolling, disclosures, Markdown rendering and live timezone changes.

## Bridge development

From `services/bridge`, the crate's own commands are `cargo fmt --check`, `cargo test`
and `cargo build --release`. From the repository root, `make check-bridge` runs the
full bridge gate, including the client contract suite and the release example
build. The bridge's own setup, lifecycle and API notes are in
[services/bridge/README.md](../services/bridge/README.md).

The headless `test-client` example in `services/bridge/examples/test-client/` is
a read-only companion. Its `check` mode validates the HTTP routes and an optional
WebSocket stream. Its `bench` mode reports timings, RSS and CPU for a running bridge
with consumed, validated bodies. It establishes a baseline and asserts no
performance threshold. Its hermetic tests spawn the real bridge against a mock
Herdr socket.

`ANDROID_SERIAL=<task-owned emulator> make ui-test-android` also runs the actual
Rust bridge against a private synthetic Herdr peer. Its stdlib Python runner
(`services/bridge/tests/android_journey.py`) builds the locked bridge, uses a
private temporary HOME/session store and loopback fixture control, and reverses
only its two owned ports on the explicitly selected emulator. No Pi/provider
process, credential, owner session or global integration is involved. Python 3,
Cargo and `adb` must be available; no Python package installation is needed.

The complete UI suite includes `ActualBridgeJourneyTest`: replacement before
prompt/control/answer, a frozen old UI callback against a live replacement, and
canonical-history recovery after a pending write loses its acknowledgement. The
peer journal proves terminal-RPC counts, not native Pi admission. Missing fixture
runner arguments fail the journey rather than silently skip it; use the Make
target, not a bare connected-test command. The runner removes its reverse
mappings, stops its owned bridge/listeners and removes its temporary store at
exit. Host-only fixture verification is
`python3 services/bridge/tests/android_journey.py --self-test` from the repo root.

The resource soak (`make resource-bridge-client`) is opt-in. It asserts that file
descriptors, child processes, temp files and memory recover after each scenario
class. Short samples never prove an absolute zero leak.

## CI

- **Pull requests** run the format, test, lint and build jobs for the platforms
  they change. Android pull requests also run the emulator UI journey. Pull
  requests that change no code run the Markdown check.
- **Pushes to `main` and manual runs** (`workflow_dispatch`) also run the
  Android emulator and simulator UI journeys. Those journeys preserve narrow synthetic evidence for
  seven days.
- **Apple job:** macOS 26 with Xcode 26.6 through `DEVELOPER_DIR`. It runs
  `make validate-apple`, creates one iOS 26.5 simulator, runs the iPhone shell
  journey and deletes only that simulator.
- **Android job:** Ubuntu 24.04 with JDK 21 and `make validate-android`. It starts
  an API 35 x86_64 Google APIs emulator, with KVM enabled only on that disposable
  runner. It runs the Android shell journey.

CI actions use released version tags, chosen by the owner, and not commit SHAs.
Dependabot proposes action major upgrades weekly and does not merge them. The job
token has read-only contents permission. Checkout does not persist credentials,
and mise-action does not export its token. Test bundles and instrumentation
reports are uploaded from synthetic-evidence paths only. No owner session, real
transcript, pairing token or signing credential is an acceptable fixture or
artifact.

The runner selections in `.github/workflows/ci.yml` are authoritative. Do not
fall back silently when a configured environment disappears. Hosted results are
evidence only for the commit that ran them.

## Plugin bridge transport

`BridgeClient` (`URLSession` and `URLSessionWebSocketTask` on iPhone, OkHttp on
Android) talks to the standalone Rust Tokio and axum process in `services/bridge`.
Herdr's plugin manifest starts this executable for its startup hook and actions.
The process is not loaded into Herdr or into an agent.

The bridge calls the local Herdr socket (`HERDR_SOCKET_PATH`) with NDJSON RPC.
It maps `agent.list`, including unnamed panes. It validates each Pi session path
that Herdr reports against Pi's canonical session store. It serves conversation
history from the Pi, Claude Code and Agy stores, and exposes a read-only pane-line
WebSocket stream. Its routes are listed in [architecture](architecture.md).

The prompt route writes text followed by Enter into the pane. Herdr's acknowledgement
and optional lifecycle wait do not select Pi's `steer` or `follow_up` queue. They do
not return a correlated Pi receipt. The bridge does not map this to a delivered or
confirmed state, and it implements no receipt contract. Owner-reconciled acceptance
is required first. The bridge implements no authentication challenge. The shared
client still maps an HTTP 401 response to its generic unauthorized error, but that
is not a bridge token flow.

## Native design rules

- **Frameworks.** Use SwiftUI and standard platform APIs first, and Compose with
  Material 3 on Android. Primitive view state stays in `@State` or the equivalent.
  Introduce an observation model when the connected flow needs shared state. Keep
  UI state on the main actor. Use actor isolation only for genuinely shared mutable
  connection state. An actor does not provide server-side approval atomicity or
  durable receipts.
- **Dependencies.** No TCA, dependency container, persistence framework or
  transport library is adopted. Evaluate a maintained library only against a
  demonstrated need, its supported platforms, maintenance and license, concurrency
  behavior and testability. Native Foundation networking is used for HTTP and
  WebSocket. It is not an SSH client.
- **Security.** The bridge has no per-request authentication, by owner decision. The
  tailnet is the boundary. Do not invent cryptography or add credentials without an
  owner decision.
- **Mac.** There is no macOS app. Herdr covers the desktop.

## Behavior and security acceptance

The attachment work must test active-branch history, cancellation, ordered
reconciliation, ambiguous delivery and duplicate prevention, stale actions,
local and remote approval races, pending or denied requests that do not execute,
and exact-grant invalidation. Keep these checks hermetic where possible. Follow
them with an explicitly task-owned runtime journey.

Before the connected UI is called usable, verify VoiceOver, Dynamic Type, keyboard
interaction, reduced motion and performance on long transcripts and tool details.
Do not invent a coverage percentage for behavior that is not yet covered. Do not
skip critical-path tests to obtain a number.

## Sources

- [AGP built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin),
  [Kotlin Gradle configuration](https://kotlinlang.org/docs/gradle-configure-project.html)
  and [Compose compiler](https://developer.android.com/develop/ui/compose/compiler).
- [AndroidX platform test storage](https://developer.android.com/reference/androidx/test/platform/io/PlatformTestStorageRegistry):
  AGP 8 and later collect outputs by default. App-private screenshots cannot be
  pulled after test cleanup uninstalls the app.
- [Android backup and transfer rules](https://developer.android.com/about/versions/12/backup-restore#xml-changes):
  modern device transfer needs explicit exclusions. `allowBackup=false` alone is not
  enough.
- [Gradle wrapper checksums](https://gradle.org/release-checksums/) and the
  [Android emulator runner](https://github.com/ReactiveCircus/android-emulator-runner).
- [SwiftUI model data](https://developer.apple.com/documentation/swiftui/managing-model-data-in-your-app)
  and [Swift 6 data-race safety](https://www.swift.org/migration/documentation/swift-6-concurrency-migration-guide/dataracesafety/).
- [Hosted macOS 26 image](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-Readme.md).
  Runner selections must be checked against the actual job, not inferred from this
  document.
- [mise-action](https://github.com/jdx/mise-action): token persistence is opt-in.
  Keep the safer default.
