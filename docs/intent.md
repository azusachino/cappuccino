# Cappuccino intent

Confirmed by the owner on 2026-10-05 after grilling. Initial scope is a personal iPhone prototype, followed by macOS. The owner authorized repository skeleton initialization separately after confirming this intent.

## Outcome

Attach a native app to long-running agents already hosted by remote Herdr machines. Catch up with their conversations, reply and resolve tool approvals without using a terminal UI.

Herdr and Pi own process/session lifetime. Opening the app never launches, replaces or restarts an agent; closing it or losing connectivity never terminates one. Reattachment recovers the same session's active branch and pending requests. Trusted Pi integration setup may use an explicit idle `/reload` once; reloading is not part of normal app attachment.

## First prototype

- Personal use, Pi first, mostly coding tasks, iPhone first; macOS follows.
- Manually register and authenticate machines on private LAN/tailnet connections. No public ingress or cloud relay.
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

The skeleton is not a working remote client. Attachment, bridge transport, authentication, approval correlation, grants and Telegram delivery need separate verified slices. Implementation choices cannot relax session continuity or fail-closed decisions.

[Discovery](discovery.md) holds primary-source findings and limits. [Plan](plan.md) defines the skeleton and the bounded feasibility spike before the full client.
