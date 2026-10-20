# Plan

[Intent](intent.md) is confirmed. The owner's order is existing-session bridge
feasibility, then the connected iPhone app, then the native Android app. There is
no macOS app: Herdr covers the desktop (2026-10-13). Live task state belongs in
Asobi. Detailed acceptance belongs in the owning issue or pull request.

## Current slices

- **#16 plugin bridge:** the Herdr-linked Rust transport in `services/bridge`. It
  serves the agent catalog, conversation lookup, the pane-line stream, and the
  owner controls: Stop, New session, prompts and answers. The bridge is the
  transport for both apps.
- **#7 transcript UI:** implemented on iPhone and Android. Canonical live Pi
  transcript parity against a running session has not been verified.
- **#12 Android connected client:** implemented against the bridge. Physical-phone
  acceptance over the tailnet is pending.
- **#8 delivery:** not implemented as Nudge or Follow-up. Herdr's prompt write is
  the only delivery path, and it returns no Pi queue receipt. Reconcile the receipt
  contract with the owner before building it.
- **#9 approvals and grants:** approvals are answered from the screen today. The
  typed gate, reusable grants and pending-request handling come after delivery.

## Bridge and Android reliability follow-through

The owner's 2026-10-20 assessment is saved in
[bridge and Android agent-product research](research/bridge-android-agent-products.md).
The proposed [multi-step reliability plan](design/bridge-android-reliability-plan.md)
turns those findings into dependency-ordered slices with acceptance tests, owning
gates and explicit feasibility/approval checkpoints. Start with recoverable sends
and restored session identity, and prove active-branch authority before claiming
canonical parity. Typed delivery and approvals continue to belong to #8 and #9;
reconcile their older terminology and acceptance with current intent first.

The initial document was planning, not implementation or deployment approval.
The owner subsequently authorized bounded implementation with Pi subagents;
[the implementation handoff](design/bridge-android-reliability-handoff.md) records
the verified A1/A2 Android checkpoints, B1 findings and remaining slices. The
latest Android journey passes 51 synthetic tests; existing live-agent and
physical-device limits remain. Bridge transport hardening is next while the
separately approved disposable D1 spike investigates the same-process read seam.

## Next steps

1. Verify canonical transcripts for Pi, Claude Code and Agy against a
   task-owned disposable session, including its active branch.
2. Run the connected iPhone journey end to end on a physical device with one
   paired machine, then broaden to several machines with per-machine failure
   states.
3. Settle the delivery receipt contract (#8), then implement it behind the
   existing fail-closed rules.
4. Build the typed approval gate and grants (#9) after delivery is accepted.
5. Add Telegram attention alerts as a separate, later slice: generic attention
   only, no code or prompt payload, no approval buttons, and no credentials in
   source or tests.

## Decisions in force

- The bridge is a Herdr plugin, not a companion daemon, and not an SSH-exec
  client. Herdr and Pi own agent and session lifetime. A client attaches and
  detaches without starting, stopping or replacing an agent.
- The bridge serves no authentication. The tailnet is the boundary, and the
  bridge refuses non-loopback binds. Public ingress is out of scope.
- Native clients share behavior through fixtures and the
  [behavior spec](behavior-spec.md), not through a shared runtime or SDK.
- Reading an agent's own session files is the canonical history source. Screen
  scrollback is a labeled, read-only fallback. Neither may stand in for the
  other.

## Verification and authority

Skeleton checks prove source, build and shell behavior, not remote-agent control.
The tests use no owner session, credential or real approval. Physical iPhone
installation, seven-day re-signing, background alerts, real approvals and live
connectivity stay unverified until their owning slices run and record evidence.

Use fresh independent verification at a stable acceptance checkpoint, under the
workstation's tier-1 policy. Source and gates must be green before that review.
Repo setup does not authorize server changes, real-machine integration
installation, pushes to the workstation's main branch, releases or deployment.

### Optional debugging instrument

The owner suggested vphone-cli. Its capabilities and host risks are recorded in
[the discovery notes](research/discovery.md#optional-debugging-vphone-cli). Keep it
read-only and optional. Do not add a dependency or replace the Simulator gate. A
guest experiment needs a separately approved host, security and storage plan, a
task-owned VM, and an iPhoneOS build and install path. Patched-guest results do not
establish normal device signing or remote-agent continuity.
