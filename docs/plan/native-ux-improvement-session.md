# Native UX improvement session

Status: direction reset by the owner to a full Android UI rewrite. The whole app follows Mihon; only the core agent session uses a terminal-inspired native layout. Theme system and app shell are both priorities. Floating controls are rejected, and the live jump fix is rejected across conversations. See the [rewrite contract](../design/mihon-shell-terminal-session.md); detailed native previews still require review. Earlier implementation/gate evidence below is historical, not acceptance of the current UI. Apple consuming parity remains outside this first slice. No whole-flow acceptance is claimed.

Tracking: [issue #33](https://github.com/azusachino/cappuccino/issues/33).
Source studies: [Telegram, Nekogram and Haven](../design/telegram-nekogram-ux-study.md), [native highlighting](../design/native-code-highlighting-research.md), [Paseo](../design/paseo-source-study.md).
Existing reader contracts: [Pi phone reader](../design/pi-phone-reader.md). Apple execution remains in [iOS parity](ios-ux-parity.md); unresolved canonical/input/tail work remains in [Pi workspace](pi-native-workspace.md). This plan owns the current continuation order, not a declaration that those plans are complete.

## Outcome and baseline

The earlier cosmetic-only direction is superseded. Rewrite the Android presentation and theme system around the owner-approved split, preserving the backend, canonical identity, independent machines and private data. Do not apply terminal styling to the whole app or retain floating controls merely because older previews/tests passed.

Current working baseline:

- Active agents are visible directly beneath their machine; no expansion click or intermediate Agents screen.
- Navigation is Machines, Attention, Settings. Back from a conversation preserves the machine list position.
- Native Pi session binding, structured tool arguments/results, disclosures, bounded previews and full-content access are present.
- Owner-selected Catppuccin Latte/Mocha app and code palettes follow System/Light/Dark selection; semantic agent/tool states remain readable without color alone.
- Machine names are readable and locally editable. UUID/pane/session IDs are not normal user-facing details.
- Connection metadata exists, but its current alert is too verbose and is not accepted as the intended design.

Current implementation checkpoint: `946e25c` on `feat/native-pi-rendering`, pushed after local bridge/Android/Markdown gates passed. The owner subsequently authorized pushing, updating the Mini bridge and reinstalling Android. The installed offline-build APK SHA256 is `3a752804220093bc2cd940f13a36a839954383de3d64e72558d9f4de99df210b`; `adb install -r` succeeded on the authorized phone and the app was launched, preserving data. This is not an executed native saved-reader journey. Mini's isolated Linux full gate/build and bridge-only rollout passed. The saved-reader journey survived app reconstruction while Mini was locally disconnected; both machine connections were restored. See [offline checkpoint](../evidence/offline-history/README.md) for artifact hashes, evidence boundaries and retained failed initial navigation attempt.

The initial plan's `a4c48668b50a43b42ae03496d8d1163ca54d4639` and APK `d583bd28c2e4903a968c153fe1f096d6a889a5b525a0ac4f3f2ed3335d293ca9` are historical entry evidence, not the current baseline. Compare actual Git and artifact identities before resuming.

Owner confirmation of basic usability does not establish full rendering/tail acceptance, canonical Agy support, native Pi input semantics or complete app/TUI parity. The older [Pi workspace plan](pi-native-workspace.md) is not overwritten or declared complete.

## Design contract

- Content first: show active work and useful state; remove explanations that merely restate the interface.
- One row/menu vocabulary across Machines, Settings and the reader. No collection of independently styled cards.
- Haven informs named-target continuity and contextual Herdr actions. Telegram/Nekogram inform readable chat, preference rows and selective disclosure. Borrow principles, not their feature breadth, custom rendering or copyleft implementations/assets.
- Catppuccin supersedes GitHub/coffee/dynamic palettes. Keep native Compose/Material and SwiftUI components. Do not assume changing an alert to a sheet alone constitutes a design improvement.
- Paseo informs native token spans, bounded replicas, explicit liveness and cursor catch-up, not an Expo migration or new orchestrating daemon. Do not import its WebView Mermaid path or its missing shell-language coverage.
- Normal machine information is compact. Technical metadata is opt-in. Warn only about an observed condition that affects use; do not infer health or compatibility from matching package versions.
- Preserve exact session identity, passive external content, image ALT handling, device-local timestamps, manual reading/inspection, composer drafts/reset and Activity-owned system bars.

## Scope and exclusions

Owned implementation areas are Android `ui/`, focused core/I/O and tests, activity wiring and owning design/evidence documentation for the approved reader/offline slices. Apple parity owns shared Apple code and tests under its linked plan. Avoid unrelated ViewModel rewrites. Snapshot storage stays private and separate from profiles, drafts, preferences and temporary cache.

The bridge already supplies the metadata needed for the initial machine-information improvement. No bridge change or repeated bridge soak is needed for presentation-only slices. The owner's added requirement to expose tool failures does require preserving Pi's `isError` flag as an optional `is_error` tool-part field; that scoped addition requires the full bridge gate before activation. If a real protocol/backend requirement emerges, stop and re-scope it with its own acceptance and full bridge gates.

No terminal embedding, WebView, library fork, remote session creation/renaming/deletion or SSH fallback. The owner separately authorized native highlighting and bounded offline history with accurate storage controls; the old blanket tokenizer/dependency/settings exclusion no longer blocks those slices. The Android TextMate dependency is already adopted; further tokenizer/runtime adoption needs a scoped license, compatibility and performance decision. No emulator restart, device setting changes, prompt/approval submission, agent session manipulation or proxy/auto-apply changes. Preserve app data and all existing dirty work. Frequent focused green commits are owner-authorized. The subsequent owner request authorized pushing the feature branch and updating Mini's bridge; no merge, issue closure, proxy/cluster changes or remote-agent session actions are authorized.

Execution is direct and sequential: no delegated agents or Asobi, per owner instructions. Track progress on issue #33, with this document as the plan; do not create a second task-record system.

## Session entry: protect the working baseline

Before edits:

1. Read owning instructions and this plan; compare branch/commit, installed artifact and current diff with the recorded baseline.
2. Inspect the authorized phone's actual current screens and capture baseline evidence under `.tmp/cappuccino-phone-debug/ux-improvement/`. A black image is not evidence. Do not interact with remote prompts or approvals.
3. Record existing failed tests separately from the new slice's regressions. Preserve raw evidence and do not reset, stash, rebaseline or weaken assertions to manufacture a clean baseline.
4. State the first slice, owned files, acceptance criteria and checks. Keep the app usable at every checkpoint.

## Current continuation order and evidence

The owner approved this order after the Paseo study. The original UX slices below retain their acceptance criteria; their old cosmetic sequence does not override the current priorities.

| Slice | Implemented | Owning gates | Device proof | Owner acceptance |
| --- | --- | --- | --- | --- |
| Catppuccin/semantic colors (`df86042`) | Yes | Android passed | Installed; whole visual matrix pending | Palette choice approved; rendered acceptance open |
| Native Android highlighting (`fd7fd54`) | Yes, initial language set | Android passed | Installed; native selection/performance journey pending | No complete highlighting acceptance |
| Compact headings (`6ffa44d`) | Yes | Android passed; native regression compiled | Installed; regression not executed | Compact mobile requirement approved |
| Recorded tools/results (`8247404`) | Yes | Android passed | Installed; consuming tools journey pending | No live-queue/delivery claim |
| Offline/canonical identity (`4c430fd`, `946e25c`) | Committed and pushed | Local full bridge/Android/Markdown gates passed | Android installed; Mini rolled out; scoped app-restart/read-only journey executed, full native matrix pending | Implementation/rollout authorized, not accepted as complete |
| Apple identity/profiles/connections (`083bea7`, `610eae7`, `cabbc9c`) | Core foundations only | Apple gates passed | Consuming multi-machine UI journey pending | No UI parity acceptance |

### Next: complete offline-history acceptance

- Resolve reusable Herdr pane locators versus immutable canonical conversation identity from the actual bridge/session binding. A verified machine UUID plus locator is not proof of canonical generation. Do not invent an ID, merge cached/live histories, or silently expand the bridge contract.
- Persist only owned verified snapshots. Keep schema/identity validation, bounds (20 entries, 32 MiB total, 4 MiB per entry), atomic replacement and corruption handling. Report actual saved storage separately from temporary cache; preserve profiles/drafts/preferences and remote sessions.
- Saved content has a capture timestamp and read-only label, unknown current agent status, no active queue/approval claims, no remote actions and no implicit session resume. Discovery/reconnection cannot silently promote it to live.
- Add hermetic restart/restoration, wrong-machine/conversation, stale selection/generation, failed save, foreground/background, no-send/no-answer and cleanup-preservation regressions. Re-run the Android gate for changed inputs.
- Execute a contained native journey and the authorized phone's passive saved-reader/restart check without device/network-setting changes or remote agent interactions. Gate/build/install alone does not close acceptance.

### Then: bounded native highlighting

Use theme-independent tokens and measured input/cache budgets, with full plain-text fallback when unsupported or over budget. Preserve exact source, Bash coverage and stale-result/cancellation guards. Additional Kotlin/Nix/Fish grammars need license/provenance and language-specific tests. Paseo's 100,000-character/200-entry constants are evidence of its policy, not our accepted thresholds. Verify long-block responsiveness and selection on native surfaces before claiming performance.

### Then: Apple consuming parity

Follow the [Apple plan](ios-ux-parity.md): wire independent machines, canonical passive reader, native selection/highlighting and identity-scoped state before Liquid Glass. Keep iOS/macOS gates and OS-availability/material fallbacks. Source research is not proof that a JavaScriptCore tokenizer works in our SwiftUI app.

### Separate unresolved work

- Attention currently depends on the selected stream and loses pending state on navigation. Independently scoped attention needs its own behavior/ownership tests; do not persist actionable approvals as offline history.
- Cursor-based incremental history requires a specified bridge contract, canonical generation/coverage and partial-catch-up tests before implementation. Full snapshot refresh is not cursor synchronization.
- Four historical native display/tail methods, flashing/popup acceptance and live Pi delivery/readiness remain open. Preserve raw failures and do not weaken tests or call a source fix an executed journey.
- Mihon-style selectable whole-app palettes are now in the approved rewrite scope, not deferred. Existing Catppuccin rendering is a baseline option, not proof that a chooser or the new theme system shipped.

## Original UX slices

### UX-1: Review one coherent visual direction

**Dependency:** session-entry baseline. **Scope:** documentation and reviewable visual specimens only.

Prepare Machines, machine information, Settings and conversation examples together in light/dark, using the same spacing, typography, menu policy and the current Catppuccin palette. Include one narrow/large-font case. Specimens must be identified as synthetic or actual; they are not proof of live behavior.

Acceptance:

- The owner can judge the whole interface, not another isolated color/control adjustment.
- Normal, offline and limited/error states have clear hierarchy; the technical view is visibly subordinate.
- The owner approves the direction and first slice before production implementation.

Verification: compare specimens with the source study and baseline; review on the phone or as correctly labeled images. ASCII study sketches are hierarchy proposals, not final visual approval.

Likely paths: `docs/design/telegram-nekogram-ux-study.md`, `docs/design/pi-phone-reader.md`, scratch specimens. Do not install an unreviewed redesign as a substitute for this checkpoint.

### UX-2: Replace the diagnostic dump with useful machine information

**Dependency:** UX-1. **Scope:** small native information flow, roughly 3–5 files.

Show machine name and connection state first, a compact App/Bridge version pair, and a Technical details entry. Use the approved native sheet or compact information surface; longer technical content has a separate reader/view, not a second stacked modal. The exact surface follows UX-1 approval rather than an unconditional sheet mandate.

Acceptance:

- Successful connections need no protocol/capability paragraph; useful information is visible without scrolling through a report.
- Technical details retains real verified metadata, missing-versus-unsupported distinctions and last-verified labeling. No UUID/pane/session fields return.
- A conflict/limitation states its impact and only offers an action the app can actually perform. Opening/dismissing information neither disconnects nor changes a remote session.

Verification: focused diagnostics tests for legacy/missing metadata, supported/unsupported flags, offline/cached metadata, incompatible protocol and wrong-profile isolation; physical open/dismiss/Back review on the authorized phone.

Likely paths: `ui/ConnectionDiagnostics.kt`, `ui/CappuccinoShell.kt`, a small native machine-information component if needed, `MachinesJourneyTest.kt`, diagnostics tests. Keep the existing handshake contract unchanged.

### Required correction: independent MacMini and Linux-mini ownership

The owner reiterated that multiple server devices were an original must-meet goal and requested immediate implementation, not another design checkpoint. The earlier single-active-server assumption was incorrect. This explicitly authorizes independent profile-keyed roster/connection ownership and foreground discovery refresh, ahead of later cosmetic slices. One foreground conversation remains a viewer choice only. Both connected server branches stay visible; failures, Add, Refresh, Disconnect and Remove are isolated. Global Refresh is explicitly Refresh all; global Close is removed. No remote session actions or implicit Linux deployment are authorized by this client correction.

Acceptance: two distinct verified machine UUIDs with identical agent locators stay separately addressable; selecting one does not cancel the other's discovery; a second server failure/failed Add/manual Disconnect preserves the first's conversation and connection; removing another profile preserves theme and selection; draft/reading/disclosure state is machine-and-agent scoped. Foreground/background lifecycle owns discovery requests; disconnected nodes need an explicit reconnect. Synthetic transport/native fixtures and gates are not proof against the physical Linux server or owner visual acceptance.

### UX-3: Make navigation and contextual actions consistent

**Dependency:** UX-2. **Scope:** Machines and conversation app-bar/menu paths, roughly 2–4 files.

Keep active agents immediately visible. Use predictable named contextual actions instead of prominent ambiguous disconnect X controls and competing Details buttons. Retain the centered conversation identity/Back geometry. Local saved-machine removal gets a focused confirmation that does not imply deletion of the remote machine or agents.

Acceptance:

- Open an active agent directly, inspect information, and return to the same list position with no extra discovery step.
- Refresh, local disconnect, rename and remove have distinct meanings and operate on the chosen machine; dismissing a menu does nothing.
- Machines, Attention, Settings order remains; agent status is readable without color alone. No unsupported remote operations are added.

Verification: native journeys for direct-open/Back, menu scoping, rename persistence, cancel/remove confirmation and offline/retry. Review title alignment and touch targets in normal and large-font states.

Likely paths: `ui/CappuccinoShell.kt`, machine-information/menu component, `MachinesJourneyTest.kt`, relevant shell tests.

**Checkpoint A:** owner reviews machine information plus navigation on the phone. Stop if the result is not the desired direction; do not continue polishing other screens around a rejected pattern.

### UX-4: Apply the same preference-row grammar to Settings

**Dependency:** Checkpoint A. **Scope:** roughly 2–3 files.

Short values such as Theme and App version align opposite their labels. Supporting text is reserved for a needed explanation. Machine-specific information stays with the machine rather than duplicated as a global connection report. Theme selection is a short, accessible choice using the approved menu/dialog convention.

Acceptance:

- Settings scans as grouped preference rows, with no redundant descriptions or invented controls.
- System/Light/Dark selection persists, updates app/code/system-bar appearance correctly, and announces the selected state accessibly.
- Long labels and large fonts remain usable; readable layout takes precedence over copying a fixed upstream row height.

Verification: native choice/dismiss/persistence journey, existing theme/token-color regressions, physical light/dark and large-font inspection. Investigate the source-observed `removeProfile` theme-reset risk; fix only if reproduced and separately tested.

Likely paths: `ui/SettingsScreen.kt`, focused UI tests and, only for a confirmed retention defect, `io/ConnectedViewModel.kt` and its tests.

### UX-5: Reduce conversation chrome without losing tool content

**Dependency:** UX-1; execute after UX-4 to preserve sequential review. **Scope:** roughly 3–5 files.

Keep assistant prose primary, thinking secondary and tool operations compact. Use the real operation/command and concise state; remove repeated generic labels and nested decoration. Tools and errors stay outside chat. Short details expand within Tools; long content uses the approved full reader. Keep native highlighting and Catppuccin token mapping, with source-preserving fallbacks.

Acceptance:

- Commands appear once at each reading level; structured arguments/results and long content remain accessible and selectable, without accidental truncation/data loss.
- Manual reading and disclosure/full-reader inspection pause follow; returning to latest is explicit. Back/dismiss preserves position and drafts.
- Light/dark code, timestamps, passive links/images and canonical-versus-scrollback distinction remain correct; generic styling does not obscure failures or approvals.

Verification: focused tool-content/Markdown tests, native expanded/full-content and manual-follow journeys, physical short/long output review. Do not send test prompts/approvals to live agents. Use contained fixtures for interaction tests, with owner-approved device execution.

Likely paths: `ui/ConversationDetails.kt`, `ui/ToolContent.kt`, optional native full-reader component, focused JVM/UI tests. Do not rewrite parsing or scroll policy merely to achieve a visual change.

**Checkpoint B:** owner reviews Settings and the conversation reader together with the already-reviewed machine screens. If the interface still lacks coherence, revise the specimen/design contract before further implementation.

### UX-6: Verify the approved whole flow and record the handoff

**Dependency:** Checkpoint B. **Scope:** tests/evidence/owning documentation, no new features.

Acceptance:

- Machines → agent → information/full reader → Back works with preserved position/identity; Settings and Attention follow the same conventions.
- Normal/narrow/large-font, light/dark, offline/reconnecting, missing metadata, protocol conflict, empty agents and long output have honest, usable states.
- New regressions are resolved, gates pass, owner physical acceptance is recorded, and historical gaps are named rather than silently closed.

Verification: run the applicable gates below; execute agreed contained native journeys and inspect the actual phone. Record commands, raw results, source/artifact identity and screenshots. No fake geometry, frozen clocks, timeout escalation, suppressions or skipped/deleted tests.

## Gates and review cadence

- At each implementation slice: focused regressions while editing; `make fmt-android` and `make validate-android` from the owning repository before an APK update. Documentation changes also run `make md-check`.
- `validate-android` checks compilation/JVM tests/lint and builds app/test APKs; it does not execute native interaction tests or establish visual acceptance.
- Native interaction tests need an explicit contained fixture/device plan. Do not implicitly restart the stopped emulator or execute a disruptive test suite against the owner's live phone.
- No repeat Rust soak for unchanged bridge code. A scoped bridge change requires `make check-bridge`, including its mandatory soak, before activation.
- Install with data preserved only after gates pass; pause at each owner review. Do not accumulate multiple rejected slices and describe them as finished.
- Four historical D1 display/tail methods remain unresolved. A new plan does not turn their failures into passes or authorize omitting them. Resolve relevant failures or obtain an explicit owner-approved disposition before claiming the affected acceptance scope complete; retain their evidence and remaining coverage limitations.

## Decisions still needed

UX-1 settles the information surface, row density and tool/full-reader treatment using a coherent specimen. The source study is the rationale, not aesthetic acceptance. No open question justifies changing the already-working navigation or adding platform features.

Action-error versus transport-error classification and any reproduced theme-retention defect are small separately tested corrections, not permission for a ViewModel rewrite. Canonical Agy lookup, native Pi queue/delivery semantics and full renderer/tail acceptance remain separate work.

## Closeout

Link results to issue #33 and owning design/evidence records. Report changed paths, branch/commit and uncommitted work, gates versus executed device checks, installed APK identity, owner decisions and remaining failures. Continuing work gets a concise handoff there under the explicit no-Asobi workflow. Do not mark a rejected/partial slice accepted, or commit/push/close broad issues as part of this plan-writing request.
