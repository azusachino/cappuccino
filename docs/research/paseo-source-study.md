# Paseo source study for Cappuccino

## Scope and evidence

The owner requested studying [Paseo][repo] during native-reader and offline-history work. This is a direct, read-only source inspection of commit `166aabd57b3f6c69f6a6f65b621aba167e7e3be8`, not an installed-app review or a proposal to replace Herdr, the bridge, Compose or SwiftUI. No Paseo daemon, agent, plugin or installer was run. Source downloads and the repository tree are scratch under `.tmp/cappuccino-paseo-study/` in the workstation.

The repository's [license][license] is Apache-2.0. Reusing implementation still requires preserving applicable attribution/notices and checking the licenses of bundled parsers and dependencies. The inspection did not complete that dependency audit; no third-party implementation was copied into Cappuccino.

## What is worth learning

### Native code rendering separates parsing from appearance

[Paseo's highlighter][highlighter] uses Lezer parsers and `highlightTree` to produce text tokens with semantic styles. [The parser registry][parsers] combines Lezer grammars, CodeMirror stream languages for Swift/Dart, and local grammars including Nix. This is not a Highlightr, Prism or Shiki integration.

[Code fences][code-block] render tokens through `MarkdownTextSpan`. [Android][android-text] uses selectable React Native `Text`; [iOS][ios-text] uses `UITextView` for selectable paragraphs/spans and plain `Text` where selection is inappropriate. Their ordinary code-text path does not need a WebView. iOS paragraph-level selection can cross inline spans, while Android explicitly documents sibling text selection boundaries. The iOS adapter also avoids creating a selectable UIKit view with a window-level recognizer for every table cell.

[The shared highlight cache][highlight-cache] stores theme-independent tokens, applies colors at rendering, limits inputs to 100,000 characters and retains 200 entries. Unsupported, oversized or failed parsing falls back to complete plain monospace text. These are upstream constants, not accepted Cappuccino performance thresholds. An entry limit alone is not a memory-byte budget.

**Cappuccino application:** keep native annotated text, preserve backing source and unknown-language fallback; share semantic tokenization across light/dark themes; add measured input/cache budgets. A Lezer adapter is another candidate for Apple JavaScriptCore, not a proven SwiftUI integration. Do not introduce a JavaScript runtime into Android merely because Expo already has one.

The inspected registry does **not** include Bash/sh, Fish or Kotlin. It is therefore not a drop-in replacement for our existing shell highlighting. Paseo's fence rendering/copy path also strips some trailing newlines; do not copy that behavior into our source-preserving backing text. Safe command-copy behavior would need a separate, explicit policy.

### Saved content is a replica, not proof of synchronization

[Paseo's replica cache][replica-cache] validates persisted payloads, retains up to 50 timeline items per cached timeline and has a default 32 MiB **logical payload** budget. That budget is not proof of a hard bound on the SQLite database/WAL's physical size. [Native storage][native-store] uses SQLite; [the row store][sql-store] keys records by `(server_id, kind, id)` and performs batched writes transactionally.

[Cached timeline preparation][viewed-sync] reads persisted rows, preserves any newer live head and explicitly applies them with `synchronized: false`. Display-only cached rows claim no coverage over live history. Its timeline model distinguishes cold, painted and synchronized content.

**Cappuccino application:** private bounded snapshots, atomic writes, verified machine isolation, a visible saved timestamp, and an explicit read-only saved view. Temporary cache cleanup must not delete saved conversations or remote sessions. Existing persistence foundation `4c430fd` is gated; subsequent VM/UI wiring is not device-accepted.

Paseo's cached agent serialization clears permissions but can retain an open turn. Do **not** treat that retained turn as permission to display current work, enable sending or answer prompts. Cappuccino's offline agents deliberately use unknown status and `working = false`.

Paseo's stable agent ID is also not interchangeable with a reusable Herdr pane locator. Our current machine/locator snapshot key must not be advertised as an immutable canonical-session ID. Any stronger conversation-generation key must be grounded in the bridge's actual session binding before cached and live histories can be merged.

### Tool liveness is explicit

[Turn liveness][liveness] is a state machine driven by snapshots, stream open/close and turn identity; an old close event cannot close a different identified turn. [Pending tool-detail logic][tool-state] requires an explicit running/executing status, no error and absent meaningful detail. Missing historical output by itself is insufficient.

[The session model][sessions] keeps permissions and attention metadata per agent, under a server-scoped session, rather than only under the currently displayed transcript.

**Cappuccino application:** retain the recorded-tools count and `Result not recorded` semantics from `8247404`. Future active indicators require live evidence. Attention must eventually cover independently owned agent prompts without navigating away and losing them; this inspection does not repair the known selected-stream-only defect or authorize prompt actions.

### Resume by cursor, not by replacing the whole conversation

[Paseo's fetch plans][sync-plan] use `(epoch, seq)` cursors for tail, after and before requests. A partial catch-up is not authoritative until no newer rows remain; errors never establish successful synchronization. The viewed-timeline owner separates replica preparation, subscription and canonical updates.

**Cappuccino application:** preserve reading position and row identity through reconnection. Cursor-based catch-up could reduce repeated full-history refreshes and flashing, but requires bridge support and a separately specified contract. Do not bolt a client-side cursor onto the existing snapshot API and imply equivalent guarantees.

## What not to import

- Paseo is an Expo/React Native client of its own agent-orchestrating daemon. Cappuccino remains a native companion for existing sessions; editor, terminal, orchestration and plugin execution are outside scope.
- [Its native Mermaid host][mermaid] explicitly uses `react-native-webview`. Our no-WebView rule still applies.
- [Its heading styles][markdown-styles] use scaled heading tiers. They are not a reason to undo our owner-approved compact mobile headings.
- Source files and browser-test names are not executed Android/iOS journeys, visual acceptance, or performance measurements. None of Paseo's tests were run here.

## Follow-through order

1. Finish offline-history wiring with fresh-identity, stale-selection, restart, read-only-action and preservation tests; prove the native journey before installing it as complete.
2. Add measured native highlighting bounds and a theme-independent token cache. Keep Bash coverage and exact source text.
3. Use the platform-specific selection lessons when wiring the Apple reader; separately test an Apple tokenizer adapter.
4. Specify canonical conversation identity, incremental history and independently scoped Attention before changing bridge contracts.

[repo]: https://github.com/getpaseo/paseo
[license]: https://github.com/getpaseo/paseo/blob/166aabd57b3f6c69f6a6f65b621aba167e7e3be8/LICENSE
[highlighter]: https://github.com/getpaseo/paseo/blob/166aabd57b3f6c69f6a6f65b621aba167e7e3be8/packages/highlight/src/highlighter.ts
[parsers]: https://github.com/getpaseo/paseo/blob/166aabd57b3f6c69f6a6f65b621aba167e7e3be8/packages/highlight/src/parsers.ts
[code-block]: https://github.com/getpaseo/paseo/blob/166aabd57b3f6c69f6a6f65b621aba167e7e3be8/packages/app/src/components/highlighted-code-block.tsx
[android-text]: https://github.com/getpaseo/paseo/blob/166aabd57b3f6c69f6a6f65b621aba167e7e3be8/packages/app/src/components/markdown-text.android.tsx
[ios-text]: https://github.com/getpaseo/paseo/blob/166aabd57b3f6c69f6a6f65b621aba167e7e3be8/packages/app/src/components/markdown-text.ios.tsx
[highlight-cache]: https://github.com/getpaseo/paseo/blob/166aabd57b3f6c69f6a6f65b621aba167e7e3be8/packages/app/src/utils/highlight-cache.ts
[replica-cache]: https://github.com/getpaseo/paseo/blob/166aabd57b3f6c69f6a6f65b621aba167e7e3be8/packages/app/src/runtime/replica-cache/index.ts
[native-store]: https://github.com/getpaseo/paseo/blob/166aabd57b3f6c69f6a6f65b621aba167e7e3be8/packages/app/src/runtime/replica-cache/row-store.native.ts
[sql-store]: https://github.com/getpaseo/paseo/blob/166aabd57b3f6c69f6a6f65b621aba167e7e3be8/packages/app/src/runtime/replica-cache/row-store-sqlite.ts
[viewed-sync]: https://github.com/getpaseo/paseo/blob/166aabd57b3f6c69f6a6f65b621aba167e7e3be8/packages/app/src/timeline/viewed-timeline-sync.ts
[liveness]: https://github.com/getpaseo/paseo/blob/166aabd57b3f6c69f6a6f65b621aba167e7e3be8/packages/app/src/timeline/turn-liveness.ts
[tool-state]: https://github.com/getpaseo/paseo/blob/166aabd57b3f6c69f6a6f65b621aba167e7e3be8/packages/app/src/utils/tool-call-detail-state.ts
[sessions]: https://github.com/getpaseo/paseo/blob/166aabd57b3f6c69f6a6f65b621aba167e7e3be8/packages/app/src/stores/session-store.ts
[sync-plan]: https://github.com/getpaseo/paseo/blob/166aabd57b3f6c69f6a6f65b621aba167e7e3be8/packages/app/src/timeline/timeline-sync-plan.ts
[mermaid]: https://github.com/getpaseo/paseo/blob/166aabd57b3f6c69f6a6f65b621aba167e7e3be8/packages/app/src/components/markdown/fence/mermaid/host.native.tsx
[markdown-styles]: https://github.com/getpaseo/paseo/blob/166aabd57b3f6c69f6a6f65b621aba167e7e3be8/packages/app/src/styles/markdown-styles.ts
