package com.azusachino.cappuccino

import android.graphics.Bitmap
import android.os.Build
import android.view.WindowInsets
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.core.ConversationPart
import com.azusachino.cappuccino.core.ConversationTurn
import com.azusachino.cappuccino.core.Endpoint
import com.azusachino.cappuccino.core.StreamEntry
import com.azusachino.cappuccino.core.StreamState
import com.azusachino.cappuccino.io.ConnectedActions
import com.azusachino.cappuccino.io.ConnectedUiState
import com.azusachino.cappuccino.io.ConnectionState
import com.azusachino.cappuccino.io.MachineProfile
import com.azusachino.cappuccino.ui.CappuccinoScreen
import com.azusachino.cappuccino.ui.CappuccinoTheme
import com.azusachino.cappuccino.ui.ThemeMode
import java.util.UUID
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Owner preview capture for the native Material 3 conversation slice. All data is synthetic and
 * stable across captures; dark/large-font variants render through the real theme path and density,
 * and width variants come from the task-owned emulator's density override (compact 320 dp vs normal
 * ~411 dp). Capture names embed the actual rendered width dp so a capture can never be mislabeled
 * after a density change. No owner device, bridge or endpoint data.
 */
@RunWith(AndroidJUnit4::class)
class ConversationPreviewScreenshotTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  private val markdownFixture =
    "# Synthetic heading\n\nUse **bold** and `inline code`.\n\n```kotlin\nval value = 42\n```\n\n- First item\n- Second item"

  private fun previewTurns(): List<ConversationTurn> =
    listOf(
      ConversationTurn(
        "p0",
        "assistant",
        null,
        (1..4).joinToString("\n\n") { "Preview paragraph $it" },
        emptyList(),
      ),
      ConversationTurn(
        "p1",
        "assistant",
        "2026-10-07T16:02:03Z",
        null,
        listOf(
          ConversationPart.Thinking(
            "Verify the disclosure rows before replying.\n\nHidden thought details: check collapse, contrast and spacing."
          ),
          ConversationPart.ToolCall(
            "bash",
            "bun run test --filter android",
            "63 passed · 0 failed",
          ),
          ConversationPart.Text(
            "Rebuilt the conversation screen on native Material 3 tokens. The transcript reads directly on the background now.\n\n### What changed\n\n- Stock **lightColorScheme** and **darkColorScheme** fallbacks\n- User turns use a `primaryContainer` bubble\n- Disclosures are tonal rows with a real chevron\n\n```kotlin\nval scheme = if (dark) darkColorScheme() else lightColorScheme()\n```"
          ),
        ),
      ),
      ConversationTurn("p2", "assistant", null, markdownFixture, emptyList()),
      ConversationTurn(
        "p3",
        "user",
        "2026-10-07T16:05:00Z",
        "Capture the Material 3 preview set",
        emptyList(),
      ),
    )

  private fun previewState(): MutableState<ConnectedUiState> {
    val machine = UUID.randomUUID()
    val profile =
      MachineProfile(
        "preview",
        "Synthetic machine",
        Endpoint.parse("https://bridge.example"),
        machine,
      )
    val agent = AgentRow(machine, "synthetic-session", "pane-1", "pi", "pi", "idle", false, "main")
    return mutableStateOf(
      ConnectedUiState(
        profiles = listOf(profile),
        activeProfileId = profile.id,
        agents = listOf(agent),
        selectedAgent = agent,
        conversationTurns = previewTurns(),
        connection = ConnectionState.Connected,
      )
    )
  }

  private fun setContent(
    state: MutableState<ConnectedUiState>,
    themeMode: ThemeMode,
    fontScale: Float = 1f,
  ) {
    compose.activity.runOnUiThread {
      compose.activity.setContent {
        CappuccinoTheme(
          themeMode = themeMode,
          // Same production theme-to-bar sync MainActivity wires, so dark captures show
          // exactly what a real user gets (production-path assertion lives in
          // SystemBarThemeSyncTest; this only keeps the fixture faithful).
          onResolvedDarkChanged = compose.activity::applySystemBarAppearance,
        ) {
          val current = LocalDensity.current
          CompositionLocalProvider(
            LocalDensity provides Density(current.density, fontScale = fontScale)
          ) {
            val s by state
            CappuccinoScreen(s, PreviewActions(state), requestConnect = { _, _ -> })
          }
        }
      }
    }
    compose.waitForIdle()
    compose.onNodeWithText("Recent agent output · Read-only").assertIsDisplayed()
  }

  @Test
  fun captureConversationCollapsedLight() {
    setContent(previewState(), ThemeMode.LIGHT)
    compose.onNodeWithText("Capture the Material 3 preview set").assertIsDisplayed()
    // Physical reader scroll back to the very top of the transcript so the collapsed capture
    // shows the start of the conversation (p0 prose plus the collapsed disclosure row), not
    // just the tail viewport the list opens on.
    repeat(3) {
      if (!anyDisplayed("Preview paragraph 1", maxTopFraction = 0.5f)) {
        compose.onNodeWithTag("recentOutputList").performTouchInput { swipeDown() }
        compose.waitForIdle()
      }
    }
    compose.onNodeWithText("Preview paragraph 1").assertIsDisplayed()
    capture("${capturePrefix()}LightCollapsed")
    // Back down to the lower prose: the markdown fixture turn and the end-aligned user bubble.
    repeat(3) {
      if (!anyDisplayed("Capture the Material 3 preview set")) {
        compose.onNodeWithTag("recentOutputList").performTouchInput { swipeUp() }
        compose.waitForIdle()
      }
    }
    compose.onNodeWithText("Capture the Material 3 preview set").assertIsDisplayed()
    capture("${capturePrefix()}LightMarkdownUserTurn")
  }

  @Test
  fun captureConversationExpandedDark() {
    val state = previewState()
    setContent(state, ThemeMode.DARK)
    // Physical reader scroll back until the disclosure rows are actually on screen
    // (off-screen pre-composed nodes exist but are not displayed).
    repeat(3) {
      if (!anyDisplayed("Thinking", maxTopFraction = 0.5f)) {
        compose.onNodeWithTag("recentOutputList").performTouchInput { swipeDown() }
        compose.waitForIdle()
      }
    }
    compose.onNodeWithText("Thinking").assertIsDisplayed()
    compose.onNodeWithText("Thinking").performClick()
    // Reader scroll so the expanded content is actually in view at narrow widths (the single
    // click already happened; this only brings the disclosed detail into the viewport).
    compose
      .onNodeWithTag("recentOutputList")
      .performScrollToNode(hasText("Hidden thought details: check collapse, contrast and spacing."))
    // Exact full paragraph text: MarkdownText renders the second thinking paragraph as one Text
    // node with exactly this content; a shorter exact matcher can never display.
    compose.waitUntil(timeoutMillis = 5_000) {
      anyDisplayed("Hidden thought details: check collapse, contrast and spacing.")
    }
    compose
      .onNodeWithText("Hidden thought details: check collapse, contrast and spacing.")
      .assertIsDisplayed()
    // At the 320x640@160 CI frame the Tool row sits below the fold after the bottom-pinning
    // scroll to the Thinking detail; a click injected at its clipped center never lands.
    // Bring the row into view and prove it is displayed before the single expand click.
    compose
      .onNodeWithTag("recentOutputList")
      .performScrollToNode(hasText("Tool: bash · Result available"))
    compose.onNodeWithText("Tool: bash · Result available").assertIsDisplayed()
    compose.onNodeWithText("Tool: bash · Result available").performClick()
    compose.onNodeWithTag("recentOutputList").performScrollToNode(hasText("63 passed · 0 failed"))
    compose.waitUntil(timeoutMillis = 5_000) { anyDisplayed("63 passed · 0 failed") }
    compose.onNodeWithText("63 passed · 0 failed").assertIsDisplayed()
    capture("${capturePrefix()}DarkExpanded")
  }

  @Test
  fun captureConversationLargeFontLight() {
    setContent(previewState(), ThemeMode.LIGHT, fontScale = 1.3f)
    compose.onNodeWithText("Capture the Material 3 preview set").assertIsDisplayed()
    capture("${capturePrefix()}LightLargeFont")
  }

  @Test
  fun captureConversationComposerImeDark() {
    setContent(previewState(), ThemeMode.DARK)
    compose.onNodeWithTag("promptInput").performClick()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      compose.waitUntil(timeoutMillis = 5000) {
        compose.activity.window.decorView.rootWindowInsets
          .getInsets(WindowInsets.Type.ime())
          .bottom > 0
      }
    }
    compose.waitForIdle()
    // Focused text field alone is not proof: assert the actual IME insets before capturing.
    val imeBottom =
      compose.activity.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom
    assertTrue(
      "Composer capture requires a visibly open IME; insets bottom=$imeBottom",
      imeBottom > 0,
    )
    capture("${capturePrefix()}DarkComposerIme")
  }

  @Test
  fun captureConversationFollowLatestLight() {
    // Replicates the merged jump-affordance journey: stream view, manual scroll away,
    // new rows while detached, FAB appears. Physical swipe only: the merged manual-reading
    // detection observes isScrollInProgress, which instant programmatic scrolls skip.
    val machine = UUID.randomUUID()
    val profile =
      MachineProfile(
        "preview",
        "Synthetic machine",
        Endpoint.parse("https://bridge.example"),
        machine,
      )
    val agent = AgentRow(machine, "synthetic-session", "pane-1", "pi", "pi", "idle", false, "main")
    val state =
      mutableStateOf(
        ConnectedUiState(
          profiles = listOf(profile),
          activeProfileId = profile.id,
          agents = listOf(agent),
          selectedAgent = agent,
          stream =
            StreamState(
              "synthetic-session",
              1,
              (1L..30L).map { StreamEntry("e$it", it, "text", "Synthetic output $it", "main") },
            ),
          connection = ConnectionState.Connected,
        )
      )
    setContent(state, ThemeMode.LIGHT)
    compose.onNodeWithText("Synthetic output 30").assertIsDisplayed()
    compose.onNodeWithTag("recentOutputList").performTouchInput { swipeDown() }
    compose.waitForIdle()
    compose.runOnUiThread {
      state.value =
        state.value.copy(
          stream =
            state.value.stream.copy(
              entries =
                state.value.stream.entries +
                  StreamEntry("e31", 31, "text", "Synthetic output 31", "main")
            )
        )
    }
    waitAndAdvanceUntilJumpHasSize()
    compose.onNodeWithText("Jump to latest").assertIsDisplayed()
    capture("${capturePrefix()}LightFollowLatest")
  }

  private fun anyDisplayed(text: String, maxTopFraction: Float = 1f): Boolean {
    val decor = compose.activity.window.decorView
    val rootW = decor.width.toFloat()
    val rootH = decor.height.toFloat()
    return compose.onAllNodesWithText(text).fetchSemanticsNodes().any {
      val b = it.boundsInRoot
      b.width > 0 &&
        b.height > 0 &&
        b.bottom > 0f &&
        b.top < rootH * maxTopFraction &&
        b.right > 0f &&
        b.left < rootW
    }
  }

  private fun waitAndAdvanceUntilJumpHasSize() {
    // The M3 extended FAB animates its width in on appearance; wait (bounded, test
    // thread) until the affordance has real size before asserting display.
    compose.waitUntil(timeoutMillis = 5_000) {
      compose.onAllNodesWithText("Jump to latest").fetchSemanticsNodes().any {
        it.boundsInRoot.width > 100
      }
    }
    compose.waitForIdle()
  }

  // Width-dp capture prefix from the live display metrics (1080 px / density override):
  // 420 -> 411 (normal), 540 -> 320 (compact), so names always match the rendered width.
  private fun capturePrefix(): String {
    val metrics = compose.activity.resources.displayMetrics
    return "Conversation${(metrics.widthPixels / metrics.density).roundToInt()}"
  }

  private fun capture(name: String) {
    val bitmap =
      requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
    try {
      PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
      }
    } finally {
      bitmap.recycle()
    }
  }

  private class PreviewActions(private val state: MutableState<ConnectedUiState>) :
    ConnectedActions {
    override fun addMachine(label: String, url: String) = Unit

    override fun selectProfile(id: String) = Unit

    override fun disconnectProfile() = Unit

    override fun refresh() = Unit

    override fun selectAgent(agent: AgentRow?) {
      state.value = state.value.copy(selectedAgent = agent)
    }

    override fun removeProfile(id: String) = Unit

    override fun retry() = Unit

    override fun setThemeMode(mode: ThemeMode) {
      state.value = state.value.copy(themeMode = mode)
    }

    override fun submitPrompt(text: String) = Unit

    override fun answerPrompt(
      promptId: String,
      optionIndex: Int?,
      optionId: String?,
      action: String?,
    ) = Unit

    override fun loadConversation() = Unit
  }
}
