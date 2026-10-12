# Agent completion notifications

## Owner intent and boundaries

Android system notifications are the default route; Telegram is optional and
runs on each owning bridge/server, not the phone. Cover all monitored agents,
not merely the transcript currently selected. Completion means a work cycle
ended, not that its task succeeded. No transcript, tool output, credential,
working-directory path or executable action belongs in an alert.

Owned paths: bridge agent/event/notification transport and tests; Android
completion models, notification lifecycle/settings and tests. This is a tier-2
published protocol addition. Local implementation is authorized; actual bridge
deployment, credentials and external Telegram test delivery require separate
owner authorization. Existing agent processes/integrations must not be
restarted or prompted to manufacture acceptance events. No delegation/Asobi.

## Signal and identity contract

Herdr's `completion_seq` identifies a completed working→idle transition,
independent of focus; idle/status text by itself is not completion. Cappuccino
currently discards it. Preserve `state_change_seq`, `completion_seq` and the
reported native agent-session reference. A native reference change, pane
replacement, sequence regression or producer restart establishes a fresh
baseline; never replay a previous session's completion into its replacement.
Unavailable identity/completion reporting is explicitly unsupported, not
heuristically fabricated from terminal text.

Initial catalog acquisition and historical/offline readers do not alert.
Repeated snapshots must not duplicate a completion. A bridge-owned event
stream/cursor is needed for background clients and server-owned Telegram:
completion markers are transient and cleared by a later Herdr state transition.
Inspect native subscription availability before relying on catalog polling;
expose delivery gaps rather than claiming unobserved cycles were captured.
Bound retained events, recipient dedupe state, retries and metadata sizes.

## Delivery and privacy

- Android: notification channel, API-33 permission and a user-visible monitoring
  policy. The existing view model cancels connections on backgrounding; simply
  calling NotificationManager there is not background support. Document and
  implement an appropriate native background lifecycle before claiming it.
  Android force-stop/OS denial cannot be bypassed or called guaranteed delivery.
- Telegram: opt-in, private server configuration/secret file; never phone storage,
  committed configuration, command-line token, response metadata or logs.
  HTTPS only to the Telegram Bot API, no redirects/custom recipient-controlled
  URL. Plain bounded text, not interpreted Markdown/HTML. Treat timeout outcomes
  as uncertain; Telegram sendMessage has no client idempotency key, so do not
  claim exactly-once retries. Permanent errors and rate limits must be visible
  without exposing secrets or message bodies.
- Tapping Android notifications is navigation only, revalidating machine/native
  session identity; never send input or attach a reused locator blindly.

## Acceptance and gates

Test baseline, duplicate, multi-agent/machine, completion while another session
is selected, replacement, sequence reset, missing signal, reconnect, retention
and offline suppression. Test permission denial/channel disable, secret/error
redaction, size bounds, delivery failure and bounded retry policy. Verify real
phone foreground/background notification journeys separately from unit tests;
real Telegram delivery remains gated by owner credential/deployment approval.

Use owning bridge and Android validation plus Markdown gates; preserve the
phone installation with stopped-app backup/install-r/prelaunch byte comparison.
Full workstation/native acceptance is not replaced by a synthetic notification.

## Primary sources

- Herdr pinned workstation source: `AgentInfo` and state-transition ownership in
  `refs/coding-agents/herdr/src/api/schema/agents.rs` and `src/app/actions.rs`.
- [Android notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Telegram sendMessage](https://core.telegram.org/bots/api#sendmessage)

Operational questions: was the completion observed, which verified instance
owned it, did each enabled route acknowledge delivery, and was failure due to
permission/connectivity/provider rejection? Logs must answer these through
bounded codes/event IDs, without dumping native references, text or token URLs.
