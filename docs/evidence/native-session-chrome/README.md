# First real session-chrome slice

This is a partial Android presentation checkpoint, not full UI/controls parity
or acceptance of the previously rejected live scroll behavior.

## Verification

- `make validate-android` passed after fixing a nullable cwd and replacing the
  deprecated Material `TabRow` with `SecondaryTabRow`; neither diagnostic was
  suppressed. Unit XML totals: **156 tests, zero failures/errors/skips**.
- Installed debug APK SHA256:
  `b3fbdb6472eee1ec8a6739db211b9552db512c62b5950799cb64c2794c124e88`.
- Authorized phone update used `adb install -r`; a fresh stopped-app private
  backup was validated first. All four existing private files matched
  byte-for-byte after installation and **before** launching the live app.
  Live archive refresh can legitimately write new saved snapshots afterwards.
- Launched the real MainActivity, not the debug synthetic preview. Both
  connections displayed Connected. Opened the discovered Mini Pi reader,
  verified Transcript/Tools/Info tabs and the real composer, then inspected
  Tools and canonical-source Info and returned to Transcript. No prompt,
  approval, command, model change or agent lifecycle action was submitted.
- Phone screenshots/hierarchies contain private live content and remain under
  `.tmp/cappuccino-mihon-preview/phone/`; they are not published here.

## Production appearance and tool awareness checkpoint

Owner follow-ups expanded the catalog to Default/Dynamic/Catppuccin/GitHub/One
Dark, restored the colored agent-detail status and required a Tools counter.
APK: `77493b3374e3b70aebcd81d2b38179382e4b07ac44318970fb3623978e14167e`.
Full Android and Markdown gates passed; **170 unit tests, zero failures,
errors or skips**. Unit checks cover fixed-palette normal-text contrast in both
modes, stable preference IDs/default migration, view-model preference
restoration without profile/mode changes, and call/result counter transitions.

Four-file stopped-app backup/install-r/prelaunch byte comparison passed again.
On the actual Appearance page, all five choices were visible and selected in
turn; each stored its expected preference. The original palette and brightness
were restored, all existing profile/preference values were compared unchanged
apart from the deliberate palette key, and the chosen preference survived
restart. An initial navigation attempt stopped without a tap because the app
restored its selected session, not the assumed Machines page; continued using
native Back and observed navigation rather than blind coordinates.

A live session showed a readable blue Working background. No prompt, model
change or approval was sent. New tool calls and first recorded returns each
increment the selected-session counter; initial history and repeated/trimmed
snapshots do not. Opening the newest tools acknowledges it, while older
inspection retains unread awareness. During a passive check of a naturally
working session, the tab displayed a nonzero counter and opening newest Tools
acknowledged it; no artificial prompt/action was used to produce activity.
Exact per-event count parity and arrivals during older inspection were not
audited by that smoke. These are not all-session/system/Telegram completion
alerts.

Private screenshots and preservation records remain in scratch. Full native
font/theme/performance matrix and owner visual acceptance remain open.

## Subsequent showcase-target checkpoint

The owner rejected the first slice as too little visual change and narrowed
navigation to Session/Tools. The subsequent APK is:
`333dbf843a217a2714f773003a3dd3d77912c767ace8f9944d35a5b8449114ee`.
Full `validate-android` and Markdown gates passed; **158 unit tests, zero
failures/errors/skips**. Two added tests cover ordinary multiline transcript
retention and Unicode-safe inline-budget clipping. The earlier redundant
saved-state condition warning was fixed, not suppressed.

The real consuming renderer now uses compact monospace body typography with
font scaling preserved, flat user blocks/plain assistant surfaces, timestamps
in the role header and inline text up to 16,000 code points; larger content
retains full-reader access. The composer is a single multiline container with
its Send and Follow/Latest controls integrated. Source support for native
Markdown, passive content and highlighting is retained.

Another validated four-file backup/install-r/hash comparison passed before
launch. On the phone, verified Session/Tools with no Info tab, actual composer,
controls hidden at the bottom, controls appearing after scrolling away, and
controls hiding after Latest returned to the bottom. Opened the Tools panel
and returned to Session; no prompt/approval was sent. Tools are descending
source order, auto-pinned at the top until deliberate older inspection; live
arrival/inspection behavior is not yet proven by this passive smoke.

Screenshots remain private scratch. Owner showcase acceptance, full historical
native matrix, tool-only live-growth and long-content performance remain open.

## Delivered in this slice

Fixed native tabs, separate in-flow tool-history panel, fixed Follow/Latest
controls, flat full-width turn surfaces and preserved existing prompt route.
Removed the reader's floating tool/pulse/badge controls and machine Add FAB;
Add is now a toolbar action. Saved-reader and canonical/scoped foundations
remain unchanged.

## Not verified or finished

The phone check is a passive consuming smoke, not the historical native matrix
or a fresh independent agent review. Tool-only live-growth scroll acceptance,
long-message layout, compact pi-pocket-like typography/composer integration,
full native Pi approval UI, model/thinking/queue/stop controls, production
three-palette settings and completion notifications remain open. Source
[capability audit](../../design/herdr-pi-capability-audit.md) records actual
support and integration gaps. Source bridge advertisement is not proof of safe
end-to-end prompt delivery. Existing screen-based approval risks remain open.

The owner prohibits emulator use for this continuation and connected-test
cleanup/uninstall on the phone. No connected Gradle tests were run. No
original data deleted by the earlier runner incident is claimed recovered.
