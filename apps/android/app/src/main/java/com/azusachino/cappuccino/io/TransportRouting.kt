package com.azusachino.cappuccino.io

import com.azusachino.cappuccino.core.BridgeAnswered
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive

/** Transports a machine can carry. Declaration order is the probe order: iroh first. */
enum class TransportKind {
  IROH,
  TAILNET,
}

/** What a machine row shows. NONE means no reading yet. */
enum class TransportBadge {
  TAILNET,
  IROH_DIRECT,
  IROH_RELAYED,
  NONE,
}

fun interface MonotonicClock {
  fun nowMs(): Long
}

/**
 * Consecutive failed iroh attempts before the process endpoint is rebuilt. Three keeps one flaky
 * dial from restarting the endpoint, and a bridge restart is still caught within three polls.
 */
const val IROH_REBUILD_AFTER_FAILURES = 3

/** A direct⇄relayed change must hold this long before the badge and poll cadence follow it. */
const val BADGE_HOLD_DOWN_MS = 10_000L

const val POLL_DIRECT_MS = 5_000L
const val POLL_RELAYED_MS = 30_000L

/**
 * Poll cadence for a machine, from the badge it shows (which already has the hold-down applied).
 */
fun pollIntervalMs(badge: TransportBadge): Long =
  if (badge == TransportBadge.IROH_RELAYED) POLL_RELAYED_MS else POLL_DIRECT_MS

/**
 * True when the failure happened before the request could reach the bridge: the dial or connect did
 * not complete. An iroh open failure surfaces as [ConnectException] (IrohSocket.connect wraps it).
 */
fun isBeforeSend(error: Exception): Boolean =
  error is ConnectException || error is UnknownHostException || error is IrohConnectTimeoutException

/**
 * True when no answer came back from the transport: a dial or connect failure, a socket error or
 * timeout, or an iroh connection error (IrohSocket reports those as IOExceptions). Errors the
 * bridge sent back, and errors raised while parsing a response, are not transport failures.
 */
fun isTransportFailure(error: Exception): Boolean = error is IOException && error !is BridgeAnswered

/** Row text for a badge; null while there is no reading yet. */
fun TransportBadge.label(): String? =
  when (this) {
    TransportBadge.TAILNET -> "tailnet"
    TransportBadge.IROH_DIRECT -> "iroh direct"
    TransportBadge.IROH_RELAYED -> "iroh relayed"
    TransportBadge.NONE -> null
  }

/**
 * Shows a direct/relayed switch only after it has held for [BADGE_HOLD_DOWN_MS]. Any other change
 * (to tailnet, or the first reading) applies at once. A missing or NONE target keeps what is shown,
 * so a dial in progress does not blank the badge.
 */
class BadgeHoldDown(private val clock: MonotonicClock) {
  var shown: TransportBadge = TransportBadge.NONE
    private set

  private var pending: TransportBadge? = null
  private var pendingSince = 0L

  fun observe(target: TransportBadge?): TransportBadge {
    if (target == null || target == TransportBadge.NONE || target == shown) {
      pending = null
      return shown
    }
    if (!(shown.isIrohPath() && target.isIrohPath())) {
      shown = target
      pending = null
      return shown
    }
    val now = clock.nowMs()
    if (pending != target) {
      pending = target
      pendingSince = now
    }
    if (now - pendingSince >= BADGE_HOLD_DOWN_MS) {
      shown = target
      pending = null
    }
    return shown
  }

  private fun TransportBadge.isIrohPath() =
    this == TransportBadge.IROH_DIRECT || this == TransportBadge.IROH_RELAYED
}

/**
 * Chooses the transport for one machine's requests. Order is iroh then tailnet. The transport that
 * last worked is sticky: it is used alone until a request on it fails or the default network
 * changes, and then the order is probed again. A network change also cancels an in-flight iroh
 * attempt and redials it at once, instead of waiting out the dial timeout.
 *
 * Only the Android runtime builds the iroh side; tests pass fake clients and a fake
 * [IrohTransport]. Non-suspend entry points are called from the ConnectivityManager thread, so
 * state sits under one lock.
 */
class MachineRouter<C : Any>(
  private val clients: Map<TransportKind, C>,
  private val clock: MonotonicClock,
  private val iroh: IrohTransport? = null,
  private val irohPath: () -> IrohPathKind = { IrohPathKind.NONE },
) {
  private val lock = Any()
  private val order = TransportKind.entries.filter { it in clients }
  private var sticky: TransportKind? = null
  private var irohFailures = 0
  private var inFlight: Deferred<*>? = null
  private var redialRequested = false
  private val holdDown = BadgeHoldDown(clock)

  /**
   * Runs [block] on the first transport that works, probing the rest in order on failure.
   *
   * A [mutation] is never re-sent after the request may have reached the bridge: only a failure
   * before any byte left (see [isBeforeSend]) moves on. Any other transport failure is still
   * counted against that transport, then surfaces as [BridgeMutationOutcomeUnknownException] so the
   * owner re-checks instead of the app resending the change.
   */
  suspend fun <T> request(mutation: Boolean = false, block: suspend (C) -> T): T {
    val tried = mutableSetOf<TransportKind>()
    var lastError: Exception? = null
    while (true) {
      val kind = nextKind(tried) ?: break
      tried += kind
      try {
        val result = attempt(kind, block)
        recordSuccess(kind)
        ConnectionLogger.log("ROUTER", "Transport $kind request succeeded")
        return result
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        ConnectionLogger.log(
          "ROUTER",
          "Transport $kind failed: ${error.javaClass.simpleName} (${error.message})",
          ConnectionLogLevel.WARN,
        )
        if (!isTransportFailure(error)) {
          // The bridge answered over this transport, so the path works; the error belongs to the
          // caller. No fall-through and no failure count. Other errors are bugs, not path health.
          if (error is BridgeAnswered) recordSuccess(kind)
          throw error
        }
        recordFailure(kind)
        if (mutation && !isBeforeSend(error)) {
          throw BridgeMutationOutcomeUnknownException(
            "The connection failed after the change was sent; it may have been applied. Check before retrying."
          )
        }
        lastError = error
      }
    }
    throw lastError ?: IllegalStateException("Machine has no transport")
  }

  /**
   * Called on a default-network change: forget stickiness and redial any in-flight iroh attempt.
   */
  fun onNetworkChanged() {
    synchronized(lock) {
      sticky = null
      inFlight?.let {
        redialRequested = true
        it.cancel()
      }
    }
    ConnectionLogger.log("ROUTER", "Network change: reset stickiness & redialing")
  }

  /**
   * Forgets stickiness and the iroh failure count, and drops the connections: the next call dials.
   */
  suspend fun redial() {
    synchronized(lock) {
      sticky = null
      irohFailures = 0
    }
    iroh?.dropConnections()
  }

  /** The badge for this machine now. Call it on each poll tick; the hold-down needs ticks. */
  fun badge(): TransportBadge =
    synchronized(lock) {
      val raw =
        when (sticky) {
          TransportKind.TAILNET -> TransportBadge.TAILNET
          TransportKind.IROH ->
            when (irohPath()) {
              IrohPathKind.DIRECT -> TransportBadge.IROH_DIRECT
              IrohPathKind.RELAYED -> TransportBadge.IROH_RELAYED
              IrohPathKind.NONE -> null
            }
          null -> null
        }
      holdDown.observe(raw)
    }

  private fun nextKind(tried: Set<TransportKind>): TransportKind? =
    synchronized(lock) {
      val sticky = sticky
      if (sticky != null && sticky !in tried) sticky else order.firstOrNull { it !in tried }
    }

  private suspend fun <T> attempt(kind: TransportKind, block: suspend (C) -> T): T {
    val client = clients.getValue(kind)
    if (kind == TransportKind.TAILNET) return block(client)
    while (true) {
      try {
        return coroutineScope {
          val job = async { block(client) }
          synchronized(lock) { inFlight = job }
          try {
            job.await()
          } finally {
            synchronized(lock) { if (inFlight === job) inFlight = null }
          }
        }
      } catch (error: CancellationException) {
        // Our own network-triggered cancel is a redial; anything else is the caller's cancel.
        if (!coroutineContext.isActive) throw error
        val redial = synchronized(lock) { redialRequested.also { redialRequested = false } }
        if (!redial) throw error
        iroh?.dropConnections()
      }
    }
  }

  private fun recordSuccess(kind: TransportKind) {
    synchronized(lock) {
      sticky = kind
      if (kind == TransportKind.IROH) irohFailures = 0
    }
  }

  private suspend fun recordFailure(kind: TransportKind) {
    val rebuild =
      synchronized(lock) {
        if (sticky == kind) sticky = null
        if (kind != TransportKind.IROH) {
          false
        } else {
          irohFailures += 1
          if (irohFailures >= IROH_REBUILD_AFTER_FAILURES) {
            irohFailures = 0
            true
          } else {
            false
          }
        }
      }
    if (rebuild) {
      ConnectionLogger.log(
        "IROH",
        "3 consecutive Iroh failures; restarting endpoint",
        ConnectionLogLevel.WARN,
      )
      iroh?.stop()
    }
  }
}
