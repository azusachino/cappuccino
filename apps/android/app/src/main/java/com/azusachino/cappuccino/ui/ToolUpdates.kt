package com.azusachino.cappuccino.ui

import com.azusachino.cappuccino.core.ToolActivityState
import com.azusachino.cappuccino.core.activityState

/** Selected-conversation updates, not an execution/success or historical-total badge. */
internal data class ToolUpdates(
  private val known: Map<String, Boolean>? = null,
  val unseen: Int = 0,
) {
  fun observe(entries: List<ToolActivityEntry>, visible: Boolean): ToolUpdates {
    val current = entries.associate {
      "${it.turnId}:${it.partIndex}" to
        (it.part.activityState != ToolActivityState.RESULT_NOT_RECORDED)
    }
    val updates =
      if (known == null) 0
      else
        current.entries.sumOf { (id, returned) ->
          when {
            id !in known -> if (returned) 2 else 1 // A call and its result may arrive together.
            returned && known[id] == false -> 1
            else -> 0
          }
        }
    val retained =
      ((known ?: emptyMap()) +
          current.mapValues { (id, returned) -> returned || known?.get(id) == true })
        .entries
        .toList()
        .takeLast(2048)
        .associate { it.toPair() }
    return ToolUpdates(retained, if (visible) 0 else unseen + updates)
  }
}
