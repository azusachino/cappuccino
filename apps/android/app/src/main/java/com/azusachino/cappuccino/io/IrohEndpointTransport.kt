package com.azusachino.cappuccino.io

import android.content.Context
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.IrohException
import computer.iroh.RecvStream
import computer.iroh.SecretKey
import computer.iroh.SendStream
import computer.iroh.presetN0
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val KEY_FILE_NAME = "iroh-secret.key"
private const val KEY_BYTES = 32
private const val IDLE_CLOSE_MS = 60_000L

/**
 * The process's iroh endpoint on the iroh-android 1.2.0 API. The endpoint starts on the first dial
 * and closes after [IDLE_CLOSE_MS] with no open streams, or on [stop]. Relay-hosted endpoints are
 * built with the n0 preset. [IrohAndroid.installAndroidContext] must run before the first dial; the
 * activity does that at startup.
 *
 * `watchNetworkChange` is deliberately not used: it spins on the device instead of firing (see the
 * design doc's spike results). Network-change handling belongs to the app.
 */
class IrohEndpointTransport private constructor(private val keyFile: File) : IrohTransport {
  private val mutex = Mutex()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var endpoint: Endpoint? = null
  private val connections = ConcurrentHashMap<String, Connection>()
  private var openStreams = 0
  private var idleJob: Job? = null

  companion object {
    @Volatile private var instance: IrohEndpointTransport? = null

    /** The one transport for this process; every caller shares its endpoint. */
    fun shared(context: Context): IrohEndpointTransport =
      instance
        ?: synchronized(this) {
          instance
            ?: IrohEndpointTransport(
                File(context.applicationContext.noBackupFilesDir, KEY_FILE_NAME)
              )
              .also { instance = it }
        }
  }

  override suspend fun start(secretKey: ByteArray) {
    mutex.withLock { startLocked(secretKey) }
  }

  override suspend fun stop() {
    mutex.withLock {
      idleJob?.cancel()
      idleJob = null
      closeLocked()
    }
  }

  override suspend fun dropConnections() {
    mutex.withLock {
      connections.values.forEach { runCatching { it.close(0L, ByteArray(0)) } }
      connections.clear()
    }
  }

  override suspend fun openStream(addr: IrohEndpointAddr, alpn: String): IrohBiStream {
    val bi =
      try {
        openBi(addr, alpn)
      } catch (error: IrohException) {
        // A dead cached connection fails on open; drop it and dial once more.
        mutex.withLock {
          connections.remove(key(addr, alpn))?.let { runCatching { it.close(0L, ByteArray(0)) } }
        }
        openBi(addr, alpn)
      }
    mutex.withLock {
      openStreams += 1
      idleJob?.cancel()
      idleJob = null
    }
    return AndroidBiStream(bi) { onStreamReleased() }
  }

  override fun pathKind(addr: IrohEndpointAddr): IrohPathKind {
    val connection = connections[key(addr, IROH_BRIDGE_ALPN)] ?: return IrohPathKind.NONE
    val selected =
      runCatching { connection.paths().firstOrNull { it.isSelected } }.getOrNull()
        ?: return IrohPathKind.NONE
    return when {
      selected.isRelay -> IrohPathKind.RELAYED
      selected.isIp -> IrohPathKind.DIRECT
      else -> IrohPathKind.NONE
    }
  }

  private suspend fun openBi(addr: IrohEndpointAddr, alpn: String): BiStream = mutex.withLock {
    val connection = connectionLocked(addr, alpn)
    connection.openBi()
  }

  private suspend fun connectionLocked(addr: IrohEndpointAddr, alpn: String): Connection {
    val key = key(addr, alpn)
    connections[key]
      ?.takeIf { it.closeReason() == null }
      ?.let {
        return it
      }
    connections.remove(key)
    val ep = endpointLocked()
    val target = addr.toIroh()
    val connection = target.use { ep.connect(it, alpn.toByteArray(Charsets.UTF_8)) }
    connections[key] = connection
    return connection
  }

  private suspend fun endpointLocked(): Endpoint {
    endpoint?.let {
      return it
    }
    startLocked(loadOrCreateKey())
    return checkNotNull(endpoint)
  }

  private suspend fun startLocked(secretKey: ByteArray) {
    if (endpoint != null) return
    check(secretKey.size == KEY_BYTES) { "iroh secret key must be $KEY_BYTES bytes" }
    endpoint =
      Endpoint.bind(
        EndpointOptions(
          preset = presetN0(),
          bindAddr = null,
          secretKey = secretKey,
          alpns = emptyList(),
        )
      )
  }

  /** Created once with owner-only permissions; the endpoint identity depends on it. */
  private fun loadOrCreateKey(): ByteArray {
    if (!keyFile.exists()) {
      keyFile.writeBytes(SecretKey.generate().toBytes())
      keyFile.setReadable(false, false)
      keyFile.setReadable(true, true)
      keyFile.setWritable(false, false)
      keyFile.setWritable(true, true)
    }
    val bytes = keyFile.readBytes()
    check(bytes.size == KEY_BYTES) { "iroh secret key file is not $KEY_BYTES bytes" }
    return bytes
  }

  private fun onStreamReleased() {
    scope.launch {
      mutex.withLock {
        openStreams -= 1
        if (openStreams == 0) {
          idleJob?.cancel()
          idleJob = scope.launch {
            delay(IDLE_CLOSE_MS)
            mutex.withLock { if (openStreams == 0) closeLocked() }
          }
        }
      }
    }
  }

  private suspend fun closeLocked() {
    connections.values.forEach { runCatching { it.close(0L, ByteArray(0)) } }
    connections.clear()
    endpoint?.let { ep ->
      runCatching { ep.shutdown() }
      ep.close()
    }
    endpoint = null
  }

  private fun key(addr: IrohEndpointAddr, alpn: String) = "${addr.endpointId}|$alpn"
}

private fun IrohEndpointAddr.toIroh(): EndpointAddr =
  EndpointAddr(EndpointId.fromString(endpointId), relayUrl, directAddrs)

/** Wraps one iroh bi-stream. Handles are cached once and released together. */
private class AndroidBiStream(private val bi: BiStream, private val onRelease: () -> Unit) :
  IrohBiStream {
  private val send: SendStream = bi.send()
  private val recv: RecvStream = bi.recv()
  private val released = AtomicBoolean(false)

  override suspend fun read(max: Int): ByteArray = recv.read(max.toUInt())

  override suspend fun write(bytes: ByteArray) = send.writeAll(bytes)

  override suspend fun finish() = send.finish()

  override suspend fun stopReading() = recv.stop(0UL)

  override fun release() {
    if (!released.compareAndSet(false, true)) return
    runCatching { send.close() }
    runCatching { recv.close() }
    runCatching { bi.close() }
    onRelease()
  }
}
