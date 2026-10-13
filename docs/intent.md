# Cappuccino intent

Confirmed by the owner on 2026-10-05 after grilling; repository skeleton initialization was authorized separately. The owner subsequently selected iPhone first, native Android next, then Mac-specific UX. The owner later approved buildable Android/iOS/macOS skeletons in one PR. On 2026-10-13 the owner dropped the macOS app: Herdr covers the desktop, so `apps/macos/` and the `CappuccinoMac` target are removed; the Swift package keeps `.macOS(.v26)` only so core tests run on the Mac host. Read-only connection, prompts, controls and the transcript UI are implemented. Delivery receipts, typed approvals, grants and alerts are not. Physical-device installation and deployment need separate owner approval.

## Outcome

Attach a native app to long-running agents already hosted by remote Herdr machines. Catch up with their conversations, reply and resolve tool approvals without using a terminal UI.

Herdr and Pi own process/session lifetime. Opening the app never launches, replaces or restarts an agent; closing it or losing connectivity never terminates one. The owner may explicitly Stop a run (Esc) or start a new session with the agent's own command (`/new`, which Pi, Claude Code and Agy all accept) from the phone; the app never does either on its own, and New session is offered only between runs (owner decision 2026-10-12). Any slash command typed in the composer runs only after the owner confirms it, because commands such as `/model` drop the agent's prompt cache (owner decision 2026-10-13). Reattachment recovers the same session's active branch and pending requests. Trusted Pi integration setup may use an explicit idle `/reload` once; reloading is not part of normal app attachment.

## First prototype

- Personal use, Pi first, mostly coding tasks. Prove the existing-session bridge and connected iPhone journey first; Android follows before Mac-specific UX. No Mac app is planned (2026-10-13).
- Manually register machines on private tailnet connections; the owner selected a no-auth bridge with tailnet membership as the network boundary on 2026-10-06. No public ingress or cloud relay.
- Direct agent conversations and one cross-machine attention inbox; no coordinating assistant.
- Full history of the selected session's active branch, with code blocks and expandable tool details.
- Explicit Nudge (steer at the next boundary) and Follow-up (after task completion).
- A Cappuccino-owned gate for shell commands, writes and unknown tools; known reads may proceed. The gate is not a sandbox and cannot retroactively gate calls already executing.
- The phone and local terminal can resolve the same pending request. First valid decision wins; stale or conflicting decisions fail visibly. Pending requests wait without default allow when the phone is offline.
- Optional reusable grants match exact tool, arguments and cwd in the same session. Manual revocation is available. Ordinary phone reconnect preserves grants; integration reload, active-branch change or session replacement revokes them. Matching granted invocations are explicit prior authorization, not automatic approval of pending requests.
- Generic Telegram alerts for approvals, failures and task completion. No sensitive previews, task contents or Telegram approval controls.
- No paid Apple Developer enrollment for now. Weekly free-signing reprovisioning is acceptable for the prototype, not a long-term distribution decision. Native APNs is deferred.

## Excluded

Agent spawning/termination, arbitrary other extensions' terminal dialogs, remote terminal UI, file/Git browser, automatic machine discovery, archived-session/branch-navigation UI, voice, a coordinator bot, public hosting and native APNs are outside the first prototype.

## Delivery boundary

Prompt text and the stop and new-session controls reach the pane without receipts. Delivery receipts, approval correlation, grants and Telegram alerts are not implemented. The bridge is a separate phone-transport facade over machine-local Herdr APIs, not an agent runtime. Herdr's PTY prompt acknowledgement does not prove Pi accepted a Nudge or Follow-up; those semantics and any receipt contract need explicit owner-reconciled acceptance before implementation. Never relax session continuity or fail-closed decisions.

[Discovery](research/discovery.md) holds the earlier Apple/bridge research and its limits. [Plan](plan.md) defines the current slices and platform order; [Android plan](https://github.com/azusachino/cappuccino/blob/35e72ff/docs/android-plan.md) defines the later native client without changing these safety requirements.
