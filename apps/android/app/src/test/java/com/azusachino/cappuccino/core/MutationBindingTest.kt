package com.azusachino.cappuccino.core

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class MutationBindingTest {
  private val agent =
    AgentRow(
      UUID.fromString("11111111-1111-4111-8111-111111111111"),
      "locator",
      "pane",
      "fixture",
      "pi",
      "idle",
      false,
      null,
      nativeSessionRef = "opaque",
    )

  @Test
  fun sharedWireBindingKeepsNativeReferenceOpaqueAndUuidLogical() {
    val fixture =
      Json.parseToJsonElement(File("../../../fixtures/mutation-session-binding.json").readText())
        .jsonObject
    val expected = fixture.getValue("valid").jsonObject.getValue("expected_session").jsonObject
    val selected =
      agent.copy(
        machineId = UUID.fromString(expected.getValue("machine_id").jsonPrimitive.content),
        paneId = expected.getValue("pane_id").jsonPrimitive.content,
        nativeSessionRef = expected.getValue("native_session_ref").jsonPrimitive.content,
      )
    val binding = requireNotNull(MutationBinding.from(selected))
    assertEquals(selected.machineId, binding.machineId)
    assertEquals(selected.paneId, binding.paneId)
    assertEquals(selected.nativeSessionRef, binding.nativeSessionRef)
  }

  @Test
  fun missingBlankAndOversizedBindingsCannotEnableActions() {
    assertNull(MutationBinding.from(agent.copy(paneId = null)))
    assertNull(MutationBinding.from(agent.copy(nativeSessionRef = null)))
    for (invalid in listOf("", " ", "a".repeat(257), "界".repeat(86))) {
      assertNull(MutationBinding.from(agent.copy(paneId = invalid)))
      assertNull(MutationBinding.from(agent.copy(nativeSessionRef = invalid)))
    }
    for (valid in listOf("a".repeat(256), "界".repeat(85))) {
      assertNotNull(MutationBinding.from(agent.copy(paneId = valid, nativeSessionRef = valid)))
    }
  }
}
