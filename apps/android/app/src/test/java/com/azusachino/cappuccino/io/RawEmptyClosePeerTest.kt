package com.azusachino.cappuccino.io

import java.net.Socket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawEmptyClosePeerTest {
  @Test
  fun closeInterruptsIncompleteUpgradeHeaderReadAndJoinsWorker() {
    val peer = RawEmptyClosePeer()
    val client = Socket("127.0.0.1", peer.port)
    try {
      client.soTimeout = 5_000
      // Keep the client connected with its header request still outstanding so the
      // server remains blocked in its read when close() arrives. shutdownOutput() would
      // end the read by EOF instead of proving forced interruption.
      client.getOutputStream().write("GET / HTTP/1.1\r\n".toByteArray(Charsets.US_ASCII))
      client.getOutputStream().flush()
      peer.awaitHeaderRead()
      peer.close()
    } finally {
      client.close()
    }
    assertTrue("accepted peer socket was not closed", peer.isAcceptedSocketClosed())
    assertFalse("peer worker remained alive after header-read cleanup", peer.isWorkerAlive())
  }

  @Test
  fun closeInterruptsMissingCloseAcknowledgementReadAndJoinsWorker() {
    val peer = RawEmptyClosePeer()
    val client = Socket("127.0.0.1", peer.port)
    try {
      client.soTimeout = 5_000
      client.getOutputStream().write(webSocketUpgrade(peer.port))
      client.getOutputStream().flush()
      assertTrue("server did not accept client", peer.awaitAccepted())
      peer.awaitCloseRead()
      peer.close()
      assertTrue("accepted peer socket was not closed", peer.isAcceptedSocketClosed())
      assertFalse("peer worker remained alive after close-read cleanup", peer.isWorkerAlive())
    } finally {
      client.close()
    }
    assertFalse("peer worker remained alive after close-read cleanup", peer.isWorkerAlive())
  }

  @Test
  fun closeWithoutAnyClientInterruptsAcceptAndJoinsWorker() {
    val peer = RawEmptyClosePeer()
    val startNanos = System.nanoTime()
    peer.close()
    val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000
    // The fixture accept timeout (30s) deliberately exceeds the join bound, so a bounded
    // return proves close() interrupted accept() rather than the accept timeout completing.
    assertTrue(
      "close completed after ${elapsedMillis}ms; accept was not interrupted before its " +
        "${peer.acceptTimeoutMillis}ms timeout",
      elapsedMillis < peer.acceptTimeoutMillis,
    )
    assertFalse("peer worker remained alive after no-client close", peer.isWorkerAlive())
  }

  @Test
  fun malformedCloseAcknowledgementIsReportedEvenThroughIntentionalClose() {
    val peer = RawEmptyClosePeer()
    val client = Socket("127.0.0.1", peer.port)
    try {
      client.soTimeout = 5_000
      client.getOutputStream().write(webSocketUpgrade(peer.port))
      client.getOutputStream().flush()
      assertTrue("server did not accept client", peer.awaitAccepted())
      peer.awaitCloseRead()
      // A genuine wire violation: a data frame where the client Close must be. The
      // fixture must keep reporting this even once an intentional close begins.
      client.getOutputStream().write(byteArrayOf(0x81.toByte(), 0x80.toByte(), 0, 0, 0, 0))
      client.getOutputStream().flush()
      val awaitError = runCatching { peer.awaitAcknowledgement() }.exceptionOrNull()
      assertTrue(
        "malformed client close acknowledgement was not reported",
        awaitError is AssertionError,
      )
    } finally {
      client.close()
    }
    val closeError = runCatching { peer.close() }.exceptionOrNull()
    assertTrue(
      "genuine wire failure vanished after intentional close",
      closeError is AssertionError &&
        closeError.cause?.message?.contains("expected a client Close frame") == true,
    )
    assertFalse("peer worker remained alive after reporting wire failure", peer.isWorkerAlive())
  }

  private fun webSocketUpgrade(port: Int): ByteArray =
    ("GET / HTTP/1.1\r\n" +
        "Host: 127.0.0.1:$port\r\n" +
        "Upgrade: websocket\r\n" +
        "Connection: Upgrade\r\n" +
        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
        "Sec-WebSocket-Version: 13\r\n\r\n")
      .toByteArray(Charsets.US_ASCII)
}
