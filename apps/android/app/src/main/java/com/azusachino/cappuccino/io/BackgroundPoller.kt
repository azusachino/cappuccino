package com.azusachino.cappuccino.io

import com.azusachino.cappuccino.core.Endpoint
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs one background poll of a machine with the foreground's iroh-first routing. The poll dials
 * its own iroh connection, makes its requests over it, and closes it when the poll ends. The shared
 * connection cache is never used, so the foreground's connections are neither reused nor closed.
 * Only reads happen here; the caller must not send mutations through it.
 *
 * Tailnet-only machines skip iroh and use [tailnetClient]. Dual machines fall back to tailnet when
 * the iroh attempt fails, through the same [MachineRouter] the foreground uses. The router is built
 * per poll and gets no [IrohTransport] of its own, so a failed poll never stops the shared endpoint
 * or redials it.
 */
class BackgroundPoller(
  private val iroh: IrohTransport,
  private val clock: MonotonicClock,
  private val irohClient:
    (IrohEndpointAddr, suspend (IrohEndpointAddr) -> IrohBiStream) -> BridgeTransport,
  private val tailnetClient: (Endpoint) -> BridgeTransport,
) {
  suspend fun <T> poll(endpoint: Endpoint, block: suspend (BridgeTransport) -> T): T {
    val addr = endpoint.iroh ?: return block(tailnetClient(endpoint))
    val connection = iroh.dialPoll(addr)
    try {
      val clients =
        buildMap<TransportKind, BridgeTransport> {
          put(TransportKind.IROH, irohClient(addr) { _ -> connection.openStream() })
          endpoint.tailnetOnly()?.let { put(TransportKind.TAILNET, tailnetClient(it)) }
        }
      return block(RoutedBridge(MachineRouter(clients, clock)))
    } finally {
      // Runs even when the service cancels the poll; the close must still reach the network.
      withContext(NonCancellable) { connection.close() }
    }
  }
}
