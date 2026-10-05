# Cappuccino

A personal native iPhone/macOS companion for conversations and attention from agents already running on your Herdr machines.

**Current state:** disconnected SwiftUI skeleton, a small Swift core and hermetic/simulator checks. It does not connect to machines, send prompts, approve tools or deliver notifications yet. There is no agent launcher or terminal emulator.

Read [intent](docs/intent.md), [plan](docs/plan.md) and [source research](docs/discovery.md). Existing remote agents keep their process/session lifetime; the phone attaches and detaches.

## Develop

Requires macOS, Xcode 16+ with Swift 6, and mise. No API keys, Herdr runtime or Apple Developer membership is needed for the tests or unsigned Simulator builds.

```sh
make setup
make check
make generate
open Cappuccino.xcodeproj
```

Choose `Cappuccino` for iPhone/iPad Simulator or `CappuccinoMac` for the shared Mac shell. Edit `project.yml`; generated Xcode files are ignored. The Mac shell compiles from the same UI, but the product milestone is iPhone-first.

If `xcode-select -p` selects Command Line Tools, use a per-command override rather than changing machine-wide settings:

```sh
env DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer make validate
```

## Gates

```sh
make fmt
make validate
xcrun simctl list devices available
make ui-test DESTINATION="platform=iOS Simulator,id=<simulator-id>"
```

`make check` covers Swift style, Markdown and SwiftPM tests. `make validate` also generates and builds the iOS Simulator and macOS apps. `make ui-test` exercises only the disconnected shell and saves screenshot attachments in Xcode's `.build/xcode/Logs/Test/` results; it proves no remote-agent behavior.

For a task-owned simulator, discover types/runtimes with `xcrun simctl list devicetypes` and `xcrun simctl list runtimes`, create a dedicated device with `xcrun simctl create <name> <type-id> <runtime-id>`, and use its returned UUID. Shut down/delete only that task-created device after preserving evidence; leave owner devices unchanged.

## Optional vphone debugging

[vphone-cli assessment](docs/discovery.md#optional-debugging-vphone-cli) covers the owner-suggested virtual iPhone tool. It is a read-only research reference, not a dependency or replacement for XCTest/Simulator. Running it needs a separately approved physical Mac host and security/storage setup, plus an iPhoneOS build rather than this skeleton's Simulator artifact. No VM was installed or started.

## Prototype constraints

Private LAN/tailnet machines, manual pairing, Pi-first integration, full active-branch history and typed approvals are planned, not implemented. Generic Telegram alerts replace native APNs for now. Free Personal Team builds on a physical iPhone require periodic reprovisioning; long-term distribution and real-device acceptance remain open.

See [CONTRIBUTING](CONTRIBUTING.md) and [AGENTS](AGENTS.md) for style, gates and ownership. The existing [GPL-3.0 license](LICENSE) remains unchanged.
