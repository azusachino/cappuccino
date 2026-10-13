# Android agent notifications checkpoint

## Delivered and observed

Owner prioritized default phone delivery; optional Telegram is a server
capability and remains unimplemented/unconfigured. The server advertises that
honestly. Android system notifications default on, subject to Android permission
and channel settings. The native connected-device foreground service monitors
all configured, unpaused machines independently of selected transcript and app
foreground state. Manual disconnect pauses that machine's alert monitoring.
No executable input or agent lifecycle action is attached to a notification.

APK: `04c683e541ae2a59e8646695609b562af3611ed7d69b4dd82a99f87bd18ac25f`.
Android validation passed: **178 tests, zero failures/errors/skips**. Two missing
API-33 guards initially failed lint; guards were fixed without suppression.
Native UI regressions compile, but no connected-test cleanup/runner was used
against the owner installation.

Fresh stopped-app backup validated four private files. Install-r succeeded;
all private file names/content hashes matched before launching the updated app.
The phone was initially locked; verification stopped rather than bypassing the
lock. After the owner unlocked it, notification permission was available.
A clearly labeled local test notification posted; after Home, dumpsys confirmed
the foreground monitoring service remained active. This test is not an agent
completion.

A separate bounded passive check then observed a **natural authoritative agent
completion** and matched its exact scoped completion key to the Android system
notification. No agent prompt, confirmation, model change or lifecycle action
was used to manufacture it. The phone had been left at Home for this check.
This establishes the observed normal background journey, not Doze/force-stop,
reboot, lost-connectivity or all-provider acceptance.

## Authorized bridge-only rollout

Owner explicitly authorized updating both Mini and Mac bridges after gates,
without agent/integration/proxy changes. Full bridge gates, including mandatory
resource soaks and release builds, passed on both platforms. Installed hashes:

- Mini: `c92df1984256ab4a903de0efd0ffcf0e090a96055c886cbd35451b7acf28596c`.
- Mac: `500d256c4f7469f6133ec206f2f72ada8634ec6e886b32b4778b394e1f57415f`.

Original binaries were retained as `.previous-agent-alerts`; earlier rollback
files were not overwritten. Only bridge processes were restarted. Listener
port, machine identities and disabled proxy auto-apply policy were retained.
Before/after process identity checks retained 26 Mini agent/JS/Herdr runtime
identities and two Mac runtime identities. Completion metadata was available
for two of two Mini catalog agents and one of one Mac agent.

## Signal, limits and unfinished work

Completion uses native state/completion sequences and a bounded hashed native
session reference, **not a fabricated canonical conversation ID**. Initial
history, missing identity, reconnect gaps, replacement and sequence regression
establish baselines, not old completion alerts. Deduplication survives phone
process recreation through bounded private metadata; alert text excludes
transcripts, tool output and native filesystem paths. Notification navigation
revalidates machine/pane/native reference before selection.

This first urgent delivery slice polls catalogs every two seconds. Herdr
completion markers are transient: an unobserved rapid cycle, transport gap or
Doze can be missed. There is no claim of durable server event replay, guaranteed
background delivery or Telegram exactly-once delivery. Android force-stop or
permission/channel denial prevents phone alerts.

Native `blocked` states produce separate input alerts. A received in-session
prompt overrides Working in the detail UI. However, a Pi question tool that
still reports Working requires native producer/integration investigation;
that missing state cannot be called fixed by the notification monitor. Native
blocked/approval alert and tap/replacement journeys remain to be verified.
No integration reload was authorized or performed.

The unused Attention destination was removed from navigation/rendering; old
saved destinations migrate to Machines, and session prompt handling remains.
The main tabs are compact again without a returned-count caption. Normal and
Errors tool views use explicit failure flags, newest first, with separate saved
list positions; unknown/missing results are not errors. Native acceptance of
these latest presentation changes remains open.

Telegram configured-route controls, durable completion feed and visible
outgoing steering acknowledgements are still pending. Private preservation,
notification, process and API records remain scratch, not published here.
