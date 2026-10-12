# iOS UX parity and Liquid Glass

Status: core identity, profiles and independent connections are implemented and gated; consuming shell/reader parity and device acceptance remain pending. The [native UX continuation plan](native-ux-improvement-session.md#current-continuation-order-and-evidence) owns cross-platform priority; [Paseo study](../design/paseo-source-study.md) informs native selection/tokenization and replicas without changing the stack.

## Authorized outcome

Bring the iOS companion up to the approved Android UX using native SwiftUI and Liquid Glass on supported systems. No disconnected-phone interaction, installation, live prompts/approvals or remote agent-session changes. Shared Apple code also consumes macOS; preserve both platform gates. Work directly, without Asobi/delegation as requested. Commit each green slice; do not claim full parity from compilation.

Historical entry baseline: Android checkpoint `a6b337d`; existing Apple `make validate-apple` passed with Xcode 27 / Swift 6.4 using the documented command-scoped DEVELOPER_DIR, without changing global selection. Baseline covers core tests and iOS/macOS builds, not a consuming UI journey.

## Actual gaps

- ContentView has Chats/Attention skeleton tabs and a single-machine Machines model. It must become Machines/Attention/Settings with independently owned profile connections and visible per-machine failures.
- Fail-closed protocol/UUID/session ownership is established in core (`083bea7`), with persisted profiles (`610eae7`) and independent connections (`cabbc9c`). Wire those foundations into the consuming UI rather than duplicating them. Core gates do not establish runtime multi-machine presentation.
- Reader is primarily pane-line transcript presentation, not typed canonical conversation/message/tool parity. Fix URL component encoding and validate selected-session response ownership before adding canonical readers.
- Attention is not end-to-end verified. Android's existing selected-stream-only Attention also loses pending state on navigation; do not copy this defect or claim all agents healthy.
- Current owner-selected theme is Catppuccin Latte/Mocha, superseding coffee/dynamic/GitHub palettes. A Mihon-style selectable palette chooser remains deferred, not shipped or cancelled. Native Apple chrome remains platform-specific; do not copy Material widgets or copyleft implementation/assets.
- Native code rendering/tokenizer and paragraph selection remain unwired. Paseo's native text adapters are references, not evidence for our SwiftUI runtime; its WebView Mermaid path stays excluded.
- Offline history parity follows the Android identity/read-only/retention contract after Android acceptance. Cached working/approval state must never become current actions.

## Ordered implementation

1. Preserve the gated identity/profile/connection foundations and their hermetic rejection/isolation tests.
2. Consume independent persisted profiles and connection jobs/generations/manual disconnect in the UI; same locator on two machines remains distinct.
3. Machines/Attention/Settings shell, inline Refresh/Disconnect, text Details/Rename/Remove, useful About and device-local temporary-cache controls. Preserve drafts/preferences and remote sessions.
4. Typed provider-neutral canonical conversation, bounded recent history, tool calls/results with source timestamps and explicit errors; passive Markdown and full readers, no tool/error rows in chat.
5. Per-conversation drafts, scroll/disclosures and hard follow preference; tool-only updates do not scroll chat. Use the contextual older-history boundary, not an intrusive Snackbar or permanent technical-limit header. Full readers use compact mobile headings; missing historical tool results do not imply a queue.
6. Foreground machine-scoped attention monitoring with actual adapter capability/readiness, correlated stale-answer guards and hermetic action tests. Never test answers against owner sessions.
7. Liquid Glass for native navigation/tab/toolbar and the combined floating Tools/Jump control on supported OS versions. Use material fallback where required; opaque readable message surfaces, reduced transparency/motion and accessible controls remain essential.

## Gates and acceptance

Each slice: `env DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer make check-apple`; stable platform checkpoint: `make validate-apple` under the same scoped environment. Documentation: `make md-check`. Required consuming UI journey uses an explicitly task-owned iOS simulator via `make ui-test DESTINATION=...`; do not implicitly restart existing devices. App deployment target remains iOS 17, so gate Liquid Glass APIs with OS availability rather than raising the minimum silently. No new dependencies or diagnostic suppressions.

Real two-machine usage, canonical session identity, passive content, follow/reading retention, offline isolation and prompt capability boundaries require separate runtime evidence. Existing Android native failures remain unresolved. Independent acceptance review is not established by this direct baseline run.

## Progress

Identity (`083bea7`), profile storage (`610eae7`) and independent connections (`cabbc9c`) passed their owning Apple gates. The corrected offline-error expectation was rerun successfully; no skipped test or consuming UI acceptance is implied. Next Apple slice is the independent-machine shell after current Android offline/highlighting work. Canonical reader, highlighting/selection, offline parity, Attention and Liquid Glass remain pending.
