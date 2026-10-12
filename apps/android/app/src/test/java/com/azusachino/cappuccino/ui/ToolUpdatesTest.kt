package com.azusachino.cappuccino.ui

import com.azusachino.cappuccino.core.ConversationPart
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolUpdatesTest {
  private fun entry(id: String, output: String? = null, failed: Boolean? = null) =
    ToolActivityEntry(id, 0, ConversationPart.ToolCall("bash", "command", output, failed))

  @Test
  fun initialHistoryIsNotNewActivity() {
    assertEquals(0, ToolUpdates().observe(listOf(entry("old", "result")), false).unseen)
  }

  @Test
  fun newCallAndItsReturnEachIncrementTheCounterOnce() {
    val initial = ToolUpdates().observe(emptyList(), false)
    val call = initial.observe(listOf(entry("new")), false)
    assertEquals(1, call.unseen)
    val result = call.observe(listOf(entry("new", "result")), false)
    assertEquals(2, result.unseen)
    assertEquals(2, result.observe(listOf(entry("new", "updated result")), false).unseen)
  }

  @Test
  fun batchedCallAndReturnAreBothCountedAndFailedReturnIsStillAReturn() {
    val initial = ToolUpdates().observe(emptyList(), false)
    assertEquals(2, initial.observe(listOf(entry("new", "result")), false).unseen)
    val call = initial.observe(listOf(entry("new")), false)
    assertEquals(2, call.observe(listOf(entry("new", failed = true)), false).unseen)
  }

  @Test
  fun viewingLatestToolsAcknowledgesUpdatesButOlderInspectionDoesNot() {
    val initial = ToolUpdates().observe(emptyList(), false)
    val update = initial.observe(listOf(entry("new", "result")), false)
    assertEquals(2, update.observe(listOf(entry("new", "result")), false).unseen)
    assertEquals(0, update.observe(listOf(entry("new", "result")), true).unseen)
  }

  @Test
  fun historyTrimmingAndTemporaryMissingResultsDoNotGenerateDuplicateReturns() {
    val initial = ToolUpdates().observe(listOf(entry("old", "result")), false)
    val trimmed = initial.observe(emptyList(), false)
    val missing = trimmed.observe(listOf(entry("old")), false)
    assertEquals(0, missing.observe(listOf(entry("old", "result")), false).unseen)
  }
}
