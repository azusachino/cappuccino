# Native Pi-paired agent workspace — primary-source research

**Status:** research only (doc-writer scope; no implementation, no deps added, no protocol changes, no runtime/device probes).
**Repo baseline:** `vendor/cappuccino` branch `research/native-pi-agent-workspace` @ `81ee365` (clean). PR 29 remains open and unmerged; nothing here assumes it landed.
**Sources:** installed Pi 1.1.0 package docs (canonical public reference: <https://pi.dev/docs/latest> — `sdk`, `rpc-commands`, `json` event stream, `message-types`, `tui`); repo source read with file/line citations below; GitHub primary sources fetched 2026-10 (repo metadata, LICENSE, README per candidate). Versions are cited per source; nothing here asserts live runtime parity — the running agent/bridge was never exercised.

## 1. Scope

Native Android with Pi feature parity for **agent roster + selected workspace**: reuse verified Compose libraries where they fit, keep minimal glue only for the existing bridge protocol adaptation, and identify what no library can provide. Not terminal embedding, not TUI implementation, not a Web view.

## 2. Pi's authoritative interaction surface (installed 1.1.0 docs)

- **Transport for custom clients:** RPC mode (`pi --mode rpc`), strict JSONL over stdin/stdout; commands (`prompt` with required `streamingBehavior` when streaming, `steer`, `follow_up`, `abort`, `clear_queue`, `get_state`, `get_messages`, `get_entries` with a durable `since` entry-id cursor incl. pre-compaction/abandoned branches, `get_tree`, model/thinking commands, `compact`, `set_auto_retry`); events (`message_update` delta-only streaming with authoritative `text_end`/`toolcall_end`/`message_end` replacements, `tool_execution_start/update/end`, `agent_end` vs `agent_settled`). Pi TUI itself is a TypeScript terminal widget framework — **not** an Android library and explicitly not embeddable here (owner constraint).
- **Content model:** `TextContent`, `ThinkingContent` (may be `redacted`), `ToolCall` + `ToolResultMessage` (with `details`, `isError`, `nestedCalls`), images as base64 `ImageContent`, `Usage` incl. `contextUsage` via `get_session_stats`.
- **Queues:** `steer` (after current turn's tool calls) and `follow_up` (after run); `pendingMessageCount` in `get_state`; `clear_queue` for interactive cancel.
- **Completion truth:** `agent_end` ends one low-level run; retries/compaction/queues may continue. Only `agent_settled` means settled; a truthful status UI keys on settled and surfaces `willRetry`.
- **Session identity:** append-only entry tree; `get_tree`/`fork`/`clone`/`switch_session` exist but are desktop-grade operations.
- **Slash/commands:** `get_commands` lists extension commands, prompt templates, skills; built-in TUI commands are *not* available over RPC.
- **Herdr vs Pi:** Pi's TUI shows the **selected session** only. The **agent roster** (all panes/machines, statuses) is Herdr's surface, consumed via the Cappuccino bridge — distinct responsibilities, not one "clone the Pi TUI" effort.

## 3. Current Cappuccino reality (source, read-only, file:line)

- **Conversation history — canonical Pi JSONL is already the primary source.** `GET /api/agents/{sessionId}/conversation` (`services/bridge/src/routes.rs` `agent_conversation`, ~:270–305) resolves the Pi session transcript via `transcript::resolve_session_transcript`, parses it with `conversation::parse_pi_transcript`, and returns `source: "canonical_log"`. Scrollback (`herdr::pane_read_recent`, `conversation::parse_scrollback_turns`, `source: "scrollback"`) is an **explicit labeled fallback** only when no canonical transcript resolves (routes.rs ~:315–323). Transcript resolution is fail-closed: paths outside the canonical store, symlink escapes, and non-`.jsonl` files answer `None` (`services/bridge/src/transcript.rs:62–79` and module doc :1–10). Runtime has not been exercised against a live agent; "implemented" is a source-level statement, not a live-parity claim.
- **Live WS stream is pane rows, not turn chunks.** `grep turn_chunk services/bridge/src` → 0 hits. Actual events (routes.rs ~:139–249): `stream_open`, `agent_status`, `prompt_request`, `prompt_resolved`, `stream_reset`, `entries` (ring-buffer pane rows). The bridge contract's `turn_chunk` (`docs/bridge-v2-contract.md`:238,366) is unimplemented; Android `BridgeProtocol.kt:88–103,281–309` matches the implemented events only.
- **Prompt delivery and approvals ARE implemented, screen-parsed.** `POST` prompt → `herdr::agent_prompt` (routes.rs `submit_agent_prompt` ~:349–389). Approval/ask-question cards are detected from visible pane text (`prompt::parse_prompt_from_screen`, `services/bridge/src/prompt.rs:1–6`; detection call sites routes.rs ~:206, :406) and answered via `herdr::pane_send_keys` with cancel (`esc`) support (~:426–452, `prompt_answered` events). The design is PTY-screen parsing with a fail-closed signature check per the contract — **not** canonical Pi approval/delivery events. Genuinely missing: delivery receipts, queue visibility (steer/follow-up/pendingMessageCount), and canonical Pi-RPC delivery/approval paths.
- **Android rendering:** `CappuccinoShell.kt` (1,334 lines) renders both sources — REST `ConversationTurnRow` for canonical turns (:1266, used :373) and live pane rows through `TranscriptParser.parseLine` (:826, :833, :843, :867, :890). `MarkdownText.kt` (187 lines) is a hand-rolled `org.commonmark` → `AnnotatedString` renderer. `BridgeProtocol.kt` models `ConversationTurn` with `Text/Thinking/ToolCall` parts (:122–136).
- **Bridge gaps that remain (contract, not UI-library):** no `turn_chunk` streaming on the WS, no receipts/queue modeling, no model/thinking-level/context-usage surface, no compaction/retry events, no session-tree identity, no abort/cancel path, no canonical Pi approval events.

## 4. Candidate libraries (primary-source verified, bounded 4-candidate comparison)

This is a bounded comparison of four serious candidates plus standard M3 composition — **not an exhaustive ecosystem survey**, and no claim is made that no other kit exists.

| Candidate | License / terms | Version / maintenance | Verdict |
| --- | --- | --- | --- |
| **mikepenz/multiplatform-markdown-renderer** | Apache-2.0 (LICENSE verified) | v0.45.0 (2026-08-28, latest release); repo pushed 2026-10-07; 1.1k stars | **Preferred candidate, pending proof-of-fit and owner approval** for workspace markdown. Modules: core (Material-free), `-m3` (our stack), `-m2`, `-code` (syntax highlighting), `coil2`/`coil3` image transformers (opt-in). API: `Markdown`, `rememberMarkdownState` (async parse), `rememberStreamingMarkdownState()` — appends chunks and re-parses only the unstable tail (README streaming section). Kotlin Multiplatform (Android/iOS/desktop); **KMP reuse is a future-Apple-code path only — not a SwiftUI drop-in.** |
| GetStream/stream-chat-android-ai | Proprietary Stream.IO "Source Code License Agreement" (LICENSE verified; SPDX `NOASSERTION`): requires current-customer status and non-competitor status. Commercial terms beyond the LICENSE head: **unknown — not assessed.** | v0.3.0 (2026-06-17); 25 stars; very young | **Not appropriate without an explicit licensing/commercial decision.** Verified coupling: components are designed around Stream's chat state/backend ecosystem (README "custom backend" guidance; Maker Account references). UI quality unassessed beyond README; small adoption base is a verified fact. |
| halilozercan/compose-richtext | Apache-2.0 | no GitHub release tags (artifacts via Maven Central); pushed 2026-06-08; ~1k stars | **Fallback only.** Compose-native rich text/markdown, but no verified streaming-tail API equivalent; adoption would need its own verification pass. |
| jeziellago/compose-markdown | MIT | 0.7.2 (2026-04-27) | **Not preferred.** Its exact composition/API surface was not deeply inspected this pass; what is verified is that it is a wrapper-style API without a documented streaming model. If re-examined, that inspection must be primary-source. |
| Roster | — | — | **Standard Material 3 composition** (`LazyColumn`, adaptive/navigation-suite layouts) is the reasonable choice: the roster's needs are native lists/navigation, and the only end-to-end "AI chat kit" examined (Stream) is licensing-blocked. This is a composition judgment, not a claim that no suitable library exists anywhere. |

Web experience references (pi-web-ui, assistant-ui, React AI Elements) are TypeScript/web — experience reference only; **no native drop-in was found within this bounded search**, and the no-WebView constraint rules out the web kits regardless.

## 5. Feature map: Pi behavior → Cappuccino today (source-cited) → plan

| Pi feature (source) | Cappuccino now | Bridge data today | Plan |
| --- | --- | --- | --- |
| Canonical history (`get_entries` tree/cursor) | **Served**: REST conversation, `canonical_log` primary, labeled scrollback fallback (routes.rs ~:270–323) | implemented (source-level; runtime unexercised) | keep; renderer swap does not touch this |
| Streaming markdown (`text_delta`, replace-on-`text_end`) | live WS is pane rows via TranscriptParser (no `turn_chunk` exists) | **missing** — contract `turn_chunk` unimplemented | contract gap; when delivered, mikepenz streaming state is the preferred renderer |
| Thinking parts (`ThinkingContent`, redacted) | rendered via `Thinking` part | present in protocol (`BridgeProtocol.kt` :133) | keep; collapsed-by-default, never fabricate un-redacted content |
| Tool calls/results (`toolcall_*`, `tool_execution_*`) | `ToolCall` part w/ input+output | present | keep protocol; mikepenz `-code` inside expandable rows |
| Prompt delivery | **implemented**: `herdr::agent_prompt` (routes.rs ~:375) | implemented | receipts still missing |
| Approvals | **implemented, screen-parsed** with fail-closed signature + cancel (prompt.rs, routes.rs ~:406–452) | implemented (PTY-screen, not canonical Pi events) | keep Cappuccino-owned gate; canonical Pi approval path is a later contract decision |
| Steer vs follow-up queues, `pendingMessageCount`, `clear_queue` | absent | **missing** | contract gap; Nudge/Follow-up semantics need reconciled receipt contract before UI |
| Completion truth `agent_settled` vs `agent_end`, retries | status from pane heuristics | `agent_status` slot (heuristic) | bridge must expose settled/willRetry from canonical events; status slot stays ephemeral |
| Model, thinking level, context usage, cost | absent | **missing** | deferred workspace chrome |
| Compaction, session tree, fork/clone | out of scope (excluded by intent) | **missing** | non-mobile/deferred |
| Images in prompts | absent | **missing** | deferred; passive rendering only, no auto-fetch |
| Slash commands (`get_commands`) | absent | **missing** | later |
| Session identity/lifetime | machine+session identity in core | present | keep; app never owns agent process lifetime |

## 6. Recommendation

**Composition (shortlist):**

1. **Roster:** native Material 3 lists + navigation — nothing to add (composition choice, §4).
2. **Workspace markdown/code:** mikepenz `multiplatform-markdown-renderer-m3` + `-code` as **preferred candidate pending proof-of-fit and owner approval — not an adopted dependency**. No Coil module initially, but **omitting Coil alone is not a security proof**: the passive-markdown contract (links/images as passive text, no automatic navigation/HTML/remote fetch — current behavior `MarkdownText.kt:157–158` and `MarkdownTextTest.kt:28`) must be re-established with the new library. The library emits styled text; how it exposes link clicks (AnnotatedString `Url` annotations, a callback, or default ACTION_VIEW behavior in app glue) is **unverified this pass** — it must be explicitly determined, and link navigation disabled or gated, in the proof-of-fit before any adoption.
3. **Keep as minimal glue:** `BridgeProtocol.kt` (typed protocol adapter), `BridgeClient.kt`, the screen-parsed prompt-card flow, and `TranscriptParser.kt` **only for the live pane-row stream** (CappuccinoShell.kt :826–890) — canonical REST turns already bypass it. Delete-condition: remove `TranscriptParser` when the WS delivers structured turn chunks, not before; it does not stand between the app and canonical history.
4. **Delete:** hand-rolled `MarkdownText.kt` (~187 lines + its tests migrate to library-driven tests), only after the proof-of-fit proves passive-media parity. Nothing else qualifies for deletion: the shell's hierarchy, status slot, and approval flow are exactly what no renderer library provides.
5. **The renderer is only a partial solution for the whole agent panel.** Roster hierarchy, agent status, and the Pi interaction model (streaming parts, approvals, queues) need standard components + the existing protocol glue regardless of which markdown renderer wins. No library delivers the whole Pi workspace experience.

**Effort/risks:** renderer swap is small and reversible; API coordinates must be verified at adoption (README quickstart pins 0.43.0 while latest release is 0.45.0 — pin the Maven Central release and verify `-m3`/`-code` artifacts resolve against Compose BOM 2026.09). Library selection does **not** close bridge gaps (`turn_chunk`, receipts, queues, settled truth); those are separate contract work needing owner-reconciled acceptance.

**One bounded next proof-of-fit (owner decision; no implementation here)** — must assess **both halves of the workspace**, not only the renderer:

1. **Roster + selected-workspace hierarchy**: M3 list/navigation composition against existing bridge models, including status-slot behavior and where model/effort/queue chrome would live even though the wire capability is missing (render as explicit unavailable, never fabricated).
2. **Selected-workspace content**: one frozen Pi-fixture transcript — text + thinking + toolCall parts with delta sequences shaped per Pi's documented `message_update` events — through `rememberStreamingMarkdownState` vs current `MarkdownText`, at 320×640, dark/light, TalkBack semantics, **with link/image passivity explicitly tested and click behavior verified-or-disabled**.

Fixture-driven rendering proves renderer fit only — **not live parity** with a real agent; that stays unproven until the owner-authorized runtime journey. Acceptance per workstation policy: real-fixture parity, truthful unavailable states, accessibility, owner visual review, fresh independent verification.

## 7. Unknowns / limits

- mikepenz artifact coordinates/Kotlin-compiler compat, and its link-click/image-transformer behavior, are **not verified by a build or source inspection** this pass; explicitly unknown until the proof-of-fit, and passivity must be treated as unproven (Coil omission is not security proof).
- Stream license full commercial terms beyond the LICENSE head: unknown, not assessed.
- Bridge canonical-path behavior is implemented in source but **runtime-unexercised**; nothing here asserts live parity in either direction.
- jeziellago's exact API surface was not deeply inspected; statements about it are limited to verified metadata.
- Public pi.dev docs may be ahead of installed 1.1.0; claims cite installed-version behavior.
- Bounded 4-candidate search: other suitable Android markdown/chat libraries may exist; none were surveyed beyond this set.

## 8. Verification

- **Independent fresh audit:** PASS — a separate GLM Flash LOW session performed a read-only primary-source audit of this document (`.tmp/cappuccino-pi-workspace/verify/recheck-report.md`, PASS at the pre-verification SHA). All its findings were resolved: the stale history/delivery claims were corrected (§3) and the REST canonical vs. WS pane-row distinction made explicit.
- **Lead metadata re-verification (GitHub API):** mikepenz/multiplatform-markdown-renderer pushed 2026-10-07T18:18:29Z, license Apache-2.0; GetStream/stream-chat-android-ai latest tag v0.3.0 published 2026-06-17T09:49:01Z. Both match the figures cited in §4.
- **Gates for this research slice:** `make md-check` pass, `git diff --check` clean; no app build, dependency, or runtime gates are required for a research-only change; source tree unchanged at `81ee365`.
- **Boundaries:** no adopted dependency, no live-parity claim, and no prototype approval is granted or implied by this document. Candidate compat/security statements in §7 remain explicitly unknown until the owner-approved proof-of-fit.

**Status:** research accepted after independent verification; no source, dependency, protocol, or PR 29 changes made.
