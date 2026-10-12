# Mihon app shell, terminal-inspired agent session

Status: owner-approved direction and module split; detailed layout is a design
proposal awaiting native preview review. No production rewrite is claimed.

## Direction

The owner is explicit: the **whole app follows Mihon**, while **only the agent
session**, Cappuccino's core feature, uses a terminal-inspired native layout.
The floating design is rejected as ugly and unintuitive. The previous live
screen-jump fix is also rejected across conversations; synthetic green checks
do not override that feedback.

This supersedes the earlier cosmetic-only continuation and deferred palette
chooser. It does not replace the native Compose stack, bridge, verified
machine/conversation identity, independent connections, private storage or
passive-content boundary. Android is the first slice; Apple is outside it.

Source basis: [pinned Mihon study](mihon-native-ui-source-study.md), commit
`866d045c7aba343fd739c57d15c81ff6df59e796`. Source patterns inform our own domain,
not a manga-gallery clone. No terminal emulator, WebView, launcher or remote
agent lifecycle control is introduced.

## Latest owner session acceptance target

The owner subsequently expanded palette scope to **Default, Dynamic,
Catppuccin, GitHub and One Dark**, superseding the earlier three-choice cap.
Brightness remains separate; One Dark's light mode uses One Light-inspired
colors. Palette previews must fit a scrollable grid, not five cramped columns.

The agent-detail status must retain a colored background and its corresponding
contrast-checked foreground. The owner reported the plain status as invisible;
restoring the pair is the acceptance target, not removing status semantics.
The owner also explicitly restored a **Tools counter**: new calls and returned
results must be observable while reading Session (or older tool entries).
Initial loaded history is not new activity; repeated snapshots, trimmed history
and partial/missing results must not manufacture new return notifications.
This is selected-conversation UI awareness, not all-session system/Telegram
notification delivery or proof of successful execution.

The pi-pocket showcase is the session-layout target, not merely permission to
add tabs to the old chat. Native Compose must deliver compact monospace
hierarchy, continuous assistant text, flat user blocks, ordinary replies inline
within an explicit rendering budget, and one integrated bottom composer.
Respect font scaling; never change the owner's system display settings.

The owner subsequently narrowed session navigation to **Session / Tools**;
**Info is removed**. Connection diagnostics remain behind the header action.
Tools use descending source order (newest first), opening at the latest record
and following arrivals while at the top; intentional older inspection retains
its position. Follow/Latest disappear at the session bottom and reappear away
from it, inside the permanent Send toolbar so visibility changes do not resize
the reader. This supersedes the earlier three-tab/fixed-visible-row sketch
below. Earlier first-slice installation is not owner acceptance of the target.

## Pi-first scope correction

The owner's pi-pocket reference does not justify excluding approvals, steer,
queue or model controls. Pi/Herdr integration is the first priority. The
[source capability audit](herdr-pi-capability-audit.md) distinguishes documented
Pi support from gaps in our bridge/client and safe live-session transport.
Existing approval handling is already present; missing typed controls are
implementation gaps, not features declared outside the goal. The same audit
records Herdr's existing completion sequences, currently dropped by our
catalog, and limitations of the screen-based answer path.

## Approved capability boundaries

| ID | Job | Depends on |
| --- | --- | --- |
| `theme-system` | Whole-app palette selection, brightness mode, preview and persistence | Existing private preferences/native rendering |
| `app-shell` | Mihon-style navigation, machine/session discovery, saved history and grouped settings | `theme-system`, existing connection/storage foundations |
| `conversation-reader` | Terminal-inspired native session, stable transcript and separate tool/reasoning inspection | `theme-system`, `app-shell`, existing canonical/passive foundations |

Both theme system and app shell are first-priority work. The reader is the
core experience, not an optional polish pass after a dashboard.

## App shell: Mihon-style native Material

- Purposeful top bars, grouped native lists and preference screens, restrained
  surfaces and a persistent application navigation bar. No oversized card grid
  or chat styling applied to every screen.
- Proposed application destinations: Sessions, Saved, Settings. Session lists
  retain clear machine grouping and independent connection states. Machine
  management remains accessible without making technical diagnostics the
  primary screen.
- Opening a session enters its focused reader route. Back returns to its
  discovery context without disconnecting a machine or losing scoped state.
- Native controls and accessibility remain primary: labeled actions, 48 dp
  touch targets, readable large-font layouts, keyboard/TalkBack semantics and
  status text rather than color alone.
- Loading, empty, unreachable, verification mismatch, setup-required and saved
  states are distinct. Sample data never stands in for a live attachment.

Mihon's [HomeScreen](https://github.com/mihonapp/mihon/blob/866d045c7aba343fd739c57d15c81ff6df59e796/app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt)
uses a navigation bar/rail scaffold and preserves each tab's state. Its
[PreferenceScreen](https://github.com/mihonapp/mihon/blob/866d045c7aba343fd739c57d15c81ff6df59e796/app/src/main/java/eu/kanade/presentation/more/settings/PreferenceScreen.kt)
uses grouped headers and native preference items in a lazy list. Adopt those
organizational ideas without importing Voyager, dependency injection, manga
models or unrelated navigation dependencies.

## Agent session: terminal-inspired, not a terminal

A compact scope header, restrained separators, explicit speaker/time labels,
and a text-first chronology replace chat bubbles. Monospace serves command,
code and compact metadata; prose must still read well at the user's font size.
Commands remain displayed text, never execution affordances.

Proposed fixed layout:

```text
←  Pi                                      Details
machine · project                 Live / Saved

Transcript          Tools          Info
──────────────────────────────────────────
You                                      12:40
Investigate this failure.

Pi                                       12:41
The failing boundary is …

    native code block / selectable output

──────────────────────────────────────────
Follow live output   [on/off]       Latest
```

- Bars and tabs occupy layout space; the viewport never extends behind them.
- No floating pills, FABs, pulse dots, unread-looking counts or tool icons in
  the session access control. Tools is a plainly named tab/action.
- Follow and Latest occupy a stable in-flow action row. New-answer indication
  must not insert/remove rows or change the reader's height. Latest represents
  meaningful new transcript content, not a tool update or render callback.
- Tools and reasoning update their own history surface, not transcript rows,
  answer boundaries or scroll commands. Explicit aborted/error outcomes remain
  recorded and inspectable with their actual source semantics.
- Full native message/code reading, selection and copying remain available.
  Do not construct tests around text hidden by a preview and call that a scroll
  defect. Long-content presentation and bounds must be explicit in the preview.
- Saved content is timestamped and read-only; historical status or permissions
  must never look actionable/current. No implicit live resume or archive/live
  merge is added.
- Missing canonical/session preparation is a setup state, not silent terminal
  scrollback presented as equivalent native conversation readiness.

## Tool history: honest labels

Counts are explanatory information inside tool history, not attention badges.
For the existing example, show separate labels such as:

```text
Shown from loaded history       100
Loaded operations               171
Loaded operations not shown      71
Failed results among those shown  1
```

These are bounded loaded records, not unread activity, a running queue, total
conversation lifetime or necessarily complete failures. Missing historical
results stay “Result not recorded,” never invented pending/current work.

## Theme and appearance

- Separate System/Light/Dark mode from the named full palette.
- Use representative miniature app/session previews, not just colored dots;
  names and a selection indicator supplement color.
- Preserve current preferences and include Catppuccin as a selectable option.
  The final preset catalog/default is decided with the native previews, not
  silently inferred from the old single-palette implementation.
- Inspect each adopted scheme's provenance/license. Keep normal text contrast
  at least 4.5:1 and large text at least 3:1, including status and syntax tokens.
- Palette changes update the native theme boundary without recreating/resetting
  the selected reader, reconnecting machines or changing scoped drafts.
- Dynamic color and pure-black dark mode are source-backed options to evaluate,
  not capabilities already delivered. Unsupported choices need an explicit
  fallback rather than a hidden theme failure.

## Reader state and jump acceptance

The UI rewrite must address behavior, not merely replace visuals.

- Separate transcript content, tool/reasoning history, and identity/status
  presentation inputs. A tool result, count, working-state update or metadata
  refresh cannot become an answer/follow event.
- Keep canonical reader identity and item keys stable across refreshes; never
  bind position to a reusable pane alone or recreate list state on status polls.
- Preserve the visible item/offset while reading. History trimming is not a
  new-answer signal; if a bounded source removes an anchor, disclose the
  limitation rather than silently pretending position was preserved.
- Rendering completion is not new content. Async Markdown/highlighting,
  refresh, tab switches and palette changes cannot independently scroll the
  reader. Active following may settle a genuine new answer at its end, but
  cannot re-arm itself from tool-only traffic.
- Real acceptance includes live tool-only growth, result/error updates,
  thinking changes, long replies, user-paused reading and switching away/back
  across conversations/providers. Record identity and viewport geometry without
  publishing private transcript content. Synthetic tests remain supporting
  evidence, not owner acceptance.

## Subsequent owner feedback: composer, noise and alerts

The owner accepts the three Transcript/Tools/Info tabs and the tidier
Following/Latest row, but rejects the missing input and excessive explanatory
wording. Add a bottom native composer; keep identity/state/content prominent
and explanations in Info rather than the transcript. A disabled preview Send
is not implemented live delivery. Existing prompt endpoints must be reviewed
for identity, stale-action, capability, acknowledgement and draft-preservation
behavior before wiring the new live consuming UI. Saved readers stay read-only.

The palette scope is capped at three: Default, Dynamic and Catppuccin. These
are a practical selected set, not a measured popularity ranking.

Completion alerts are a new requested capability: configurable Android system
notifications and/or Telegram in Settings. Settings shown in a synthetic design
preview do not activate either channel. Required acceptance includes:

- Opt-in channel and session/machine scope; explicit permission/configuration
  states, no unsolicited notifications or secret-bearing screenshots.
- Verified session completion, not tool results, first-loaded idle state,
  historical failures, scrollback guesses or replay after reconnect.
- Duplicate/stale transition rejection and independently scoped machines;
  selected-stream-only attention is insufficient for all-session alerts.
- Explicit foreground/background/closed-app delivery semantics. Existing
  foreground polling does not prove background completion delivery.
- A selected Telegram route, credential ownership and separately authorized
  infrastructure changes; no fabricated delivery or automatic bot deployment.

The completion-event contract and background route remain unresolved. No
notification permission is requested and no real prompt, approval or Telegram
message is sent merely to review the UI.

## Delivery and verification

Use the workstation-task playbook; this is continuing tier-1 work. Proposed
owned paths are Android presentation/theme preferences and owning design/tests;
core identity, bridge and persistence contracts are retained. Any required
foundation change must be separately justified and gated.

1. Owner reviews native previews of the shell, session and palette chooser.
2. Implement thin theme/shell/reader slices, retaining the prior build until
   the new consuming path is ready. No speculative generic navigation framework.
3. Run `make -C vendor/cappuccino validate-android` and
   `make -C vendor/cappuccino md-check`; native tests use a task-owned emulator
   or isolated application identity, with exact method/test counts recorded.
4. For an authorized phone install, validate a private backup, use
   `adb -s <authorized-device> install -r <gated-apk>`, and compare existing
   private data afterwards. Never run Gradle connected-test cleanup or uninstall
   against the owner's app. Deleted original data is not claimed as recovered.
5. Require the real consuming phone journey and owner visual/live-reader
   acceptance. Preserve the historical native failures and independent-review
   limitation until actually resolved; do not mark the whole rewrite DONE.

No delegated agents or Asobi, per the owner's standing instructions. No remote
agent restart, owner prompts/approvals, proxy changes, merge or issue closure.
