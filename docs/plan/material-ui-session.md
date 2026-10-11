# Material UI improvement session

## Intent and ownership

The owner considers the overall phone UX good, but wants a full visual improvement using F-Droid 2's native Material direction, not another isolated chat restyle. Android dynamic colors were explicitly selected, with a fixed fallback and GitHub syntax colors retained inside code blocks. Scope is Android presentation; bridge/session semantics and deployment are unchanged. Work is direct, with no Asobi or delegated agents, as requested. Existing native acceptance gaps remain open.

Baseline: `08f0408` records the bridge/Android/design checkpoints; `84fb775` fixes Settings' remaining single-active-machine connection shortcut. Future slices get their own tested commits. No pushes or merges are authorized by this session.

Primary reference: [F-Droid 2.0 official announcement](https://f-droid.org/2026/09/24/f-droid-2.0-a-new-chapter-for-android-freedom.html), which identifies Kotlin Compose and Material Design. Do not assume Material Expressive, copy GPL implementation/assets, or add dependencies merely to imitate it.

## Visual direction

- Use the installed Compose Material 3 components and semantic tokens. Wallpaper-derived colors on Android 12+, fixed light/dark fallback otherwise; System/Light/Dark remains independent of palette selection. No wallpaper or phone settings are changed.
- Machines, Attention and Settings remain the three destinations. Standard app bars, navigation, list-row density and tonal grouping replace ad hoc chrome. Keep per-machine Refresh/Disconnect inline and Details/Rename/Remove text-only in overflow.
- Conversation retains directional coherent message blocks, compact contained timestamps and the full reader. A compact app bar identifies agent/machine/workspace. Keep follow controls discoverable without letting chrome dominate reading space.
- Tools and Jump remain one quiet floating control above the IME-aware composer, with numeric failure information; ordinary tools/errors do not re-enter chat. No flashing surfaces.
- Settings uses consistent preference rows and per-machine Connections. Sheets, dialogs and technical details share the same type scale, spacing, surfaces and native controls.
- UI strings/statuses must remain truthful. Connection/prompt-delivery errors remain actionable; non-actionable provider response failures stay out of chat and remain available diagnostically.

## Ordered slices

1. Enable the existing dynamic Material theme by default; test both modes, unsupported-API fallback and explicit fixed previews.
2. Redesign Machines/Attention/Settings hierarchy and chrome, with rendered light/dark specimens before expanding the change. Test long labels, empty/loading/error/disconnected states and independent machine details.
3. Harmonize conversation app bar, follow control, message surfaces, composer and floating controls without changing scrolling, drafts or identity.
4. Harmonize all sheets/dialogs/full readers; keep disclosure, selection and passive media behavior.
5. Review the complete flow on the authorized physical phone, including two connected machines, keyboard and large text. Record actual screenshots and owner corrections, then commit each verified correction.

## Verification boundary

Each Android slice uses `make fmt-android`, `make validate-android` and `make md-check`; focused tests precede broad acceptance. Instrumentation compilation is not execution. Four historical D1 display/tail methods remain unresolved and must not be skipped or called fixed by a palette change. Do not implicitly restart an emulator, change phone settings, clear app data, send agent prompts/approvals or manipulate remote sessions. Full native/independent acceptance is not established by compilation or owner visual feedback alone; record the missing coverage explicitly.

## Progress

- Settings per-machine connection routing committed and installed; Android/docs gates passed. Its native regression test compiled, not executed.
- Dynamic-theme foundation in progress. Remaining screen redesign and rendered acceptance are pending.
