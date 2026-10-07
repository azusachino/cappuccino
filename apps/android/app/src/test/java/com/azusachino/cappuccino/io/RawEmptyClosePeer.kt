package com.azusachino.cappuccino.io

import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

/** One-shot bounded raw WebSocket peer used only to send an empty-status Close frame. */
internal class RawEmptyClosePeer : AutoCloseable {
  private val listener = ServerSocket(0)

  // Longer than the close-join/test budget so a no-client close proves the accept was
  // interrupted by close(), not completed by this timeout.
  internal val acceptTimeoutMillis = 30_000L
  private val acknowledged = CountDownLatch(1)
  private val failure = AtomicReference<Throwable?>()
  private val lock = java.util.concurrent.locks.ReentrantLock()
  private var accepted: java.net.Socket? = null
  private var closing = false
  private val intentionalClose = AtomicBoolean()
  private val acceptedReady = CountDownLatch(1)
  private val headerRead = CountDownLatch(1)
  private val upgradeRead = CountDownLatch(1)
  private val closeRead = CountDownLatch(1)
  private val worker = Thread(::serve, "raw-empty-close-peer").apply { start() }
  val port: Int = listener.localPort

  private fun serve() {
    try {
      listener.soTimeout = acceptTimeoutMillis.toInt()
      val socket = listener.accept()
      val closeImmediately = lock.withLock {
        acceptedReady.countDown()
        if (closing) true
        else {
          accepted = socket
          false
        }
      }
      if (closeImmediately) {
        // Racing publication after close began: close the owned socket immediately and
        // return; never continue protocol parsing on it.
        socket.close()
        return
      }
      socket.use {
        socket.soTimeout = 30_000
        val input = socket.getInputStream()
        val request = ByteArrayOutputStream()
        var matched = 0
        val marker = byteArrayOf(13, 10, 13, 10)
        headerRead.countDown()
        while (request.size() < 8_192 && matched < marker.size) {
          val byte = input.read()
          if (byte < 0) {
            if (intentionalClose.get()) return
            error("EOF during WebSocket upgrade")
          }
          request.write(byte)
          matched = if (byte == marker[matched].toInt()) matched + 1 else if (byte == 13) 1 else 0
        }
        if (matched != marker.size) error("WebSocket request headers exceeded limit")
        upgradeRead.countDown()
        val headers = request.toString(Charsets.US_ASCII.name())
        val key =
          headers
            .lineSequence()
            .first { it.startsWith("Sec-WebSocket-Key:", true) }
            .substringAfter(':')
            .trim()
        val accept =
          Base64.getEncoder()
            .encodeToString(
              MessageDigest.getInstance("SHA-1")
                .digest(
                  (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)
                )
            )
        socket
          .getOutputStream()
          .write(
            ("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n")
              .toByteArray(Charsets.US_ASCII)
          )
        socket.getOutputStream().write(byteArrayOf(0x88.toByte(), 0x00))
        socket.getOutputStream().flush()
        closeRead.countDown()
        val first = input.read()
        val second = input.read()
        check(first >= 0 && second >= 0) { "client did not acknowledge Close" }
        check(first and 0x0f == 0x08) { "expected a client Close frame" }
        val length = second and 0x7f
        check(second and 0x80 != 0 && length <= 125) { "invalid bounded client close frame" }
        val mask = input.readNBytes(4)
        check(mask.size == 4) { "truncated close mask" }
        val payload = input.readNBytes(length)
        check(payload.size == length) { "truncated close payload" }
        val decoded =
          ByteArray(length) { index ->
            (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
          }
        check(decoded.size >= 2) { "client Close omitted status" }
        check(((decoded[0].toInt() and 0xff) shl 8) or (decoded[1].toInt() and 0xff) == 1000) {
          "expected outgoing close status 1000, got ${((decoded[0].toInt() and 0xff) shl 8) or (decoded[1].toInt() and 0xff)}"
        }
        acknowledged.countDown()
      }
    } catch (error: Throwable) {
      // Only the expected socket-close interruption of an in-progress accept/read is
      // silenced once an intentional close is underway. Genuine protocol, assertion and
      // unexpected failures stay recorded and observable even after/during close.
      val expectedInterruption =
        intentionalClose.get() && (error is SocketException || error is SocketTimeoutException)
      if (!expectedInterruption) failure.set(error)
      acknowledged.countDown()
    }
  }

  fun isWorkerAlive() = worker.isAlive

  fun isAcceptedSocketClosed() = accepted?.isClosed == true

  fun awaitAccepted() = acceptedReady.await(5, TimeUnit.SECONDS)

  fun awaitHeaderRead() {
    check(headerRead.await(5, TimeUnit.SECONDS)) {
      "server did not reach upgrade-header read phase"
    }
    failure.get()?.let { throw AssertionError("raw close peer failed", it) }
  }

  fun awaitUpgradeRead() {
    check(upgradeRead.await(5, TimeUnit.SECONDS)) {
      "server did not reach upgrade-header read phase"
    }
    failure.get()?.let { throw AssertionError("raw close peer failed", it) }
  }

  fun awaitCloseRead() {
    check(closeRead.await(5, TimeUnit.SECONDS)) { "server did not reach close-ack read phase" }
    failure.get()?.let { throw AssertionError("raw close peer failed", it) }
  }

  fun awaitAcknowledgement() {
    check(acknowledged.await(5, TimeUnit.SECONDS)) { "server did not finish close acknowledgement" }
    failure.get()?.let { throw AssertionError("raw close peer failed", it) }
  }

  override fun close() {
    intentionalClose.set(true)
    // Never wait for a client, accept, or readiness here. Snapshot the owned accepted
    // socket under lock while marking closing; the racing accept/publication path closes
    // its socket and returns on its own.
    val socketToClose = lock.withLock {
      closing = true
      accepted
    }
    // Attempt every cleanup step even when one fails; collect and surface each failure.
    val cleanupErrors = mutableListOf<Throwable>()
    try {
      listener.close()
    } catch (error: Throwable) {
      cleanupErrors += error
    }
    try {
      socketToClose?.close()
    } catch (error: Throwable) {
      cleanupErrors += error
    }
    worker.join(5_000)
    val joinError =
      if (worker.isAlive) IllegalStateException("raw close peer thread did not terminate") else null
    val errors = buildList {
      failure.get()?.let(::add)
      joinError?.let(::add)
      addAll(cleanupErrors)
    }
    if (errors.isNotEmpty()) {
      val primary = errors.first()
      errors.drop(1).forEach(primary::addSuppressed)
      throw AssertionError("raw close peer failed", primary)
    }
  }
}
