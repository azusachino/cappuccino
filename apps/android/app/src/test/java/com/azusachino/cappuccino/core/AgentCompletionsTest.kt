package com.azusachino.cappuccino.core

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCompletionsTest {
  private val machine = UUID.fromString("11111111-1111-4111-8111-111111111111")

  private fun agent(
    state: Long,
    completed: Long? = null,
    ref: String = "native-ref:${"a".repeat(64)}",
    pane: String = "pane",
  ) =
    AgentRow(
      machine,
      pane,
      pane,
      "pi",
      "pi",
      "idle",
      false,
      null,
      stateChangeSeq = state,
      completionSeq = completed,
      nativeSessionRef = ref,
    )

  @Test
  fun initialHistoryAndIdleDoNotNotify() {
    val tracker = AgentCompletions()
    assertTrue(tracker.observe(machine, listOf(agent(4, 4))).isEmpty())
    assertTrue(tracker.observe(machine, listOf(agent(5))).isEmpty())
  }

  @Test
  fun completionNotifiesOnceRegardlessOfSelectedTranscript() {
    val tracker = AgentCompletions()
    tracker.observe(machine, listOf(agent(1), agent(2, pane = "other")))
    assertEquals(2, tracker.observe(machine, listOf(agent(3, 3), agent(4, 4, pane = "other"))).size)
    assertTrue(tracker.observe(machine, listOf(agent(3, 3), agent(4, 4, pane = "other"))).isEmpty())
  }

  @Test
  fun replacementAndSequenceRegressionAreFreshBaselines() {
    val tracker = AgentCompletions()
    tracker.observe(machine, listOf(agent(10)))
    assertTrue(tracker.observe(machine, listOf(agent(1, 1))).isEmpty())
    assertTrue(
      tracker.observe(machine, listOf(agent(11, 11, "native-ref:${"b".repeat(64)}"))).isEmpty()
    )
  }

  @Test
  fun transportGapAndMissingIdentityDoNotReplayCompletion() {
    val tracker = AgentCompletions()
    tracker.observe(machine, listOf(agent(1)))
    tracker.reset(machine)
    assertTrue(tracker.observe(machine, listOf(agent(3, 3))).isEmpty())
    tracker.observe(machine, listOf(agent(4).copy(nativeSessionRef = null)))
    assertTrue(tracker.observe(machine, listOf(agent(5, 5))).isEmpty())
  }

  @Test
  fun removedPaneCannotTransferItsCompletionToAReplacement() {
    val tracker = AgentCompletions()
    tracker.observe(machine, listOf(agent(1)))
    tracker.observe(machine, emptyList())
    assertTrue(tracker.observe(machine, listOf(agent(3, 3))).isEmpty())
  }
}
