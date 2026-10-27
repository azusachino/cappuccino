package com.azusachino.cappuccino.core

import org.junit.Assert.*
import org.junit.Test

class RecentHistoryTest {
  @Test
  fun messagesAreBoundedInOriginalOrderWithoutMutatingInput() {
    val input = (0..220).map { ConversationTurn("t$it", "agent", null, "reply $it", emptyList()) }
    val recent = boundedConversation(input)
    assertEquals(200, recent.turns.size)
    assertEquals(21, recent.omittedMessages)
    assertEquals("t21", recent.turns.first().id)
    assertEquals("t220", recent.turns.last().id)
    assertEquals(221, input.size)
  }

  @Test
  fun toolLimitAppliesEvenInsideOneMessageAndRetainsProseAndStatus() {
    val tools =
      (0..150).map { ConversationPart.ToolCall("tool$it", "argument", "result", it == 150) }
    val input =
      listOf(
        ConversationTurn(
          "t",
          "agent",
          null,
          "Partial reply",
          tools + ConversationPart.ResponseStatus("aborted", null),
        )
      )
    val recent = boundedConversation(input)
    val retained = recent.turns.single().parts.filterIsInstance<ConversationPart.ToolCall>()
    assertEquals(100, retained.size)
    assertEquals(51, recent.omittedTools)
    assertEquals("tool51", retained.first().name)
    assertEquals(true, retained.last().isError)
    assertEquals("Partial reply", recent.turns.single().text)
    assertEquals(
      ConversationPart.ResponseStatus("aborted", null),
      recent.turns.single().parts.last(),
    )
    assertEquals(152, input.single().parts.size)
  }

  @Test
  fun toolChangesDoNotFollowButNewRepliesDo() {
    val tool = ConversationPart.ToolCall("bash", "test", null)
    val before = listOf(ConversationTurn("reply", "agent", null, "Same reply", listOf(tool)))
    val after =
      listOf(before.single().copy(parts = listOf(tool.copy(output = "failed", isError = true))))
    assertEquals(conversationFollowContent(before), conversationFollowContent(after))
    assertTrue(
      conversationFollowContent(listOf(ConversationTurn("tool", "agent", null, null, listOf(tool))))
        .isEmpty()
    )
    assertNotEquals(
      conversationFollowContent(before),
      conversationFollowContent(listOf(before.single().copy(text = "New reply"))),
    )
    assertNotEquals(
      conversationFollowContent(before),
      conversationFollowContent(
        listOf(
          before.single().copy(parts = listOf(ConversationPart.ResponseStatus("aborted", null)))
        )
      ),
    )
  }

  @Test
  fun trimmingOlderHistoryDoesNotLookLikeANewAnswer() {
    val older = ConversationTurn("older", "agent", null, "Older answer", emptyList())
    val latest = ConversationTurn("latest", "agent", null, "Last answer", emptyList())
    assertEquals(
      conversationFollowContent(listOf(older, latest)),
      conversationFollowContent(listOf(latest)),
    )
  }

  @Test
  fun reasoningWithToolsDoesNotTriggerChatFollowing() {
    val reply = ConversationTurn("reply", "agent", null, "Last answer", emptyList())
    val reasoning =
      ConversationTurn(
        "tool-step",
        "agent",
        null,
        null,
        listOf(
          ConversationPart.Thinking("Inspecting the next file"),
          ConversationPart.ToolCall("bash", "pwd", null),
        ),
      )
    assertEquals(
      conversationFollowContent(listOf(reply)),
      conversationFollowContent(listOf(reply, reasoning)),
    )
    assertTrue(conversationFollowContent(listOf(reasoning)).isEmpty())
  }

  @Test
  fun responseFailuresRemainRecordedButDoNotChangeChatFollowing() {
    val reply = ConversationTurn("t", "agent", null, "Partial reply", emptyList())
    val failed = reply.copy(parts = listOf(ConversationPart.ResponseStatus("failed", "terminated")))
    assertEquals(
      conversationFollowContent(listOf(reply)),
      conversationFollowContent(listOf(failed)),
    )
    assertFalse(failed.parts.single().visibleInChat)
    assertEquals("terminated", (failed.parts.single() as ConversationPart.ResponseStatus).detail)
    assertTrue(ConversationPart.ResponseStatus("aborted", null).visibleInChat)
  }

  @Test
  fun omittedToolsIncludeOperationsLostOutsideTheMessageWindow() {
    val input =
      (0 until 350).map { index ->
        ConversationTurn(
          "t$index",
          "agent",
          null,
          null,
          listOf(ConversationPart.ToolCall("tool$index", "", "result")),
        )
      }
    val recent = boundedConversation(input)
    val tools = recent.turns.flatMap { it.parts }.filterIsInstance<ConversationPart.ToolCall>()
    assertEquals(100, tools.size)
    assertEquals(250, recent.omittedTools)
    assertEquals(150, recent.omittedMessages)
    assertEquals("tool250", tools.first().name)
    assertEquals("tool349", tools.last().name)
    assertEquals(350, tools.size + recent.omittedTools)
    assertEquals(350, input.size)
  }

  @Test
  fun omittedToolsAreCountedEvenWhenAllAreOutsideTheRetainedWindow() {
    val input =
      listOf(
        ConversationTurn(
          "old-tool",
          "agent",
          null,
          null,
          listOf(ConversationPart.ToolCall("read", "", null)),
        )
      ) + (0 until 201).map { ConversationTurn("text$it", "agent", null, "reply", emptyList()) }
    val recent = boundedConversation(input)
    assertEquals(1, recent.omittedTools)
    assertTrue(recent.turns.flatMap { it.parts }.none { it is ConversationPart.ToolCall })
  }

  @Test
  fun smallAndEmptyWindowsAreUnchanged() {
    assertEquals(RecentConversation(emptyList(), 0, 0), boundedConversation(emptyList()))
    val one = ConversationTurn("t", "user", null, "hello", emptyList())
    assertEquals(listOf(one), boundedConversation(listOf(one)).turns)
  }
}
