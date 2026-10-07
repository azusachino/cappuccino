# Android read-only connected implementation

Implementation plan for the first Android-connected part of [issue #12](https://github.com/azusachino/cappuccino/issues/12). This is an acceptance plan, not evidence that attachment works. Live ownership and handoffs remain in Asobi (`cappuccino:connected-companion:task-8`). See [implementation checklist](todo.md).

## Outcome and ordering

Build a native Android debug APK that lets the owner add a private bridge endpoint, discover existing Herdr agents, select one and follow its recent read-only output. Recover safely after foreground transitions, connection loss and activity recreation. Then iterate features and UI on the owner's phone before further iOS distribution work.

The owner has changed the earlier iPhone-first ordering to Android-first iteration. Do not wait for iPhone delivery/approvals; do not pretend those capabilities exist. Issue #12's original daemon/token/Keystore and delivery/approval acceptance remains historical broader scope, superseded only for this explicitly read-only slice. Completing this plan does not close the entire issue.

Tier 2 under the existing connected-companion acceptance record: owning issue/PR, recorded gates and fresh independent verification before acceptance. Owner controls merges and physical-device installation. No deployment, production signing, owner session mutations or Tailscale configuration changes are authorized.

## Source baseline and integration dependency

- Main at planning time: `552e954` (PR #19 merged).
- PR #21 merged into `feat/herdr-native-integration` after #19 merged, not into main. Follow-up [PR #22](https://github.com/azusachino/cappuccino/pull/22) brings that accepted tree to main; it needs owner merge.
- Android branch: `feat/android-connected`, planning baseline `30a3a7259d392e5494d611f370a896e91eb6df23` (local merge of main and accepted bridge-client tree).
- Baseline tree `ccbc19978b8c65f1f9f264479ba3445d3b9aa1a7` is identical to independently accepted `a6e07c1` and PR #21 merge `ee1e432`. No bridge implementation change is part of this plan.
- Preserve bridge lifecycle/resource/HTTP2 acceptance; preserve Apple and frozen `services/daemon` source. The Android PR must clearly depend on #22 until it lands.

## What the bridge actually supplies

Read source, not stale comments in Apple adapters, for the current contract:

| Route | Current response | Android behavior |
| --- | --- | --- |
| `GET /api/session` | `event: paired`, nonempty `machine_id`, `protocol: 1`, plugin identifier | Validate before saving a profile; this is discovery, not authentication |
| `GET /api/agents` | Machine ID and named/unnamed agent rows | Validate machine identity and each locator; list without starting or modifying agents |
| `GET /api/transcript?session=...` | Locator, `available`, optional machine-local path/reason | Display availability only; never fetch a local path or claim this contains history |
| `WS /api/stream?session=...` | `stream_open`, `entries`, `stream_reset`, `error` | Consume current read-only pane-line stream with scoped reconciliation |

A stream ring is per connection. Its generation and sequence are not globally durable cursors. Entries are generally `kind: output`; IDs are derived from branch/text, so identical repeated text can be collapsed upstream. `active_branch` reflects the bridge's branch reporting, not established Pi conversation ancestry. A reconnect has no durable replay/resume endpoint. Never infer speaker/tool roles from terminal text or relabel pane output as canonical Pi messages.

Use existing [behavior spec](../docs/behavior-spec.md) and [fixtures](../fixtures/) to test intended pure rendering/reconciliation behavior, while testing the actual bridge shapes separately. Fixture support is not proof those richer shapes arrive from production. Do not clone the reference daemon protocol into Android.

## Explicit non-goals

No send route, PTY input, Nudge/Follow-up delivery, tool approval or grant storage. No Pi extension/adapter, agent orchestration, terminal emulator, file browser, SSH runtime, public relay, automatic discovery, token resurrection, FCM, foreground service or permanent background socket. No iOS/AltStore or Mac UX changes. No bridge wire change or canonical-history endpoint hidden inside client work.

## Native implementation boundaries

Keep one Gradle app module and the current pinned wrapper/toolchain and GPL license:

- `core/`: validated machine/agent identifiers, endpoint policy, wire/domain values, pure stream reducer and presentation state. No Activity, Context or Compose dependencies in pure logic.
- `io/`: concrete bridge HTTP/WebSocket adapter, wire parser and private profile storage. One small injectable transport seam for tests, not an SDK or service container.
- `ui/`: Compose Machines, Chats/list and selected output screen, plus honest Attention state.
- Activity/ViewModel: lifecycle ownership and UI intents; no socket creation from recomposition. One lifecycle-owned controller/ViewModel with immutable `StateFlow` is sufficient.

Use maintained OkHttp for HTTP and classic WebSocket transport, coroutines and AndroidX lifecycle-aware collection. Pin exact released compatible versions in the existing catalog after checking official docs, license and SDK/toolchain support. Prefer a small JSON parser using a maintained JVM-testable library; `kotlinx.serialization.json` tree parsing needs no generated SDK or serialization compiler plugin. Do not add a navigation framework, DI container, database, DataStore, KMP or module split without a concrete blocker. Small private profile metadata can use platform private preferences through an injected store.

The writer records dependency versions, upstream sources and why each dependency is needed. AGP built-in Kotlin stays enabled; do not add the legacy Kotlin Android plugin.

## Endpoint, identity and data safety

- Normal phone endpoint: owner-entered `https://...` private Tailscale Serve URL. Trust platform TLS/hostname validation; never install trust-all managers or silently downgrade HTTPS/WSS. Tailnet membership is the deployment boundary, not something the client can establish from a hostname alone.
- Reject userinfo, fragments, queries, unsupported schemes and non-root URL paths. Normalize trailing slash; use URL-builder query encoding for session locators, including spaces, Unicode, `&`, `?`, `#` and pane punctuation. Never concatenate an unescaped locator.
- Disable cross-endpoint redirects, cookies, credentials and disk response caching. Do not log bodies, paths, real URLs, hostnames or transcript text.
- Cleartext, if needed for synthetic emulator tests, is debug-only and restricted to loopback/emulator host (`localhost`, `127.0.0.1`, `10.0.2.2`) using Android network security configuration. No application-wide cleartext permission or production TLS weakening. Check target API 37 network-permission behavior against official docs before implementation.
- Persist only explicit profile ID, display label, normalized endpoint and accepted machine ID. Backups/transfer remain disabled. No transcript/agent catalog cache, tokens, approvals or grants on disk. Keystore credential work is inapplicable to this no-auth slice, not secretly implemented by plain preferences.
- Save a new profile only after a valid `/api/session` response. A profile's accepted machine ID must match future session/catalog responses. Identity mismatch clears the selection, stops the stream and requires explicit reconnect/re-add; never silently rebind a stored profile to another machine.
- Agent keys are `{machine_id, session_id}` current display locators, not immutable Pi session UUIDs/occupant epochs. Keep pane metadata when available. Machine changes and disappearing/replaced rows invalidate selection. Never promise complete occupant-change detection from the current API.
- Use finite HTTP timeouts and bounded bodies/frames. Initial local client safety limits: 5-second connection/request timeout; HTTP body 2 MiB; text WS frame 1 MiB; 2,000 retained output rows and 4 MiB retained UTF-8 text. Bound HTTP reads while consuming; reject oversized WebSocket messages before JSON parsing/retention. OkHttp delivers assembled messages to its listener, so a callback-size check is not a pre-allocation wire-frame limit or a total transport memory guarantee. Record that library boundary rather than claiming it is enforced before receipt. These are implementation caps, not measured performance budgets. Visible truncation/recovery markers are required; oversized input fails visibly. Document any necessary adjustment rather than removing limits.
- HTTP may negotiate H2 on TLS; report actual protocol if evidence is collected, not guaranteed H2. Classic WS uses HTTP/1.1 Upgrade. No RFC8441 or tailnet ALPN claim. Android need not reproduce the separate Rust client's strict-H2 benchmark gate.

## Screen and interaction design

Owner requirement: follow standard Android practices and **Material You using Material 3**. Use wallpaper-derived `dynamicLightColorScheme` / `dynamicDarkColorScheme` on Android 12 (API 31) and later, following system light/dark mode. On supported older APIs use a consistent Material 3 light/dark fallback; never call dynamic-color APIs below their supported level. Route colors and typography through `MaterialTheme`, including semantic status/error roles, without hardcoded screen palettes. Do not force a brand palette over the owner's system theme.

Use standard Material 3 top app bars, navigation bars, text fields, dialogs, buttons and list surfaces with native behavior and accessibility. Follow Android edge-to-edge/system-bar, safe drawing and IME inset guidance; account for insets once, avoid obscured or double-padded controls, and preserve standard/predictive back behavior. Keep ViewModel-owned immutable UI state, lifecycle-aware collection and unidirectional UI events; do not move networking into composables or reinvent platform components.

References for content hierarchy are the existing Compose shell and accepted Apple Machines/transcript presentation, not copied styling or new branding. Compact content-first lists, semantic colors, 8/16/24 dp spacing and 48 dp minimum targets; no decorative dashboard/cards/gradients. Verify dynamic light/dark on API 31+ and fallback light/dark below API 31 through focused theme tests; capture actual supported emulator UI evidence without claiming an unrun device matrix.

### Machines

- Empty state: explain private bridge setup and offer **Add machine**.
- Form: optional label, explicit HTTPS bridge URL, **Connect**; show connecting state and actionable validation/TLS/network/protocol errors next to the form. Do not dismiss failures or save an unverified endpoint.
- Saved rows: label, safe local connection status and active selection; select, explicit retry and remove. Removal disconnects only the phone and deletes local profile metadata. No remote stop/setup/reload action.
- Persist multiple profiles so two-machine identity isolation can be tested; one active machine and one selected stream at a time. No simultaneous background subscriptions.

### Chats

- No machine: link to Machines. Loading, empty agent list, unreachable bridge and protocol failure are distinct states.
- Rows: label, machine context, current locator, branch if available and text status (working/idle/unknown). Preserve unnamed-pane rows; never fabricate sessions.
- Refresh is explicit and also runs on foreground recovery. Selecting opens the read-only output screen. Back returns to the list and closes that stream; selection is not a remote detach command.

### Selected output

- Header: agent label, machine context, reported branch and connection state; standard Android back navigation.
- Persistent explanation: **Recent agent output · Read-only**. Transcript availability can be shown separately, but does not change the pane-output label.
- `LazyColumn` with stable connection-scoped keys, selectable text, monospace for explicit code/output content, wrapping and expandable typed tool details only when present in supported fixtures/wire data. Do not synthesize tool cards from shell text.
- Start at latest output; auto-follow only while already near the bottom. When reading earlier output, new frames must not force-scroll. Offer a small **Jump to latest** action/new-output indicator.
- Show reset, reconnect-window, missing-sequence and local-truncation markers in reading order. Distinguish old retained stale output from a newly established stream; never imply the gap was recovered.
- Send/Nudge/Follow-up controls remain unavailable with **Sending is not supported by this bridge yet**. No editable composer accepting text that cannot be delivered. Existing shell choice tests may be adapted, not discarded to avoid unavailable-action coverage.

### Attention and accessibility

Attention explains that tool approvals/alerts are unavailable, not that there are definitively zero remote pending requests. No enabled decision buttons.

Use visible labels/headings and proper semantics. Status is not color-only. Test dark/light, long labels, narrow portrait/landscape, large font, back navigation and focus after dialog dismissal. TalkBack/manual device claims remain separate from synthetic semantics checks. Keyboard URL entry must not obscure Connect/errors; output has no active message keyboard.

## State, ordering and lifecycle

A small explicit state model separates profile/catalog state from selected-stream state: disconnected, connecting, connected, paused/stale, recovering and error. Selection has a monotonically increasing local attachment epoch, independent of bridge generation.

1. New machine/session selection cancels all previous calls/socket/retry work before publishing replacement state. Every callback carries its epoch; stale successes/errors/frames cannot mutate the new selection.
2. On a new socket, require matching `stream_open` locator and valid generation before entries. Bridge generations/sequences are scoped to this connection; never compare a new socket's generation/seq with an old socket's cursor.
3. Within a stream generation, accept complete entries only, dedupe by ID, order by sequence and display gaps. Duplicate identical frames are no-ops. Conflicting IDs/sequence payloads are protocol errors, not silent overwrite. Preserve explicit gap records with distinct stable keys.
4. A newer `stream_reset` starts a new visible window and clears old active-generation entries/dedupe. Repeated same-generation reset is idempotent; regressive reset is rejected. Reload availability/catalog as appropriate without treating `/api/transcript` metadata as history. Sequence need not restart at one after reset in the existing bridge.
5. Disconnect preserves the old visible window as stale until recovery, then replaces it with the new recent window and a visible discontinuity notice. Do not merge separate socket sequence spaces or claim missing history was backfilled.
6. While foreground and selected, transient transport failure can retry with capped delays (1, 2, 4, then 8 seconds, at most four automatic attempts); expose Retry when exhausted. Do not retry identity/TLS/protocol failures automatically. One retry owner only; manual Retry cancels pending retry. Reset retry allowance only after successful stream establishment, not every failed callback.
7. Stop/cancel sockets, HTTP calls and retries when the app backgrounds. Resume by verifying machine identity, refreshing catalog, validating selection and opening a fresh socket. No invisible always-on service.
8. ViewModel retains UI state across activity recreation; avoid duplicate attachment jobs. Process death restores saved profiles and optional selected profile/locator via saved state, then revalidates from the bridge. Do not restore transcript data, assume old selection valid or start connections before foreground.
9. Removal, tab/selection changes, controller disposal and cancellation close owned resources deterministically. All test servers, emulator processes, temporary files and worker peers have explicit identity-scoped ownership/cleanup; failed cleanup remains a blocker.

## Ordered implementation slices

Each slice adds focused tests and reaches a source-ready checkpoint before the next. Never substitute idle/exit-zero for acceptance evidence.

### A — Wire values and reconciliation

Implement endpoint validation/builders, parsed identity/catalog/availability/stream events, pure reducer and local caps. Tests consume repository fixtures through a single source path, not independently edited copies. Add Android-only synthetic actual-wire/cancellation cases without changing shared fixture semantics.

Gate: focused Kotlin unit tests, format and documented parser dependency checks. Include malformed types, unknown events, wrong machine/locator, duplicate/conflicting IDs, gaps/out-of-order entries, incomplete entries, reset behavior, connection-scoped counters and caps.

### B — Concrete transport and profile/controller ownership

Implement OkHttp GET/WS, request cancellation, private profile store and lifecycle controller. Use task-owned local HTTP/WS mock servers to exercise real parsing/body consumption, request/query encoding, error/clean EOF/abrupt disconnect, retry exhaustion, delayed old callback, foreground pause/resume and disposal. A fake reducer alone does not prove socket ownership.

Gate: unit/transport/controller tests and Android lint. Assert calls/socket/retries terminate on switch/background/disposal, and no mutating requests are ever made. Verify process-death profile restoration without transcript persistence.

### C — Consuming Compose journey

Replace sample shell states with Machines -> catalog -> selected output while preserving unavailable actions and original three destinations. Inject controlled dependencies for instrumentation; never ship a debug button that presents sample data as connected production.

Gate: `make validate-android`, then `ANDROID_SERIAL=<task-owned> make ui-test-android`. Run actual HTTP/WS synthetic integration as well as UI seam tests. Cover add/retry/remove, unnamed agent, live append, scroll-follow opt-out, tool expansion fixture, gap/reset, two machines with same locator, reconnect, rotation/recreation, background/foreground, malformed/error states and disabled sending/approvals. Preserve the target's required Chats/Attention/Machines PNG exports; add output/error screenshots rather than dropping shell artifacts.

### D — Fresh verification and debug APK handoff

Freeze source and obtain fresh independent verification (Luna medium or Sonnet-class, not the low-effort writer). Source-first review before expensive emulator jobs; run actual focused/native/build/UI gates and verify task resource cleanup. Shared Make/workflow/fixture changes require both platform gates; Android-only changes preserve Apple source and document the gate scope. Run Markdown and diff checks for changed records.

Record exact commit, branch, toolchain/dependencies, gate commands/exit codes, emulator identity, screenshot paths, APK path/size/SHA-256 and debug-signing/update caveat in owning verification docs/PR. APK expected path: `apps/android/app/build/outputs/apk/debug/app-debug.apk`. Assemble alone is not attachment acceptance. No claim of live canonical Pi parity or physical-phone support from emulator success.

### E — Owner phone and subsequent iteration

After verified APK handoff, owner installs and supplies actual phone/OS identity and private endpoint locally. With explicit live acceptance authority, prove one phone -> one machine and then two-machine switching, read-only output/error/lifecycle behavior and preserved task-owned Herdr/Pi identities. Never prompt or alter the owner's working agents for this proof. Record feedback without publishing private URLs/transcripts. Physical-device, real-tailnet and canonical-history claims remain open until separately evidenced. Delivery/approval implementation needs its own resolved backend acceptance and approval.

## Gates and approvals

Owner requirement: **commit `.env` containing only non-secret project defaults**, and put secrets/private or machine-specific overrides in ignored `.env.local` (or an explicitly documented external secret store). Mise's `[env]` / `_.file` loads the committed base followed by optional local overrides. No redundant `.env.example` is needed. Do not invent unused configuration keys merely to fill the base file; a commented baseline is valid when no portable active defaults are needed.

Use documented syntax verified against installed mise 2026.5.12; do not shell-source dotenv. Loading must work from the owning root and subdirectories. Missing `.env.local` must not break clean-checkout/CI gates, and committed defaults must not replace CI-supplied SDK configuration. Do not dump `mise env` output into logs: it exports actual values, including secrets.

Machine-local `ANDROID_HOME`, optional macOS `DEVELOPER_DIR` and JDK selection can be supplied through `.env.local` or the inherited environment. Keep host-specific SDK paths and real bridge endpoints out of the portable baseline. Never overwrite preexisting owner local configuration. Before making `.env` trackable, check its contents for private values; if the writer previously created an ignored SDK-path `.env`, move only those task-owned local values into `.env.local` without overwriting an existing owner file. Do not embed dotenv secrets/real machine URLs into the APK through Gradle BuildConfig/resources. This is development tool environment, not runtime authentication/profile storage. Normal local entry points from the Cappuccino root become:

```text
mise exec -- make check-android
mise exec -- make validate-android
ANDROID_SERIAL=<task-owned serial> mise exec -- make ui-test-android
mise exec -- make md-check
git diff --check
```

Verify base-only and base-plus-local loading, precedence, missing optional local file and nested-directory resolution without printing private values. Verify `.env` is trackable and `.env.local`/private variants remain ignored; inspect the eventual tracked diff for secrets. The writer is additionally granted only `.mise.toml`, `.gitignore`, tracked `.env`, task-owned ignored `.env.local`, removal of a task-created redundant `.env.example` if any, and the local-development environment section of `docs/development.md`. Preserve existing tool pins and Make/CI command contracts; no Makefile/workflow edits are granted. Because the project environment is shared, validate both platform gates before final acceptance, not just Android.

Do not change global SDKs, install redundant toolchains or use owner AVD `Pixel_8_Pro_API_35`. Create a uniquely named task AVD from an available compatible image, record PID/serial/AVD identity, then stop/remove only task-created resources after reports/artifacts are captured. No Podman VM or expensive bridge soak is required for unchanged bridge source. If an actual bridge mock integration is added, keep it hermetic and `CAPP_BRIDGE_SERVE_AUTO_APPLY=0`.

The writer can edit `apps/android/**`, this checklist, the explicitly granted development-environment paths above and its assigned evidence scratch. Lead owns plan/issue/PR records; no concurrent source writers. Changes to root Makefile/workflows/shared fixtures, bridge, Apple or agent instruction files need a surfaced reason and lead grant first. No commits/pushes/remote writes from the worker; lead handles gates, reviewed commits and draft PR. No further delegation from the worker.

## Risks and stop conditions

- Backend has no canonical history contents/resume cursor: accept honest recent output only; broader history remains deferred.
- Locator is not occupant identity: no sending, grant or immutable identity claim.
- Upstream text-derived IDs collapse identical repeated lines: don't hide this with client heuristics.
- Missing private HTTPS endpoint is a device-stage prerequisite, not permission to relax TLS or mutate Serve.
- Dependency/compiler/network-permission mismatch: stop and report before toolchain or permission widening.
- Failure to prove cancellation/cleanup, UI instrumentation instability or missing independent report: keep acceptance blocked; no selective skipped tests, suppression baseline or claimed pass.
- Worker compaction, idle state or settled prompt while nested jobs run: require written checkpoint/final report, actual terminal job results and one nonwaiting final notification to lead; no blind `/new` or ownership transfer.

## Primary implementation references

Writer must check these for the chosen released versions and document relevant facts, not trust old comments:

- [OkHttp](https://square.github.io/okhttp/), [WebSocket API](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-web-socket/): concrete HTTP/WS and cancellation.
- [Lifecycle-aware Compose state](https://developer.android.com/develop/ui/compose/state), [Android lifecycle coroutines](https://developer.android.com/topic/libraries/architecture/coroutines): foreground ownership/collection.
- [Saved state](https://developer.android.com/topic/libraries/architecture/saving-states): recreation versus process death.
- [Network security configuration](https://developer.android.com/privacy-and-security/security-config), [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission): TLS/cleartext and target-SDK behavior.
- [Compose testing](https://developer.android.com/develop/ui/compose/testing): consuming semantics/instrumentation.
- [Kotlin serialization](https://kotlinlang.org/docs/serialization.html): compatible JSON parsing without an unnecessary generated SDK.

Canonical local sources: `services/bridge/src/{routes,agents,reconcile,transcript}.rs`, `docs/{architecture,behavior-spec,verification}.md`, repository fixtures, and Apple transcript source/tests as behavioral reference rather than presumed-correct wire implementation.
