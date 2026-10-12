# Canonical offline history checkpoint

## Source and gates

Implementation: `946e25c`, pushed on `feat/native-pi-rendering`. Progress record: `85e7a25`. Scope and contract: [offline conversation identity](../../design/offline-conversation-identity.md); ordering and remaining criteria: [native UX plan](../../plan/native-ux-improvement-session.md).

- Local full bridge gate passed after the recorded parallel integration failure, focused diagnostic and owned-machine startup correction. Mandatory resource soak and release example completed; the focused test was not substituted for the full gate.
- Android and Markdown gates passed after canonical ID/response machine binding and schema-2 snapshot changes. These include hermetic pane reuse/move, machine isolation, legacy readability without saving, reconstruction, read-only actions and discovery-isolation regressions.
- The Mini build used an isolated source archive of `946e25c`, not a reset/switched vendor checkout. Its full Linux bridge gate, mandatory soak and release binary build passed before replacement.

## Authorized rollout

The owner explicitly requested pushing, updating the old Mini bridge and reinstalling Android. No merge, proxy/cluster changes or remote-agent operations were included.

Installed Linux binary SHA256: `82b23c8e3193ec6f6c364df1e93de4c6b2d1b80ff20e907a23168c3a34c5d0ec`.
Previous binary SHA256: `c3c3bc3750b3e654bd5f15242a79cda4fa8e1941199be35b5552b926dd48250c`.
A backup named `cappuccino-bridge.previous-946e25c` remains beside the installed executable on Mini. The actual plugin state root was verified; stop/start used the authenticated bridge lifecycle with auto-apply disabled and the existing Herdr socket. Only the bridge was restarted. Machine identity, daemon/agents, relay and configuration were not replaced.

Passive loopback and existing HTTPS-route checks confirmed protocol/plugin identity, unchanged expected machine identity and a canonical response carrying the same machine identity plus a supported opaque conversation ID. No raw transcript or private machine address is included here. Package version remains `0.1.0`; source revision/artifact hashes, not a version bump, identify this update.

Android APK SHA256: `3a752804220093bc2cd940f13a36a839954383de3d64e72558d9f4de99df210b`. `adb install -r` succeeded on the authorized OnePlus and MainActivity opened. No uninstall, app-data clearing or device/network-setting change occurred.

## Executed native scope

A passive phone journey, not just compilation:

1. Open Mini's canonical Agy reader. Settings subsequently showed one saved conversation with 346 kB of saved storage, separate from temporary cache's 0 B.
2. Force-stop/relaunch the app after confirming an empty composer; no phone reboot or system connectivity change. Saved storage remained visible.
3. Disconnect only Mini in the client; the other machine stayed connected. Open the saved picker and its Mini record.
4. Observe saved capture time and `Read-only`, unknown current agent status, no live-follow control and a disabled native composer. Cached content was displayed after reconstruction while Mini was locally disconnected.
5. Restore both connected machine branches through the client's named Refresh action. No send, answer, command execution or remote session action was used.

Private raw XML/screenshots stay in workstation scratch `.tmp/cappuccino-mini-update-946e25c/`, not this repository. An initial coordinate-driven batch failed its expected status-label assertion and subsequent navigation landed in machine information; it is not successful offline evidence. That raw attempt was retained. The later stepwise run used observed semantics (`Not connected`), stop-on-failure assertions and confirmed the saved-reader behavior above. This does not establish that the older popup/flashing defect is resolved.

## Not established

- Full native regression suite, historical four display/tail failures, phone hardware reboot, complete light/dark/large-font matrix and owner visual acceptance.
- Independent fresh-agent verification; execution remained direct under the owner's no-delegation instruction. Do not label this tier-2 work independently verified or the whole task DONE.
- Complete stale in-flight persistence coverage, measured highlighting budgets, Apple consuming parity, independent Attention or live delivery/approval readiness.
- Mac bridge rollout. Older bridges remain readable but cannot save new snapshots without canonical identity support.
