package com.hermes.mobile.data.remote

import com.hermes.mobile.data.model.ChatItem
import com.hermes.mobile.data.model.WorkStep

// 过程只留在当前连接的内存中，限制条数和长度，避免长任务挤占聊天界面和内存。
private const val MaxSteps = 100
private const val MaxStepText = 8_000

internal fun ChatItem.Activity.reasoning(event: RunEvent.Reasoning): ChatItem.Activity {
    if (event.text.isBlank()) return this
    val last = steps.lastOrNull()
    val next = if (event.delta && last?.kind == WorkStep.Kind.Reasoning) {
        steps.dropLast(1) + last.copy(text = (last.text + event.text).take(MaxStepText))
    } else steps + WorkStep("reasoning-${event.seq ?: steps.size}", WorkStep.Kind.Reasoning, "思考", event.text.take(MaxStepText), finished = true)
    return copy(steps = next.takeLast(MaxSteps))
}

internal fun ChatItem.Activity.tool(event: RunEvent.Step): ChatItem.Activity {
    val matching = steps.indexOfLast { it.kind == WorkStep.Kind.Tool &&
        if (event.callId != null) it.id == "tool-${event.callId}" else it.title == event.tool && !it.finished }
    val step = WorkStep(
        "tool-${event.callId ?: event.seq ?: steps.size}", WorkStep.Kind.Tool, event.tool,
        event.text.take(MaxStepText), event.finished, event.failed,
    )
    val next = if (matching < 0) steps + step else steps.toMutableList().also { list ->
        val previous = list[matching]
        list[matching] = previous.copy(
            text = listOf(previous.text, event.text.take(MaxStepText)).filter { it.isNotBlank() }.distinct().joinToString("\n").take(MaxStepText),
            finished = previous.finished || event.finished, failed = previous.failed || event.failed,
        )
    }
    return copy(steps = next.takeLast(MaxSteps))
}
