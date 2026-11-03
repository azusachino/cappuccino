# Telegram alerts: setup and operations

The bridge can post one plain-text message to a private Telegram group when an agent
on that machine finishes a work cycle or waits for an answer. Design and privacy
rules: [telegram attention alerts](design/telegram-attention-alerts.md). This page is
the owner's checklist for one machine.

The bot token is a secret. Never paste it into chat, a commit, a log, or a command line.
The chat id is not secret, but it stays in the untracked bridge config, not in committed files.

## 1. Store the token (harus-nix)

The token lives in harus-nix's sops secrets, rendered to a stable file for the bridge.

1. In `vendor/harus-nix`, add the value under the key `cappuccino_telegram_bot_token`:
   `sops secrets/secrets.yaml`. Paste the token into the editor, save, and quit.
2. Check that the key exists without printing the value:
   `sops decrypt --extract '["cappuccino_telegram_bot_token"]' secrets/secrets.yaml | wc -c`
3. Apply the configuration for this machine, for example `make switch-macmini`.
4. Check that the file exists and is read-only:
   `stat -L -f '%Sp %N' ~/.config/harus/cappuccino-telegram-token` should show `-r--------`.

Each machine that sends alerts needs its own sops entry and rendered file.

## 2. Configure the bridge

Add a `[telegram]` table to the bridge config, `~/.config/cappuccino-bridge/config.toml`
(or the path in `CAPP_BRIDGE_CONFIG`):

```toml
[telegram]
enabled = false                                                  # set true after step 4
chat_id = "<numeric group id, negative for groups>"
token_file = "/Users/<you>/.config/harus/cappuccino-telegram-token" # absolute path; no ~
machine_label = "<name for this machine, 1-40 printable characters>"
events = ["completion", "needs_answer", "route_failure"]
```

- `machine_label` is required and appears in every message. Use a different label on
  each machine, because the bot posts from all of them into the same group.
- A relative or `~` path does not expand. Use the absolute path.
- Keep `enabled = false` until the token file exists. Enabling with a missing file
  reports `misconfigured` and sends nothing.

## 3. Name each agent session (optional, recommended)

Messages read `claude #2 finished a work cycle` by default. Numbers are per agent kind
and reset when the bridge restarts. To make a session recognisable, name its pane in herdr:

```sh
herdr agent rename <pane id> <name>     # for example: herdr agent rename w1:pA1 telegram
```

A named pane shows as `claude #2 (telegram)`. Names are not inherited across sessions,
so name each new session you want to track. Leave a pane unnamed and it stays generic.

## 4. Restart and verify the route

After any config change, reinstall from the repo when the bridge code has changed, then
restart it:

```sh
cd vendor/cappuccino
cargo install --path services/bridge --locked   # only after updating the repo
cappuccino-bridge stop
cappuccino-bridge start
curl -s http://127.0.0.1:7392/api/session | jq .telegram
```

The status shows `route`, `last_delivered_at`, `uncertain_count`, `dropped_count` and
`last_http_status`. No secret is printed. Routes:

| Route | Meaning |
| --- | --- |
| `disabled` | `enabled = false` |
| `misconfigured` | Config or token file is missing or invalid; nothing is sent |
| `enabled` | Configured and working as far as the last send showed |
| `rate_limited` | Telegram asked the bridge to slow down; it retries |
| `failing` | Send errors, retried |
| `rejected` | Telegram rejected the token or chat; nothing else is sent |

## 5. Test send (owner approval required)

Send one synthetic test message only after the owner approves it. It is not an
ordinary agent operation. The message must be plain text under 200 characters.
The bridge's send path is exercised by a real agent finishing a cycle, not by the test.

Run it through the Telegram API with the token kept out of argv and history:

```sh
T="$(cat ~/.config/harus/cappuccino-telegram-token)"
printf 'url = "https://api.telegram.org/bot%s/sendMessage"\nrequest = "POST"\ndata-urlencode = "chat_id=<group id>"\ndata-urlencode = "text=[cappuccino] test · <machine label>"\n' "$T" \
  | curl -s --max-time 10 -K - -o .tmp/telegram-test.json
unset T
jq -c '{ok, message_id: .result.message_id}' .tmp/telegram-test.json && rm -f .tmp/telegram-test.json
```

Then confirm the message arrived in the group. Direct sends do not change
`last_delivered_at`, because that field records bridge sends only.

## 6. Check a real completion

Let an agent on this machine finish a work cycle. Expect one message. A repeated
status poll must not send again. If an agent finishes while the bridge is stopped, no
message is sent for it on restart; `watching_since` shows when watching started.

## Limits

- `needs_answer` fires when an agent enters `blocked`. A second prompt in the same
  blocked stretch does not alert.
- A rejected route sends no route-failure message. Read the status instead.
- Completions during a bridge restart are a gap, not a backlog.
- An agent missing from polls for more than 120 seconds loses its baseline and gets a
  new number when it returns.
- Alerts depend on herdr's `completion_seq`. If herdr stops reporting it, completions
  go silent and the status does not say so yet.
