# Cappuccino: native Herdr companion discovery

Source research from 2026-10-05 that informed the [intent](../intent.md) and the [plan](../plan.md). Findings remain source and document inspection, not runtime verification of a bridge or reference app.

## References and lessons

Read-only clones are catalogued in the workstation under `refs/coding-agents/`. These are research material, not adopted dependencies. Revisions below pin this inspection; refreshing the shelf later does not update these conclusions automatically.

| Project | What the primary sources show | Useful lesson | Fit limitation |
| --- | --- | --- | --- |
| [Happy](https://github.com/slopus/happy) | Its README describes a CLI wrapper, mobile/web clients, encrypted sync server and terminal-to-phone handoff. The mobile app is Expo, not a SwiftUI reference. | Conversation-first UX, permission attention and handoff between devices. | Launches through its wrapper; not evidence that arbitrary existing Herdr agents can be attached unchanged. |
| [HAPI](https://github.com/tiann/hapi) | Native SwiftUI/UIKit iOS client, self-hosted hub storing history, REST + SSE clients, wrapped agents, permission/question routing, pairing and push infrastructure. | Strongest reference for a typed native conversation/approval client and reconnect/push separation. | A complete alternative runtime/hub, not a drop-in Herdr client. Multi-hub UI currently has one active hub; not proof of a unified cross-machine inbox. |
| [Agmente](https://github.com/rebornix/Agmente) | iOS client using ACP and Codex app-server over WebSocket, with protocol-specific history/session flows and tool rendering. | Structured events and explicit capabilities, rather than guessing messages from screen text. | Requires those protocol endpoints; not evidence that it can control an arbitrary Herdr TUI session. |
| [Claude Code Mobile](https://github.com/thedomainai/claude-code-mobile) | SwiftUI client reading Claude JSONL logs over SSH and writing through tmux; local notifications require the app to be running. | Closest small reference for private SSH/Tailscale access to existing sessions and native transcript rendering. | Claude-specific, tmux-based, and raw-key approvals are not safe generic permission semantics. Source presence is not a security or production-readiness endorsement. |

Inspected revisions:

- Happy: `dafe9a51858f72c6f04a9337f3a93c87f603b5b7`.
- HAPI: `5153ff23c196c7b84818752f7a631420ef0eb25b`.
- Agmente: `87f224e7d5884d450f4d54cc1d72724416e6d750`.
- Claude Code Mobile: `523ff7a13dc155ad500f2da8ac2c2ffd0174976e`.
- Herdr source: `e35f3937b0efe40ec0dab675709c68e1d8e8c9e6`. Installed client/server reported 0.9.3; current source and versioned 0.9.3 documentation must be distinguished from that running binary.

Further primary-source reading:

- [HAPI architecture](https://github.com/tiann/hapi/blob/5153ff23c196c7b84818752f7a631420ef0eb25b/docs/guide/how-it-works.md): CLI/runner ↔ hub, persistent history, REST + SSE, and permission flow.
- [HAPI native clients](https://github.com/tiann/hapi/blob/5153ff23c196c7b84818752f7a631420ef0eb25b/docs/guide/native-apps.md): HTTPS pairing, capability limits, push setup, multi-hub behavior.
- [HAPI chat session](https://github.com/tiann/hapi/blob/5153ff23c196c7b84818752f7a631420ef0eb25b/ios/Hapi/Models/ChatSession.swift): ordered streaming, explicit catch-up and SSE resume cursor.
- [Agmente agent guide](https://github.com/rebornix/Agmente/blob/87f224e7d5884d450f4d54cc1d72724416e6d750/Agents.md): ACP versus Codex session/history handling and persistent reconnect identity.
- [Claude Code Mobile README](https://github.com/thedomainai/claude-code-mobile/blob/523ff7a13dc155ad500f2da8ac2c2ffd0174976e/README.md): SSH/log/input architecture and foreground notification limits.
- [Happy README](https://github.com/slopus/happy/blob/dafe9a51858f72c6f04a9337f3a93c87f603b5b7/README.md): wrapper and restart/handoff behavior.

## Optional debugging: vphone-cli

The owner suggested [vphone-cli](https://github.com/Lakr233/vphone-cli) for debugging sessions. Read-only source revision `49fdb7d3c015aa2fb732c9f4a8a278410295c8d8` is on the workstation's `refs/computer-use/vphone-cli/` shelf; it is not a Cappuccino dependency or required gate.

- It runs a patched iOS research guest through Apple's Virtualization.framework on a physical Apple Silicon Mac with macOS 15+. Upstream documents screenshot/input automation, a UI tree, app launch and HTTP/WebSocket guest APIs. This could support repeatable native UI investigation and later disposable reconnect/attention scenarios, not agent orchestration.
- Host setup is consequential: Recovery must enable research guests and at least relax SIP debugging restrictions; private-entitlement admission requires AMFI allowlisting and privileged helper authorization. The more permissive alternative disables SIP/AMFI. Default guest storage is a 64 GB virtual disk plus firmware. Merely considering the tool authorizes none of these changes, installation, downloads or VM creation.
- A guest needs an iPhoneOS/device build; the current `make build-ios` produces a Simulator app, not a guest-installable artifact. A future experiment needs a separate device-build/install decision and an explicitly task-owned guest.
- Upstream distinguishes installation paths: `ideviceinstaller` exercises installd, whereas `apps.install` re-signs/places the app itself and bypasses that gate. Its instructions warn that `devicectl` installation/launch times out against these guests. An automated launch or successful patched-guest install is not evidence of normal physical-iPhone provisioning.
- The optional TCP API controls a root guest daemon. Keep it loopback-only with a per-launch bearer token, keep tokens and transcripts out of artifacts, and use test-only accounts/data. Do not expose it on the tailnet as a substitute for Cappuccino's authenticated bridge.

Recommendation: retain Xcode Simulator/XCTest as the normal shell gate. Consider vphone as an opt-in debugging instrument after the owner selects a host and approves its exact security/storage changes. Record firmware, runtime/build identity and installation path separately; it does not establish physical-device signing, APNs/background behavior or the remote bridge. No vphone binaries, VM or firmware were run or installed during this inspection.

Primary sources at the inspected revision: [README](https://github.com/Lakr233/vphone-cli/blob/49fdb7d3c015aa2fb732c9f4a8a278410295c8d8/README.md), [host setup](https://github.com/Lakr233/vphone-cli/blob/49fdb7d3c015aa2fb732c9f4a8a278410295c8d8/Documents/Guides/host-setup.md), [guest API](https://github.com/Lakr233/vphone-cli/blob/49fdb7d3c015aa2fb732c9f4a8a278410295c8d8/Research/vphoned_http_api.md) and [installation caveats](https://github.com/Lakr233/vphone-cli/blob/49fdb7d3c015aa2fb732c9f4a8a278410295c8d8/AGENTS.md).

## Herdr: useful runtime, not yet a conversation API

The installed 0.9.3 CLI supports agent discovery, read, prompt, wait and send-keys; the running local server was reachable. No real owner agent was prompted or approved as an experiment.

The [versioned 0.9.3 socket API documentation](https://github.com/herdrdev/herdr/blob/e35f3937b0efe40ec0dab675709c68e1d8e8c9e6/docs/versions/0.9.3/website/src/content/docs/socket-api.mdx) establishes:

- Local newline-delimited JSON over a Unix domain socket (named pipe on Windows), not an existing mobile HTTPS service.
- Discovery/state and lifecycle subscriptions can drive an agent list and basic attention state.
- `agent.read` / `pane.read` return terminal-oriented text; this is not a normalized user/assistant/tool transcript.
- `agent.prompt` submits text to the live agent; it refuses an already-blocked approval/question UI. Delivery/wait state is not a durable, per-message receipt.
- Input via `agent.send_keys` is terminal control. The documented API does not provide a typed permission request/decision contract equivalent to HAPI or ACP.
- Native `agent_session` references can be exposed when an integration reports them. They are identifiers/paths, not message history. They are optional, so universal attachment cannot be assumed.
- Subscriptions do not replay earlier lifecycle events, retained history is not durable, and an overrun closes the subscription with `events_lost`. Reconnect needs authoritative state reconciliation, not blind event replay.

The [current source agent schema](https://github.com/herdrdev/herdr/blob/e35f3937b0efe40ec0dab675709c68e1d8e8c9e6/src/api/schema/agents.rs) corroborates terminal read parameters, text prompts and optional session references. It must not substitute for a capability check against the installed binary in a future spike.

Saved SSH machine forwarding already exists in the installed CLI. This helps reach machine runtimes, but does not by itself supply mobile pairing, a network API, normalized transcripts, durable inbox items or push notifications.

**Main design risk:** a pretty chat UI built on terminal snapshots still cannot reliably reconstruct message boundaries or safely approve the right pending tool operation. `blocked` also does not identify the question or permission payload. A stale approval must never translate blindly into a keystroke on whichever dialog is now open.

## Pi and Apple follow-up research

Installed Pi documentation and source were inspected at version 1.0.2 under the install directory of a custom `PI_CODING_AGENT_DIR` (`install/releases/1.0.2/node_modules/@earendil-works/pi-coding-agent/`).

- `docs/sessions.md` describes active-branch context and retained historical branches. `docs/session-format.md` is the reference for implementing a history reader; concatenating a whole JSONL file would mix alternative histories.
- `docs/slash-commands.md` explicitly supports `/reload` after adding discovered extensions. `docs/extensions.md` exposes `pi.sendUserMessage()`, lifecycle/message events and asynchronous `tool_call` handlers that can block execution.
- `dist/modes/interactive/interactive-mode.js`, `handleReloadCommand()`, refuses reload while streaming or compacting. `dist/core/agent-session.js`, `reload()`, replaces resources/runtime around the existing session manager; it does not launch a replacement Pi process or new conversation. Extension runtime state is invalidated and must be cleaned up.
- `docs/security.md` states that Pi does not ask for approval before every tool call. `examples/extensions/permission-gate.ts` demonstrates an awaited confirmation hook, not a security sandbox.
- Therefore, lack of RPC attachment is **not** proof that a trusted companion extension cannot be added to an existing TUI session. Idle reload is documented; an authenticated remote event/action bridge and correlated tool approvals are new work whose runtime behavior still needs a bounded spike. Already executing calls cannot be retroactively gated.

Apple primary sources:

- [Developer account overview](https://developer.apple.com/help/account/basics/about-your-developer-account): free Personal Team device provisioning expires after 7 days and requires rebuilding/reinstalling.
- [iOS capability matrix](https://developer.apple.com/help/account/reference/supported-capabilities-ios/): the original HTML row for Push notifications includes paid ADP/ADEP, not the free Apple Developer column. Readable extraction omits the checkmarks, so the original table was checked.
- [Sending requests to APNs](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns): provider initiates an outbound authenticated POST over HTTP/2/TLS to Apple. No public inbound agent-control endpoint follows from this requirement.
- [Background pushes](https://developer.apple.com/documentation/usernotifications/pushing-background-updates-to-your-app): delivery is not guaranteed and can be throttled; this is not a persistent background socket or reliable alert mechanism.

The two dynamic APNs pages failed readable extraction; their official DocC JSON was retrieved from `https://developer.apple.com/tutorials/data/documentation/usernotifications/` with the corresponding `.json` filenames. No live APNs registration, build signing, push or Telegram delivery was tested.
