package com.azusachino.cappuccino.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectionLoggerTest {
  @Before
  fun setUp() {
    ConnectionLogger.clear()
  }

  @Test
  fun logsAreRecordedAndExported() {
    ConnectionLogger.log("NETWORK", "Network online")
    ConnectionLogger.log("ROUTER", "Probing iroh", ConnectionLogLevel.INFO)
    ConnectionLogger.log("ROUTER", "Iroh failed", ConnectionLogLevel.WARN)
    ConnectionLogger.log("STREAM", "Stream failed", ConnectionLogLevel.ERROR)

    val logs = ConnectionLogger.getLogs()
    assertEquals(4, logs.size)
    assertEquals("NETWORK", logs[0].category)
    assertEquals(ConnectionLogLevel.INFO, logs[0].level)
    assertEquals("Network online", logs[0].message)

    assertEquals(ConnectionLogLevel.WARN, logs[2].level)
    assertEquals(ConnectionLogLevel.ERROR, logs[3].level)

    val exported = ConnectionLogger.exportText()
    assertTrue(exported.contains("[I/NETWORK] Network online"))
    assertTrue(exported.contains("[W/ROUTER] Iroh failed"))
    assertTrue(exported.contains("[E/STREAM] Stream failed"))
  }

  @Test
  fun bufferCapsAtCapacity() {
    repeat(350) { i -> ConnectionLogger.log("TEST", "Message $i") }
    val logs = ConnectionLogger.getLogs()
    assertEquals(300, logs.size)
    assertEquals("Message 50", logs.first().message)
    assertEquals("Message 349", logs.last().message)
  }

  @Test
  fun listenersAreNotified() {
    var notified = 0
    val unsubscribe = ConnectionLogger.subscribe { notified++ }
    ConnectionLogger.log("TEST", "Hello")
    ConnectionLogger.log("TEST", "World")
    assertEquals(2, notified)
    unsubscribe()
    ConnectionLogger.log("TEST", "After unsubscribe")
    assertEquals(2, notified)
  }
}
