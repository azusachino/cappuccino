# Native Pi workspace — forward plan (roster + selected workspace)

**Status:** draft for owner plan review (repaired after fresh independent review). Planning only — no implementation, prototype, dependency, device, bridge, runtime or remote action is authorized by this document; no execution follows from draft lgtm.
**Baseline:** `research/native-pi-agent-workspace` @ `15994f1` (stacked on `81ee365`; PR 29 open, unmerged — nothing here assumes it lands).
**Foundation:** [pi-native-workspace-research.md](../pi-native-workspace-research.md) is the accepted capability/library evidence with file:line citations; this plan links rather than duplicates it. Prior records ([android-plan.md](../android-plan.md), [design/android-material3.md](../design/android-material3.md), PR 29 history) remain authoritative for what they covered.

## Goal and non-goals

Native Android with **Pi feature parity** for the agent roster + selected workspace — a real agent workspace, not a restyled chat UI and not terminal/TUI embedding (Pi's TTY framework is TypeScript; not an Android library; out of scope).

Non-goals: owner runtime/deploy changes, physical devices, live-Pi attachment, Apple-side work, window/desktop design, issue 12 closure, wire-protocol migration (separate Tier 2 track, Step E).

## Current capability truth (source-level; research §3, §5)

| Capability | Today |
| --- | --- |
| Canonical Pi JSONL history, structured turns | **Served** — REST `/api/agents/{id}/conversation`, `canonical_log` primary, labeled scrollback fallback, fail-closed transcript resolution |
| Live WS stream | Pane rows only; contract `turn_chunk` **unimplemented** — a distinct, honest gap |
| Prompt send, approvals (answer/cancel) | **Implemented**, screen-parsed with fail-closed signature — not canonical Pi approval events |
| Queue intents (steer/follow-up), receipts, abort, settled-vs-end truth, model/effort/context surface | **Not declared implemented** — rendered as explicitly unavailable, never fabricated |

The current app can be presented truthfully **without any RPC migration**; renderer and hierarchy work precede and do not depend on bridge contract work.

## Library stance

- **Roster:** standard Material 3 composition (`LazyColumn`, adaptive navigation). No kit adopted.
- **Workspace markdown/code:** mikepenz `multiplatform-markdown-renderer-m3` (+ `-code`) is the **preferred candidate, not adopted** — adoption only after the Step B proof-of-fit proves passive-link/image/offline parity and owner approval. The hand-rolled `MarkdownText.kt` may be replaced only then.
- Stream's AI-chat kit requires an **explicit licensing/commercial decision** (research §4: terms beyond the LICENSE head unknown/not assessed) — it is an owner call, not blanket-banned; not adopted. Minimal protocol adapter stays; no full "native Pi UI kit" is claimed or built.

## Per-screen hierarchy, priority, acceptance

**Roster (machines → agents):** machine grouping; per agent row = identity/name, job/pane, session id, live status; tap focuses the selected session.
**Workspace (selected agent):** header = agent/session identity + truthful status; body = ordered assistant/user turns with tool-call and thinking content inside their owning turn (collapsed-by-default thinking, expandable tool results, long code with actual-tail + follow-latest); composer pinned bottom with connectivity state.

| Element | Priority |
| --- | --- |
| Canonical history render, status truth, approvals (answer/cancel), roster focus | **Must-pair existing** (wire today) |
| Queue intents, abort, receipts, model/effort/context | **Visibly unavailable** (bridge gaps — render disabled-with-reason, never fabricated) |
| Compaction, session tree/fork, slash commands, images in prompts | **Deferred** (desktop-grade; out of scope here) |

## Steps (bounded; each has its own gate and approval)

**A — Parity & interaction acceptance doc.** Deps: none (this section is the acceptance; Step A validates it against current source and Pi's documented model, it does not defer a "missing section"). Paths: `docs/plan/`. Acceptance: (1) every table row above verified against a research/source citation or marked unavailable; (2) no capability is claimed working without a wire today; (3) owner sign-off recorded before any prototype.
**B — Fixture-only prototype, BOTH screens.** Requires owner plan acceptance first, then a separate bounded approval of this outlined scope. Synthetic Pi-shaped fixtures only — never owner-captured transcripts. Paths: task-owned `vendor/cappuccino/apps/android/` prototype branch or `.tmp/…-prototype/` scratch; no production path, no permanently installed UI dependency before proof. Gate: dependency resolve + assemble + lint + JVM tests **only**; instrumented fixture tests are Step C (they need a device).
**C — Owner preview acceptance + fit/library decision.** Requires a fresh task-scoped device approval: exact serial/AVD, port 5560, identity checks; physical devices excluded; prior M3 AVD approval does not carry over; 5554/owner-Pixel bridge/agent Tailscale never authorized. Preview: short 320×640 @160 plus normal width/height/density, light/dark, IME actually visible (not just focused), tool/thought collapse-expand, status transitions, offline/disconnected/readonly/empty, long code actual-tail/follow-latest, stock TalkBack semantics as a target check (not physical accessibility proof). Includes B's instrumented fixture tests on the approved serial. Output: approved fit decision, mikepenz adopt/defer.
**D — Production vertical slices (each after C, each independently verifiable):**

- **D1 Rendering** — replace markdown path once proof-of-fit passes. Paths: `MarkdownText.kt` + its tests → renderer module. ~Small. Acceptance: (1) passive markdown — links/images as text, no navigation/fetch/execution/HTML/webview — re-tested; (2) thinking redaction+collapse and tool-result expand preserved; (3) assistant-plain/user-native distinction changed only with owner preview approval.
- **D2 Roster** — machine→agent hierarchy, identity/job/session/status rows, focus affordance. Paths: existing Compose roster screen + adapters. ~Small. Acceptance: (1) every row field matches live model, no fabricated status; (2) unavailable queue/abort/model-effort actions disabled-with-reason; (3) focus switches the workspace session, fail-closed on stale identity.
- **D3 Workspace chrome** — header truth, status slot (settled vs heuristic labeled), composer connectivity. Paths: shell/composer composables + `BridgeProtocol.kt` glue. ~Small. Acceptance: (1) status never claims settled from a heuristic; (2) device-zone timestamps `yyyy-MM-dd HH:mm:ss` in Activity-owned timezone + system-bar resolved theme preserved; (3) refresh/coalescing and composer connectivity behavior unchanged.
**E — Optional separate Tier 2 bridge verticals.** Each queue/cancel/status/receipt improvement is its own feature vertical with intent-to-verified acceptance issue + PR + fresh review. No bundled wire migration into D.

## Verification (independent document review recorded)

- This doc: `make md-check` exit 0 and `git diff --check` clean, source identity unchanged at `15994f1`. **Independent document review PASSED** (fresh plan-only recheck, `verify/recheck-report.md`): all material + minor findings confirmed repaired, both doc gates re-run by the reviewer at pre-record SHA `c5cab6c06bafb61c9440e8d5ad0e800b98d9fdbe583d1e65180d1f4d235e08ac` with HEAD/dirt unchanged. **Owner plan acceptance remains pending** — scope stays draft; no prototype, device, dependency, wire or library-adoption work is authorized by this document or that PASS.
- Per-slice production gates: `make validate-android` + forced JVM tests + UI journey on the exact approved serial; **full `make validate` before any PR**; Apple side via documented per-command toolchain selection (no global `xcode-select` change). Hosted CI exact-head-green before ready-for-review. No diagnostic suppressions, lint baselines, skipped tests, weakened assertions, or blind CI reruns. Preview captures: original-pixel hash preserved + privacy-inspected before any publish. No ARM-local vs x86-CI parity claim.

## Approval sequencing and boundaries

Draft lgtm authorizes **nothing executable**. First decision: owner reads this plan and approves/adjusts acceptance. Second, separate: one bounded approval of the Step B fixture prototype (outlined scope above). Third, distinct: fresh task-scoped device permission for C. Plan approval ≠ prototype permission ≠ device permission ≠ wire decision ≠ library adoption.
