# Contributing

Cappuccino is a personal native client, not a replacement agent runtime. Read [intent](docs/intent.md) and [plan](docs/plan.md) before expanding scope.

## Setup and checks

Use macOS 26+ and Xcode 26+ (Swift 6.2+), then run:

```sh
make setup
make fmt
make validate
make ui-test DESTINATION="platform=iOS Simulator,id=<simulator-id>"
```

See [README](README.md) for Xcode selection and simulator discovery. Tests are hermetic; they need no Herdr, Pi or Telegram credentials.

## Style

- Two-space indentation, enforced by Apple's swift-format and `.editorconfig`.
- Keep pure logic separate from platform/UI and future transport I/O.
- Prefer concrete, small types and native controls. Introduce a dependency or abstraction only when a working slice needs it.
- Comments explain a non-obvious why; avoid narrating what code does.
- Add tests for behavior changes and simulator evidence for meaningful UI changes.

See [development and quality](docs/development.md) for native-first framework decisions, Mac-specific work and future connected-flow acceptance. CI actions use version tags by owner choice; Dependabot proposes upgrades, without automatic merging. Swift compiler warnings are errors; never suppress data-race diagnostics or weaken tests to get green.

Keep changes focused and use conventional commit prefixes. Edit declarative project source, not generated Xcode files. Never weaken a gate to make a failing slice appear complete. Credentials, signing profiles, private addresses and real agent history are not test fixtures.
