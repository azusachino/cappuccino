package com.azusachino.cappuccino.io

/** ALPN the bridge serves its iroh endpoint on. */
const val IROH_BRIDGE_ALPN = "cappuccino/1"

/** ALPN the bridge serves the one-time pairing exchange on. */
const val IROH_PAIR_ALPN = "cappuccino-pair/1"

/** Path the transport currently uses to reach a peer; NONE means no live path. */
enum class IrohPathKind {
  DIRECT,
  RELAYED,
  NONE,
}

/** Bridge endpoint from a pairing payload. Kept free of iroh types so pure code can use it. */
data class IrohEndpointAddr(
  val endpointId: String,
  val relayUrl: String?,
  val directAddrs: List<String>,
)

/**
 * One bi-directional iroh stream at the suspend level. The socket adapter wraps it in blocking
 * `java.io` streams. Finish and stop stay separate because iroh distinguishes them on the wire.
 */
interface IrohBiStream {
  /** Next chunk of at most [max] bytes. An empty array means the peer finished its send side. */
  suspend fun read(max: Int): ByteArray

  suspend fun write(bytes: ByteArray)

  /** Ends our send side (QUIC FIN): the peer reads EOF after the bytes already written. */
  suspend fun finish()

  /** Tells the peer we no longer read this stream. */
  suspend fun stopReading()

  /** Releases native handles. Safe to call more than once. */
  fun release()
}

/**
 * One iroh endpoint per app process. Implementations load the native library, so only the Android
 * runtime may construct one; JVM tests use fakes of this interface.
 */
interface IrohTransport {
  /** Starts the process endpoint with the 32-byte ed25519 secret key. Idempotent while running. */
  suspend fun start(secretKey: ByteArray)

  /** Closes the endpoint and every connection on it. */
  suspend fun stop()

  /**
   * Closes every cached connection but keeps the endpoint. A redial after a network change calls
   * this first, because a cached connection can stay pinned to a dead path.
   */
  suspend fun dropConnections()

  /** Dials [addr] on [alpn] (the bridge ALPN by default) and opens a bi-stream to it. */
  suspend fun openStream(addr: IrohEndpointAddr, alpn: String = IROH_BRIDGE_ALPN): IrohBiStream

  /** Reports the current path kind to [addr], without dialling. */
  fun pathKind(addr: IrohEndpointAddr): IrohPathKind

  /**
   * A connection for one background poll. It is not in the shared cache, so the foreground never
   * reuses it and closing it never touches the foreground's connections. Nothing dials until the
   * first [IrohPollConnection.openStream].
   */
  fun dialPoll(addr: IrohEndpointAddr, alpn: String = IROH_BRIDGE_ALPN): IrohPollConnection
}

/** The connection one background poll owns. The poll closes it when it ends. */
interface IrohPollConnection {
  /** Opens a bi-stream on this connection, dialling it first if it is not open. */
  suspend fun openStream(): IrohBiStream

  /** Closes the connection and every stream on it. Safe to call more than once. */
  suspend fun close()
}
