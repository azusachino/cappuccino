package com.azusachino.cappuccino.core

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentWorkspaceTest {
  @Test
  fun preservesReportedDirectoryWithoutUsingItForIdentity() {
    val machine = UUID.randomUUID()
    val json =
      """{"machine_id":"$machine","agents":[{"machine_id":"$machine","session_id":"exact-pane","cwd":"/work/project","active_branch":"main"}]}"""
    val row = parseAgents(parseJsonObject(json), machine).single()
    assertEquals("/work/project", row.cwd)
    assertEquals("main", row.branch)
    assertEquals("exact-pane", row.sessionId)
  }

  @Test
  fun olderBridgeWithoutDirectoryRemainsUnknown() {
    val machine = UUID.randomUUID()
    val json =
      """{"machine_id":"$machine","agents":[{"machine_id":"$machine","session_id":"exact-pane"}]}"""
    assertNull(parseAgents(parseJsonObject(json), machine).single().cwd)
  }
}
