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

  private fun identity(digit: Char = 'a') = "pi:${digit.toString().repeat(64)}"

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
      val saved = OfflineConversations(folder).save(machine, agent(), history(), identity())
      val reopened = requireNotNull(OfflineConversations(folder).read(machine, identity()))
      assertEquals(saved, reopened)
      assertEquals(history(), reopened.history)
      assertEquals("unknown", reopened.agent.status)
      assertFalse(reopened.agent.working)
      assertTrue(reopened.savedAt > 0)
      assertTrue(OfflineConversations(folder).bytes() > 0)
      assertNull(OfflineConversations(folder).read(other, identity()))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun sameLocatorOnDifferentMachinesRemainsSeparateAndCannotEscapeStorage() {
    val root = Files.createTempDirectory("offline-identities").toFile()
    try {
      val store = OfflineConversations(File(root, "offline"))
      store.save(machine, agent("../../draft"), history(), identity())
      store.save(other, agent("../../draft", other), history(), identity())
      assertEquals(machine, store.read(machine, identity())?.agent?.machineId)
      assertEquals(other, store.read(other, identity())?.agent?.machineId)
      assertEquals(2, store.list(setOf(machine, other)).size)
      assertEquals(1, store.list(setOf(machine)).size)
      assertFalse(File(root, "draft").exists())
      assertTrue(
        File(root, "offline").listFiles()!!.all { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }
      )
      assertThrows(IllegalArgumentException::class.java) {
        store.save(other, agent(), history(), identity())
      }
      assertThrows(IllegalArgumentException::class.java) { store.read(machine, "../../draft") }
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun reusedPaneKeepsDistinctCanonicalConversationsAndMovedPaneUpdatesSameConversation() {
    val root = Files.createTempDirectory("offline-generations").toFile()
    try {
      val store = OfflineConversations(root)
      store.save(machine, agent(), history(), identity('a'))
      val newHistory =
        RecentConversation(
          listOf(ConversationTurn("new", "agent", null, "New conversation", emptyList())),
          0,
          0,
        )
      store.save(machine, agent(), newHistory, identity('b'))
      assertEquals(history(), store.read(machine, identity('a'))?.history)
      assertEquals(newHistory, store.read(machine, identity('b'))?.history)
      assertEquals(2, store.list(setOf(machine)).size)
      store.save(machine, agent("different-pane"), newHistory, identity('a'))
      assertEquals("different-pane", store.read(machine, identity('a'))?.agent?.sessionId)
      assertEquals(2, store.list(setOf(machine)).size)
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
      store.save(machine, agent("first"), history(), identity('a'))
      store.save(machine, agent("second"), history(), identity('b'))
      store.save(machine, agent("third"), history(), identity('c'))
      assertEquals(2, store.list(setOf(machine)).size)
      assertNotNull(store.read(machine, identity('c')))
      temporaryCacheBytes(cache, clear = true)
      assertEquals("preserve draft", draft.readText())
      assertNotNull(store.read(machine, identity('c')))
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
        OfflineConversations(linked).save(machine, agent(), history(), identity())
      }
      val folder = File(root, "offline")
      val store = OfflineConversations(folder)
      store.save(machine, agent(), history(), identity())
      val file = folder.listFiles()!!.single()
      file.writeText(file.readText().replace("\"schema\":2", "\"schema\":1"))
      assertThrows(IllegalArgumentException::class.java) { store.read(machine, identity()) }
      file.writeText("not JSON")
      assertThrows(Exception::class.java) { store.read(machine, identity()) }
      assertTrue(outside.listFiles()!!.isEmpty())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun invalidOrWrongProviderIdentityCannotBeSaved() {
    val root = Files.createTempDirectory("offline-provider").toFile()
    try {
      val store = OfflineConversations(root)
      for (id in listOf("w1:p1", "", "pi:short", "agy:${"a".repeat(64)}")) {
        assertThrows(IllegalArgumentException::class.java) {
          store.save(machine, agent(), history(), id)
        }
      }
      assertEquals(0, store.bytes())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun oversizeOrUnboundedWritePreservesPreviousSnapshotAndLeavesNoPartialFiles() {
    val root = Files.createTempDirectory("offline-atomic").toFile()
    try {
      val store = OfflineConversations(root, maxEntryBytes = 2048, maxBytes = 4096)
      val original = store.save(machine, agent(), history(), identity())
      val tooLarge =
        RecentConversation(
          listOf(ConversationTurn("large", "agent", null, "x".repeat(5000), emptyList())),
          0,
          0,
        )
      assertThrows(IllegalArgumentException::class.java) {
        store.save(machine, agent(), tooLarge, identity())
      }
      val tooMany =
        RecentConversation(
          (0..200).map { ConversationTurn("t$it", "agent", null, null, emptyList()) },
          0,
          0,
        )
      assertThrows(IllegalArgumentException::class.java) {
        OfflineConversations(root).save(machine, agent(), tooMany, identity())
      }
      assertEquals(original, store.read(machine, identity()))
      assertFalse(root.listFiles()!!.any { it.name.endsWith(".tmp") })
    } finally {
      root.deleteRecursively()
    }
  }
}
