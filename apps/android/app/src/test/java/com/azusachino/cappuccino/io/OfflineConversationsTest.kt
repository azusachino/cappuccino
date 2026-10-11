package com.azusachino.cappuccino.io

import com.azusachino.cappuccino.core.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class OfflineConversationsTest {
  private val machine = UUID.fromString("11111111-1111-4111-8111-111111111111")
  private val other = UUID.fromString("22222222-2222-4222-8222-222222222222")

  private fun agent(locator: String = "w1:p1", id: UUID = machine) =
    AgentRow(id, locator, "p1", "Saved agent", "pi", "working", true, "feature", "/project")

  private fun history() =
    RecentConversation(
      listOf(
        ConversationTurn(
          "turn",
          "agent",
          "2026-01-01T00:00:00Z",
          "世界🙂\r\n",
          listOf(
            ConversationPart.Text("original"),
            ConversationPart.Thinking("thought"),
            ConversationPart.ResponseStatus("failed", "terminated"),
            ConversationPart.ToolCall(
              "bash",
              "printf '%s' \"${'$'}HOME\"\r\n",
              null,
              null,
              "start",
              null,
              42,
            ),
            ConversationPart.ToolCall("read", "argument", "output", true, "start", "finish", 45),
          ),
        )
      ),
      12,
      250,
    )

  @Test
  fun restartRoundTripPreservesIdentityContentIndicesCountersAndUnknownFlags() {
    val root = Files.createTempDirectory("offline-history").toFile()
    try {
      val folder = File(root, "offline")
      val saved = OfflineConversations(folder).save(machine, agent(), history())
      val reopened = requireNotNull(OfflineConversations(folder).read(machine, "w1:p1"))
      assertEquals(saved, reopened)
      assertEquals(history(), reopened.history)
      assertEquals("unknown", reopened.agent.status)
      assertFalse(reopened.agent.working)
      assertTrue(reopened.savedAt > 0)
      assertTrue(OfflineConversations(folder).bytes() > 0)
      assertNull(OfflineConversations(folder).read(other, "w1:p1"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun sameLocatorOnDifferentMachinesRemainsSeparateAndCannotEscapeStorage() {
    val root = Files.createTempDirectory("offline-identities").toFile()
    try {
      val store = OfflineConversations(File(root, "offline"))
      store.save(machine, agent("../../draft"), history())
      store.save(other, agent("../../draft", other), history())
      assertEquals(machine, store.read(machine, "../../draft")?.agent?.machineId)
      assertEquals(other, store.read(other, "../../draft")?.agent?.machineId)
      assertEquals(2, store.list(setOf(machine, other)).size)
      assertEquals(1, store.list(setOf(machine)).size)
      assertFalse(File(root, "draft").exists())
      assertTrue(
        File(root, "offline").listFiles()!!.all { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }
      )
      assertThrows(IllegalArgumentException::class.java) { store.save(other, agent(), history()) }
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun evictionKeepsNewestSnapshotAndNeverTouchesDraftsOrTemporaryCache() {
    val root = Files.createTempDirectory("offline-limits").toFile()
    try {
      val draft = File(root, "draft").also { it.writeText("preserve draft") }
      val cache = File(root, "cache").also { it.mkdir() }
      File(cache, "temporary").writeText("temporary")
      val store = OfflineConversations(File(root, "offline"), maxEntries = 2)
      store.save(machine, agent("first"), history())
      store.save(machine, agent("second"), history())
      store.save(machine, agent("third"), history())
      assertEquals(2, store.list(setOf(machine)).size)
      assertNotNull(store.read(machine, "third"))
      temporaryCacheBytes(cache, clear = true)
      assertEquals("preserve draft", draft.readText())
      assertNotNull(store.read(machine, "third"))
      assertEquals(2, store.list(setOf(machine)).size)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun rejectsLinkedRootsCorruptRecordsAndUnsupportedSchemas() {
    val root = Files.createTempDirectory("offline-validation").toFile()
    try {
      val outside = File(root, "outside").also { it.mkdir() }
      val linked = File(root, "linked")
      Files.createSymbolicLink(linked.toPath(), outside.toPath())
      assertThrows(IllegalArgumentException::class.java) {
        OfflineConversations(linked).save(machine, agent(), history())
      }
      val folder = File(root, "offline")
      val store = OfflineConversations(folder)
      store.save(machine, agent(), history())
      val file = folder.listFiles()!!.single()
      file.writeText(file.readText().replace("\"schema\":1", "\"schema\":2"))
      assertThrows(IllegalArgumentException::class.java) { store.read(machine, "w1:p1") }
      file.writeText("not JSON")
      assertThrows(Exception::class.java) { store.read(machine, "w1:p1") }
      assertTrue(outside.listFiles()!!.isEmpty())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun oversizeOrUnboundedWritePreservesPreviousSnapshotAndLeavesNoPartialFiles() {
    val root = Files.createTempDirectory("offline-atomic").toFile()
    try {
      val store = OfflineConversations(root, maxEntryBytes = 2048, maxBytes = 4096)
      val original = store.save(machine, agent(), history())
      val tooLarge =
        RecentConversation(
          listOf(ConversationTurn("large", "agent", null, "x".repeat(5000), emptyList())),
          0,
          0,
        )
      assertThrows(IllegalArgumentException::class.java) { store.save(machine, agent(), tooLarge) }
      val tooMany =
        RecentConversation(
          (0..200).map { ConversationTurn("t$it", "agent", null, null, emptyList()) },
          0,
          0,
        )
      assertThrows(IllegalArgumentException::class.java) {
        OfflineConversations(root).save(machine, agent(), tooMany)
      }
      assertEquals(original, store.read(machine, "w1:p1"))
      assertFalse(root.listFiles()!!.any { it.name.endsWith(".tmp") })
    } finally {
      root.deleteRecursively()
    }
  }
}
