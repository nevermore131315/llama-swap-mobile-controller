package io.github.nevermore131315.llamaswapcontroller.data.llamaswap

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A decoded message from llama-swap's `GET /api/events` stream. */
sealed interface LlamaSwapEvent {
    /** Full replacement of every model's status (not a diff). */
    data class ModelStatus(
        val models: List<LlamaSwapModel>,
    ) : LlamaSwapEvent

    /**
     * A new activity log row was stored. llama-swap sends only its id (`{"id":N}`, sendActivity in
     * internal/server/apigroup.go); the row itself must be fetched from /api/metrics/activity.
     */
    data class Activity(
        val id: Long,
    ) : LlamaSwapEvent

    /** Any other message type (uiConfig, profileChanged, inflight, …), kept for liveness only. */
    data class Other(
        val type: String,
    ) : LlamaSwapEvent
}

/**
 * The SSE `data:` payload: `{"type":"…","data":"<JSON STRING>"}`. The inner `data` is a
 * JSON-encoded string (llama-swap's messageEnvelope.Data is a Go `string`), so the payload
 * must be decoded twice.
 */
@Serializable
internal data class LlamaSwapEnvelope(
    val type: String = "",
    val data: String = "",
)

@Serializable
internal data class LlamaSwapActivityId(
    val id: Long = 0,
)

object LlamaSwapEventParser {
    const val TYPE_MODEL_STATUS = "modelStatus"
    const val TYPE_ACTIVITY = "activity"

    private val modelListSerializer = ListSerializer(LlamaSwapModel.serializer())

    /**
     * Decodes one SSE event's data payload. Returns null for malformed payloads so a single
     * bad message never tears down the stream.
     */
    fun parsePayload(payload: String): LlamaSwapEvent? {
        val envelope =
            runCatching { LlamaSwapJson.decodeFromString(LlamaSwapEnvelope.serializer(), payload) }
                .getOrNull() ?: return null
        if (envelope.type == TYPE_ACTIVITY) {
            val id =
                runCatching { LlamaSwapJson.decodeFromString(LlamaSwapActivityId.serializer(), envelope.data) }
                    .getOrNull()
                    ?.id
            // An undecodable id still means "something new was logged": refresh with id 0.
            return LlamaSwapEvent.Activity(id ?: 0)
        }
        if (envelope.type != TYPE_MODEL_STATUS) return LlamaSwapEvent.Other(envelope.type)
        val models =
            runCatching { LlamaSwapJson.decodeFromString(modelListSerializer, envelope.data) }
                .getOrNull() ?: return null
        return LlamaSwapEvent.ModelStatus(models)
    }
}

/**
 * Incremental Server-Sent Events line parser (WHATWG event-stream rules, data + event fields).
 * Feed it each line without its terminator; it returns the event's data when a blank line
 * completes an event.
 */
class SseLineParser {
    private val data = StringBuilder()
    private var hasData = false

    /** Returns the completed event's data payload, or null if the event is not complete yet. */
    fun feed(line: String): String? {
        if (line.isEmpty()) {
            if (!hasData) return null
            val payload = data.toString()
            data.setLength(0)
            hasData = false
            return payload
        }
        if (line.startsWith(":")) return null // comment / keep-alive
        val colon = line.indexOf(':')
        val field = if (colon == -1) line else line.substring(0, colon)
        var value = if (colon == -1) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        if (field == "data") {
            if (hasData) data.append('\n')
            data.append(value)
            hasData = true
        }
        // "event", "id" and "retry" carry nothing llama-swap clients need (event is always "message").
        return null
    }
}
