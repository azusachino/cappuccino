package com.azusachino.cappuccino.io

import java.io.InputStream
import java.io.OutputStream

/** ALPN the bridge serves its iroh endpoint on. */
const val IROH_BRIDGE_ALPN = "cappuccino/1"

/** Path the transport currently uses to reach a peer; NONE means no live path. */
enum class IrohPathKind {
  DIRECT,
  RELAYED,
  NONE,
}

/** Bridge endpoint from a pairing payload. Kept free of iroh types so pure code can use it. */
data class IrohEndpointAddr(
  val endpointId: String,
  val relayUrl: String,
  val directAddrs: List<String>,
)

/** The two directions of one bi-stream, ready to be wrapped by a `java.net.Socket`. */
class IrohStreamPair(val input: InputStream, val output: OutputStream)

/**
 * One iroh endpoint per app process. Implementations load the native library, so only the Android
 * runtime may construct one; JVM tests use fakes of this interface.
 */
interface IrohTransport {
  /** Starts the process endpoint with the 32-byte ed25519 secret key. Idempotent while running. */
  suspend fun start(secretKey: ByteArray)

  /** Closes the endpoint and every connection on it. */
  suspend fun stop()

  /** Dials [addr] on [IROH_BRIDGE_ALPN] and opens a bi-stream to it. */
  suspend fun openStream(addr: IrohEndpointAddr): IrohStreamPair

  /** Reports the current path kind to [addr], without dialling. */
  fun pathKind(addr: IrohEndpointAddr): IrohPathKind
}
