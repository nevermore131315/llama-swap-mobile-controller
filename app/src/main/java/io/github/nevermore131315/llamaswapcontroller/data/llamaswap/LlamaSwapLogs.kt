package io.github.nevermore131315.llamaswapcontroller.data.llamaswap

import io.github.nevermore131315.llamaswapcontroller.data.remote.OkHttpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Which llama-swap log stream to follow. Maps to GET /logs/stream[/{path}]. */
enum class LlamaSwapLogSource(
    val pathSegment: String?,
) {
    ALL(null),
    PROXY("proxy"),
    UPSTREAM("upstream"),
    HTTP("http"),
}

/** Live log tail. Read-only: only GET requests. */
interface LlamaSwapLogsApi {
    /**
     * Streams log lines from [source]. llama-swap sends its buffered history first, then new
     * lines as they happen. Completes when the server closes the stream; errors on failure.
     */
    fun logLines(source: LlamaSwapLogSource): Flow<String>
}

/**
 * OkHttp client for llama-swap's plain-text log streams. Like [LlamaSwapHttpClient] it uses the
 * external client so llama-swap never receives dashboard credentials.
 */
class LlamaSwapLogsHttpClient(
    private val baseUrl: () -> String,
    http: OkHttpClient = OkHttpProvider.external,
) : LlamaSwapLogsApi {
    // Log streams go quiet while nothing happens, so reads must not time out.
    private val stream: OkHttpClient =
        http
            .newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    internal fun url(source: LlamaSwapLogSource): HttpUrl {
        val base =
            baseUrl().trim().toHttpUrlOrNull()
                ?: throw IOException("Invalid llama-swap URL: ${baseUrl()}")
        val builder = base.newBuilder().addPathSegment("logs").addPathSegment("stream")
        source.pathSegment?.let { builder.addPathSegment(it) }
        return builder.build()
    }

    override fun logLines(source: LlamaSwapLogSource): Flow<String> =
        callbackFlow {
            val call =
                stream.newCall(
                    Request
                        .Builder()
                        .url(url(source))
                        .header("Accept", "text/plain")
                        .get()
                        .build(),
                )
            launch(Dispatchers.IO) {
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            throw LlamaSwapHttpException(response.code, "HTTP ${response.code}")
                        }
                        val source = response.body.source()
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            send(line)
                        }
                    }
                    close()
                } catch (e: IOException) {
                    close(e)
                }
            }
            awaitClose { call.cancel() }
        }
}
