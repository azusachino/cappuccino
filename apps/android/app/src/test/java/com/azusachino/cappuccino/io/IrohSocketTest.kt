package com.azusachino.cappuccino.io

import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohSocketTest {
  private val addr = IrohEndpointAddr("bridge-id", null, emptyList())
  private val remote =
    InetSocketAddress(InetAddress.getByAddress("iroh.invalid", byteArrayOf(127, 0, 0, 1)), 80)

  private fun connected(stream: FakeBiStream = FakeBiStream()): Pair<IrohSocket, FakeBiStream> {
    val socket = IrohSocket(addr) { stream }
    socket.connect(remote, 1_000)
    return socket to stream
  }

  @Test
  fun connectOpensTheBridgeStreamAndCarriesBytesBothWays() {
    val (socket, stream) = connected()
    assertTrue(socket.isConnected)
    assertFalse(socket.isClosed)
    assertEquals(remote, socket.remoteSocketAddress)

    socket.getOutputStream().write("GET /".toByteArray())
    assertEquals("GET /", stream.written.toString(Charsets.UTF_8))

    stream.inbound.trySend("HTTP/1.1 200".toByteArray())
    val buffer = ByteArray(64)
    val count = socket.getInputStream().read(buffer)
    assertEquals("HTTP/1.1 200", String(buffer, 0, count, Charsets.UTF_8))
  }

  @Test
  fun largeChunksAreDeliveredAcrossSmallReads() {
    val (socket, stream) = connected()
    stream.inbound.trySend("abcdef".toByteArray())
    val input = socket.getInputStream()
    assertEquals("ab", String(ByteArray(2).also { input.read(it, 0, 2) }, Charsets.UTF_8))
    assertEquals("cdef", String(ByteArray(4).also { input.read(it, 0, 4) }, Charsets.UTF_8))
  }

  @Test
  fun peerFinishedReadsEndOfStream() {
    val (socket, stream) = connected()
    stream.inbound.close()
    assertEquals(-1, socket.getInputStream().read())
  }

  @Test
  fun shutdownOutputFinishesOnceAndRejectsLaterWrites() {
    val (socket, stream) = connected()
    socket.shutdownOutput()
    socket.shutdownOutput()
    assertTrue(socket.isOutputShutdown)
    assertEquals(1, stream.finishes)
    assertThrows(SocketException::class.java) { socket.getOutputStream().write(1) }
  }

  @Test
  fun closeFinishesStopsReleasesAndIsIdempotent() {
    val (socket, stream) = connected()
    socket.close()
    socket.close()
    assertTrue(socket.isClosed)
    assertEquals(1, stream.finishes)
    assertEquals(1, stream.stops)
    assertTrue(stream.released)
    assertThrows(SocketException::class.java) { socket.getOutputStream().write(1) }
  }

  @Test
  fun closeAfterShutdownOutputDoesNotFinishTwice() {
    val (socket, stream) = connected()
    socket.shutdownOutput()
    socket.close()
    assertEquals(1, stream.finishes)
  }

  @Test
  fun readTimeoutKeepsThePendingReadSoNoBytesAreLost() {
    val (socket, stream) = connected()
    socket.soTimeout = 50
    assertThrows(SocketTimeoutException::class.java) { socket.getInputStream().read() }

    stream.inbound.trySend("late".toByteArray())
    socket.soTimeout = 1_000
    val buffer = ByteArray(8)
    val count = socket.getInputStream().read(buffer)
    assertEquals("late", String(buffer, 0, count, Charsets.UTF_8))
  }

  @Test
  fun connectFailureSurfacesAsConnectException() {
    val socket = IrohSocket(addr) { error("no route to bridge") }
    assertThrows(ConnectException::class.java) { socket.connect(remote, 1_000) }
    assertFalse(socket.isConnected)
  }

  @Test
  fun connectTimeoutSurfacesAsSocketTimeout() {
    val socket =
      IrohSocket(addr) {
        delay(10_000)
        FakeBiStream()
      }
    assertThrows(SocketTimeoutException::class.java) { socket.connect(remote, 50) }
  }

  @Test
  fun writeBeforeConnectFails() {
    val socket = IrohSocket(addr) { FakeBiStream() }
    assertThrows(SocketException::class.java) { socket.getOutputStream().write(1) }
  }

  @Test
  fun placeholderDnsResolvesToLoopbackWithoutLookup() {
    val addresses = IROH_DNS.lookup("iroh.invalid")
    assertEquals(listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))), addresses)
    assertTrue(addresses.first().isLoopbackAddress)
  }
}
