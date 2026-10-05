# Contributing

Cappuccino is a personal native client, not a replacement agent runtime. Read [intent](docs/intent.md) and [plan](docs/plan.md) before expanding scope.

## Setup and checks

Apple uses macOS 26+/Xcode 26+ (Swift 6.2+); Android uses JDK 21/API 37 SDK. With both toolchains available, run:

```sh
make setup
make fmt
make validate
make ui-test DESTINATION="platform=iOS Simulator,id=<task-owned simulator-id>"
ANDROID_SERIAL=<task-owned emulator serial> make ui-test-android
```

For focused iteration, use `fmt-apple`/`validate-apple` or `fmt-android`/`validate-android`. See [README](README.md) for scoped toolchain selection and task-owned device discovery. Tests are hermetic; they need no Herdr, Pi or Telegram credentials.

## Style

- Two-space indentation, enforced by swift-format, ktfmt Google style and `.editorconfig`.
- Keep pure logic separate from platform/UI and future transport I/O.
- Prefer concrete, small types and native controls. Introduce a dependency or abstraction only when a working slice needs it.
- Comments explain a non-obvious why; avoid narrating what code does.
- Add tests for behavior changes and simulator evidence for meaningful UI changes.

See [development and quality](docs/development.md) for native-first framework decisions, Mac-specific work and future connected-flow acceptance. CI actions use version tags by owner choice; Dependabot proposes upgrades, without automatic merging. Swift/Kotlin compiler and Android lint warnings are errors; never suppress diagnostics, add lint baselines or weaken tests to get green.

Keep changes focused and use conventional commit prefixes. Edit declarative project source, not generated Xcode files. Never weaken a gate to make a failing slice appear complete. Credentials, signing profiles, private addresses and real agent history are not test fixtures.
