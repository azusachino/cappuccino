# herdr-web-ui Architecture, Design Decisions & UX Analysis for Cappuccino

## 1. Context & Motivation

Cappuccino is an Apple and Android native companion for monitoring and managing coding agents orchestrated by Herdr. The initial Android shell suffered from critical UX degradation:

- Streaming text repeatedly re-emitted in-progress fragments as permanent stacked rows.
- The UI forced OS wallpaper dynamic color extraction on Android 12+, completely wiping out Herdr's lamp-lit amber and graphite palette.
- Disconnecting or switching machines was unintuitive, with bare text buttons instead of standard Material Design iconography and clear action surfaces.
- The transcript lacked structure, treating prompts, tool executions, and internal status chrome as identical raw text.

To fix Cappuccino's native Android client cleanly, this document conducts an architectural and design study of `herdr-web-ui` (`refs/coding-agents/herdr-web-ui`), extracting its design decisions, layout system, transcript heuristics, and interaction model.

---

## 2. Core Atmosphere & Identity

As defined in `herdr-web-ui`'s `DESIGN.md`:
> *"A warm terminal: an amber-phosphor console on lamp-lit graphite (dark) or ledger paper (light), with chat-app clarity. Tonal surfaces and hairlines keep the chrome out of the way. There is ONE chrome color, amber: selection, focus, the terminal cursor and the user's own action (Send, primary buttons). Agent states carry the remaining saturated colors and none of them is amber."*

### Key Rules

1. **Amber is exclusively for user focus & action**: Selection indicators, focus outlines, primary interactive buttons, and the selected session rail.
2. **Neutral User Turns**: User prompts are raised neutral cards (`--bg-elevated`), never colored bubbles. This prevents long threads from turning into a chaotic wall of color.
3. **Dedicated Agent Status Colors**:
   - `working` (#6CB8D6 in dark, #155A72 in light): Calming cyan/steel blue with a breathing dot.
   - `idle` (#9B9183 in dark, #685E52 in light): Dim neutral.
   - `blocked` (#FF7B70 in dark, #A82323 in light): Coral/red indicating "Needs you".
   - `done` (#93C36B in dark, #2F6317 in light): Olive/soft green indicating completed turns.

---

## 3. Transcript Structure & Mental Model

In `herdr-web-ui`, the raw scrollback text from the agent's PTY is parsed into structured, quiet transcript messages rather than raw log dumps (`src/lib/transcript.ts` and `src/components/ChatView.tsx`):

### Speaker Attribution & Categorization

- **Full-Width Rule Lines** (`^[─━═]{6,}$`): Act as turn separators. Never rendered as text; they flush pending message blocks.
- **User Prompt Echoes** (`^[❯>]\s?`): Captured and formatted as right-aligned elevated bubbles (`chat-bubble`), separating user instructions from agent work.
- **Tool Work Rows** (`● Read(...)`, `● Edit(...)`, `○ Bash(...)`): Classified as tool activity. They feature monospace tool parameters and glyph indicators.
- **Agent Status Chrome** (`working · Gemini...`, `⣾ Running command...`, `Context...`, `Usage...`): Dimmed metadata rows that do not clutter chat bubbles.
- **Agent Answers**: Rendered cleanly as markdown prose without unnecessary avatar boxes or names.

### Streaming Tail Ingestion

When agents stream tokens, the last line changes on each poll before a newline arrives. `herdr-web-ui` and the bridge handle this with:

- Longest Common Subsequence (LCS) alignment in reconciliation.
- Holding candidate lines while the agent is `working`.
- Collapsing consecutive streaming prefixes on the client side so they replace rather than stack.

---

## 4. UX & Navigation Decisions for Cappuccino Android

Porting `herdr-web-ui`'s UX to native Jetpack Compose on Android requires adopting native conventions while preserving Herdr's design language:

### 1. Connection & Session Lifecycle

- **Explicit Disconnect / Switch**: Users must never be forced to close the app to disconnect.
  - The top app bar provides an explicit Disconnect/Switch action (icon: `ic_close` or `ic_power_settings_new`).
  - Active connection status is visibly summarized with an interactive status chip.
- **Navigation Bar**: Clean bottom navigation destinations with Material Vector Drawables:
  - **Chats** (`ic_chat`): Agent list and current conversation stream.
  - **Attention** (`ic_attention`): Approvals, confirmations, and blocked states.
  - **Machines** (`ic_machine`): Connected bridge endpoints and host profiles.
  - **Settings** (`ic_settings`): Theme selection (System, Light, Dark) and display preferences.

### 2. Message Cards & Visual Hierarchy

- **User Turns**: Right-aligned surface cards with rounded corners (`12.dp`), neutral elevated fill, and readable prose.
- **Agent Turns**: Left-aligned markdown/prose with subtle typography.
- **Tool Invocations**: Compact monospace cards with distinct glyph status indicators.
- **Status Metadata**: Centered or dimmed small chips for execution state and token usage.

### 3. Theme Engine

- Respect `ThemeMode` (`SYSTEM`, `LIGHT`, `DARK`) stored persistently in `ProfileStore`.
- Disable wallpaper dynamic color extraction by default to preserve the Herdr amber/graphite identity, with opt-in system dynamic color support.

---

## 5. Message Handling Architecture: Lessons for Cappuccino Bridge & Client

A deep examination of `herdr-web-ui` (`src/lib/transcript.ts` and `server/pi.ts`) demonstrates that treating raw terminal scrollback lines uniformly in an LCS window (as Cappuccino's bridge initially did) causes severe UX bugs:

### 1. Root Cause of Status Line Duplication

- Agent TUIs repaint the status line continuously during an active turn:
  `working · Gemini 2.5 Flash · 224.0K used (9%)` ➔ `working · Gemini 2.5 Flash · 227.1K used (9%)`
- Terminal spinners cycle through glyphs: `⣾`, `⣽`, `⣻`, `⢿`, `⡿`, `⣟`, `⣯`, `⣷`.
- In an LCS diffing algorithm, because the text content mutates with every counter update or spinner tick, the diff matcher considers each update a brand new line. It commits them consecutively into `entries`, littering scrollback with dozens of orphaned status lines.

### 2. The herdr-web-ui Solution

- **Transient Status Slot vs. Durable History**:
  In `herdr-web-ui`, lines matching `STATUS_LINE`:
  `/^(?:\[[^\]]*\]\s*│|⏵|⣾|█|Context\s|Usage\s|[·•]\s*\d+\s*shell|✳|※)/`
  are explicitly tagged with role `status`. The client does not append them to conversation history. Instead, they update an ephemeral status bar at the top or bottom of the session chrome.
- **Turn Boundaries via Horizontal Rules and Prompt Echoes**:
  - Full-width box-drawing rules (`RULE_LINE = /^[─━═]{6,}$/`) demarcate the end of an agent turn.
  - User prompt echoes (`USER_LINE = /^❯\s?/`) flush previous assistant buffers into a completed message card and initiate the new user turn.
  - Decorative rule characters are filtered out rather than delivered as text to the mobile client.

### 3. Architecture Applied to Cappuccino

1. **Rust Bridge (`services/bridge`)**:
   - Classify pane lines into `kind: "user" | "assistant" | "status"`.
   - Send active status updates as ephemeral metadata events (`event: "status"`) rather than durable appended history lines (`event: "entries"`).
   - Suppress decorative border lines (`─━═`) from the event stream.
2. **Android Client (`CappuccinoShell.kt` & `TranscriptParser.kt`)**:
   - `collapseOutputRows()`: Prune all previously observed `TranscriptRole.STATUS` lines when rendering scrollback, displaying only the single latest active status badge.
   - Deduplicate contiguous identical user prompt echoes so Compose does not flicker across stream updates.
   - Parse assistant markdown through AST rendering (`org.commonmark`) rather than wrapping raw lines in individual monospace boxes.
