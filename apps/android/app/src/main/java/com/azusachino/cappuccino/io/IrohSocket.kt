package com.azusachino.cappuccino.io

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient

private const val READ_CHUNK = 16 * 1024
private const val CLOSE_TIMEOUT_MS = 2_000L

/** Address reported for every iroh socket; no DNS lookup ever runs for the placeholder host. */
private val IROH_LOOPBACK: InetAddress =
  InetAddress.getByAddress("iroh.invalid", byteArrayOf(127, 0, 0, 1))

/** Resolves the placeholder host to a fixed loopback address so OkHttp never queries DNS. */
val IROH_DNS: Dns = Dns { listOf(IROH_LOOPBACK) }

/**
 * A `java.net.Socket` whose bytes travel over one iroh bi-stream. OkHttp creates it unconnected,
 * calls [connect] with the placeholder address and then reads and writes like any socket.
 *
 * Half-close: [shutdownOutput] finishes the send side. [close] finishes the send side if still
 * open, stops reading, and releases the stream. A read that times out keeps its pending iroh read
 * alive, so the next read continues it instead of losing bytes; OkHttp relies on this when it
 * probes pooled connections with a 1 ms timeout.
 */
class IrohSocket(
  private val addr: IrohEndpointAddr,
  private val open: suspend (IrohEndpointAddr) -> IrohBiStream,
) : Socket() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var stream: IrohBiStream? = null
  private var remote: InetSocketAddress? = null
  private var pendingRead: Deferred<ByteArray>? = null
  private var carry = ByteArray(0)
  private var soTimeoutMs = 0
  @Volatile private var closed = false
  private var outputShut = false
  private var inputShut = false

  override fun connect(endpoint: SocketAddress?, timeout: Int) {
    if (closed) throw SocketException("Socket is closed")
    if (stream != null) throw SocketException("Socket is already connected")
    val opened =
      try {
        runBlocking {
          if (timeout > 0) withTimeout(timeout.toLong()) { open(addr) } else open(addr)
        }
      } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
        throw SocketTimeoutException("iroh connect timed out")
      } catch (error: Exception) {
        throw ConnectException(error.message ?: "iroh connect failed")
      }
    stream = opened
    remote = endpoint as? InetSocketAddress
  }

  override fun isConnected(): Boolean = stream != null

  override fun isClosed(): Boolean = closed

  override fun getInetAddress(): InetAddress? = remote?.address

  override fun getPort(): Int = remote?.port ?: 0

  override fun getRemoteSocketAddress(): SocketAddress? = remote

  override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()

  override fun getLocalPort(): Int = 0

  override fun getLocalSocketAddress(): SocketAddress? = null

  override fun setSoTimeout(timeout: Int) {
    require(timeout >= 0) { "timeout can't be negative" }
    soTimeoutMs = timeout
  }

  override fun getSoTimeout(): Int = soTimeoutMs

  // Nothing below the stream is a TCP socket, so these options have no effect; they only accept
  // OkHttp's calls.
  override fun setTcpNoDelay(on: Boolean) = Unit

  override fun getTcpNoDelay(): Boolean = true

  override fun setKeepAlive(on: Boolean) = Unit

  override fun getKeepAlive(): Boolean = false

  override fun getInputStream(): InputStream = input

  override fun getOutputStream(): OutputStream = output

  override fun shutdownOutput() {
    checkConnected()
    if (outputShut) return
    outputShut = true
    blocking { stream?.finish() }
  }

  override fun isOutputShutdown(): Boolean = outputShut

  override fun shutdownInput() {
    checkConnected()
    inputShut = true
  }

  override fun isInputShutdown(): Boolean = inputShut

  override fun close() {
    if (closed) return
    closed = true
    scope.cancel()
    val current = stream
    if (current != null) {
      runCatching {
        blocking {
          withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
            if (!outputShut) {
              outputShut = true
              current.finish()
            }
            current.stopReading()
          }
        }
      }
      current.release()
    }
    super.close()
  }

  private val input =
    object : InputStream() {
      override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
      }

      override fun read(b: ByteArray, off: Int, len: Int): Int = readInto(b, off, len)
    }

  private val output =
    object : OutputStream() {
      override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

      override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw SocketException("Socket is closed")
        checkConnected()
        if (outputShut) throw SocketException("Socket output is shutdown")
        if (len == 0) return
        val bytes = b.copyOfRange(off, off + len)
        blocking { stream?.write(bytes) }
      }
    }

  private fun readInto(b: ByteArray, off: Int, len: Int): Int {
    if (closed) throw SocketException("Socket is closed")
    checkConnected()
    if (len == 0) return 0
    if (inputShut) return -1
    if (carry.isEmpty()) {
      val pending =
        pendingRead
          ?: scope.async { checkNotNull(stream).read(READ_CHUNK) }.also { pendingRead = it }
      val chunk =
        try {
          runBlocking {
            if (soTimeoutMs > 0) withTimeout(soTimeoutMs.toLong()) { pending.await() }
            else pending.await()
          }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
          // Keep the pending read: the next call continues it, so no bytes are dropped.
          throw SocketTimeoutException("iroh read timed out")
        } catch (error: CancellationException) {
          throw SocketException("Socket is closed")
        } catch (error: Exception) {
          pendingRead = null
          throw IOException(error.message ?: "iroh read failed", error)
        }
      pendingRead = null
      if (chunk.isEmpty()) return -1
      carry = chunk
    }
    val count = minOf(len, carry.size)
    carry.copyInto(b, off, 0, count)
    carry = carry.copyOfRange(count, carry.size)
    return count
  }

  private fun checkConnected() {
    if (stream == null) throw SocketException("Socket is not connected")
  }

  /**
   * Runs a suspend stream call from a blocking java.io method, turning failures into IOException.
   */
  private fun <T> blocking(block: suspend () -> T): T =
    try {
      runBlocking { block() }
    } catch (error: IOException) {
      throw error
    } catch (error: Exception) {
      throw IOException(error.message ?: "iroh stream failed", error)
    }
}

/**
 * Builds sockets for [addr]. OkHttp only uses the no-argument overload, which returns an
 * unconnected socket it connects itself; the other overloads would need a resolved host, so they
 * refuse.
 */
class IrohSocketFactory(
  private val addr: IrohEndpointAddr,
  private val open: suspend (IrohEndpointAddr) -> IrohBiStream,
) : SocketFactory() {
  override fun createSocket(): Socket = IrohSocket(addr, open)

  override fun createSocket(host: String?, port: Int): Socket = refuse()

  override fun createSocket(
    host: String?,
    port: Int,
    localHost: InetAddress?,
    localPort: Int,
  ): Socket = refuse()

  override fun createSocket(host: InetAddress?, port: Int): Socket = refuse()

  override fun createSocket(
    host: InetAddress?,
    port: Int,
    localHost: InetAddress?,
    localPort: Int,
  ): Socket = refuse()

  private fun refuse(): Nothing =
    throw IOException("iroh sockets are created unconnected and connected by OkHttp")
}

/**
 * Per-machine OkHttp clients over iroh. Each client keeps the tailnet client's timeouts and
 * cookie/cache policy, swaps in the iroh socket factory and placeholder DNS, and never uses a
 * proxy.
 */
class IrohHttpClients(
  private val transport: IrohTransport,
  private val base: OkHttpClient,
) {
  private val clients = ConcurrentHashMap<IrohEndpointAddr, OkHttpClient>()

  fun clientFor(addr: IrohEndpointAddr): OkHttpClient =
    clients.getOrPut(addr) {
      base
        .newBuilder()
        .socketFactory(IrohSocketFactory(addr) { transport.openStream(it) })
        .dns(IROH_DNS)
        .proxy(Proxy.NO_PROXY)
        .build()
    }
}
