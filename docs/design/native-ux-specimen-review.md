# UX-1 specimen review

Status: first static proposal; owner review pending. This is not an installed redesign.

Plan: [native UX improvement session](../plan/native-ux-improvement-session.md).
Rationale: [Telegram, Nekogram and Haven source study](telegram-nekogram-ux-study.md).

## Prepared material

Workstation scratch: `.tmp/cappuccino-phone-debug/ux-improvement/`.

- `baseline.png`: actual authorized-phone Machines capture. Active agents are visible; the refresh/disconnect controls, tall machine row and large status badges remain in the installed baseline.
- `ux-light.svg.png` and `ux-dark.svg.png`: four-screen synthetic boards, showing Machines, machine information, Settings and conversation together.
- `ux-dark-large-reader.svg.png`: the same direction with larger conversation body text. It is not an Android font-scale test.
- `ux-light-states.svg.png` and `ux-dark-states.svg.png`: offline discovery, incompatible protocol and read-only terminal fallback examples.
- Matching SVGs and `specimens.py` reproduce the illustrations without new dependencies or upstream code.

Boards use synthetic machine names, messages, state and version values. Example test/terminal output is not an executed test or live session. They were drawn as static SVG and rendered with platform Quick Look tools, not Compose. Fonts, glyphs, touch targets, insets, scroll, IME behavior and accessibility require later native verification. Do not use the boards as acceptance evidence for those properties.

## Proposed treatment

- Compact machine grouping row with active agents immediately visible. Tap machine identity to open information; infrequent actions live in contextual menus. Global navigation remains Machines, Attention, Settings.
- One quiet sheet for machine identity/state and the App/Bridge version pair. Technical details opens a separate view. A protocol conflict replaces the success state with a short explanation; it does not show active agents behind a false Connected state.
- Settings labels and short values share a row; descriptions that repeat the destination disappear. No new settings.
- A centered conversation identity/Back group, subordinate context and one menu. Prose has priority over thinking/tool chrome; command, result and long-reader access remain present without duplicating the command.
- Read-only fallback is clearly terminal output, not reconstructed assistant/tool history. Missing canonical history and unsupported prompt input are separate conditions; the fallback specimen deliberately illustrates both, not a rule that one implies the other.
- Synthetic labels and illustrative-output annotations in the boards are review aids, not production UI copy.

## Review boundary

Decide whether this density, hierarchy and information placement are closer to the owner's taste. In particular, approve or revise the compact machine sheet and the quiet row-based presentation before UX-2. A general approval of the plan is not automatic acceptance of this first visual proposal.

Production APK, bridge, proxy, preferences and remote sessions were not modified for UX-1. Existing dirty work and historical test gaps remain intact. Once approved, preserve the approved specimens in the owning evidence record alongside the owner decision; scratch paths are not the sole permanent design authority.
