package io.github.nevermore131315.llamaswapcontroller.data.llamaswap

import io.github.nevermore131315.llamaswapcontroller.data.remote.OkHttpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Operations the Llama-swap screen needs. Implemented over HTTP by [LlamaSwapHttpClient]. */
interface LlamaSwapApi {
    suspend fun version(): LlamaSwapVersion

    /** Snapshot via GET /v1/models + GET /running (fallback when the event stream is down). */
    suspend fun models(): List<LlamaSwapModel>

    /** Loads (and, in an exclusive swap group, swaps to) [modelId]. Suspends until it is ready. */
    suspend fun load(modelId: String)

    suspend fun unload(modelId: String)

    suspend fun unloadAll()

    /** GET /api/events. Completes normally when the server closes the stream; errors on failure. */
    fun events(): Flow<LlamaSwapEvent>
}

/** Read-only activity log endpoints, used by the Activity tab. Only GET requests. */
interface LlamaSwapActivityApi {
    /**
     * GET /api/metrics/activity, newest first. [minId] (inclusive, 0 = unbounded) fetches only rows
     * newer than those already shown. [limit] must be 1..999 (server-enforced).
     */
    suspend fun activity(
        limit: Int,
        minId: Long = 0,
    ): LlamaSwapActivityPage

    /** GET /api/metrics/stats: totals plus prefill/decode speed histograms. */
    suspend fun activityStats(): LlamaSwapActivityStats
}

/** Read-only GPU/system stats, used by the Performance tab. Only GET requests. */
interface LlamaSwapPerformanceApi {
    /**
     * GET /api/performance. [after] (a timestamp previously returned by the server, sent verbatim)
     * fetches only newer samples; null fetches the whole buffered history. A disabled monitor
     * (HTTP 503 with `{"enabled":false}`) is returned as `enabled = false`, not thrown.
     */
    suspend fun performance(after: String? = null): LlamaSwapPerformance
}

class LlamaSwapHttpException(
    val code: Int,
    message: String,
) : IOException(message)

/**
 * OkHttp client for llama-swap. Deliberately separate from the dashboard's [OkHttpProvider.base]
 * client: llama-swap is another host, so it must never receive dashboard cookies, auth headers
 * or client certificates. [baseUrl] is read on every call so a settings change applies at once.
 */
class LlamaSwapHttpClient(
    private val baseUrl: () -> String,
    http: OkHttpClient = OkHttpProvider.external,
) : LlamaSwapApi,
    LlamaSwapActivityApi,
    LlamaSwapPerformanceApi {
    private val rest: OkHttpClient =
        http
            .newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    // Event stream: llama-swap sends nothing while idle, so reads must not time out.
    private val stream: OkHttpClient = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    // Loading a large model can take minutes; the response arrives only once it is ready.
    private val loader: OkHttpClient =
        http
            .newBuilder()
            .readTimeout(LOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .callTimeout(LOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .build()

    private fun url(vararg segments: String): HttpUrl {
        val base =
            baseUrl().trim().toHttpUrlOrNull()
                ?: throw IOException("Invalid llama-swap URL: ${baseUrl()}")
        val builder = base.newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    override suspend fun version(): LlamaSwapVersion =
        LlamaSwapJson.decodeFromString(LlamaSwapVersion.serializer(), getString(url("api", "version")))

    override suspend fun models(): List<LlamaSwapModel> {
        val list = LlamaSwapJson.decodeFromString(LlamaSwapModelList.serializer(), getString(url("v1", "models")))
        val running = LlamaSwapJson.decodeFromString(LlamaSwapRunningList.serializer(), getString(url("running")))
        return mergeModelsAndRunning(list.data, running.running)
    }

    /**
     * Uses the same mechanism as llama-swap's own UI (`loadModel` in ui/src/stores/api.ts):
     * GET /upstream/{model}/ starts the process and returns once it serves requests. There is
     * no dedicated load endpoint; config `hooks.on_startup.preload` is startup-only.
     */
    override suspend fun load(modelId: String) {
        val target =
            url("upstream", modelId, "")
                .newBuilder()
                .addQueryParameter("_", System.currentTimeMillis().toString())
                .build()
        val failure =
            withContext(Dispatchers.IO) {
                loader
                    .newCall(
                        Request
                            .Builder()
                            .url(target)
                            .get()
                            .build(),
                    ).await()
                    .use { runCatching { it.requireSuccess() }.exceptionOrNull() }
            } ?: return
        // llama-swap answers this GET by proxying it to the model's own server once the
        // process is up. llama-server started with --no-webui returns 404 for "/", so a
        // non-2xx here does not mean the load failed: trust the model's state instead.
        val state = runCatching { models().firstOrNull { it.id == modelId }?.state }.getOrNull()
        if (state != LlamaSwapState.READY) throw failure
    }

    override suspend fun unload(modelId: String) = post(url("api", "models", "unload", modelId))

    override suspend fun unloadAll() = post(url("api", "models", "unload"))

    override suspend fun activity(
        limit: Int,
        minId: Long,
    ): LlamaSwapActivityPage {
        val target =
            url("api", "metrics", "activity")
                .newBuilder()
                .addQueryParameter("limit", limit.coerceIn(1, MAX_ACTIVITY_LIMIT).toString())
                .apply { if (minId > 0) addQueryParameter("min_id", minId.toString()) }
                .build()
        return LlamaSwapJson.decodeFromString(LlamaSwapActivityPage.serializer(), getString(target))
    }

    override suspend fun activityStats(): LlamaSwapActivityStats =
        LlamaSwapJson.decodeFromString(LlamaSwapActivityStats.serializer(), getString(url("api", "metrics", "stats")))

    // Whole call off Main: the body read blocks on the network and the full history is ~1.5 MB
    // of JSON, which is too much to decode on the UI thread.
    override suspend fun performance(after: String?): LlamaSwapPerformance =
        withContext(Dispatchers.IO) { fetchPerformance(after) }

    private suspend fun fetchPerformance(after: String?): LlamaSwapPerformance {
        val target =
            url("api", "performance")
                .newBuilder()
                .apply { if (!after.isNullOrBlank()) addQueryParameter("after", after) }
                .build()
        val body =
            rest
                .newCall(
                    Request
                        .Builder()
                        .url(target)
                        .get()
                        .build(),
                ).await()
                .use { response ->
                    if (response.code == HTTP_UNAVAILABLE) {
                        val text = runCatching { response.body.string() }.getOrDefault("")
                        val disabled =
                            runCatching { LlamaSwapJson.decodeFromString(LlamaSwapPerformance.serializer(), text) }
                                .getOrNull()
                        if (disabled != null && !disabled.enabled) return disabled
                        throw LlamaSwapHttpException(response.code, "HTTP ${response.code}: ${text.take(200).trim()}")
                    }
                    response.requireSuccess()
                    response.body.string()
                }
        return LlamaSwapJson.decodeFromString(LlamaSwapPerformance.serializer(), body)
    }

    override fun events(): Flow<LlamaSwapEvent> =
        callbackFlow {
            val call =
                stream.newCall(
                    Request
                        .Builder()
                        .url(url("api", "events"))
                        .header("Accept", "text/event-stream")
                        .build(),
                )
            launch(Dispatchers.IO) {
                try {
                    call.execute().use { response ->
                        response.requireSuccess()
                        val source = response.body.source()
                        val parser = SseLineParser()
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            val payload = parser.feed(line) ?: continue
                            LlamaSwapEventParser.parsePayload(payload)?.let { send(it) }
                        }
                    }
                    close()
                } catch (e: IOException) {
                    close(e)
                }
            }
            awaitClose { call.cancel() }
        }

    // Body reads block on the network, so they must not run on the caller's (often Main)
    // dispatcher: await() only moves the header wait off-thread.
    private suspend fun getString(url: HttpUrl): String =
        withContext(Dispatchers.IO) {
            rest
                .newCall(
                    Request
                        .Builder()
                        .url(url)
                        .get()
                        .build(),
                ).await()
                .use { response ->
                    response.requireSuccess()
                    response.body.string()
                }
        }

    private suspend fun post(url: HttpUrl) {
        withContext(Dispatchers.IO) {
            rest
                .newCall(
                    Request
                        .Builder()
                        .url(url)
                        .post(ByteArray(0).toRequestBody())
                        .build(),
                ).await()
                .use { it.requireSuccess() }
        }
    }

    private fun Response.requireSuccess() {
        if (!isSuccessful) {
            val detail = runCatching { body.string() }.getOrDefault("").take(200).trim()
            throw LlamaSwapHttpException(code, "HTTP $code${if (detail.isNotEmpty()) ": $detail" else ""}")
        }
    }

    private companion object {
        const val LOAD_TIMEOUT_MINUTES = 15L
        const val MAX_ACTIVITY_LIMIT = 999
        const val HTTP_UNAVAILABLE = 503
    }
}

private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            },
        )
    }
