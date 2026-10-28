# Native Material 3 design contract — conversation screen

Status: superseded 2026-10-27 by the owner's liquid-glass direction for Android
(the floating glass islands, dock and composer pill). Kept for the transcript
grammar rules that still apply: prose is plain text, activity collapses to one
line, and no receipt claims. The original decision follows.

Original status: accepted 2026-10-08 for the conversation screen only. The owner approved the seven preview captures in [the evidence record](https://github.com/azusachino/cappuccino/blob/35e72ff/docs/evidence/android-material3/README.md). Other screens, layouts beyond the shared color tokens, and any broader rollout remain out of scope.

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

Tests passing alone is **not** visual acceptance. Review actual preview screenshots
against this contract for any later change to the conversation screen.

## Sources

Public official documentation fetched via GET only (no private source or content sent
to any URL): developer.android.com Material Design 3 in Compose (designsystems/
material3), app bars, navigation bar, and the Compose Material3 Typography/Shapes API
references; m3.material.io shape overview and type scale tokens (JS-rendered; used as
role/token citations). Token values are API-derived from the pinned material3 library;
component defaults are authoritative, so this contract mandates no numeric values.
