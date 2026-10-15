package com.azusachino.cappuccino.io

import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.core.ConversationPart
import com.azusachino.cappuccino.core.ConversationTurn
import com.azusachino.cappuccino.core.MAX_RECENT_MESSAGES
import com.azusachino.cappuccino.core.MAX_RECENT_TOOLS
import com.azusachino.cappuccino.core.RecentConversation
import com.azusachino.cappuccino.core.isCanonicalConversationId
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*

data class SavedConversationInfo(
  val agent: AgentRow,
  val savedAt: Long,
  val messageCount: Int,
  val conversationId: String,
)

internal data class SavedConversation(
  val agent: AgentRow,
  val conversationId: String,
  val history: RecentConversation,
  val savedAt: Long,
)

/** Private app files, separate from temporary cache. Saved content never establishes live state. */
internal class OfflineConversations(
  private val directory: File,
  private val maxEntries: Int = 50,
  private val maxBytes: Long = 64L * 1024 * 1024,
  private val maxEntryBytes: Int = 4 * 1024 * 1024,
  private val maxRetentionMillis: Long = 30L * 24 * 60 * 60 * 1000L,
) {
  init {
    require(
      maxEntries > 0 && maxEntryBytes > 0 && maxBytes >= maxEntryBytes && maxRetentionMillis > 0
    )
  }

  @Synchronized
  fun save(
    machineId: UUID,
    agent: AgentRow,
    history: RecentConversation,
    conversationId: String,
  ): SavedConversation {
    require(agent.machineId == machineId && agent.sessionId.isNotBlank())
    require(
      isCanonicalConversationId(conversationId) && conversationId.startsWith("${agent.agent}:")
    )
    val snapshot =
      SavedConversation(
        agent.copy(status = "unknown", working = false),
        conversationId,
        history,
        System.currentTimeMillis(),
      )
    val bytes = encodeSavedConversation(snapshot).toByteArray(Charsets.UTF_8)
    require(bytes.size <= maxEntryBytes) { "Conversation exceeds offline storage limit" }
    decodeSavedConversation(
      bytes.toString(Charsets.UTF_8)
    ) // Validate the same bounded schema we read.
    root(create = true)
    val destination = File(directory, key(machineId, conversationId))
    val temporary = File.createTempFile(".pending-", ".tmp", directory)
    try {
      FileOutputStream(temporary).use {
        it.write(bytes)
        it.fd.sync()
      }
      Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
      prune(destination)
    } finally {
      temporary.delete()
    }
    return snapshot
  }

  @Synchronized
  fun read(machineId: UUID, conversationId: String): SavedConversation? {
    if (!root()) return null
    val file = File(directory, key(machineId, conversationId))
    if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return null
    require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= maxEntryBytes) {
      "Invalid saved conversation file"
    }
    val snapshot = decodeSavedConversation(file.readText(Charsets.UTF_8))
    require(snapshot.agent.machineId == machineId && snapshot.conversationId == conversationId) {
      "Saved conversation identity mismatch"
    }
    return snapshot
  }

  @Synchronized
  fun delete(machineId: UUID, conversationId: String): Boolean {
    if (!root()) return false
    val file = File(directory, key(machineId, conversationId))
    if (
      Files.exists(file.toPath(), NOFOLLOW_LINKS) &&
        Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)
    ) {
      return file.delete()
    }
    return false
  }

  @Synchronized
  fun clearAll(machineIds: Set<UUID>? = null): Int {
    if (!root()) return 0
    var count = 0
    for (file in files()) {
      if (machineIds == null) {
        if (file.delete()) count++
      } else {
        try {
          val snapshot = decodeSavedConversation(file.readText(Charsets.UTF_8))
          if (snapshot.agent.machineId in machineIds) {
            if (file.delete()) count++
          }
        } catch (_: Exception) {
          if (file.delete()) count++
        }
      }
    }
    return count
  }

  @Synchronized
  fun list(machineIds: Set<UUID>): List<SavedConversation> {
    prune(null)
    return files()
      .mapNotNull { file ->
        try {
          require(file.length() <= maxEntryBytes) { "Invalid saved conversation size" }
          decodeSavedConversation(file.readText(Charsets.UTF_8)).also {
            require(file.name == key(it.agent.machineId, it.conversationId)) {
              "Saved conversation identity mismatch"
            }
          }
        } catch (_: Exception) {
          null
        }
      }
      .filter { it.agent.machineId in machineIds }
      .sortedByDescending { it.savedAt }
  }

  @Synchronized fun bytes(): Long = files().sumOf { it.length() }

  private fun root(create: Boolean = false): Boolean {
    val path = directory.toPath()
    require(!Files.isSymbolicLink(path)) { "Offline storage must not be linked" }
    if (!Files.exists(path, NOFOLLOW_LINKS)) {
      if (!create) return false
      Files.createDirectories(path)
    }
    require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Invalid offline storage directory" }
    if (create) {
      requireNotNull(directory.listFiles())
        .filter { it.name.startsWith(".pending-") && it.name.endsWith(".tmp") }
        .forEach {
          require(Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) && it.delete()) {
            "Could not clean unfinished offline write"
          }
        }
    }
    return true
  }

  private fun files(): List<File> {
    if (!root()) return emptyList()
    val candidates = requireNotNull(directory.listFiles()) { "Could not read offline storage" }
    return candidates
      .filter { it.name.endsWith(".json") }
      .onEach {
        require(Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)) {
          "Invalid offline storage entry"
        }
      }
  }

  private fun prune(protected: File?) {
    val now = System.currentTimeMillis()
    val allFiles = files()
    for (file in allFiles) {
      if (file != protected && (now - file.lastModified() > maxRetentionMillis)) {
        file.delete()
      }
    }
    val candidates =
      files()
        .sortedWith(
          compareBy<File> { it == protected }.thenBy { it.lastModified() }.thenBy { it.name }
        )
        .toMutableList()
    var total = candidates.sumOf { it.length() }
    while (candidates.size > maxEntries || total > maxBytes) {
      val oldest = candidates.removeAt(0)
      if (oldest != protected) {
        check(oldest.delete()) { "Could not enforce offline storage limit" }
        total -= oldest.length()
      } else {
        break
      }
    }
  }

  private fun key(machineId: UUID, conversationId: String): String {
    require(isCanonicalConversationId(conversationId))
    val bytes = "$machineId\n$conversationId".toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
      "%02x".format(it)
    } + ".json"
  }
}

internal fun encodeSavedConversation(snapshot: SavedConversation): String = buildJsonObject {
  put("schema", 2)
  put("conversation_id", snapshot.conversationId)
  put("machine", snapshot.agent.machineId.toString())
  put("locator", snapshot.agent.sessionId)
  put("label", snapshot.agent.label)
  put("agent", snapshot.agent.agent)
  put("pane", snapshot.agent.paneId)
  put("branch", snapshot.agent.branch)
  put("cwd", snapshot.agent.cwd)
  put("saved_at", snapshot.savedAt)
  put("omitted_messages", snapshot.history.omittedMessages)
  put("omitted_tools", snapshot.history.omittedTools)
  putJsonArray("turns") {
    for (turn in snapshot.history.turns) add(
      buildJsonObject {
        put("id", turn.id)
        put("role", turn.role)
        put("timestamp", turn.timestamp)
        put("text", turn.text)
        putJsonArray("parts") {
          for (part in turn.parts) add(
            buildJsonObject {
              when (part) {
                is ConversationPart.Text -> {
                  put("type", "text")
                  put("text", part.text)
                }
                is ConversationPart.Thinking -> {
                  put("type", "thinking")
                  put("text", part.text)
                }
                is ConversationPart.ResponseStatus -> {
                  put("type", "status")
                  put("status", part.status)
                  put("detail", part.detail)
                }
                is ConversationPart.ToolCall -> {
                  put("type", "tool")
                  put("name", part.name)
                  put("input", part.input)
                  put("output", part.output)
                  put("error", part.isError?.let { JsonPrimitive(it) } ?: JsonNull)
                  put("started", part.startedAt)
                  put("finished", part.finishedAt)
                  put("index", part.originalPartIndex?.let { JsonPrimitive(it) } ?: JsonNull)
                }
              }
            }
          )
        }
      }
    )
  }
}
  .toString()

internal fun decodeSavedConversation(source: String): SavedConversation {
  val root = Json.parseToJsonElement(source).jsonObject
  fun JsonObject.string(key: String): String =
    requireNotNull(this[key] as? JsonPrimitive).also { require(it.isString) }.content
  fun JsonObject.optional(key: String): String? =
    if (getValue(key) == JsonNull) null else string(key)
  fun JsonObject.number(key: String): Long =
    requireNotNull(this[key] as? JsonPrimitive)
      .also { require(!it.isString) }
      .long
      .also { require(it >= 0) }
  fun JsonObject.integer(key: String): Int =
    number(key).also { require(it <= Int.MAX_VALUE) }.toInt()
  require(root.integer("schema") == 2) { "Unsupported offline snapshot version" }
  val conversationId =
    root.string("conversation_id").also { require(isCanonicalConversationId(it)) }
  val machine = UUID.fromString(root.string("machine"))
  val locator = root.string("locator").also { require(it.isNotBlank()) }
  val agent =
    AgentRow(
      machine,
      locator,
      root.optional("pane"),
      root.string("label"),
      root.string("agent"),
      "unknown",
      false,
      root.optional("branch"),
      root.optional("cwd"),
    )
  val turns =
    root.getValue("turns").jsonArray.map { node ->
      val turn = node.jsonObject
      val parts =
        turn.getValue("parts").jsonArray.map { element ->
          val part = element.jsonObject
          when (part.string("type")) {
            "text" -> ConversationPart.Text(part.string("text"))
            "thinking" -> ConversationPart.Thinking(part.string("text"))
            "status" ->
              ConversationPart.ResponseStatus(part.string("status"), part.optional("detail"))
            "tool" -> {
              val error =
                part.getValue("error").let {
                  if (it == JsonNull) null
                  else
                    requireNotNull(it as? JsonPrimitive)
                      .also { value -> require(!value.isString) }
                      .boolean
                }
              val index = if (part.getValue("index") == JsonNull) null else part.integer("index")
              ConversationPart.ToolCall(
                part.string("name"),
                part.string("input"),
                part.optional("output"),
                error,
                part.optional("started"),
                part.optional("finished"),
                index,
              )
            }
            else -> throw IllegalArgumentException("Unknown saved conversation part")
          }
        }
      ConversationTurn(
        turn.string("id"),
        turn.string("role"),
        turn.optional("timestamp"),
        turn.optional("text"),
        parts,
      )
    }
  require(
    turns.size <= MAX_RECENT_MESSAGES &&
      turns.sumOf { it.parts.count { part -> part is ConversationPart.ToolCall } } <=
        MAX_RECENT_TOOLS
  ) {
    "Saved history exceeds its window"
  }
  require(conversationId.startsWith("${agent.agent}:")) { "Saved provider identity mismatch" }
  return SavedConversation(
    agent,
    conversationId,
    RecentConversation(turns, root.integer("omitted_messages"), root.integer("omitted_tools")),
    root.number("saved_at"),
  )
}
