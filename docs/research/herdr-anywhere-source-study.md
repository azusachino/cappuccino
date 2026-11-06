# Herdr Anywhere source study for Cappuccino

## Scope and evidence

The owner requested studying [Herdr Anywhere][repo] -- the closest kin to Cappuccino's problem space: a phone client for existing Herdr agents. This is a read-only source inspection of commit `f344bc5d6ee147a1714567aab7d8445897c0689b` (v0.1.8), held on the workstation's reference shelf at `refs/coding-agents/herdr-anywhere/`. Nothing was built, installed, paired or executed; the companion, Tailcat and agents were not run. The repository's [license][license] is MIT. No code was copied into Cappuccino.

The project is a Go companion (macOS arm64) plus one TSX app building Android, iOS and web. Its strategy inverts Cappuccino's: the companion is a byte-pipe -- for each phone WebSocket it spawns one `hc connect` and copies bytes unchanged, so the [phone speaks herdr-core's protocol itself][companion]. Cappuccino is a strong bridge returning typed turns to thin clients. The inversion is deliberate on both sides and neither subsumes the other.

## What is worth learning

### Signed self-updates with consent gates

[The updater][manifest] verifies an Ed25519-signed `latest.json` manifest against a key built into the binary, then checks each file's SHA-256 digest and size; [Android additionally requires][release-readme] the APK to carry the same signing key as the installed build. GitHub checks are opt-in ("ask" until the phone answers), then once a day; installing without asking is a separate, louder switch. A failed digest leaves the old binary in place, and the install replaces the process in place.

**Cappuccino application:** Cappuccino has no update story -- Android is sideloaded and bridge installs are hand-run per machine. A signed-manifest update channel for the companion and the Android APK is the highest-value adoption here. Scope it as its own spec: it touches deployment, signing keys and the owner's release flow, which today are manual by decision.

### Pairing and revocation converge on the same shapes

Single-use 128-bit codes with a 10 minute TTL, 256-bit device tokens stored only as SHA-256, a rate limit on failed pair attempts, and `revoke <id>` closing the device's live connections at once. These mirror Cappuccino's spent prompt ids, token design and `CLOSE_REVOKED` behavior -- independent convergence that supports the v2 contract's choices rather than challenging them.

One difference cuts against them: their revoke drops the phone from Tailcat by restarting the Tailcat node, so **every** paired phone's connections drop and reconnect. Cappuccino's revocation closes exactly one endpoint's tunnels.

**Cappuccino application:** none required. Keep per-endpoint revocation semantics if the transport layer ever changes.

### Tailcat is the Tailscale-native answer to iroh

The `--tailcat` transport gives direct-when-possible WireGuard-encrypted traffic with public relays that see only ciphertext -- the same value proposition as iroh, from the tailnet the owner already runs. The phone side is a vendored [TailPocket client][tunnel] with keys in Keychain/Android Keystore and an invite prefix (`herdr1:`) that namespaces invites per app.

**Cappuccino application:** treat this as transport optionality, not migration. Cappuccino's v2 contract already isolates transport (pairing and ALPN sit outside `/api/`, each versioned separately), so a third transport is additive. Watch Tailcat's maturity before any spike; do not couple route or pairing code to iroh internals.

### Alerts ride herdr-core's own reports

Agent status, including what a blocked agent waits for (`kind`, `msg`), comes from the `hc` stream, not from screen parsing; [alert mechanics][alerts] port herdr-app's own sound semantics (per-player 15 second limit) instead of reinventing them. Agents without reporting (OpenCode, Cursor, Codex) degrade to terminals without alerts, stated as a capability matrix in the README.

**Cappuccino application:** the bridge already reads the same `agent_status` surface. The matrix phrasing is worth copying into Cappuccino's README: which agents produce prompt cards and which only terminals is a per-agent fact owners ask about first.

### The install document is written for agents

[INSTALL.md][install] is an ordered procedure where every step ends in a machine-checkable pass condition and failures stop the run. `pair --json` and `devices --json` exist so dialogs and scripts consume pairing without scraping output.

**Cappuccino application:** `docs/iroh-setup.md` is owner-shaped. An agent-runnable install path for the bridge -- the same steps with `command -v` style checks -- would have turned today's manual v2 deploy into one instruction. Additive doc; no contract change.

### A file upload path from phone to agent

`POST /api/files` lands a phone photo or file at a path under `~/.cache` with 7 day retention and a 2 GiB cap, so an agent can read what the phone sent. It is the only phone-to-agent data channel besides prompts.

**Cappuccino application:** a candidate `prompt_input` extension for the v2 contract -- additive, but it needs a capability bump path (§4.7) and a decision about retention and privacy before any design work. Record it as a possible capability, not a plan.

## What not to import

- The plaintext LAN mode (`ws://`, "use it on a network you trust") contradicts Cappuccino's refusal of non-loopback unencrypted binds. Do not relax that.
- The companion holds no machine identity at all; a phone trusts whatever answers its URL. Cappuccino's `machine_id`, `expected_session` binding and identity-change refusal are load-bearing for multi-machine owners -- today's pairing confusion was diagnosable only because identities are checked.
- The byte-pipe buys freedom from sync bugs by abandoning history: no turns, no tokens, no offline reading, no prompt cards. That is the rejected alternative, not a simplification to adopt piecemeal.
- Their serve process dies on reboot ("nothing restarts it") and pairing is re-offered each boot. Cappuccino's Herdr startup hook already solves this.
- The TSX-multi-target approach ships a solid Android and an untried iPhone. It is evidence for, not against, Cappuccino's native-per-platform choice.

## Follow-through order

1. Spec a signed-manifest self-update channel for the Android app and the companion; owner approval covers signing keys and the release flow before any code.
2. Add an agent-runnable bridge install path under `docs/`, mirroring `docs/iroh-setup.md` steps with pass conditions.
3. Open a contract discussion (not code) for a file-upload capability under §4.7's additive rules.
4. Keep Tailcat on watch; revisit only if iroh relay friction shows up in practice.

[repo]: https://github.com/jt-wang/herdr-anywhere
[license]: https://github.com/jt-wang/herdr-anywhere/blob/main/LICENSE
[companion]: https://github.com/jt-wang/herdr-anywhere/blob/main/companion/README.md
[manifest]: https://github.com/jt-wang/herdr-anywhere/blob/main/companion/internal/update/manifest.go
[release-readme]: https://github.com/jt-wang/herdr-anywhere#updates-itself
[tunnel]: https://github.com/jt-wang/herdr-anywhere/blob/main/app/src/tunnel.ts
[alerts]: https://github.com/jt-wang/herdr-anywhere/blob/main/app/src/alerts.ts
[install]: https://github.com/jt-wang/herdr-anywhere/blob/main/INSTALL.md
