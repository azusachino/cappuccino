# Cappuccino

<p align="center">
  <img src="docs/assets/icon.png" width="128" alt="Cappuccino icon" />
</p>

<h1 align="center">Cappuccino</h1>

<p align="center">
  <a href="#license"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="GPL-3.0 license" /></a>
  <img src="https://img.shields.io/badge/platform-Android%20%7C%20iOS-black" alt="Android and iOS" />
  <img src="https://img.shields.io/badge/Herdr-0.9.0%2B-4C4F69" alt="Herdr 0.9.0 or later" />
  <img src="https://img.shields.io/badge/agents-Pi%20%7C%20Claude%20Code%20%7C%20Agy-8839EF" alt="Supported agents" />
</p>

Cappuccino is a native iPhone and Android app for the coding agents already
running in [Herdr](https://github.com/herdrdev/herdr) panes on your own machines. It lists
those agents, shows their conversations, and lets you send a prompt, stop a
run, start a new session, or answer an approval. It does not start, stop or
replace the agents. A small Rust bridge, installed as a Herdr plugin, serves
the apps over your private tailnet.

## Contents

- [Why](#why)
- [What you can do](#what-you-can-do)
- [How it works](#how-it-works)
- [Design and trade-offs](#design-and-trade-offs)
- [Getting started](#getting-started)
- [Limitations](#limitations)
- [Documentation](#documentation)
- [Development](#development)
- [Contributing](#contributing)
- [License](#license)

## Why

Long-running agents keep working after you leave the terminal, but checking
on them from a phone usually means a remote terminal app. Cappuccino reads
the agent's own session record and the pane's screen instead, so the phone
shows a conversation rather than a terminal. The agent keeps running when the
phone disconnects, and reconnecting shows the same session.

## What you can do

- **Machines:** add each Herdr machine by its bridge URL. Each machine lists
  its agents. An unreachable machine shows its own error and does not block
  the others.
- **Agents and status:** see each agent's name, working directory, branch (where
  the repository is known) and state (idle, working or blocked), as Herdr reports them.
- **Conversations:** read the canonical transcript for Pi, Claude Code and
  Agy. If an agent has no session file the bridge can resolve, the app shows
  the pane's terminal scrollback instead, labeled as scrollback and read-only.
- **Prompts:** send text to an agent. It arrives as typed input, not as a
  queued delivery with a receipt.
- **Stop:** send Escape to interrupt the current run. Offered while the agent
  is working.
- **New session:** type `/new` into an idle Pi, Claude Code or Agy session.
  The bridge refuses it while the agent is working or blocked.
- **Approvals and questions:** answer tool approval prompts and Claude Code's
  AskUserQuestion forms. The bridge reads them from the bottom of the screen
  and presses the matching key. An answer is refused if the prompt on screen
  has changed.
- **Slash commands:** a message that starts with an agent command, such as
  `/model opus`, asks for confirmation before it is sent. Commands like
  `/model` drop the agent's prompt cache, and `/new` ends the conversation, so
  each one needs an explicit yes.
- **Session stats:** the composer shows the model, thinking level, context
  tokens and window, and the share of input read from the prompt cache. Pi and
  Claude Code also report the context window when it is known. Agy reports
  token counts without a model.
- **Saved conversations (Android):** recent conversations stay on the device
  so you can read them offline. A saved view is read-only, shows when it was
  captured, and offers no actions.
- **Notifications (Android):** a foreground service watches the configured
  machines and alerts you when an agent finishes or waits for input. The iPhone
  app has no notifications.

## How it works

```text
phone (iPhone or Android)
  │  HTTPS / WebSocket over the tailnet
  ▼
tailscale serve   https://<machine>.<tailnet>.ts.net  ->  127.0.0.1:7392
  │
  ▼
cappuccino-bridge (loopback 127.0.0.1:7392)
  │  NDJSON over the Herdr socket
  ▼
Herdr  ->  agent panes (Pi, Claude Code, Agy)
```

The bridge is a Rust program that Herdr starts through its plugin manifest.
It runs its own loopback HTTP and WebSocket server and keeps that server
running after startup. For each request it asks Herdr, over Herdr's local
socket, for the agent list, pane text or a prompt operation. For a
conversation it reads the agent's session file, after checking that the path Herdr
reports is inside that agent's session store. `tailscale serve` maps the loopback port
to an HTTPS address on your tailnet, and nothing else is exposed. Stop and New
session only type into the existing pane, so neither starts nor ends a process.

## Design and trade-offs

- **No app-level authentication.** The tailnet is the boundary: anyone on
  your tailnet who has the URL can use the bridge. The bridge refuses any
  non-loopback bind, and `tailscale serve` is the only supported way to expose
  it. Do not expose it to public ingress.
- **A Herdr plugin, not a separate daemon.** Herdr's plugin system starts the
  bridge and provides its status, stop and log actions. The bridge is not
  loaded into Herdr or into an agent. Herdr and the agent own process lifetime,
  so the app never launches or kills an agent.
- **Reading prompts off the screen, failing closed.** The bridge does not
  integrate with each agent's prompt flow. It reads the visible prompt, gives it
  a single-use id, and checks the bottom of the screen for it. It refuses an
  answer for a stale or reused id, and it asks you to confirm any slash command
  before sending it.
- **Prompt text is terminal input.** A prompt is written to the pane with
  Enter. Herdr does not report which Pi queue accepted it, so the app does not
  claim a delivery receipt.
- **Native apps, shared behavior.** The iPhone and Android apps are written
  separately in Swift and Kotlin. They share behavior through a common set of
  fixtures and the behavior spec, not through shared code.

### Claude Code notes

- **Transcript:** the bridge reads Claude Code's project session files, under
  `CLAUDE_CONFIG_DIR` when that is set, otherwise under `~/.claude`.
- **Approvals and questions:** Claude Code writes an AskUserQuestion to its
  transcript only after it is answered. While the form waits, the bridge reads
  it from the screen, and it does the same for tool approvals.
- **New session:** `/new` starts a fresh conversation in the same process.
- **Context window:** 200K for Claude 3 models, Haiku 4.5, Sonnet 4.5, Opus 4.5,
  Opus 4.1, Opus 4.0 and Sonnet 4.0. Every other model is 1M. A count above
  200K shows that the larger window applies.
- **Commands:** slash commands are confirmed before they are sent, as above.

## Getting started

### Requirements

- Herdr 0.9.0 or later. The plugin manifest sets this minimum, and the bridge
  is tested against Herdr 0.9.3.
- Tailscale on each Herdr machine and on each phone, on the same tailnet.
- A Rust toolchain (`cargo`) on each Herdr machine, to build the bridge.
- iPhone app: macOS 26 or later with Xcode 26 or later (Swift 6.2), and
  [mise](https://mise.jdx.dev). The app targets iOS 17 and later.
- Android app: JDK 21 and an Android SDK with platform API 37 and build-tools
  36.0.0. The app runs on Android 13 (API 33) and later.

### 1. Prepare each agent's Herdr integration

The bridge can only read a Pi conversation after Herdr's integration has
reported the session. Install the integration in the config directory that
the running Pi process uses (`<pi-config-dir>` below, for example a custom `PI_CODING_AGENT_DIR`):

```sh
env PI_CODING_AGENT_DIR="<pi-config-dir>" herdr integration install pi
env PI_CODING_AGENT_DIR="<pi-config-dir>" herdr integration status
```

Then run Pi's `/reload` while it is idle, as described in the
[integration prerequisite](services/bridge/README.md#agent-integration-prerequisite).
Use the supported integration for other agent types.

### 2. Install and link the bridge on each Herdr machine

From the root of this repository:

```sh
cargo install --path services/bridge --locked
herdr plugin link "$PWD/services/bridge"
herdr plugin list
herdr plugin action invoke status --plugin cappuccino-bridge
```

Install the binary first. Linking the plugin does not build it. Cargo's bin
directory (normally `~/.cargo/bin`) must be on the `PATH` that Herdr passes to
plugin commands. The `status` action confirms this.

### 3. Expose the bridge to your tailnet

On `start` and `status`, the bridge checks Tailscale Serve and adds the entry
if it is missing. The entry maps `https://<machine>.<tailnet>.ts.net` to the
loopback port. The bridge prints the URL. Check it from any device on the tailnet:

```sh
curl https://<machine>.<tailnet>.ts.net/api/session
```

If you want to run the Serve command yourself, set `serve.auto_apply` to
`false` in `~/.config/cappuccino-bridge/config.toml` (or `config.json`), or set
`CAPP_BRIDGE_SERVE_AUTO_APPLY=0`, before you start the bridge. The bridge then prints the command instead
of running it. The
[bridge setup guide](services/bridge/README.md#install-and-lifecycle) covers
the one-time Tailscale operator setup, the config file and the other bridge
actions.

### 4. Build and install the iPhone app

```sh
make setup
make dev
```

xtool builds the app, signs it with your own Apple ID and installs it on a USB-connected
iPhone. `make ipa` builds an unsigned `.ipa` (`apps/ios/xtool/Cappuccino.ipa`) for SideStore or
AltStore to re-sign. Running from Xcode is the fallback. The one-time xtool setup and every
route are in [the iPhone install guide](docs/install-iphone.md). With free provisioning, the
installed app must be re-signed about every seven days. There is no App Store or TestFlight build.

### 5. Build and install the Android app

Open `apps/android/` in Android Studio and run the app on your phone. To build
the debug APK without Android Studio, run `make build-android`. The APK is
written to `apps/android/app/build/outputs/apk/debug/app-debug.apk`. Only debug
builds are provided.

### 6. Add a machine

In the app, open Machines and add the bridge URL: the `https://…ts.net`
address from step 3. A loopback address such as `http://127.0.0.1:7392` reaches
the bridge only from the same machine, for example the iOS Simulator. Each
saved machine is bound to the machine ID the bridge reports.

## Limitations

- The bridge has no authentication. Its safety depends on the tailnet and on
  loopback-only binding.
- Prompts are terminal input. Nudge and Follow-up queue selection and delivery
  receipts are not implemented.
- Approvals are answered from the screen. There is no typed approval gate, no
  reusable grants, and no reconciliation of pending requests after a
  reconnect.
- Canonical history is read from each agent's session file, and that path must
  be reported by Herdr. An agent without a usable reported session shows the
  read-only scrollback fallback.
- Live parity with an owner's active session has not been verified. The
  automated UI journeys use synthetic transcripts.
- The bridge is not supervised after startup. No Herdr shutdown hook stops it,
  so use the plugin stop action.
- There are no push or Telegram alerts. The iPhone app has no notifications and
  no saved conversations.
- The Android app is available only as a debug build. Release signing and
  physical-phone installation are separate work.
- The Mac app was retired. Herdr covers the desktop.

For the tested behavior and its limits, see [verification](docs/verification.md).

## Documentation

- [docs/intent.md](docs/intent.md): the confirmed product intent and owner
  decisions.
- [docs/plan.md](docs/plan.md): the current issue slices and what is next.
- [docs/architecture.md](docs/architecture.md): components, bridge routes and
  the Herdr input boundary.
- [docs/behavior-spec.md](docs/behavior-spec.md): the provisional behavior
  contract shared by the two apps.
- [docs/user-stories.md](docs/user-stories.md): the MVP stories and setup for
  two Herdr machines.
- [docs/install-iphone.md](docs/install-iphone.md): installing the iPhone app
  with xtool, SideStore or the Xcode fallback, and the free Apple ID limits.
- [docs/verification.md](docs/verification.md): what has been exercised and
  the current limits.
- [docs/development.md](docs/development.md): toolchains, gates and CI for
  contributors.
- [docs/bridge-v2-contract.md](docs/bridge-v2-contract.md): the bridge HTTP and WebSocket contract, including the answer, prompt-id and command-confirmation rules.
- [services/bridge/README.md](services/bridge/README.md): bridge installation,
  lifecycle, API, configuration and security notes.
- [docs/design/](docs/design): current design notes for the apps, including
  [agent completion notifications](docs/design/agent-completion-notifications.md),
  [Android Material 3](docs/design/android-material3.md),
  [conversation rework](docs/design/conversation-rework.md),
  [offline conversation identity](docs/design/offline-conversation-identity.md),
  [Pi phone reader](docs/design/pi-phone-reader.md) and
  [Herdr and Pi capability audit](docs/design/herdr-pi-capability-audit.md).
- [docs/research/](docs/research): source studies and research notes behind
  the design, including [discovery](docs/research/discovery.md).
- [docs/licenses/](docs/licenses): notices for bundled third-party fonts and
  derived code.
- [CONTRIBUTING.md](CONTRIBUTING.md) and [AGENTS.md](AGENTS.md): style, gates
  and ownership rules.

## Contributing

Issues and pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md)
for setup, style and the validation gates, and [docs/development.md](docs/development.md)
for toolchain pins and CI. Keep scope within the product intent in
[docs/intent.md](docs/intent.md): Cappuccino reads and relays agents that you
already own; it does not start, stop or replace them.

## Development

Contributor setup, repository layout, toolchain pins, make targets, gates,
scoped toolchain selection and task-owned devices are in
[docs/development.md](docs/development.md). Style and contribution rules are
in [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Cappuccino is licensed under the GNU General Public License v3.0. See
[LICENSE](LICENSE).
