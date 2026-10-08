package io.github.zero2005x.glassesaicompanion.service.ai

import okio.BufferedSource

/**
 * Minimal Server-Sent Events reader.
 *
 * Iterates `data:` payloads from an OkHttp [BufferedSource]; a blank line
 * terminates an event. `data: [DONE]` surfaces as [SseEvent.Done].
 * Malformed lines are skipped so one bad event never kills the stream.
 */
object SseParser {

    sealed interface SseEvent {
        data class Data(val payload: String, val event: String? = null) : SseEvent
        data object Done : SseEvent
    }

    /**
     * Read events until the stream ends or [Done] is encountered.
     * Blocking — call from an IO dispatcher.
     */
    fun readEvents(source: BufferedSource, onEvent: (SseEvent) -> Unit) {
        var dataBuffer = StringBuilder()
        var eventName: String? = null

        fun dispatch() {
            if (dataBuffer.isEmpty()) {
                eventName = null
                return
            }
            val payload = dataBuffer.toString().trimEnd('\n')
            dataBuffer = StringBuilder()
            if (payload == "[DONE]") {
                onEvent(SseEvent.Done)
            } else {
                onEvent(SseEvent.Data(payload, eventName))
            }
            eventName = null
        }

        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> dispatch()
                line.startsWith(":") -> Unit // comment / heartbeat
                line.startsWith("data:") -> {
                    dataBuffer.append(line.removePrefix("data:").trimStart()).append('\n')
                }
                line.startsWith("event:") -> {
                    eventName = line.removePrefix("event:").trim()
                }
                else -> Unit // ignore field-less or unknown lines
            }
        }
        dispatch()
    }
}
