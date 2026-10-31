package com.azusachino.cappuccino.io

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList

enum class ConnectionLogLevel {
  INFO,
  WARN,
  ERROR,
}

data class ConnectionLogEntry(
  val id: Long,
  val timestamp: Long,
  val formattedTime: String,
  val level: ConnectionLogLevel,
  val category: String,
  val message: String,
)

object ConnectionLogger {
  private const val MAX_LOGS = 300
  private var nextId = 1L
  private val logs = ConcurrentLinkedDeque<ConnectionLogEntry>()
  private val listeners = CopyOnWriteArrayList<() -> Unit>()
  private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

  fun log(category: String, message: String, level: ConnectionLogLevel = ConnectionLogLevel.INFO) {
    val now = System.currentTimeMillis()
    val formatted = synchronized(timeFormat) { timeFormat.format(Date(now)) }
    val entry =
      ConnectionLogEntry(
        id = synchronized(this) { nextId++ },
        timestamp = now,
        formattedTime = formatted,
        level = level,
        category = category,
        message = message,
      )
    logs.addLast(entry)
    while (logs.size > MAX_LOGS) {
      logs.pollFirst()
    }
    listeners.forEach { runCatching { it.invoke() } }
  }

  fun getLogs(): List<ConnectionLogEntry> = logs.toList()

  fun clear() {
    logs.clear()
    listeners.forEach { runCatching { it.invoke() } }
  }

  fun subscribe(listener: () -> Unit): () -> Unit {
    listeners.add(listener)
    return { listeners.remove(listener) }
  }

  fun exportText(): String =
    logs.joinToString("\n") { entry ->
      val lvl =
        when (entry.level) {
          ConnectionLogLevel.INFO -> "I"
          ConnectionLogLevel.WARN -> "W"
          ConnectionLogLevel.ERROR -> "E"
        }
      "[${entry.formattedTime}] [$lvl/${entry.category}] ${entry.message}"
    }
}
