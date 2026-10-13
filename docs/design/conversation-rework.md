# Conversation page rework

Status: first slice installed on the owner's phone 2026-10-12; owner review pending.

## Owner decisions (2026-10-12)

The owner rated the working Android client's experience below average and
authorized a full rework. Agent priority is Pi, then Claude, then Agy.
[pi-pocket's showcase](../licenses/pi-pocket/README.md) is the visual target.

- One visual language for the whole app: JetBrains Mono, square tiles, flat
  surfaces mixed from each palette's foreground over its background. The five
  palettes and System/Light/Dark stay.
- Tool activity appears inline in the transcript as a one-line summary that
  opens into a timeline. The Tools tab and its counter stay as the full log.
- Status is a colored square beside its word in the header subline, plus a
  live line at the transcript's end while working or waiting. This supersedes
  the colored header pill; color is never the only cue.
- Agent icons: a pie for Pi, a starburst for Claude (Cappuccino-drawn, not
  vendor artwork).

## Why the old page was hard to read

- `FontFamily.Monospace` resolved to Droid Sans Mono, and Material's body
  letter spacing (0.25–0.5 sp) widened it further.
- Markdown paragraphs and lists fell back to the renderer's `bodyLarge` because
  only `text` was overridden; headings outside compact mode used display sizes.
- Every turn carried a label and surface; tools lived only behind a tab.

## Sources

- [pi-pocket `web/style.css`](https://github.com/TannerMidd/pi-pocket/blob/ed50991eb9c9b92e717f819073de36633bf83425/web/style.css)
  (MIT): 14 px / 1.6 mono body, 4/8/11 % fills, user prompt row, activity
  timeline, live line. Values are reimplemented in `ui/AppStyle.kt` and
  `ui/TranscriptRows.kt`; no code is copied.
- [HAPI Android](https://github.com/tiann/hapi/tree/5153ff23c196c7b84818752f7a631420ef0eb25b/android)
  (AGPL-3.0, patterns only): one-row tool groups opening an inspector,
  collapsed reasoning, a queued-messages bar above the composer for later
  steering work.

## Implemented in this slice

- `ui/AppStyle.kt`: `JetBrainsMono` (ligatures off), `AppTypography`,
  square `AppShapes`, `AppColors` and the derived Material surface roles.
- `core/TranscriptActivity.kt`: segments a turn into prose and activity,
  summarizes Pi and Claude tool names alike and computes durations only from
  recorded start and result times.
- `ui/TranscriptRows.kt`: user prompt row, activity group and steps, live
  status line. A missing result reads "no result", never failure or running.
- Header, Markdown typography, compact composer and agent icons.

## Open

- Machines list, settings and tabs still carry older chrome.
- Composer steering states (Nudge/Follow-up, honest acknowledgements) are a
  separate slice; their receipt contract still needs owner acceptance.
- Instrumented tests compile but were not run: the only device is the owner's
  phone, where connected-test runners are not allowed.
