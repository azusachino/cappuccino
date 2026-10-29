package com.azusachino.cappuccino.io

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.core.ConversationTurn
import com.azusachino.cappuccino.core.Endpoint
import com.azusachino.cappuccino.core.MutationBinding
import com.azusachino.cappuccino.core.QuestionAnswer
import com.azusachino.cappuccino.core.StreamEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * The process-wide owner of every machine's transport choice. Tailnet-only machines get a plain
 * [BridgeClient]. A machine with an iroh pairing gets a [RoutedBridge] over one [MachineRouter],
 * which keeps its own stickiness, badge and failure count. Also registers the app's single
 * default-network callback, which cancels and redials in-flight iroh attempts.
 */
class MachineTransports private constructor(context: Context) {
  private val iroh: IrohTransport = IrohEndpointTransport.shared(context)
  private val irohClients = IrohHttpClients(iroh, BridgeClient.defaultClient())
  private val routers = ConcurrentHashMap<Endpoint, MachineRouter<BridgeTransport>>()

  init {
    // One callback for the process. `watchNetworkChange` is not used (see IrohEndpointTransport).
    context
      .getSystemService(ConnectivityManager::class.java)
      .registerDefaultNetworkCallback(
        object : ConnectivityManager.NetworkCallback() {
          override fun onAvailable(network: Network) = onNetworkChanged()

          override fun onLost(network: Network) = onNetworkChanged()
        }
      )
  }

  /** Bridge client for [endpoint]; iroh first with tailnet fallback when both are paired. */
  fun bridgeFor(endpoint: Endpoint): BridgeTransport {
    val addr = endpoint.iroh ?: return BridgeClient(endpoint)
    return RoutedBridge(routerFor(endpoint, addr))
  }

  /** The badge for [endpoint]'s row. Call it on each poll tick so the hold-down can settle. */
  fun badge(endpoint: Endpoint): TransportBadge {
    val addr = endpoint.iroh ?: return TransportBadge.TAILNET
    return routerFor(endpoint, addr).badge()
  }

  private fun routerFor(
    endpoint: Endpoint,
    addr: IrohEndpointAddr,
  ): MachineRouter<BridgeTransport> =
    routers.getOrPut(endpoint) {
      val clients =
        buildMap<TransportKind, BridgeTransport> {
          put(TransportKind.IROH, BridgeClient(Endpoint.iroh(addr), irohClients.clientFor(addr)))
          endpoint.tailnetOnly()?.let { put(TransportKind.TAILNET, BridgeClient(it)) }
        }
      MachineRouter(clients, clock, iroh) { iroh.pathKind(addr) }
    }

  private fun onNetworkChanged() {
    routers.values.forEach { it.onNetworkChanged() }
  }

  companion object {
    private val clock = MonotonicClock { SystemClock.elapsedRealtime() }

    @Volatile private var instance: MachineTransports? = null

    fun shared(context: Context): MachineTransports =
      instance
        ?: synchronized(this) {
          instance ?: MachineTransports(context.applicationContext).also { instance = it }
        }
  }
}

/** A [BridgeTransport] whose every call runs through [router]. */
private class RoutedBridge(private val router: MachineRouter<BridgeTransport>) : BridgeTransport {
  // The client that last succeeded supplies the machine name and details the UI reads after verify.
  private val lastUsed = AtomicReference<BridgeTransport?>(null)

  private suspend fun <T> call(
    mutation: Boolean = false,
    block: suspend (BridgeTransport) -> T,
  ): T = router.request(mutation) { client -> block(client).also { lastUsed.set(client) } }

  override val bridgeDetails: BridgeDetails?
    get() = lastUsed.get()?.bridgeDetails

  override val machineName: String?
    get() = lastUsed.get()?.machineName

  override suspend fun verify(expectedMachine: UUID?): UUID = call { it.verify(expectedMachine) }

  override suspend fun agents(machine: UUID): List<AgentRow> = call { it.agents(machine) }

  // The stream opens lazily, so routing selects a transport here and its own errors reach the
  // view model's retry instead of failing over.
  override fun stream(sessionId: String): Flow<StreamEvent> = flow {
    emitAll(call { it.stream(sessionId) })
  }

  override suspend fun conversation(sessionId: String): List<ConversationTurn> = call {
    it.conversation(sessionId)
  }

  override suspend fun conversationSnapshot(sessionId: String): ConversationSnapshot = call {
    it.conversationSnapshot(sessionId)
  }

  override suspend fun submitPrompt(
    sessionId: String,
    text: String,
    confirmed: Boolean,
    binding: MutationBinding,
  ) = call(mutation = true) { it.submitPrompt(sessionId, text, confirmed, binding) }

  override suspend fun controlAgent(sessionId: String, action: String, binding: MutationBinding) =
    call(mutation = true) { it.controlAgent(sessionId, action, binding) }

  override suspend fun answerQuestions(
    sessionId: String,
    promptId: String,
    answers: List<QuestionAnswer>,
    binding: MutationBinding,
  ) = call(mutation = true) { it.answerQuestions(sessionId, promptId, answers, binding) }

  override suspend fun answerPrompt(
    sessionId: String,
    promptId: String,
    optionIndex: Int?,
    optionId: String?,
    action: String?,
    binding: MutationBinding,
  ) =
    call(mutation = true) {
      it.answerPrompt(sessionId, promptId, optionIndex, optionId, action, binding)
    }
}
