# Native Material 3 design contract — conversation screen

Status: rev 3. Rev 2 was the doc-only contract revision (TL corrections applied);
rev 3 records acceptance of the first implementation slice only — see "Owner
acceptance (conversation slice)" at the end of this document. Design-only origin:
no application code was changed by this document. First implementation is a bounded
conversation-screen vertical slice; broader rollout stays parked. Baseline inspected:
`feat/android-material3-redesign` at `36f3afe` (merged PR #28), clean checkout.

Target clarified by the owner: **native Material 3** (standard Android M3 surfaces, app
bars, type, navigation), not web-MUI and not square-everything. M3 uses shape and
outlined components where they are native defaults; this contract rejects only custom
decoration that replaces them.

## Screen job

Catch up on the transcript the bridge supplies for the selected session and reply to
it. Primary action: reading the transcript. Primary interactive action: sending a
prompt. Everything else is contextual metadata, status, or expansion of transcript
detail. No live parity with the owner's agents or runtime is claimed or verified from
this design phase; nothing here asserts transport or runtime behavior beyond what the
merged source already implements.

## Diagnosis: what reads as bespoke at PR #28

Evidence: decoded screenshots `ConversationCollapsed.png`, `ConversationExpanded.png`,
`ConversationMarkdown.png`, `ConversationLatest.png` (4 of the 5 committed; the fifth,
`DeviceTimezoneChanged.png`, is known from the evidence README only), plus the merged
source under `apps/android/app/src/main/java/com/azusachino/cappuccino/ui/`.

1. **Repeated custom decorative borders.** Hand-added `BorderStroke` wraps the metadata
   banner, user rows, tool rows, status rows, gap chips, disclosure cards, code blocks
   and the approval card. M3 builds hierarchy from tonal surfaces (elevation + surface
   roles), not from repeated strokes around nested cards.
2. **Ad-hoc radii.** One hand-picked `RoundedCornerShape(dp)` literal per surface,
   outside `MaterialTheme.shapes` and component defaults.
3. **Percentage-width surfaces.** `ConversationTurnRow` uses
   `Modifier.fillMaxWidth(0.92f)` — chat-app bubble sizing, not an M3 layout.
4. **Hand-mixed palette overriding M3 roles.** `Theme.kt` sets cream backgrounds, brown
   outlines and amber accents by hand; with dynamic color off by default the app reads
   as a custom skin rather than M3.
5. **Glyph-drawn affordances.** Text-glyph chevrons (`▸`/`▾`), `❯` prefixes, hand-drawn
   status dots, disclosure cards built from bordered `TextButton`s.
6. **Nested transcript card.** The whole transcript sits inside one large rounded
   container with cards inside it.

Importing `androidx.compose.material3` did not deliver adherence because component
defaults were overridden nearly everywhere (colors, borders, radii, container shapes).
The fix is removing overrides, not adding components.

## Design direction

**Theme.** First preview uses the **stock M3 fallback schemes**:
`lightColorScheme()` / `darkColorScheme()` with no hand-mixed overrides. Keep the
existing theme architecture unchanged: `ThemeMode` preference (SYSTEM/LIGHT/DARK),
`selectColorScheme` behavior, and the dynamic-color default (off) all stay as merged —
these decisions are not reopened by this contract. Acknowledged scope: color tokens are
shared, so this reset changes the *colors* of other screens (they read the same
scheme); it does not change their layouts, which stay out of this slice.

**Shape.** Surfaces take shapes from `MaterialTheme.shapes` tokens or from component
defaults — both are authoritative and API-derived from the pinned library; no hardcoded
dp radius values appear in code or review criteria. "Full" is component pill/circle
behavior (FAB, badges), not a numeric value. M3 legitimately keeps rounded shapes and
pill affordances; nothing is squared off.

**Typography.** `MaterialTheme.typography` role names only — with one explicit
exception: **monospace** (`FontFamily.Monospace`) for code blocks, inline code and tool
input/output, because code is content, not decoration.

**Rejected:** repeated custom decorative `BorderStroke` usage and card nesting as
hierarchy; percentage-width surfaces; glyph affordances; hand-mixed scheme slots.
**Not rejected:** legitimate M3 outlined components — the composer keeps
`OutlinedTextField`, and any component whose default variant is outlined stays outlined.
Component defaults win wherever they exist.

## Component contract

Annotated layout (compact phone 320 dp; normal phone ~411 dp uses the same structure —
content is width-adaptive, not re-composed):

```text
┌────────────────────────────────────────┐
│ ←  pi                        ( IDLE )  │ 1. TopAppBar, M3 defaults
│    machine · session-id                │
├────────────────────────────────────────┤
│ Recent agent output · Read-only        │ 2. transcript metadata, plain text
│ machine · session · branch             │
├────────────────────────────────────────┤
│ Connecting… ▸ [progress]               │ 3. state line (loading/error only)
├────────────────────────────────────────┤
│ Assistant  2026-10-08 01:02:03         │ 4. transcript on background
│ Prose as bodyMedium text, full width.  │    (LazyColumn, no wrapping card)
│ ╭ Thinking ─────────────── ⌄ ╮         │ 5. disclosures: tonal rows
│ ╰─────────────────────────────╯        │
│ ╭ Tool: bash · Result available ⌄ ╮    │
│ ╰────────────────────────────────╯     │
│  code block: monospace bodySmall on    │ 6. tonal surfaceContainer,
│  surfaceContainer, shapes token        │    no border
│                        ╭─────────╮     │ 7. user turn: primaryContainer
│                        │ You ... │     │    bubble, end-aligned,
│                        ╰─────────╯     │    content-hugging
│                    ( ⌄ Jump to latest )│ 8. Extended FAB when hasNewOutput
│ [ Prompt agent…            ]    (Send) │ 9. composer row
├────────────────────────────────────────┤
│  Chats   Attention  Machines  Settings │ 10. NavigationBar, M3 defaults
└────────────────────────────────────────┘
```

| # | Region | Contract |
| --- | --- | --- |
| 1 | Top app bar | `TopAppBar` passed to `Scaffold.topBar`; remove the `colors` override so M3 defaults apply. Title: agent label; second line machine · session in `labelSmall`/`onSurfaceVariant`. Status as tonal pill (component-full shape, semantic status color, `labelSmall`). Back `IconButton` when an agent is selected. |
| 2 | Transcript metadata | "Recent agent output · Read-only" describes **the transcript** (catch-up output view); it is not a composer state. Render as two plain text lines under the app bar (`labelMedium`, `labelSmall`/`onSurfaceVariant`), 16 dp side padding; delete the outlined banner Surface. Content is whatever the bridge supplies (machine · session · branch when present). |
| 3 | Error / loading / empty | Connecting: `LinearProgressIndicator` + "Connecting…". Recovering: "Reconnecting; earlier output may be missing" in `onSurfaceVariant`. Error: message in `error` role. Empty: centered "No recent output received." in `onSurfaceVariant`. Plain text only. |
| 4 | Transcript | `LazyColumn` on `colorScheme.background`, 16 dp side padding, existing `testTag`, keys, spacer and tail-follow logic unchanged. No wrapping card, no percentage-width containers. |
| 5a | Sender roles | Assistant turn: no container — role label + timestamp row, prose directly on background. User turn: role label + timestamp above a `primaryContainer` surface with a `MaterialTheme.shapes` token (end-aligned, content-hugging, text wraps; never `fillMaxWidth(fraction)`). Timestamps keep the merged device-zone behavior (`yyyy-MM-dd HH:mm:ss`). |
| 5b | Thought / tool expansion | Replace the glyph card in `ConversationDetails` with a tonal disclosure row: surface using a `MaterialTheme.shapes` token, headline `labelLarge` ("Thinking", "Tool: bash · Result available"), one-line `bodySmall` summary, trailing expand icon rotating when expanded, expanded content monospace `bodySmall` inset in the same surface. Keep `rememberSaveable` state and the existing accessibility state descriptions. |
| 5c | Prose / code | `MarkdownText` parser and `semantics { heading() }` unchanged. Headings on `onSurface` (drop primary tint); code on `surfaceContainer`, `shapes` token, monospace `bodySmall`, horizontal scroll for long lines; no borders. Passive Markdown unchanged: no link/image execution or fetch. |
| 5d | Stream rows | Same treatments in stream view: user rows as the user bubble; tool/status/gap rows as plain text lines; gap markers centered `onSurfaceVariant`; status dot allowed as a small semantic-color circle with cleared semantics. |
| 6 | Approval card | Stays prominent: `ElevatedCard` with default colors/shape (no custom border). Title `titleMedium`; command monospace `bodySmall` on a tonal block; options full-width `FilledTonalButton`s; cancel `TextButton`. All answer/cancel semantics unchanged. |
| 8 | Follow-latest | `ExtendedFloatingActionButton` ("Jump to latest", arrow icon), M3 defaults, bottom-end above composer, visible only when `hasNewOutput`; same scroll behavior. |
| 9 | Composer | Keep `OutlinedTextField` (legitimate M3 outlined component; default shape/colors — remove the custom colors override) plus trailing send `IconButton`. **Send behavior unchanged from merged source:** the composer is enabled when the connection is `Connected`, and send is enabled for nonblank input; "read-only" metadata never disables it. `imePadding()` stays. |
| 10 | Navigation | `NavigationBar`/`NavigationBarItem` with overrides removed (delete `containerColor`/`tonalElevation`); existing drawables may stay this slice; no new icon dependency. |

## Preserved behavior (non-negotiable)

Canonical fail-closed identity; passive Markdown (no link/image execution or fetch);
collapsed-by-default disclosures with accessibility state descriptions; device-zone
timestamp `yyyy-MM-dd HH:mm:ss` and timezone-change lifecycle; actual-tail following,
manual reading detection, jump-to-latest and stale/coalesced refresh handling; pending
approval semantics; **composer connectivity and nonblank-send behavior exactly as
merged**. No transport or model refactor rides along; no new third-party UI framework.

## First implementation slice

- `ui/Theme.kt` — swap the hand-mixed schemes for stock `lightColorScheme()` /
  `darkColorScheme()` fallbacks; keep `ThemeMode`, `selectColorScheme` and dynamic-color
  default untouched (shared color-token change; other screens change color, not layout).
- `ui/CappuccinoShell.kt` — conversation branch only: app bar, metadata lines,
  transcript/stream rows, jump FAB, composer, state lines. Other branches keep working,
  restyled only by the shared token reset.
- `ui/ConversationDetails.kt`, `ui/MarkdownText.kt` — restyle per contract; parser and
  semantics preserved.
- `res/drawable/` — at most two small vector assets (expand chevron, jump arrow).
- Tests: behavior assertions keep their meaning; copy-coupled assertions may be updated
  to equivalent copy, never weakened or deleted.

Out of scope until owner approves preview screenshots: other screens' layouts, icon-set
replacement, dynamic-color default flip, adaptive/tablet layout.

## Acceptance checklist

- [ ] Compact phone (320 dp, matching the evidence emulator) and normal phone (~411 dp),
      portrait; light and dark.
- [ ] Large text (`fontScale` 1.3): no clipped labels; disclosure rows grow.
- [ ] IME open: composer visible above keyboard; tail reachable; FAB does not overlap
      the composer.
- [ ] Assistant turn with thinking + tool disclosure, collapsed and expanded.
- [ ] Markdown turn: heading, bold, inline code, fenced block, list (matches the
      committed synthetic fixtures).
- [ ] User bubble end-aligned and content-hugging (no fraction-width fill).
- [ ] "Jump to latest" appears only when detached from tail and clears on use;
      manual scroll detaches; new content while detached raises the affordance.
- [ ] No custom decorative border strokes on the screen; outlined surfaces only where
      an M3 component's default is outlined (e.g. `OutlinedTextField`).
- [ ] Shapes only via `MaterialTheme.shapes` tokens or component defaults; pill/circle
      where components define full; no ad-hoc radius literals; no hardcoded dp roster.
- [ ] Status badge tonal pill; timestamp `yyyy-MM-dd HH:mm:ss` in device zone.
- [ ] Connecting / Recovering / Error / Empty render cardless as specified.
- [ ] Composer enabled when connected, send enabled on nonblank input — unchanged
      from merged source.
- [ ] Existing instrumented journeys pass without semantic weakening.

Tests passing alone is **not** visual acceptance. The owner reviews actual preview
screenshots against this contract before the slice leaves draft or any PR opens, and
before any broader rollout.

## Staged acceptance sequence

1. This revised contract (proposal).
2. TL/owner direction decision.
3. Bounded worker implementation of the slice (separate assignment).
4. Focused `make check-android` / `make validate-android` on the implementation branch.
5. Complete task-owned `make ui-test-android` on a new task-owned emulator from an
   existing image (owner5554 and the phone/running bridge/agents untouched).
6. **Owner reviews real preview screenshots BEFORE PR / broader rollout** — tests alone
   do not establish visual acceptance.
7. Fresh independent verification: source + runtime + design adherence.
8. Evidence delivery; rollout decision stays with the owner.

This design phase has zero device authority: no emulators, AVDs, devices, runtime
probes or remote actions were used or are authorized by it.

## Sources

Public official documentation fetched via GET only (no private source or content sent
to any URL): developer.android.com Material Design 3 in Compose (designsystems/
material3), app bars, navigation bar, and the Compose Material3 Typography/Shapes API
references; m3.material.io shape overview and type scale tokens (JS-rendered; used as
role/token citations). Token values are API-derived from the pinned material3 library;
component defaults are authoritative, so this contract mandates no numeric values.

## Session evidence (correction)

- Model: glm-5.3-flash (provider zai-coding-cn), pi-coding-agent.
- Reasoning effort, recorded as reported: the TL states the launch argv requested
  medium; the session's own records show thinking level **high** (session JSONL
  `thinking_level_change: high`; `PI_REASONING_LEVEL=high` in the environment). Both are
  recorded here; I did not change the effort setting at any point in this session (no
  such change was issued by me), and I do not speculate on why the reported argv and
  session records differ.
- Screenshot decode set this phase: 4 of the 5 committed evidence PNGs (listed under
  Diagnosis). `DeviceTimezoneChanged.png` was not decoded here and is cited only
  from the evidence README.

## Doc-writer ownership release

Design-only doc-writer ownership of
`vendor/cappuccino/docs/design/android-material3.md` and
`.tmp/cappuccino-material3-redesign/designer/` is released with this revision. The
contract remains a proposal awaiting TL/owner direction; implementation, visual
acceptance and independent verification are separate unclaimed assignments. No
implementation, owner visual approval or independent acceptance is claimed.

## Repair ledger (implementation phase, appended by the repair writer)

Status only; the contract above is unchanged. Owner review of real previews and any
PR / broader rollout remain PENDING and are not claimed here.

- **Custom-skin rejection (prior direction).** The pre-contract custom skin (hand-mixed
  scheme slots, decorative borders, ad-hoc radii, glyph affordances) stands rejected per
  the Diagnosis above; the implementation slice follows the native Material 3 contract.
- **Sole instrumented failure — root cause and repair.** An independent read-only
  diagnostic reproduced the one failing preview journey (`captureConversationExpandedDark`)
  and traced it to an exact-text test matcher: the wait/display finders matched
  `"Hidden thought details"` while the fixture's expanded thinking paragraph renders as
  exactly `"Hidden thought details: check collapse, contrast and spacing."`, so the exact
  matcher could never succeed regardless of UI state. Repair: both wait and display
  assertions now use the full paragraph text exactly — no assertion weakening, retries,
  skips or production UI changes. The native `ExtendedFloatingActionButton`
  follow-latest journeys reproduce PASS on the current tree (existing journeys plus the
  preview journey); no framework-bug claim is made.
- **Preview evidence status.** Fresh full-screen synthetic captures (final source, one
  instrumented session each) cover the normal-width phone (effective ~393–411 dp), light
  and dark: top-of-transcript collapsed disclosures, single-click expanded thought/tool
  detail with the full paragraph and result visible (reader `performScrollToNode` added so
  narrow-width disclosure content enters the viewport after the single click), markdown +
  user turn, large font 1.3, and the jump affordance. The earlier "composer focused, no
  visible keyboard" image stays rejected and relabeled. Capture index and hashes live in
  the repair scratch; owner visual approval is not obtained.
- **Honest limits.** No compact-320 dp capture was delivered: the task emulator accepted
  the 540 density override in `wm density` while app rendering kept reporting physical
  440-based metrics, and the one fully passing compact run's outputs were then destroyed
  by the final full-suite run's per-run output wipe. The final set's composer capture
  shows IME insets asserted open without the keyboard visible in frame (mid-density-
  transition run); it is relabeled, not counted as IME evidence — a genuine visible-keyboard
  capture (achieved and visually verified in an earlier run, also lost to the same wipe)
  remains outstanding. The large-font capture opens mid-transcript (viewport shot; it does
  not show the entire history). These limits stand recorded for the owner's review.

## Findings ledger (surgical UI fixes, appended by the ui-fixes writer)

Status only; the contract above is unchanged. No owner acceptance is claimed; owner
visual approval of real previews still gates any PR / broader rollout.

The fresh independent review (`review/report.md`) confirmed two real deviations from
this contract, and both were repaired surgically:

1. **Follow-latest geometry (region 8).** The `hasNewOutput` extended FAB was placed
   before the weighted transcript container, rendering it at the top of the
   conversation column — a real deviation from "bottom-end above composer" despite a
   matching comment. Repair: the FAB row moved in flow between the transcript and the
   composer, so it sits at the bottom end, never overlaps composer/IME/navigation and
   never steals transcript height; jump, tail-following and manual-reading behavior are
   untouched. Regression coverage is geometry-based, not text-only: the jump journeys
   in `CappuccinoShellTest` now assert the affordance's actual bounds — bottom half of
   screen, above the composer top, not overlapping the transcript above it.
2. **System-bar contrast vs resolved app theme.** `enableEdgeToEdge()` ran once with
   system-following appearance, so an in-app DARK preference while the system is light
   drew dark status icons on the near-black surface. Repair: theme resolution stays the
   pure helper; `CappuccinoTheme` gained an explicit optional `onResolvedDarkChanged`
   sink reported after each composition; the platform window mutation is activity-owned
   (`MainActivity.applySystemBarAppearance` sets status AND navigation icon appearance
   from the actually resolved theme; `enableEdgeToEdge()` remains the one edge-to-edge
   enabler in `onCreate`). `SystemBarThemeSyncTest` drives the real Settings journey on
   the real production composition (no `setContent` override) and asserts the real
   window's insets controller: DARK while system light → light icons, LIGHT while
   system dark → dark icons, SYSTEM tracking a system-mode change with no preference
   change. The preview fixture wires the same production sync so its dark captures show
   what production renders.

Gates at final source: `make fmt-android`, `make check-android` (63 JVM tests, 0
failures, task executed), `make validate-android`, `ANDROID_SERIAL=emulator-5560 make
ui-test-android` (21 tests, 0 failures/errors/skipped; instrumentation budget 2 of 3
invocations, ~2 min of 25), preview-class rerun at temporary density 540 (5/5; density
reset to 440, night mode `no`, timezone mutated only by the pre-existing timezone
journey (restores the inherited zone in its `finally`; run green), emulator retained for
reviewer/owner), `make md-check`, `git diff --check`. Full-suite PNGs were copied to
scratch immediately after each run; the owner preview set (7 captures) and per-image
SHA-256 live in `ui-fixes/preview/index.md`. No test was skipped, weakened or made
conditional; existing assertions are preserved and only strengthened.

## Owner acceptance (conversation slice)

Recorded status only; the contract above is unchanged. On 2026-10-08 the owner
reviewed the 7-capture preview set of real screenshots and **explicitly approved the
conversation slice** — the conversation-screen appearance under this contract, at the
captured 393/320 dp states. The approval is scoped exactly to that: it is not
approval of a broader redesign, merge, deployment, or any live/physical acceptance,
and it does not reopen any decision recorded above.

Verification backing the accepted state: fresh independent recheck (GLM flash, low
reasoning, no implementation context) confirmed both review findings FIXED —
region-8 FAB bottom geometry and the theme → system-bar appearance sync — with a
forced 63-test JVM run and the full 21-test instrumented suite at zero failures,
errors and skips, plus `make validate-android`, `make md-check` and `git diff --check`
all exit 0. The recheck reviewed the whole tree at tracked-diff SHA-256
`d930c1f97b4e96da9e0094fb63f2b96528098367d2c46ff56ed2b1d1631cb7f8` with a 12-path
manifest identical before and after its gates. (A bookkeeping defect in the
ui-fixes writer's own after-manifest — 10 paths listed, omitting 2 — was corrected
in scratch against the stable 12-path reviewed record; the source was never
affected.) The verified content is committed unchanged as
`cc898ef6f8bd58dcd875ef1503f282e523c1781b`; the captures predate that commit and
were taken on the same reviewed content.

The approved evidence set lives in
[`docs/evidence/android-material3/`](../evidence/android-material3/README.md).
Remaining parked: other screens' layouts, icon-set replacement, dynamic-color
default flip, adaptive/tablet layout — unchanged from the out-of-scope list above.
No PR exists at this acceptance; broader rollout stays an owner decision.
