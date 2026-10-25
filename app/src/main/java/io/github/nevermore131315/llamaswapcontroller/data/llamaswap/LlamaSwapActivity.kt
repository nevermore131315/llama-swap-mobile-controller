package io.github.nevermore131315.llamaswapcontroller.data.llamaswap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Wire types for llama-swap's activity log (`store.ActivityLogEntry`, `store.ActivityPage`
 * and `store.ActivityStats` in internal/store/activity.go). Every field has a default so a
 * missing or renamed field degrades to "unknown" instead of failing the whole page.
 *
 * Sentinels: llama-swap stores `-1` for speeds and draft counts the upstream did not report
 * (buildMetrics in internal/server/metrics.go), and `0` for rows with no response at all
 * (e.g. status 499, client disconnected). Use the `has…`/`…OrNull` accessors, not raw values.
 */
@Serializable
data class LlamaSwapActivityPage(
    val data: List<LlamaSwapActivityEntry> = emptyList(),
    val page: Int = 0,
    val limit: Int = 0,
    /** Rows matching the query in the store (not the server lifetime request count). */
    val total: Long = 0,
    @SerialName("total_pages") val totalPages: Int = 0,
)

@Serializable
data class LlamaSwapActivityEntry(
    val id: Long = 0,
    /** RFC 3339 with offset, possibly with fractional seconds (Go `time.Time`). */
    val timestamp: String = "",
    /**
     * Raw connection source as llama-swap recorded it: `ip:<addr>`, `xff:<addr>` (client-supplied
     * proxy header) or a Tailcat identity. Not a caller/profile identity: every local caller is
     * `ip:127.0.0.1`.
     */
    val src: String = "",
    val model: String = "",
    @SerialName("req_path") val reqPath: String = "",
    @SerialName("resp_content_type") val respContentType: String = "",
    @SerialName("resp_status_code") val respStatusCode: Int = 0,
    val tokens: LlamaSwapTokenMetrics = LlamaSwapTokenMetrics(),
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("has_capture") val hasCapture: Boolean = false,
    @SerialName("error_msg") val errorMsg: String = "",
    val metadata: Map<String, String> = emptyMap(),
) {
    /** Epoch millis of [timestamp], or null when it is missing or unparseable. */
    val epochMs: Long? get() = parseRfc3339Millis(timestamp)
}

@Serializable
data class LlamaSwapTokenMetrics(
    /** Prompt tokens served from the KV cache. Negative or 0 means none/unknown. */
    @SerialName("cache_tokens") val cacheTokens: Long = 0,
    @SerialName("draft_tokens") val draftTokens: Long = 0,
    @SerialName("draft_acc_tokens") val draftAccTokens: Long = 0,
    /** New (non-cached) prompt tokens processed; llama-server's `timings.prompt_n`. */
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    /** Prefill speed in tokens/second; <= 0 means not reported. */
    @SerialName("prompt_per_second") val promptPerSecond: Double = 0.0,
    /** Decode (generation) speed in tokens/second; <= 0 means not reported. */
    @SerialName("tokens_per_second") val tokensPerSecond: Double = 0.0,
) {
    val promptSpeedOrNull: Double? get() = promptPerSecond.takeIf { it > 0 }
    val genSpeedOrNull: Double? get() = tokensPerSecond.takeIf { it > 0 }
    val cacheTokensOrNull: Long? get() = cacheTokens.takeIf { it > 0 }

    /** Accepted/drafted fraction (0..1) for speculative decoding, or null when nothing was drafted. */
    val draftAcceptance: Double?
        get() = if (draftTokens > 0 && draftAccTokens >= 0) draftAccTokens.toDouble() / draftTokens else null

    /** True when the row carries no token counts at all (typical for 499 / failed requests). */
    val isEmpty: Boolean get() = inputTokens <= 0 && outputTokens <= 0 && cacheTokens <= 0
}

/** GET /api/metrics/stats. */
@Serializable
data class LlamaSwapActivityStats(
    /**
     * llama-swap computes this as `MAX(id)` over the store, i.e. requests recorded since its
     * activity ids started (they restart at 1 with an in-memory store after a restart).
     */
    @SerialName("total_requests") val totalRequests: Long = 0,
    @SerialName("total_input_tokens") val totalInputTokens: Long = 0,
    @SerialName("total_output_tokens") val totalOutputTokens: Long = 0,
    @SerialName("total_cache_tokens") val totalCacheTokens: Long = 0,
    /** Prefill speeds (prompt tokens/second); null when no row reported one. */
    @SerialName("prompt_histogram") val promptHistogram: LlamaSwapHistogram? = null,
    /** Decode speeds (generated tokens/second); null when no row reported one. */
    @SerialName("gen_histogram") val genHistogram: LlamaSwapHistogram? = null,
)

/** `store.HistogramData`: speeds binned into equal-width buckets plus percentiles, in tokens/second. */
@Serializable
data class LlamaSwapHistogram(
    val bins: List<Int> = emptyList(),
    val min: Double = 0.0,
    val max: Double = 0.0,
    val binSize: Double = 0.0,
    val p50: Double = 0.0,
    val p95: Double = 0.0,
    val p99: Double = 0.0,
)

/** HTTP status grouping for display. */
enum class LlamaSwapStatusKind {
    OK,

    /** 499: llama-swap's code for "client disconnected before response". */
    CLIENT_DISCONNECTED,
    ERROR,

    /** 0 / missing. */
    UNKNOWN,
    ;

    companion object {
        const val CLIENT_CLOSED_REQUEST = 499

        fun of(code: Int): LlamaSwapStatusKind =
            when {
                code in 200..299 -> OK
                code == CLIENT_CLOSED_REQUEST -> CLIENT_DISCONNECTED
                code <= 0 -> UNKNOWN
                else -> ERROR
            }
    }
}

internal fun parseRfc3339Millis(value: String): Long? {
    if (value.isBlank()) return null
    return try {
        OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

/**
 * Merges freshly fetched rows into the current list: deduplicated by id, newest (highest id)
 * first, capped at [limit].
 */
fun mergeActivity(
    current: List<LlamaSwapActivityEntry>,
    fresh: List<LlamaSwapActivityEntry>,
    limit: Int,
): List<LlamaSwapActivityEntry> =
    (fresh + current)
        .distinctBy { it.id }
        .sortedByDescending { it.id }
        .take(limit)
