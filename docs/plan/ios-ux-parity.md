# iOS UX parity and Liquid Glass

## Authorized outcome

Bring the iOS companion up to the approved Android UX using native SwiftUI and Liquid Glass on supported systems. No disconnected-phone interaction, installation, live prompts/approvals or remote agent-session changes. Shared Apple code also consumes macOS; preserve both platform gates. Work directly, without Asobi/delegation as requested. Commit each green slice; do not claim full parity from compilation.

Baseline: Android checkpoint `a6b337d`; existing Apple `make validate-apple` passed with Xcode 27 / Swift 6.4 using the documented command-scoped DEVELOPER_DIR, without changing global selection. Baseline covers core tests and iOS/macOS builds, not a consuming UI journey.

## Actual gaps

- ContentView has Chats/Attention skeleton tabs and a single-machine Machines model. It must become Machines/Attention/Settings with independently owned profile connections and visible per-machine failures.
- Bridge pairing currently accepts any nonempty string ID without validating protocol/plugin identity; discovery accepts missing machine/session fields. Establish fail-closed UUID ownership before multi-machine presentation.
- Reader is primarily pane-line transcript presentation, not typed canonical conversation/message/tool parity. Fix URL component encoding and validate selected-session response ownership before adding canonical readers.
- Attention is not end-to-end verified. Android's existing selected-stream-only Attention also loses pending state on navigation; do not copy this defect or claim all agents healthy.
- Theme requirement now means Mihon-style selectable palette choices, superseding the brief dynamic-color experiment and fixed-only coffee choice. Native Apple chrome remains platform-specific; do not copy Material widgets or licensed Mihon implementation/assets.

## Ordered implementation

1. Verified bridge identity and strict owned agent discovery, with hermetic rejection tests.
2. Independent persisted machine profiles, isolated jobs/generations/manual disconnect and stale-result guards; same locator on two machines remains distinct.
3. Machines/Attention/Settings shell, inline Refresh/Disconnect, text Details/Rename/Remove, useful About and device-local temporary-cache controls. Preserve drafts/preferences and remote sessions.
4. Typed provider-neutral canonical conversation, bounded recent history, tool calls/results with source timestamps and explicit errors; passive Markdown and full readers, no tool/error rows in chat.
5. Per-conversation drafts, scroll/disclosures and hard follow preference; tool-only updates do not scroll chat. Omission notice is transient once per conversation, not a permanent header.
6. Foreground machine-scoped attention monitoring with actual adapter capability/readiness, correlated stale-answer guards and hermetic action tests. Never test answers against owner sessions.
7. Liquid Glass for native navigation/tab/toolbar and the combined floating Tools/Jump control on supported OS versions. Use material fallback where required; opaque readable message surfaces, reduced transparency/motion and accessible controls remain essential.

## Gates and acceptance

Each slice: `env DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer make check-apple`; stable platform checkpoint: `make validate-apple` under the same scoped environment. Documentation: `make md-check`. Required consuming UI journey uses an explicitly task-owned iOS simulator via `make ui-test DESTINATION=...`; do not implicitly restart existing devices. App deployment target remains iOS 17, so gate Liquid Glass APIs with OS availability rather than raising the minimum silently. No new dependencies or diagnostic suppressions.

Real two-machine usage, canonical session identity, passive content, follow/reading retention, offline isolation and prompt capability boundaries require separate runtime evidence. Existing Android native failures remain unresolved. Independent acceptance review is not established by this direct baseline run.

## Progress

Apple baseline gates passed. Identity foundation is the first implementation slice; remaining parity and rendered acceptance are pending.
