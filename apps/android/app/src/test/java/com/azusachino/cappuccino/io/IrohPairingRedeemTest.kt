package com.azusachino.cappuccino.io

import com.azusachino.cappuccino.core.ProtocolException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohPairingRedeemTest {
  private fun blocking(block: suspend () -> Unit) = runBlocking { block() }

  private val token = "a".repeat(64)
  private val pairing =
    IrohPairing(IrohEndpointAddr("bridge-id", null, listOf("10.0.0.2:4433")), token)

  /** Transport that answers the pairing ALPN with [reply] and records the dialled ALPN. */
  private class ScriptedTransport(private val reply: String) : IrohTransport {
    val stream = FakeBiStream()
    var dialledAlpn: String? = null

    init {
      if (reply.isNotEmpty()) stream.inbound.trySend(reply.toByteArray())
      stream.inbound.close()
    }

    override suspend fun start(secretKey: ByteArray) = Unit

    override suspend fun stop() = Unit

    override suspend fun dropConnections() = Unit

    override suspend fun openStream(addr: IrohEndpointAddr, alpn: String): IrohBiStream {
      dialledAlpn = alpn
      return stream
    }

    override fun pathKind(addr: IrohEndpointAddr): IrohPathKind = IrohPathKind.NONE

    override fun dialPoll(addr: IrohEndpointAddr, alpn: String): IrohPollConnection =
      error("not used by this test")
  }

  @Test
  fun okReplyPairsAndSendsOnlyTheTokenOnThePairAlpn() = blocking {
    val transport = ScriptedTransport("OK\n")
    redeemIrohPairing(transport, pairing)
    assertEquals(IROH_PAIR_ALPN, transport.dialledAlpn)
    assertEquals(token, transport.stream.written.toString(Charsets.UTF_8))
    assertEquals(1, transport.stream.finishes)
    assertTrue(transport.stream.released)
  }

  @Test
  fun errReplyFailsWithoutEchoingTheToken() = blocking {
    val error =
      assertThrows(ProtocolException::class.java) {
        runBlocking { redeemIrohPairing(ScriptedTransport("ERR\n"), pairing) }
      }
    assertEquals("Bridge rejected the pairing token", error.message)
    assertTrue(error.message!!.contains(token).not())
  }

  @Test
  fun replyWithoutTheExactNewlineFormIsRejected() = blocking {
    assertThrows(ProtocolException::class.java) {
      runBlocking { redeemIrohPairing(ScriptedTransport("OK"), pairing) }
    }
  }

  @Test
  fun oversizedReplyIsRejected() = blocking {
    assertThrows(ProtocolException::class.java) {
      runBlocking { redeemIrohPairing(ScriptedTransport("OK\n".repeat(10)), pairing) }
    }
  }
}
