package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapApi
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapEvent
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapHttpClient
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapModel
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapSettings
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Monotonic milliseconds shared by the ViewModel and the UI's elapsed-time ticker. */
object LlamaSwapClock {
    fun nowMs(): Long = System.nanoTime() / 1_000_000
}

/**
 * Load/unload requests run here, not in viewModelScope: a load blocks until the model is ready
 * and cancelling the HTTP call (e.g. by navigating away) could abort it server-side.
 */
object LlamaSwapActionScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO)

sealed interface LlamaSwapConnection {
    /** Not streaming (screen not visible). */
    data object Idle : LlamaSwapConnection

    data object Connecting : LlamaSwapConnection

    data object Live : LlamaSwapConnection

    /** The stream dropped or failed; next attempt at [retryAtMs] ([LlamaSwapClock] time). */
    data class Retrying(
        val attempt: Int,
        val retryAtMs: Long,
        val reason: String?,
    ) : LlamaSwapConnection
}

data class PendingConfirm(
    val action: LlamaSwapAction,
    val evicted: List<LlamaSwapModel>,
)

data class LlamaSwapActionError(
    val action: LlamaSwapAction,
    val message: String,
)

data class LlamaSwapModelsUiState(
    val baseUrl: String = "",
    val models: List<LlamaSwapModel> = emptyList(),
    /** True once any model list (event or REST snapshot) has been received from this server. */
    val hasSnapshot: Boolean = false,
    val isRefreshing: Boolean = false,
    /** Last snapshot failure; only surfaced while no snapshot is available. */
    val loadError: String? = null,
    val connection: LlamaSwapConnection = LlamaSwapConnection.Idle,
    val pendingLoads: Set<String> = emptySet(),
    val pendingUnloads: Set<String> = emptySet(),
    val unloadAllPending: Boolean = false,
    /** When each loading model was first seen starting (or requested), in [LlamaSwapClock] time. */
    val loadingSinceMs: Map<String, Long> = emptyMap(),
    val confirm: PendingConfirm? = null,
    val actionError: LlamaSwapActionError? = null,
    /** The Playground is streaming: load/unload actions are locked so they can't kill the reply. */
    val playgroundStreaming: Boolean = false,
) {
    /** Local models to list: unlisted ones only while they are occupying the group. */
    val visibleModels: List<LlamaSwapModel>
        get() = models.filter { !it.isPeer && (!it.unlisted || it.isActive) }

    val anyActive: Boolean get() = models.any { it.isActive && !it.isPeer }

    fun displayName(id: String): String = models.firstOrNull { it.id == id }?.displayName ?: id
}

class LlamaSwapModelsViewModel(
    private val settings: LlamaSwapSettings,
    private val api: LlamaSwapApi = LlamaSwapHttpClient(baseUrl = { settings.baseUrl.value }),
    private val actionScope: CoroutineScope = LlamaSwapActionScope,
    private val clock: () -> Long = LlamaSwapClock::nowMs,
    private val streamLock: PlaygroundStreamLock = PlaygroundStreamLock.shared,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            LlamaSwapModelsUiState(baseUrl = settings.baseUrl.value, playgroundStreaming = streamLock.streaming.value),
        )
    val uiState: StateFlow<LlamaSwapModelsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            streamLock.streaming.collect { on ->
                _uiState.update { it.copy(playgroundStreaming = on, confirm = if (on) null else it.confirm) }
            }
        }
    }

    private val _activityEvents =
        MutableSharedFlow<Long>(
            extraBufferCapacity = ACTIVITY_EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * Ids of new activity log rows announced on the event stream. Shared with the Activity tab so
     * the screen keeps a single /api/events connection.
     */
    val activityEvents: SharedFlow<Long> = _activityEvents.asSharedFlow()

    private var streamJob: Job? = null

    /** Starts the live event stream. Call when the screen reaches STARTED. */
    fun start() {
        if (streamJob?.isActive == true) return
        streamJob =
            viewModelScope.launch {
                settings.baseUrl.collectLatest { url ->
                    if (url != _uiState.value.baseUrl) {
                        // Another server: nothing from the old one applies.
                        _uiState.value =
                            LlamaSwapModelsUiState(baseUrl = url, playgroundStreaming = streamLock.streaming.value)
                    }
                    if (LlamaSwapSettings.isConfigured(url)) runStream()
                }
            }
    }

    /** Stops the stream. Call when the screen drops below STARTED. */
    fun stop() {
        streamJob?.cancel()
        streamJob = null
        _uiState.update { it.copy(connection = LlamaSwapConnection.Idle) }
    }

    private suspend fun runStream() {
        var attempt = 0
        while (true) {
            _uiState.update { it.copy(connection = LlamaSwapConnection.Connecting) }
            var failure: String? = null
            try {
                api.events().collect { event ->
                    attempt = 0
                    if (_uiState.value.connection != LlamaSwapConnection.Live) {
                        _uiState.update { it.copy(connection = LlamaSwapConnection.Live) }
                    }
                    when (event) {
                        is LlamaSwapEvent.ModelStatus -> applyModels(event.models)
                        is LlamaSwapEvent.Activity -> _activityEvents.tryEmit(event.id)
                        is LlamaSwapEvent.Other -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: e::class.simpleName
            }
            attempt++
            val wait = backoffMs(attempt)
            _uiState.update {
                it.copy(connection = LlamaSwapConnection.Retrying(attempt, clock() + wait, failure))
            }
            // Keep the list usable through a REST snapshot while the stream is down.
            fetchSnapshot()
            delay(wait)
        }
    }

    /** Pull-to-refresh / retry: GET /v1/models + /running. */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            fetchSnapshot()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    private suspend fun fetchSnapshot() {
        try {
            val fresh = api.models()
            val current = _uiState.value.models.associateBy { it.id }
            // The REST listing lacks loading progress; keep what the stream last said for unchanged states.
            applyModels(
                fresh.map { m ->
                    val prev = current[m.id]
                    if (prev != null && prev.state == m.state) {
                        m.copy(
                            loadingProgress = prev.loadingProgress,
                            loadingMessage = prev.loadingMessage,
                            uptimeMs = prev.uptimeMs,
                            unlisted = prev.unlisted,
                        )
                    } else {
                        m
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(loadError = e.message ?: e::class.simpleName) }
        }
    }

    private fun applyModels(models: List<LlamaSwapModel>) {
        val now = clock()
        _uiState.update { state ->
            val since = state.loadingSinceMs.toMutableMap()
            val byId = models.associateBy { it.id }
            for (m in models) {
                if (m.state == LlamaSwapState.STARTING) since.putIfAbsent(m.id, now)
            }
            since.keys.retainAll { id ->
                byId[id]?.state == LlamaSwapState.STARTING || id in state.pendingLoads
            }
            state.copy(models = models, hasSnapshot = true, loadError = null, loadingSinceMs = since)
        }
    }

    /** Fires [action] at once unless it would stop a running model, in which case it asks first. */
    fun request(action: LlamaSwapAction) {
        if (streamLock.streaming.value) return
        val evicted = EvictionPolicy.evictedBy(action, _uiState.value.models)
        if (evicted.isEmpty()) {
            perform(action)
        } else {
            _uiState.update { it.copy(confirm = PendingConfirm(action, evicted)) }
        }
    }

    fun confirm() {
        val pending = _uiState.value.confirm ?: return
        if (streamLock.streaming.value) {
            _uiState.update { it.copy(confirm = null) }
            return
        }
        // State may have moved while the dialog was open: never stop a model the user wasn't shown.
        val nowEvicted = EvictionPolicy.evictedBy(pending.action, _uiState.value.models)
        val shown = pending.evicted.map { it.id }.toSet()
        if (nowEvicted.any { it.id !in shown }) {
            _uiState.update { it.copy(confirm = PendingConfirm(pending.action, nowEvicted)) }
            return
        }
        _uiState.update { it.copy(confirm = null) }
        perform(pending.action)
    }

    fun dismissConfirm() {
        _uiState.update { it.copy(confirm = null) }
    }

    fun clearActionError() {
        _uiState.update { it.copy(actionError = null) }
    }

    private fun perform(action: LlamaSwapAction) {
        val state = _uiState.value
        when (action) {
            is LlamaSwapAction.Load -> {
                if (action.modelId in state.pendingLoads) return
                _uiState.update {
                    it.copy(
                        pendingLoads = it.pendingLoads + action.modelId,
                        loadingSinceMs = it.loadingSinceMs + (action.modelId to clock()),
                    )
                }
            }

            is LlamaSwapAction.Unload -> {
                if (action.modelId in state.pendingUnloads) return
                _uiState.update { it.copy(pendingUnloads = it.pendingUnloads + action.modelId) }
            }

            LlamaSwapAction.UnloadAll -> {
                if (state.unloadAllPending) return
                _uiState.update { it.copy(unloadAllPending = true) }
            }
        }
        actionScope.launch {
            try {
                when (action) {
                    is LlamaSwapAction.Load -> api.load(action.modelId)
                    is LlamaSwapAction.Unload -> api.unload(action.modelId)
                    LlamaSwapAction.UnloadAll -> api.unloadAll()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(actionError = LlamaSwapActionError(action, e.message ?: e::class.simpleName.orEmpty()))
                }
            } finally {
                _uiState.update {
                    when (action) {
                        is LlamaSwapAction.Load -> {
                            val stillStarting =
                                it.models.any { m -> m.id == action.modelId && m.state == LlamaSwapState.STARTING }
                            it.copy(
                                pendingLoads = it.pendingLoads - action.modelId,
                                loadingSinceMs =
                                    if (stillStarting) it.loadingSinceMs else it.loadingSinceMs - action.modelId,
                            )
                        }

                        is LlamaSwapAction.Unload -> {
                            it.copy(pendingUnloads = it.pendingUnloads - action.modelId)
                        }

                        LlamaSwapAction.UnloadAll -> {
                            it.copy(unloadAllPending = false)
                        }
                    }
                }
            }
            // Without a live stream nothing else would tell us the outcome.
            if (_uiState.value.connection != LlamaSwapConnection.Live) fetchSnapshot()
        }
    }

    companion object {
        private const val BACKOFF_BASE_MS = 1_000L
        private const val BACKOFF_MAX_MS = 30_000L
        private const val ACTIVITY_EVENT_BUFFER = 64

        /** 1s, 2s, 4s … capped at 30s, for the [attempt]-th consecutive failure (1-based). */
        fun backoffMs(attempt: Int): Long {
            val exp = (attempt - 1).coerceIn(0, 15)
            return (BACKOFF_BASE_MS shl exp).coerceAtMost(BACKOFF_MAX_MS)
        }
    }
}
