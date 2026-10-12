package com.azusachino.cappuccino.core

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class CompletionMetadataTest {
  private val machine = UUID.fromString("11111111-1111-4111-8111-111111111111")

  private fun parse(extra: String = "") =
    parseAgents(
        parseJsonObject(
          """
    {"event":"agents","machine_id":"$machine","agents":[
      {"machine_id":"$machine","session_id":"locator","agent":"pi"$extra}
    ]}
  """
        ),
        machine,
      )
      .single()

  @Test
  fun oldBridgeHasNoFabricatedCompletionSignal() {
    val agent = parse()
    assertNull(agent.stateChangeSeq)
    assertNull(agent.completionSeq)
    assertNull(agent.nativeSessionRef)
  }

  @Test
  fun completionMetadataSurvivesCatalogParsing() {
    val reference = "native-ref:${"a".repeat(64)}"
    val agent =
      parse(",\"state_change_seq\":41,\"completion_seq\":41,\"native_session_ref\":\"$reference\"")
    assertEquals(41L, agent.stateChangeSeq)
    assertEquals(41L, agent.completionSeq)
    assertEquals(reference, agent.nativeSessionRef)
  }

  @Test
  fun malformedSequencesAndReferencesAreRejected() {
    listOf(
        ",\"state_change_seq\":-1",
        ",\"completion_seq\":-1",
        ",\"completion_seq\":\"41\"",
        ",\"native_session_ref\":\"locator\"",
      )
      .forEach { extra ->
        try {
          parse(extra)
          fail("Invalid completion metadata accepted")
        } catch (_: ProtocolException) {}
      }
  }
}
