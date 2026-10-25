package io.github.nevermore131315.llamaswapcontroller.data.llamaswap

import io.github.nevermore131315.llamaswapcontroller.data.remote.OkHttpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One message of an OpenAI-style chat history. [role] is "user" or "assistant". */
@Serializable
data class LlamaSwapChatMessage(
    val role: String,
    val content: String,
)

@Serializable
internal data class LlamaSwapChatRequest(
    val model: String,
    val messages: List<LlamaSwapChatMessage>,
    // No default: LlamaSwapJson does not encode defaults, and stream must always be sent.
    val stream: Boolean,
)

/** A piece of a streamed assistant reply. */
sealed interface LlamaSwapChatDelta {
    data class Content(
        val text: String,
    ) : LlamaSwapChatDelta

    /** `delta.reasoning_content` from thinking models (and llama-swap's own loading messages). */
    data class Reasoning(
        val text: String,
    ) : LlamaSwapChatDelta
}

/** The server reported an error inside an already-started stream (an SSE `error` frame). */
class LlamaSwapStreamException(
    message: String,
) : IOException(message)

/** Streaming chat for the Playground tab. */
interface LlamaSwapChatApi {
    /**
     * POST /v1/chat/completions with `stream: true`. Emits deltas until `data: [DONE]` or the
     * server closes the stream. Cancelling the collector cancels the HTTP call (Stop).
     */
    fun streamChat(
        modelId: String,
        messages: List<LlamaSwapChatMessage>,
    ): Flow<LlamaSwapChatDelta>
}

/** Parses the `data:` payloads of an OpenAI chat-completions stream. */
object LlamaSwapChatSse {
    const val DONE = "[DONE]"

    /**
     * Deltas carried by one chunk payload (often none: role-only, usage or timings chunks).
     * Throws [LlamaSwapStreamException] for an `error` frame. Unparseable payloads are skipped.
     */
    fun parse(payload: String): List<LlamaSwapChatDelta> {
        val root =
            try {
                LlamaSwapJson.parseToJsonElement(payload) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return emptyList()
        root["error"]?.let { throw LlamaSwapStreamException(errorMessage(it)) }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return emptyList()
        val delta = choice["delta"] as? JsonObject ?: return emptyList()
        val out = mutableListOf<LlamaSwapChatDelta>()
        delta.string("reasoning_content")?.takeIf { it.isNotEmpty() }?.let { out += LlamaSwapChatDelta.Reasoning(it) }
        delta.string("content")?.takeIf { it.isNotEmpty() }?.let { out += LlamaSwapChatDelta.Content(it) }
        return out
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun errorMessage(error: JsonElement): String =
        when (error) {
            is JsonPrimitive -> error.contentOrNull
            is JsonObject -> error.string("message")
            else -> null
        }?.ifBlank { null } ?: "Server reported an error"
}

/**
 * OkHttp client for llama-swap chat completions. Uses the external client like
 * [LlamaSwapHttpClient] so llama-swap never sees dashboard credentials.
 */
class LlamaSwapChatHttpClient(
    private val baseUrl: () -> String,
    http: OkHttpClient = OkHttpProvider.external,
) : LlamaSwapChatApi {
    // llama-swap holds the request (no bytes) while it cold-loads the model, which can take
    // minutes, and a long reply has no overall bound: only the gap between bytes is limited.
    private val stream: OkHttpClient =
        http
            .newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    internal fun url(): HttpUrl {
        val base =
            baseUrl().trim().toHttpUrlOrNull()
                ?: throw IOException("Invalid llama-swap URL: ${baseUrl()}")
        return base
            .newBuilder()
            .addPathSegment("v1")
            .addPathSegment("chat")
            .addPathSegment("completions")
            .build()
    }

    override fun streamChat(
        modelId: String,
        messages: List<LlamaSwapChatMessage>,
    ): Flow<LlamaSwapChatDelta> =
        callbackFlow {
            val body =
                LlamaSwapJson
                    .encodeToString(
                        LlamaSwapChatRequest.serializer(),
                        LlamaSwapChatRequest(modelId, messages, stream = true),
                    ).toRequestBody(JSON)
            val call =
                stream.newCall(
                    Request
                        .Builder()
                        .url(url())
                        .header("Accept", "text/event-stream")
                        .post(body)
                        .build(),
                )
            // execute() and every body read block on the network: all of it stays on IO.
            launch(Dispatchers.IO) {
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            val text = runCatching { response.body.string() }.getOrDefault("")
                            // Prefer the OpenAI error envelope's message over the raw body.
                            val reason =
                                (runCatching { LlamaSwapChatSse.parse(text) }.exceptionOrNull()?.message ?: text)
                                    .take(200)
                                    .trim()
                            throw LlamaSwapHttpException(
                                response.code,
                                "HTTP ${response.code}${if (reason.isNotEmpty()) ": $reason" else ""}",
                            )
                        }
                        val source = response.body.source()
                        val parser = SseLineParser()
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            val payload = parser.feed(line) ?: continue
                            if (payload.trim() == LlamaSwapChatSse.DONE) break
                            LlamaSwapChatSse.parse(payload).forEach { send(it) }
                        }
                    }
                    close()
                } catch (e: IOException) {
                    close(e)
                }
            }
            awaitClose { call.cancel() }
        }

    private companion object {
        const val READ_TIMEOUT_MINUTES = 15L
        val JSON = "application/json".toMediaType()
    }
}
