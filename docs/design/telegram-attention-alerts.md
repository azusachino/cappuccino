# Telegram attention alerts: workflow design

Status: proposed for owner decision (issue #11). Nothing here is implemented.
Builds on [agent completion notifications](agent-completion-notifications.md),
which owns the completion signal and the Android side. This page covers the
owner-facing workflow: configuration, when a message is sent, delivery and
failure handling, and the operations an owner runs.

## Scope

- One owner, one Telegram group chat with the bot (owner decision; see below).
- Sent by the bridge, on the machine that observes the agents. The phone is not
  involved, so alerts arrive while the app is closed.
- Generic attention only: the agent finished a work cycle, or it is waiting for
  an answer. No transcript, tool output, prompt text, working-directory path, pane
  id, or session identifier, and no approve/deny buttons. One exception: a
  per-kind session number appears after the agent kind (`claude #2`). The bridge
  assigns it at first sighting of a session, counts from 1, and resets on restart.
  It is not an identifier. The herdr pane name appears in parentheses only when the
  owner sets `show_pane_names = true`; it is off by default. The bridge never uses
  the terminal title or a generated name.
- Off by default. Nothing is sent until the owner configures a token and chat.

Out of scope: Android push or FCM, Telegram-side approval controls, multiple
recipients, group chats, and delivery guarantees. Telegram has no idempotency
key, so a timeout is reported as uncertain, never as delivered.

## Configuration

Configuration lives in the bridge's TOML file (`CAPP_BRIDGE_CONFIG`), with the
secret kept outside the repository:

```toml
[telegram]
enabled = false                      # off by default; the bridge reports the route as disabled
chat_id = "<group chat id>"          # the private group with only the owner and the bot
token_file = "<path to secret file>" # read at start; never a literal token and never a CLI argument
machine_label = "<owner's name>"     # required when enabled; 1-40 printable characters
show_pane_names = false              # opt-in: add the herdr pane name to messages
events = ["completion", "needs_answer", "route_failure"]
```

The owner's setup steps (token in harus-nix, config, herdr naming, verification)
are in [telegram alerts setup](../telegram-alerts-setup.md).

- The token is read from `token_file` at startup and on an explicit reload. It
  is never logged, never returned in status, and never passed on the command
  line. The file is rendered by the owner's secret manager on the host, the same
  pattern as the other services in the workstation; it is not written by the
  bridge.
- `enabled = true` with a missing or unreadable token file is a configuration
  error at startup. The bridge keeps running, reports the route as
  `misconfigured`, and sends nothing. Agent lifetime and in-app control never
  depend on Telegram.
- Changing `events` or `chat_id` needs a restart. Rotating the token needs an
  explicit reload, so a half-written file is never used.

## When to notify

Two event kinds. Everything else stays in the app.

| Event | Trigger | Dedupe key |
| --- | --- | --- |
| `completion` | Herdr `completion_seq` advances: a working→idle transition | machine, pane, native session reference, `completion_seq` |
| `needs_answer` | The agent reports a pending question or prompt | machine, pane, native session reference, prompt identifier |

Rules that decide whether an observation becomes a message:

1. **Baseline first.** The first observation of an agent after bridge start,
   pane replacement, a native session reference change or a sequence regression
   sets the baseline and sends nothing. A replacement never inherits the previous
   session's completion.
2. **Once per key.** A repeated snapshot with the same key does not send again.
3. **Idle text is not completion.** Only a `completion_seq` advance counts.
4. **Downtime is a gap, not a backlog.** If the bridge was not running when a
   completion happened, nothing is sent on restart. The status records the gap
   (`watching_since`) so the owner can see it.
5. **Bounded state.** Dedupe state covers agents seen in the last two minutes;
   the agent list itself bounds the count. Pending messages are capped at 50;
   when the cap is hit the oldest is dropped and a dropped counter increases.
6. **Brief gaps keep the baseline.** An agent missing from polls for up to two
   minutes keeps its baseline, so a completion that happens during a short herdr
   gap is still reported when the agent returns. A herdr error or an unparseable
   snapshot changes nothing at all.

## Message

Plain text, no Markdown or HTML parsing, at most 200 characters:

```text
[cappuccino] <machine label> · <agent kind> #<n> finished a work cycle
[cappuccino] <machine label> · <agent kind> #<n> (<pane name>) is waiting for an answer
```

- The machine label is the owner's own name for the machine, not the hostname.
- The agent kind is the reported kind (for example `pi`).
- `#<n>` is a per-kind session number, from 1. It is assigned at first sighting of a
  session and resets when the bridge restarts. It is not a session identifier.
- `(<pane name>)` appears only when `show_pane_names = true` and the herdr pane has
  a name, up to 40 characters after removing control and invisible formatting
  characters. The bridge never uses the terminal title or a generated name.

Nothing else is added: no pane id, session id, path, transcript or prompt text.
When the name is shown, it is the bridge's `session_id` for that agent, so anything
that can read `/api/agents` sees it too. Any process in a pane can rename that pane
with `herdr agent rename`, so the name is only as trusted as the pane it is set on.
That is why it is opt-in.

## Delivery and failure

- HTTPS to the Telegram Bot API only. No redirects, no URL taken from a message
  or response, a 10 second timeout per call.
- Outcomes the bridge distinguishes, and what the owner sees:

| Outcome | Bridge action | Status shown |
| --- | --- | --- |
| Accepted (2xx) | Mark delivered | `last_delivered_at` |
| Rate limited (429) | Wait the advertised `retry_after`, at most 3 tries | `rate_limited` until it clears |
| Rejected (4xx, bad token or chat) | Stop sending; do not retry | `rejected` with the HTTP status; no route-failure message (the chat itself refused) |
| Server error (5xx) | One retry after 30 seconds | `failing` |
| Timeout or connection reset | Mark as **uncertain**; do not retry automatically | `uncertain` with the count |

- No claim of exactly-once delivery. An uncertain send is shown as uncertain and
  is never silently sent again.
- A `rejected` route stops until the owner fixes the configuration and reloads.
- Logs carry bounded codes and event identifiers only: outcome, HTTP status,
  event kind and a short event ID. Never the token, the URL with the token, the
  chat ID, the message body or any native reference.

## Status

The bridge reports Telegram status alongside the existing capabilities, without
any secret:

```text
telegram: enabled | disabled | misconfigured | rejected | failing | rate_limited
last_delivered_at, uncertain_count, dropped_count, watching_since, last_http_status
```

The existing `telegram_notifications` capability stays `false` until a route is
configured and has delivered at least once, so the app never advertises a route
that is not working.

## Operations

| Operation | Who | How |
| --- | --- | --- |
| Enable | owner | Set `enabled = true`, `chat_id`, `token_file`; restart the bridge |
| Check | owner | Read the status fields above; no secret is printed |
| Rotate token | owner | Replace the secret file, then an explicit reload |
| Pause | owner | Set `enabled = false` and restart, or stop the route from status |
| Test send | owner, with approval | A single synthetic `[cappuccino] test` message. Needs a separate owner approval, as the design requires |
| Clear pending | owner | Drops the pending queue and records the drop count |

Ordinary agent operations never send a test message, and no operation prompts or
restarts an agent to create an event.

## Verification plan

- Unit: the dedupe and baseline rules above, including replacement, sequence
  reset, repeated snapshots, and downtime gaps.
- Bridge integration: a fake Telegram endpoint on loopback, covering each row of
  the delivery table, redirect refusal, the 200-character cap and the redaction
  of the token from logs and status.
- Restart: bridge restart with pending messages, checking that the queue is not
  replayed as a backlog.
- Real Telegram delivery only after owner approval, as the completion design
  already requires.

## Owner decisions (2026-11-02)

1. **Events:** `completion`, `needs_answer`, and route failures. Route failure
   messages are fixed text with the outcome code only, for example
   `[cappuccino] <label> · telegram route failing (HTTP 500)`, sent only when the route starts failing. They never include the
   token, the URL, the chat ID or any response body.
2. **Token storage:** the secret manager renders the file on the host;
   `token_file` points at it. The bridge never writes the file.
3. **Downtime:** no catch-up after a restart. The status shows
   `watching_since`.
4. **Chat target:** a private group whose only members are the owner and the
   bot. The owner keeps it that way; the bridge does not check membership, and
   the group is not expected to change. The bot keeps Telegram's default privacy
   mode, since the bridge only sends. A changed chat ID is handled like any other
   rejected route.

Still open: whether to include the working-directory name. The recommendation
stays to leave it out, since it can reveal private project names.
