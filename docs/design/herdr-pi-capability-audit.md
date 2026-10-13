# Herdr–Pi capability audit before session UI scope

Status: read-only source audit, not live control validation. The owner's first
priority is the Herdr plugin and Pi companion. Missing client/bridge plumbing
must not be described as a feature being outside that goal.

## Inspected versions and boundaries

- Herdr reference: `refs/coding-agents/herdr`, commit
  `e35f3937b0efe40ec0dab675709c68e1d8e8c9e6`, Cargo version `0.9.3`.
- Managed Pi integration: `src/integration/assets/pi/herdr-agent-state.ts`,
  integration version **9**; read in full.
- Installed Pi documentation: `@earendil-works/pi-coding-agent` release
  `1.1.0`, `docs/rpc.md`, `docs/rpc-commands.md` and
  `docs/rpc-extension-ui.md`; read in full. These document Pi's own control
  surface, not a socket already available on every interactive Pi process.
- Cappuccino: committed base `5bc1673`, plus current uncommitted debug-preview
  work. Inspected bridge adapter/routes, catalog mapping and Android transport
  and existing prompt/approval consumers. The phone preview is not that live
  consuming path.

No commands, approvals, model changes, reloads or session actions were sent.
The initial source phase did not re-inspect remote deployed versions/effective
extensions. Fresh checks subsequently executed are recorded in the runtime
verification section below; earlier installation evidence is not substituted
for those checks.

## The managed integration is reporting, not full remote control

Version 9 sends `pane.report_agent_session` and `pane.report_agent` to Herdr.
It reports native session identity and `working`, `blocked`, `idle` states.
It listens to `session_start`, `agent_start`, `agent_settled` and the
`herdr:blocked` extension event; state reports are sequenced and coalesced.
It has no inbound control server/handler for model, thinking, steer, follow-up,
queue management or typed dialog responses.

Its `session_start` gate is explicitly **TUI only** (`ctx.mode === "tui"`).
Starting a fresh `pi --mode rpc` child would expose Pi RPC, but would not attach
those controls to the owner's already-running interactive Pi. Do not replace,
restart or recreate that session to obtain a convenient API. The existing
extension type definitions do expose `sendUserMessage`, `setModel`,
`setThinkingLevel` and context `abort`; a scoped same-process control adapter
is an architectural option, not an installed/verified capability.

## Coverage matrix

| Capability | Pi's documented surface | Current Herdr / Cappuccino path | Actual gap |
| --- | --- | --- | --- |
| Native identity and lifecycle | Session state/events | Managed v9 reports identity/lifecycle; Herdr discovery and Cappuccino canonical reader consume them | Preserve verified identity; do not confuse locator with canonical session |
| Prompt and images | `prompt`, optional images and streaming behavior; accepted/queued/handled disposition | Herdr `agent.prompt` is PTY text submission; bridge `/api/agents/{sessionId}/prompt`; Android `submitPrompt` and original composer exist | Typed disposition, images and full delivery acknowledgement are not forwarded |
| Steer / follow-up | `steer`, `follow_up`, prompt streaming behavior | No named typed endpoint in audited Herdr schema or Cappuccino routes | Missing control plumbing, **not outside the goal**; PTY text is not proof of queue semantics |
| Queue inspection/control | `get_state` pending count; `clear_queue`; steering/follow-up modes | Not exposed by the current bridge/client | Preserve authoritative queue behavior and clear/restore semantics |
| Stop / retry cancellation | `abort`, `abort_retry`, `abort_bash` | Generic Herdr keystrokes exist; no typed Pi abort route in Cappuccino | No verified native stop/abort acknowledgement |
| Model selection | `get_available_models`, `set_model`, `cycle_model`, model in `get_state` | No typed model endpoint in audited Herdr schema/bridge/client | Missing same-session model-control path, not an excluded feature |
| Thinking level | Get/set/cycle supported levels | No typed bridge/client control | Model-specific allowed levels and acknowledgements needed |
| Approval / question UI | Extension dialog `select`, `confirm`, `input`, `editor` with correlated request/response IDs | v9 reports blocked state, not dialog payloads; bridge already parses visible screen prompts and forwards answers; original Android `PromptCardView`/`answerPrompt` exist | Existing implementation is heuristic, not complete native Pi UI parity |
| Extension notifications/widgets/status | `notify`, `setStatus`, `setWidget`, `setTitle`, `set_editor_text` | Blocked label only through stock v9 reporting; no general typed UI bridge | Forward meaningful supported UI records without claiming arbitrary terminal components are native |
| Complete transcript and branches | `get_messages`, `get_entries`, `get_tree`, `get_last_assistant_text` | Canonical file reader and bounded history exist | Not all tree/branch/history commands exposed as live controls |
| Session navigation/management | New/switch/fork/clone, fork-message list, name, export | Herdr owns start/resume/focus/rename; no equivalent Cappuccino routes for the full Pi list | Must preserve ownership; discovery of support is not authorization to execute lifecycle changes |
| Context/cost/compaction | Session stats, compact, auto-compaction | No current typed client controls/stats route | Authoritative current usage and compaction semantics, not fabricated footer values |
| Retry policy | Auto-retry configuration | Not exposed through current bridge/client | Control/acknowledgement missing |
| Commands/skills/templates | `get_commands`; prompt expansion | Plain text can reach Pi through PTY | No authoritative command discovery/autocomplete; don't equate all RPC commands with TUI built-ins |
| Bash execution | Typed bash streaming/result and exclusion-from-context | No typed bridge route | Audit records support; never turn displayed transcript commands into execution buttons |
| Completion alerts | Pi `agent_settled`; Herdr completion identity/events | Herdr `AgentInfo` has `state_change_seq` and `completion_seq`; events subscribe/wait exist | Cappuccino catalog currently drops both sequence fields; no system/Telegram notification delivery |

RPC UI explicitly degrades custom terminal components and TUI-specific header,
footer, editor/theme controls. Native compatibility must cover actual supported
interactions, not promise to render arbitrary terminal components.

## Fresh read-only runtime verification

Subsequent inspection reached Mini over SSH and both existing private HTTPS
bridges, without sending input or changing agents:

- Mini CLI and running server: Herdr `0.9.3`, compatible private protocol `22`;
  Pi reports `1.1.0`. The running Pi foreground PID's environment confirms the
  effective `.luna/agent` directory and discovered `w1:p21` pane binding.
- Effective Mini Pi integration status is current `v9`; its discovered live Pi
  agent has a canonical session report. The private bridge conversation GET
  verifies canonical source, conversation ID and expected machine/locator
  binding. Transcript contents remain private scratch, not evidence published
  here. Observed Pi was working; no idle reload was attempted.
- The local Mac Herdr running server is also `0.9.3`, compatible private
  protocol `22`. Mini and Mac schema inventories expose agent prompt/send-keys,
  wait, discovery and event subscribe/wait, but no named typed Pi model/queue
  methods. Both bridges verify the expected machine identity and advertise
  prompt input/answers. Advertisement is not end-to-end action validation.
- A further installed Pi source check (`interactive-mode.js`) confirms that
  default TUI submission while streaming uses `streamingBehavior: "steer"`;
  its follow-up handler uses `"followUp"` and describes Alt+Enter. Thus the PTY
  route can reach default TUI steering, even though our transport does not
  expose explicit modes or authoritative queue/disposition acknowledgements.
  This was source-verified, not exercised against the owner's running agent,
  and is not proof for custom editors/keybindings.

Private raw records are under `.tmp/cappuccino-mihon-preview/runtime/`; no
credentials, real transcript text or private network URLs are promoted here.

## Concrete current defects / overclaims

1. Approval handling is already in Cappuccino's original client and bridge.
   Saying approval controls are outside the goal was unverified and wrong.
2. Pi supports steering, follow-up/queue modes, models, thinking and aborts.
   Missing current bridge endpoints identify implementation work; they do not
   justify redefining a Pi-first companion as only a transcript viewer.
3. `agent.prompt` (`AgentPromptParams`) carries target, text and optional wait,
   not a Pi model/queue API. Its Herdr handler checks the foreground agent and
   rejects blocked agents, then submits encoded text/Enter through the PTY.
   Cappuccino's `prompt_sent` response is not Pi's correlated run disposition.
4. The approval parser invents default Allow Once/Always/Reject choices from
   screen signatures. Answer routing sends digit+Enter; unknown option IDs
   fall back to index zero, cancellation ignores key-send errors, and the
   free-text `AnswerQuestion` branch lacks the screen/prompt-ID revalidation
   performed by `AnswerPrompt`. These paths do not establish safe generic Pi
   extension UI support. Do not exercise them against owner prompts as a test.
5. Herdr `done` is readiness plus unseen completion, not proof of task success.
   The API offers completion sequence identity independent of viewing state.
   Cappuccino drops it in `src/agents.rs`; use that source fact when designing
   notifications rather than inventing an unsupported generic done signal.
6. The current debug preview has disabled Send and mock alert preferences.
   It neither removes existing production actions nor implements new controls.

## Source anchors

Herdr paths, relative to the pinned reference:

- `src/integration/assets/pi/herdr-agent-state.ts`
- `src/api/schema.rs`: complete agent method enumeration
- `src/api/schema/agents.rs`: `AgentPromptParams`, `AgentInfo` sequence fields
- `src/app/api/agents.rs`: `queue_agent_prompt`, foreground/blocked checks
- `src/api/subscriptions.rs`: agent status/finished event coverage
- `docs/preview/website/src/content/docs/agent-automation.mdx`: seen/done semantics

Cappuccino paths:

- `services/bridge/src/herdr.rs`: socket methods actually forwarded
- `services/bridge/src/modules/agents.rs`: implemented HTTP endpoints
- `services/bridge/src/prompt.rs`: heuristic cards and fabricated default choices
- `services/bridge/src/routes.rs`: prompt/answer submission branches
- `services/bridge/src/agents.rs`: catalog fields retained/dropped
- `apps/android/app/src/main/java/com/azusachino/cappuccino/io/BridgeClient.kt`
- `apps/android/app/src/main/java/com/azusachino/cappuccino/io/ConnectedViewModel.kt`
- `apps/android/app/src/main/java/com/azusachino/cappuccino/ui/CappuccinoShell.kt`

## Consequence for the rewrite

Use the owner's pi-pocket screenshot for session hierarchy, density and the
integrated composer. Keep supported Pi controls in the capability/design map.
Distinguish Pi support, existing-session transport availability, safe native
consumption and tested runtime readiness. A screenshot is neither evidence of
transport support nor permission to exclude a control.

Next work must establish the same-running-session control contract and exact
Pi extension UI coverage before implementing controls or claiming full feature
parity. Fresh remote/version inspection is read-only; installing/loading a new
adapter or manipulating agent state needs separate explicit owner approval.
