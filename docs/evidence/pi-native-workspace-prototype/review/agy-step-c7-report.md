# Cappuccino C7 Independent Source & Criteria Verification Report

**Evaluator:** Antigravity (Agy) Independent Verification Session  
**Visible Model / Effort / Version:** Gemini 3.8 Flash (Medium) / platform runtime environment  
**Status:** INDEPENDENT SOURCE & CRITERIA VERIFICATION (Read-Only). Stage B/C Technical Criteria: **VERIFIED PASS**. Owner Visual Acceptance and Library Adoption: **PENDING OWNER REVIEW** (Advisory only; NOT final Step C sign-off, NOT live Pi parity, NOT physical device sign-off, NOT production merge).  
**Review Target:** `/home/haru/Projects/project-github/harus-workstation/.tmp/cappuccino-pi-workspace-prototype-review-c7/`  
**Report Destination:** `/home/haru/Projects/project-github/harus-workstation/.tmp/cappuccino-pi-workspace-prototype-review-c7-agy/report.md`  

---

## 1. Operating Scope, Governance & Authority

- **Owner Permission:** Under `INDEPENDENT-VERIFICATION.md`, the owner explicitly authorized **matching current runtime evidence reuse** for this independent verification pass, eliminating redundant device re-executions while maintaining full independent source and artifact evaluation.
- **Source State:** FROZEN. Zero source files modified, zero builds executed, zero device/adb commands issued, zero Git/Asobi/remote commands.
- **Owning Repository State:** Clean at `6d3463268b9fddaf5f8e29d8b92e618e7242064b` (`research/native-pi-agent-workspace`). PR 29/31 remain open/unmerged. Production is untouched.
- **Identity Guard:** Existing owner session preserved alive. Evaluator operated strictly in read-only diagnostic role.

---

## 2. Provenance & Exact Digest Verification

- **Common 24-File Source Manifest SHA-256:**  
  `1a1a61e41d6da3c79354362abe62f1109c4694ba8c56883432affe8970aada0c`
- **File Integrity:** Verified across all 24 source, configuration, resource, and build files relative to `android/`:
  - All 24 files passed exact SHA-256 matching (`24 OK, 0 FAILED`).
  - Correctly includes the new native dark resource `app/src/main/res/values-night/themes.xml` and `gradle/wrapper/gradle-wrapper.jar` (`238e777fcddd7e34f9708186085def2abd6e08e658505b38718d79d74c21abd5`).
- **Packaged Test Manifest (`evidence/packaged-test-manifest-aapt2.txt`):**  
  Confirmed packaged instrumentation runner is `androidx.test.runner.AndroidJUnitRunner` for package `com.azusachino.cappuccino.prototype.stepb.test`.

---

## 3. Performed vs. Reused Evidence

### 3.1 Performed Verifications (Independent by Agy)

1. **Manifest Integrity:** Executed SHA-256 digest checks on all 24 bundle files (`24 OK`).
2. **Image Integrity:** Verified 64-hex SHA-256 digests and pixel dimensions on all 12 curated original PNGs in `gallery/` (`12 OK`).
3. **Source Code Audits:** Independent line-by-line inspection of:
   - `PrototypeApp.kt` (Box minimum, `handledAbsence` cleanup on `atTail`, `tailFollowRequested` guard).
   - `PrototypeActivity.kt` (single unconditional root URI provider, window flags SideEffect).
   - `values/themes.xml` and `values-night/themes.xml` (decor window flag initialization).
   - `PassiveMarkdown.kt` and `PassivePolicy.kt` (NoOpUriHandler, image fallback).
   - `StepCJourneyTest.kt` (disambiguated test `i`, test `j` bar flags, conditional scroll in `c`).
4. **Log & XML Inspections:** Examined test runner outputs, test XMLs, and metadata in `provenance.json`.

### 3.2 Reused Evidence (Owner-Approved Reuse Under INDEPENDENT-VERIFICATION.md)

1. **Prerequisite Gates (`evidence/prerequisite/gradle.log`):**  
   `compileDebugKotlin`, `compileDebugAndroidTestKotlin`, `ktfmtCheck`, `testDebugUnitTest` (32/32 JVM tests green: ComposerAndActions: 2, FollowPolicy: 8, Prototype: 15, StreamPlayer: 5, StructuredPassivity: 2), and `lintDebug` clean (exit 0).
2. **4 UI Test Runs (13 tests each, exit 0, 0 failures, 0 errors, 0 skipped):**
   - `short-light` (`141744-final-source-short-light`): 320×640@160, font 1.0, night: no — 13/13 green.
   - `short-dark` (`141814-final-source-short-dark`): 320×640@160, font 1.0, night: yes — 13/13 green.
   - `normal-light` (`141118-normal-light`): 1080×2340@440, font 1.0, night: no — 13/13 green.
   - `normal-dark` (`141158-normal-dark`): 1080×2340@440, font 1.0, night: yes — 13/13 green.
   - *Total UI tests executed across profiles: 52 tests, 0 failures.*

---

## 4. Curated Gallery Verification (12 Original PNGs)

All 12 images in `gallery/` match their declared dimensions, themes, profiles, and 64-hex SHA-256 digests in `index.md` and `provenance.json`:

| File | Dimensions / Density | Theme | Verified SHA-256 | Test / State Provenance |
| :--- | :--- | :--- | :--- | :--- |
| `roster.png` | 320×640 @160 | light | `8c7f085fe110eab0594c026575c8528346bb6e4a82bf5cb1f9e22e5d7b2f0118` | `a_roster_shows_groups...` |
| `workspace-default.png` | 1080×2340 @440 | dark | `d135102652430ab958ef7d54b4a0429d1e127f63ceebbec917de02fb65eec974` | `i_workspace_transcript...` |
| `ime-short-light.png` | 320×640 @160 | light | `52db547b9bc82f33414638ca4e2c341bdfcc8bfcf3fa9433c1d2be6fb0445ca7` | `d_connected_composer_ime_visible` |
| `ime-normal-dark.png` | 1080×2340 @440 | dark | `b34cb1fd5b3058ef857c4d253397214767121746bbd950a1d4b770524032cddd` | `d_connected_composer_ime_visible` |
| `thinking-expanded.png` | 1080×2340 @440 | light | `686ee0eb0560110b013678d70153aa6cea5cff06fa644603b753bef131a4c198` | `c_cross_session_state_reset` |
| `tool-expanded.png` | 1080×2340 @440 | light | `c8f46fc57915e344fdc5ac9fdef458c2b4d6fd56b73dfb880589060d6bfc3eab` | `c_cross_session_state_reset` |
| `unavailable-capabilities.png` | 1080×2340 @440 | dark | `17500c97d20e64d4e15a710b7729712dafe90122df0cb722eec4b6d849ea939e` | `e_all_unavailable_actions...` |
| `growth-during-jump.png` | 320×640 @160 | light | `b6e0cdc50b8e7217f97547e5546279ff9044f07a9fd49f928d25276b1a512ab5` | `g3_size_growth_during_jump...` |
| `manual-reading.png` | 320×640 @160 | dark | `556b080e315d179646938622b93f206fc2c832d78d2a3229fa4603c25636a849` | `g_drag_from_tail_disables_follow...` |
| `readonly.png` | 320×640 @160 | light | `f39b6dcf6b6a5c56e76c011707045befb746ebaccb99477020edd2f591253a37` | `b_select_back_and_reselect...` |
| `disconnected.png` | 320×640 @160 | dark | `acf18c654c09ab98c69c1415dc4e8a2849a26d2e296288ce28a3ad0833c413df` | `b_select_back_and_reselect...` |
| `stream-structured.png` | 1080×2340 @440 | light | `a6cc2e6afae1c87fb09207e5c38411d87f73bbf36ba693b1ad889ed6a7688ff7` | `s_streaming_structured...` |

*Verification Notes:*

- All normal profile images measure **1080×2340** (superseding and replacing the prior invalid 1080×1920 captures).
- In dark mode captures (`workspace-default.png`, `ime-normal-dark.png`), status bar icons (clock, cellular, battery) render in **crisp white with high contrast**, resolving the previous black-on-dark defect.
- In `ime-short-light.png` and `ime-normal-dark.png`, the multiline composer is completely displayed above the soft keyboard, with positive unclipped geometry and legible typed text.
- Both `thinking-expanded.png` and `tool-expanded.png` depict the actual displayed expanded content.

---

## 5. Technical Audit of Architectural & Behavioral Criteria

### 5.1 Native Container Unconditional Height & Streaming Lifecycle

- **Implementation:** `PrototypeApp.kt:556-562`
- **Audit Findings:**
  1. The container `Box` carries `modifier.defaultMinSize(minHeight = bodyLineHeightDp)` where `bodyLineHeightDp` is derived from `MaterialTheme.typography.bodyLarge.lineHeight.toDp()`.
  2. The misleading `shown < player.chunks.size` conditional branch was eliminated.
  3. During asynchronous Markdown AST parsing, even if `PassiveStreamingMarkdown` emits zero child nodes in early frames, the Box maintains its intrinsic one-line minimum. It never collapses to 0px height, preserving the in-flight parser and semantics node composition at the LazyList viewport edge.
  4. Authoritative replacement (`nextOr` delivering `authoritativeOverride`) bumps `player.generation`, clears old deltas, and replaces with authoritative content. Test `f` asserts absence of `"in chunks"` plain text.
  5. UI reset epoch (`streamEpoch`) guarantees that a partial reset during generation 0 recreates rendering state without mutating pure model contracts (`StreamPlayer.reset()` stays generation 0, keeping all 32 JVM tests green).

### 5.2 Absent Tail Recovery & Clean Tail Reset

- **Implementation:** `PrototypeApp.kt:415-492`
- **Audit Findings:**
  1. The `snapshotFlow` collector observes `TailObservation(tailSize, atTail, busy, programmatic, lastIndex, total)`.
  2. **Startup Intent Preserved:** `tailFollowRequested` is set to `true` **only** upon an explicit chip click (`onJumpLatest`) or the first stream step (`player.step > 0`). Startup with `followLatest = true` alone does NOT trigger auto-jump, ensuring the initial transcript history at index 0 remains visible (test `i` passes cleanly on all 4 profiles).
  3. **Absence Recovery:** When `!atTail && tailFollowRequested && total > 0` and the list is idle (`!busy && programmatic == 0`), it executes `jumpToActualTail()` once per signature `"$total:$lastIndex"`.
  4. **Clean Tail Reset:** At line 480:

     ```kotlin
     } else if (atTail) {
       handledAbsence = null
     ```

     When `atTail` is achieved, `handledAbsence` is reset to `null`. If a second late pre-tail expansion occurs under the same visible index, the recovery mechanism cleanly re-triggers rather than being permanently dropped.
  5. **Manual Gesture Protection:** Active user scroll disarms `followLatest = false` via `FollowPolicy.followTransition`. Recovery only runs when `!busy && programmatic == 0 && followLatest`. Manual reading anchor stability is preserved (test `g`).

### 5.3 Root URI Provider, Intent Defense & Truthful Image URL Fallback

- **Implementation:** `PrototypeActivity.kt:61-66`, `PassiveMarkdown.kt:50-111`, `PassivePolicy.kt:23-28`
- **Audit Findings:**
  1. **Stable Root Provider:** A single unconditional `CompositionLocalProvider(LocalUriHandler provides (testUriHandlerOverride ?: currentHandler))` wraps `PrototypeApp`. Changing `testUriHandlerOverride` never alters the slot-table tree or remounts the application state.
  2. **Intent Defense:** Inner `NoOpUriHandler` in `PassiveMarkdown.kt` wraps all rendered markdown. Link spans build with `linkInteractionListener = null`. In test `h`, 5 link span glyph clicks yield **0 hits** on the recording URI spy and **0 external browser launches** on the blocking `ACTION_VIEW` monitor.
  3. **Truthful Image URL Fallback:** In mikepenz 0.45.0, `resolveImageAlt` returns `null` in both static and streaming paths. `PassivePolicy.passiveImageText(alt, link)` truthfully renders `[image: $link]`. Both static and streaming images render the URL fallback as passive text; no remote fetching occurs.

### 5.4 System Bar Appearance & Native Theme Harmonization

- **Implementation:** `values/themes.xml:3-6`, `values-night/themes.xml:10-17`, `PrototypeActivity.kt:50-55`, `StepCJourneyTest.kt:164-216`
- **Audit Findings:**
  1. `values/themes.xml` configures `windowLightStatusBar = true` and `windowLightNavigationBar = true` on `android:Theme.Material.Light.NoActionBar`.
  2. `values-night/themes.xml` configures `windowLightStatusBar = false` and `windowLightNavigationBar = false` on `android:Theme.Material.NoActionBar`.
  3. Android's window decor initializes with the correct matching status/nav bar icon appearance flags immediately upon window creation.
  4. Test `j` explicitly asserts runtime `WindowCompat.getInsetsController` flags against theme run arguments, confirming `isAppearanceLightStatusBars == !expectedNight`.
  5. Dark mode captures display crisp white status bar icons, completely resolving C-6's black-on-dark defect.

### 5.5 Test Delta Audits: Disambiguated Test `i` & Conditional Scroll in Test `c`

- **Audit Findings:**
  1. **Test `i` (`onAllNodesWithText("You (fixture)")[0]`):** In `conversationFor(identity)`, both Turn 1 and Turn 3 have role label `"You (fixture)"`. On a tall 1080×2340 screen, both turns are composed simultaneously, which throws an `AmbiguousNodesException` if `onNodeWithText` is used. Selecting `[0]` specifically asserts the first turn in list order is displayed. This is a necessary selector disambiguation, not an assertion weakening.
  2. **Test `c` (Conditional Scroll):** In short profiles (320×640), expanding both tool input/output and thought bodies exceeds the viewport height. The test asserts `assertIsDisplayed()`, and if scrolled offscreen, scrolls to the node and re-asserts `assertIsDisplayed()` before taking the screenshot. This guarantees that captured artifacts reflect verified displayed states without resorting to double-clicks, artificial clocks, or assertion suppressions.

---

## 6. Criteria Assessment Summary

| Criterion | Evaluation | Justification & Evidence |
| :--- | :--- | :--- |
| **Prerequisites & 32 Core JVM Tests** | **MET** | 32/32 unit tests pass; compile, ktfmt, and lint clean (`evidence/prerequisite/`). |
| **Common 24-File Manifest Integrity** | **MET** | 24/24 files match SHA-256 `1a1a61e41d6da...` (`24 OK, 0 FAILED`). |
| **Packaged Instrumentation Runner** | **MET** | `androidx.test.runner.AndroidJUnitRunner` confirmed via `aapt2 dump xmltree`. |
| **Approved Profile Resolution Matrix** | **MET** | 4 full passes (13/13 each): short light/dark (320×640@160) and normal light/dark (1080×2340@440). |
| **Native Unconditional Container Height** | **MET** | `Box` modifier carries `defaultMinSize(minHeight = bodyLineHeightDp)`. |
| **Absent Tail Recovery & Clean Tail Reset** | **MET** | Recovers absent tail; `handledAbsence = null` executed on acquiring tail; manual gestures protected. |
| **Intent Defense & Passive URI Policy** | **MET** | 0 recording spy hits; 0 `ACTION_VIEW` launches; truthful passive image URL fallback. |
| **Native Bar Contrast & Night Alignment** | **MET** | Verified via test `j` flags and high-contrast white icons in dark gallery originals. |
| **Representative Curated Gallery** | **MET** | 12 original PNGs verified across dimensions, themes, full SHA-256 digests, and provenance. |
| **Stage B/C Technical Verdict** | **MET** | All technical requirements under Plan Stage B and C are satisfied with evidence. |
| **Owner Visual Acceptance & Adoption** | **PENDING** | Open for owner review; no production merge, live Pi parity, or library adoption claimed. |

---

## 7. Technical Verdict & Next Actions

- **Technical Verification Verdict:** **PASS (Stage B/C Criteria Met)**.  
  The scratch Android prototype codebase successfully satisfies all specified lifecycle, layout, passivity, follow-latest, and profile-matrix requirements under rigorous multi-profile automated test evidence.
- **Pending Owner Decision:**  
  Independent source and criteria verification is complete. The 12 original gallery captures and architectural contracts are submitted for owner visual evaluation and library adoption determination. No production merges or live Pi integrations should occur prior to that explicit owner review.
