# Telegram, Nekogram and Haven source study for Cappuccino

Status: research and proposed direction; no redesign approved or implemented by this note.

The owner finds Cappuccino's overall presentation unsatisfactory. Changing colors, individual controls and individual navigation steps has not produced a coherent phone reader. This study examines concrete source patterns before another implementation pass.

## Evidence and limits

Study-only shallow, sparse clones are at `.tmp/cappuccino-phone-debug/source-apps/`:

- Telegram Android: `f2908b14133bbffbf7ab04f641ecb5bfaf533242`.
- Nekogram: `577d3924c703fc70c1f72f84f62c2a07c0a5cd8f`.
- Haven: `a747f4a4ef434c1196451a6219449d6b14c85681`, including terminal/connections, shared UI and SSH session-manager source.
- Cappuccino: branch `feat/native-pi-rendering`, base `a4c48668b50a43b42ae03496d8d1163ca54d4639`, plus the current uncommitted Android/bridge work. Findings describe that working tree, not just the base commit.

Relevant Java implementations and call sites were read; these apps were not built, installed or exercised. Source establishes hierarchy and interaction mechanisms, not perceived smoothness or owner preference. Existing phone captures establish Cappuccino's former oversized connection alert and machine/agent layout; the black post-install capture does not establish acceptance of the latest diagnostics. No new screenshots are presented as proof.

Telegram and Nekogram's checked-out root licenses are GPL version 2; Haven's is AGPL version 3. Use this study for interaction principles, not copied implementations, graphics, animations or a port of Telegram's custom view system. Keep native Compose, the existing Markdown/highlighter stack and owner-selected GitHub light/dark colors.

## 1. Settings are rows with values, not explanatory panels

**Observed:** Telegram's [TextSettingsCell](https://github.com/DrKLO/Telegram/blob/f2908b14133bbffbf7ab04f641ecb5bfaf533242/TMessagesProj/src/main/java/org/telegram/ui/Cells/TextSettingsCell.java#L73-L144) separates a leading label from a trailing current value, centers both vertically, and uses ellipsis. Its normal measured row is 50 dp plus the divider. That is a source observation, not a size requirement for Compose.

Nekogram's [general settings](https://github.com/Nekogram/Nekogram/blob/577d3924c703fc70c1f72f84f62c2a07c0a5cd8f/TMessagesProj/src/main/java/tw/nekomimi/nekogram/settings/NekoGeneralSettingsActivity.java#L129-L183) compose headers, check rows, value rows and group separators. Explanations appear where a setting needs them; options are conditionally present for the current configuration. [Appearance settings](https://github.com/Nekogram/Nekogram/blob/577d3924c703fc70c1f72f84f62c2a07c0a5cd8f/TMessagesProj/src/main/java/tw/nekomimi/nekogram/settings/NekoAppearanceSettingsActivity.java#L45-L112) follow the same vocabulary rather than giving every preference a new card shape.

**Cappuccino gap:** `SettingsScreen.kt` uses supporting text for every row, even short values such as theme and version. Combined with generous heading gaps, this increases scanning distance. The real version and selected theme are useful; descriptions that restate the destination are not.

**Recommendation:** one row grammar: label, optional necessary explanation, current value or navigation affordance. Put short theme/version values opposite their labels. Let long values and large fonts wrap; do not copy Telegram's fixed height or ellipsis indiscriminately. Retain only existing, working settings.

## 2. Choices, information and confirmations need different surfaces

**Observed:** Nekogram's [PopupHelper.show](https://github.com/Nekogram/Nekogram/blob/577d3924c703fc70c1f72f84f62c2a07c0a5cd8f/TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/PopupHelper.java#L44-L82) uses checked, anchored options when a row anchor exists. Without an anchor it creates a radio-choice alert with Cancel. [BaseNekoSettingsActivity.showPopup](https://github.com/Nekogram/Nekogram/blob/577d3924c703fc70c1f72f84f62c2a07c0a5cd8f/TMessagesProj/src/main/java/tw/nekomimi/nekogram/settings/BaseNekoSettingsActivity.java#L389-L408) updates the displayed value after selection and explicitly handles insets.

Telegram's [proxy deletion](https://github.com/DrKLO/Telegram/blob/f2908b14133bbffbf7ab04f641ecb5bfaf533242/TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java#L469-L490) uses a focused alert: a specific deletion title, consequence text, Cancel and Delete. [NewContactBottomSheet](https://github.com/DrKLO/Telegram/blob/f2908b14133bbffbf7ab04f641ecb5bfaf533242/TMessagesProj/src/main/java/org/telegram/ui/NewContactBottomSheet.java#L159-L209) is a contextual form surface. [BottomSheet](https://github.com/DrKLO/Telegram/blob/f2908b14133bbffbf7ab04f641ecb5bfaf533242/TMessagesProj/src/main/java/org/telegram/ui/ActionBar/BottomSheet.java#L1629-L1699) exposes configurable swipe/outside dismissal, custom content and titles.

These sources do **not** justify the blanket claim that Telegram puts everything in sheets or reserves every alert for errors.

**Cappuccino gap:** alerts currently hold theme choices, naming forms and a long diagnostic report; tool content has a separate dialog. Information volume, not only rounded corners, makes the connection dialog feel wrong. Immediate Remove in the machine menu has no confirmation.

**Recommendation:** use an anchored choice for a short local choice; a compact native sheet for machine information; a normal reader screen for long technical/tool content; a focused alert for local removal confirmation. Do not stack a second modal over the sheet for technical details. Dismissal must not disconnect, stop an agent or lose a draft. A sheet is not automatically the right surface for long code.

## 3. Status describes the user's situation

**Observed:** Telegram's [proxy status implementation](https://github.com/DrKLO/Telegram/blob/f2908b14133bbffbf7ab04f641ecb5bfaf533242/TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java#L178-L219) differentiates the selected connection from merely available alternatives, and presents Connected, Connecting, Checking, Available or Unavailable. Ping is conditional data, not an invented always-present measurement.

**Cappuccino gap:** the new `ConnectionDiagnostics.kt` exposes package/protocol/capability details and caveats at the first level. Correct engineering data has become the primary reading task. A server's implemented endpoints do not establish whether Agy has canonical history or Pi supports a particular interaction.

**Recommendation:** show machine name and Connected/Reconnecting/Offline first. Keep a compact App/Bridge version pair available, with technical details one level deeper. Show a compatibility warning only when there is an actionable limitation or conflict. Missing legacy advertisement is unknown, not incompatible. Do not replace unsupported agent behavior with a global broken-server message.

Docker's [version command](https://docs.docker.com/reference/cli/docker/version/) separates independently versioned client/server components and describes API negotiation; [kubectl version](https://kubernetes.io/docs/reference/kubectl/generated/kubectl_version/) is an explicit diagnostic entry point. Borrow the separation and honest compatibility semantics, not their console dumps. Cappuccino currently requires protocol 1; it does not implement Docker-style negotiation or Kubernetes' version-skew policy.

## 4. The conversation header owns identity, not every action

**Observed:** [ChatAvatarContainer](https://github.com/DrKLO/Telegram/blob/f2908b14133bbffbf7ab04f641ecb5bfaf533242/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAvatarContainer.java#L279-L311) distinguishes title and subordinate status; [the header click handler](https://github.com/DrKLO/Telegram/blob/f2908b14133bbffbf7ab04f641ecb5bfaf533242/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAvatarContainer.java#L359-L365) opens profile information in eligible chat modes. This makes identity an entry point to information without turning each subtitle into a separate full-height button.

**Cappuccino gap:** the original title-plus-machine TextButton caused vertical misalignment. The recent single-row fix removes that defect in source, but the app bar still mixes identity, status and a visible Details text action. Machines also exposes a prominent refresh and an X for disconnect, whose meaning is not obvious.

**Recommendation:** one aligned Back/icon/title group and one overflow action. If a subordinate machine/status line is needed, it must fit the same deliberate header geometry, not add another button row. Put infrequent refresh, machine information and explicitly named local disconnect actions in predictable menus. Do not use an X to imply closing/stopping a remote agent.

## 5. Reading and returning to live output are separate intents

**Observed:** Telegram's [chat scroll listener](https://github.com/DrKLO/Telegram/blob/f2908b14133bbffbf7ab04f641ecb5bfaf533242/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java#L6890-L6928) tracks position, scroll direction and page-down visibility. Its list orientation and thresholds belong to Telegram's implementation; this excerpt alone is not proof of its entire arrival/auto-follow policy.

**Cappuccino gap:** thinking/tool disclosures, Arguments/Result headings, code headers and full-detail buttons can create a stack of competing containers around a single operation. A chat app has no directly equivalent agent-tool transcript, so copying bubbles does not solve that hierarchy.

**Recommendation:** assistant prose remains the primary reading content. Thinking stays secondary and collapsed by default. A tool row should lead with the operation/real command and a concise state, not repeated generic labels. Expand inline for useful short details; use a full reader for long content. Keep structured arguments, results, passive links/image captions and full-content access. Show a return-to-latest control only when following is paused; inspection and manual scrolling must not pull the reader back down.

Do not assume that the four historically failing D1 display/tail methods are solved by a visual redesign. Their evidence and unresolved status remain separate acceptance work.

## 6. Machines should expose work immediately

There is no direct Telegram equivalent to Cappuccino's machine/agent tree. This is an owner requirement, not an upstream-derived feature.

Keep active agents visible under their machine without expansion or an intermediate Agents screen. The existing single-active-machine connection model constrains other machines: never fill inactive nodes with unverified or foreign cached agents. Preserve position on Back. Keep Machines, Attention, Settings in that order.

Do not introduce chat folders, search, unread counts, new settings, account switching or multi-machine background polling just because Telegram has them. Attention currently depends on observed pending-prompt state; a global all-machine badge needs data the current client does not have.

## 7. Haven is the closer Herdr workflow reference

The owner reports that Haven feels better for Herdr. That experience matters, but the inspected default-branch revision is not confirmed to match the installed version. The package query for `sh.haven.app` on the authorized phone returned no version; no Haven app was opened or controlled.

**Observed transport boundary:** Haven's [SessionManager.HERDR](https://github.com/GlassHaven/Haven/blob/a747f4a4ef434c1196451a6219449d6b14c85681/core/ssh/src/main/kotlin/sh/haven/core/ssh/SessionManager.kt#L45-L49) builds SSH commands to attach to a named Herdr session and enumerate it with `herdr session list --json`. Its kill operation stops/deletes that remote session. The [terminal feature documentation](https://github.com/GlassHaven/Haven/blob/a747f4a4ef434c1196451a6219449d6b14c85681/docs/features/terminal.md) describes a VT100/xterm terminal with Herdr auto-attach. The inspected path does not establish a native Pi structured-reader dashboard: some of the visible workspace/pane/agent interaction is Herdr's TUI inside the terminal. None of those commands were executed for this study.

**Observed identity:** [TabTitle.kt](https://github.com/GlassHaven/Haven/blob/a747f4a4ef434c1196451a6219449d6b14c85681/feature/terminal/src/main/kotlin/sh/haven/feature/terminal/TabTitle.kt) separates an owner/session label from program-generated terminal titles. A preference preserves the named multiplexer session instead of letting a program title replace it; [TabTitleTest](https://github.com/GlassHaven/Haven/blob/a747f4a4ef434c1196451a6219449d6b14c85681/feature/terminal/src/test/kotlin/sh/haven/feature/terminal/TabTitleTest.kt) covers that precedence. Tests were read, not run. Adopt stable human labels for presentation, never terminal titles as canonical Pi identity.

**Observed contextual controls:** [TabActionsMenu](https://github.com/GlassHaven/Haven/blob/a747f4a4ef434c1196451a6219449d6b14c85681/feature/terminal/src/main/kotlin/sh/haven/feature/terminal/TerminalScreen.kt#L233-L373) groups session actions in one scoped menu. It refreshes remote-session data on opening, shows a loading state, disables impossible reordering, and conditionally offers Details, Rename and Save connection. [TerminalViewModel](https://github.com/GlassHaven/Haven/blob/a747f4a4ef434c1196451a6219449d6b14c85681/feature/terminal/src/main/kotlin/sh/haven/feature/terminal/TerminalViewModel.kt#L3392-L3424) determines whether details/save actions are available from real transport/session state. This is a useful contrast to advertising a broad capability list to explain every possible action.

**Observed shared navigation vocabulary:** [SessionPickerDialog](https://github.com/GlassHaven/Haven/blob/a747f4a4ef434c1196451a6219449d6b14c85681/core/ui/src/main/kotlin/sh/haven/core/ui/SessionPickerDialog.kt#L44-L177) is reused at connect time and for a new tab. Existing session names are directly clickable rows. Restore, rename, kill and plain-shell actions are conditional parameters; an enumeration error appears next to the task it interrupted. This is not permission to add session creation, remote deletion or SSH fallback to Cappuccino.

**Observed information design:** Haven's [WhereaboutsDialog](https://github.com/GlassHaven/Haven/blob/a747f4a4ef434c1196451a6219449d6b14c85681/feature/terminal/src/main/kotlin/sh/haven/feature/terminal/TerminalScreen.kt#L2896-L2978) actually uses Compose `AlertDialog`, not a bottom sheet. It groups destination, route and session identity, and shows a room-mismatch warning only on mismatch. It also includes an internal door ID, which conflicts with this owner's explicit preference and must not be adopted. This strengthens the conclusion that hierarchy and useful information matter more than substituting one container component for another.

**Cappuccino recommendations from Haven:**

- Keep the active work visible and directly selectable; no machine-expansion step.
- Give each target a stable owner-understandable label and one context menu with actions that actually work for that target.
- Present current task failure locally: discovery failure, transcript unavailable and protocol conflict are not the same problem.
- Keep machine identity, app/bridge versions and advanced transport/protocol data at different information levels. Warn when an observed condition changes what the owner can do, not merely because metadata exists.
- Share menu/row conventions across entry points; do not create a slightly different control scheme in Settings, Machines and the reader.
- Preserve native structured conversation and exact Pi binding. Do not embed a terminal, invent canonical history from titles, execute Herdr stop/delete/attach commands, or inherit Haven's keyboard/SSH/session-management feature breadth.

Haven is the primary reference for task continuity and contextual Herdr controls; Telegram/Nekogram are complementary references for readable chat and preference hierarchy. Neither is a wholesale visual specification. The owner still needs to approve the proposed native treatment.

## Proposed direction for review

One quiet, row-based interface with GitHub colors; technical content remains accessible but does not define the normal interface. These are information-hierarchy sketches, not rendered prototypes or approved geometry.

```text
Machines                                  +   ⋮

MacMini.local                         Connected
  π  Pi                              Working
     main
  ✦  Agy                             Idle

Machines             Attention             Settings
```

Machine name/status is a compact grouping row, not a hero card. Agent labels and state carry the useful content. Add and menus remain accessible actions; no select-machine instruction or saved count.

```text
Machine information                         —
MacMini.local
Connected

App version                               0.1.0
Bridge version                            0.1.0
Technical details                             ›
Rename                                        ›
```

Normal connection information has no compatibility paragraph. A verified incompatibility replaces the success state with the specific problem and a valid next action. Technical details is a separate screen, retaining the existing verified metadata and error distinctions without UUID fields.

```text
‹   π  Pi                                     ⋮

    assistant prose / native Markdown

    › bash · completed
      cargo test ...

                 ↓ Latest   [only while paused]

    composer or concise read-only explanation
```

The tool remains an agent-specific disclosure, not a copied Telegram message bubble. The actual command appears once. Timestamps and supporting context are quiet but readable; status is never color-only.

```text
Settings

Appearance
Theme                                      Dark

Machines
Manage machines                               ›

About
App version                               0.1.0
```

Machine-specific connection information belongs to the machine, not duplicated as a global Settings connection panel. This relocation is proposed, not applied.

## Before implementation

1. Owner reviews hierarchy and information placement, particularly the compact machine sheet and long-content reader. Source similarity does not settle taste.
2. Prepare a coherent visual specimen using existing real/synthetic local content, clearly identified, without changing live agent sessions. Review light and dark versions together rather than installing another isolated control change.
3. Check short/wide labels, long commands, missing metadata, offline/reconnecting states, protocol conflict, empty agents, long output and large fonts. No invented health measurements or fake geometry.
4. Confirm Back/dismiss/scroll/draft behavior and accessible names, selection states, focus, touch targets and non-color status. Preserve Activity-owned system bars and passive external content.
5. Only after design review, implement one approved slice and run its owning gates. Source reading and compilation do not establish physical UX acceptance.

## Code/data gaps to carry separately

- `ConnectedViewModel.removeProfile` constructs a fresh `ConnectedUiState`; the source defaults include System theme. Investigate whether removing a profile transiently resets an owner-selected theme. This is a source finding, not a device-reproduced bug.
- Prompt/answer failures currently become `ConnectionState.Error`; distinguish action failure from transport failure before offering a generic reconnect remedy.
- Bridge capability flags advertise endpoints. Agent-level readiness, canonical Agy identity, native Pi input semantics and full Pi/TUI parity remain unestablished.
- A consistent menu/sheet policy and row density have not yet been applied. The latest installed connection diagnostics remain intentionally unchanged during this study.

No app/bridge code, device settings, proxy, remote prompts or approvals were changed for this research. No commits, pushes or issue closure are implied.
