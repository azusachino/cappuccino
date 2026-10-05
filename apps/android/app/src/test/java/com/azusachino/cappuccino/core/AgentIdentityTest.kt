package com.azusachino.cappuccino.core

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AgentIdentityTest {
  private val machine = UUID.fromString("00000000-0000-0000-0000-000000000001")

  @Test
  fun sessionsAreMachineScoped() {
    val otherMachine = UUID.fromString("00000000-0000-0000-0000-000000000002")
    assertNotEquals(AgentIdentity(machine, "session"), AgentIdentity(otherMachine, "session"))
  }

  @Test
  fun identicalAgentReferencesAreEqual() {
    assertEquals(AgentIdentity(machine, "session"), AgentIdentity(machine, "session"))
  }

  @Test
  fun deliveryChoicesRemainDistinct() {
    assertEquals(listOf("Nudge", "Follow-up"), MessageDelivery.entries.map { it.label })
    assertNotEquals(MessageDelivery.NUDGE, MessageDelivery.FOLLOW_UP)
  }
}
