package io.github.nevermore131315.llamaswapcontroller.data.llamaswap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire + domain types for a llama-swap server (https://github.com/mostlygeek/llama-swap).
 *
 * llama-swap is a separate server from the Hermes Agent dashboard: it has its own base URL
 * (see [LlamaSwapSettings]), no auth, and its own JSON conventions (mixed camelCase and
 * snake_case), so it uses [LlamaSwapJson] rather than the dashboard's SnakeCase Json.
 */
val LlamaSwapJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

/** GET /api/version. */
@Serializable
data class LlamaSwapVersion(
    val version: String = "",
    val commit: String = "",
    @SerialName("build_date") val buildDate: String = "",
)

/** GET /v1/models. */
@Serializable
internal data class LlamaSwapModelList(
    val data: List<LlamaSwapModelListEntry> = emptyList(),
)

@Serializable
internal data class LlamaSwapModelListEntry(
    val id: String = "",
    val name: String = "",
    val description: String = "",
)

/** GET /running. */
@Serializable
internal data class LlamaSwapRunningList(
    val running: List<LlamaSwapRunningEntry> = emptyList(),
)

@Serializable
internal data class LlamaSwapRunningEntry(
    val model: String = "",
    val state: String = "",
    val name: String = "",
)

/**
 * One model as reported by the `modelStatus` SSE message (the `apiModel` Go struct in
 * llama-swap's internal/server/apigroup.go). Also built from the /v1/models + /running
 * fallback, where the loading/uptime fields are unavailable.
 */
@Serializable
data class LlamaSwapModel(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val state: String = LlamaSwapState.STOPPED,
    val unlisted: Boolean = false,
    @SerialName("peerID") val peerId: String = "",
    /** Fraction loaded (0..1) the upstream last reported; null when not reported. */
    val loadingProgress: Double? = null,
    /** Loading step the upstream last reported; only set while starting. */
    val loadingMessage: String = "",
    /** How long the model has been ready, as of this payload. 0 when not ready. */
    val uptimeMs: Long = 0,
) {
    /** Friendly alias for display; falls back to the id when the alias is blank. */
    val displayName: String get() = name.ifBlank { id }

    /** True while the process occupies the swap group (running, ready or starting). */
    val isActive: Boolean get() = LlamaSwapState.isActive(state)

    val isPeer: Boolean get() = peerId.isNotEmpty()
}

/** Process states from llama-swap internal/process/process.go. */
object LlamaSwapState {
    const val STOPPED = "stopped"
    const val STARTING = "starting"
    const val READY = "ready"
    const val STOPPING = "stopping"
    const val SHUTDOWN = "shutdown"

    private val INACTIVE = setOf(STOPPED, STOPPING, SHUTDOWN, "")

    /** Unknown non-empty states are treated as active so eviction warnings err on the safe side. */
    fun isActive(state: String): Boolean = state.lowercase() !in INACTIVE
}

/** Joins the /v1/models listing with /running into the same shape the SSE stream delivers. */
internal fun mergeModelsAndRunning(
    models: List<LlamaSwapModelListEntry>,
    running: List<LlamaSwapRunningEntry>,
): List<LlamaSwapModel> {
    val states = running.associate { it.model to it.state }
    val listed =
        models.map { m ->
            LlamaSwapModel(
                id = m.id,
                name = m.name,
                description = m.description,
                state = states[m.id]?.ifBlank { null } ?: LlamaSwapState.STOPPED,
            )
        }
    // A running model missing from /v1/models (unlisted) must still be known for eviction checks.
    val listedIds = listed.map { it.id }.toSet()
    val extra =
        running
            .filter { it.model !in listedIds }
            .map { LlamaSwapModel(id = it.model, name = it.name, state = it.state, unlisted = true) }
    return listed + extra
}
