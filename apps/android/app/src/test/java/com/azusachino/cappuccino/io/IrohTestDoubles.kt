package com.azusachino.cappuccino.io

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

/** In-memory bi-stream: tests push inbound chunks and inspect what was written and finished. */
class FakeBiStream : IrohBiStream {
  val inbound = Channel<ByteArray>(Channel.UNLIMITED)
  val written = ByteArrayOutputStream()
  var finishes = 0
  var stops = 0
  var released = false

  override suspend fun read(max: Int): ByteArray =
    inbound.receiveCatching().getOrNull() ?: ByteArray(0)

  override suspend fun write(bytes: ByteArray) {
    written.write(bytes)
  }

  override suspend fun finish() {
    finishes++
  }

  override suspend fun stopReading() {
    stops++
  }

  override fun release() {
    released = true
  }
}

/**
 * Transport whose streams are real TCP connections to [port], standing in for the bridge tunnel.
 */
class TcpTunnelTransport(private val port: Int) : IrohTransport {
  override suspend fun start(secretKey: ByteArray) = Unit

  override suspend fun stop() = Unit

  override suspend fun openStream(addr: IrohEndpointAddr, alpn: String): IrohBiStream =
    withContext(Dispatchers.IO) { TcpBackedStream(java.net.Socket("127.0.0.1", port)) }

  override fun pathKind(addr: IrohEndpointAddr): IrohPathKind = IrohPathKind.NONE
}

private class TcpBackedStream(private val socket: java.net.Socket) : IrohBiStream {
  override suspend fun read(max: Int): ByteArray =
    withContext(Dispatchers.IO) {
      val buffer = ByteArray(max)
      val count = socket.getInputStream().read(buffer)
      if (count <= 0) ByteArray(0) else buffer.copyOf(count)
    }

  override suspend fun write(bytes: ByteArray) =
    withContext(Dispatchers.IO) {
      socket.getOutputStream().write(bytes)
      socket.getOutputStream().flush()
    }

  override suspend fun finish() = withContext(Dispatchers.IO) { socket.shutdownOutput() }

  override suspend fun stopReading() = Unit

  override fun release() = socket.close()
}
