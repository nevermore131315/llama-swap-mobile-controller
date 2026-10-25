package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapActivityApi
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapActivityEntry
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapActivityStats
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapHttpClient
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapSettings
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.mergeActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How the Activity tab is currently kept up to date. */
enum class ActivityUpdateMode {
    /** Not running (tab hidden or screen stopped). */
    PAUSED,

    /** New rows are announced on /api/events and fetched as they arrive. */
    LIVE,

    /** The event stream is down: GET polling every [LlamaSwapActivityViewModel.POLL_INTERVAL_MS]. */
    POLLING,
}

data class LlamaSwapActivityUiState(
    val baseUrl: String = "",
    /** Newest first, at most [LlamaSwapActivityViewModel.MAX_ROWS]. */
    val entries: List<LlamaSwapActivityEntry> = emptyList(),
    val stats: LlamaSwapActivityStats? = null,
    /** True once activity has been fetched from this server at least once. */
    val hasSnapshot: Boolean = false,
    val isRefreshing: Boolean = false,
    /** Last fetch failure; cleared by the next successful fetch. */
    val loadError: String? = null,
    val mode: ActivityUpdateMode = ActivityUpdateMode.PAUSED,
    val expanded: Set<Long> = emptySet(),
)

/**
 * Activity log for the Llama-swap screen. Only issues GET requests.
 *
 * Live updates reuse the Models tab's single /api/events connection: llama-swap announces each
 * stored row as an `activity` message carrying only its id, so each announcement triggers a
 * `?min_id=` fetch of the rows newer than the newest one shown. While that stream is down the
 * tab polls instead.
 */
class LlamaSwapActivityViewModel(
    private val settings: LlamaSwapSettings,
    private val api: LlamaSwapActivityApi = LlamaSwapHttpClient(baseUrl = { settings.baseUrl.value }),
    private val activityEvents: Flow<Long> = emptyFlow(),
    private val connection: Flow<LlamaSwapConnection> = flowOf(LlamaSwapConnection.Idle),
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LlamaSwapActivityUiState(baseUrl = settings.baseUrl.value))
    val uiState: StateFlow<LlamaSwapActivityUiState> = _uiState.asStateFlow()

    private var job: Job? = null
    private val fetchLock = Mutex()

    /** Starts fetching and live updates. Call while the Activity tab is visible. */
    fun start() {
        if (job?.isActive == true) return
        job =
            viewModelScope.launch {
                settings.baseUrl.collectLatest { url ->
                    if (url != _uiState.value.baseUrl) {
                        _uiState.value = LlamaSwapActivityUiState(baseUrl = url)
                    }
                    if (LlamaSwapSettings.isConfigured(url)) follow()
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        _uiState.update { it.copy(mode = ActivityUpdateMode.PAUSED) }
    }

    private suspend fun follow() =
        coroutineScope {
            val wake = Channel<Unit>(Channel.CONFLATED)
            launch { activityEvents.collect { wake.trySend(Unit) } }
            launch {
                var first = true
                connection.map { it == LlamaSwapConnection.Live }.distinctUntilChanged().collectLatest { live ->
                    _uiState.update {
                        it.copy(mode = if (live) ActivityUpdateMode.LIVE else ActivityUpdateMode.POLLING)
                    }
                    // Rows may have been stored while the stream was down; catch up once it is back.
                    if (live && !first) wake.trySend(Unit)
                    first = false
                    while (!live) {
                        delay(pollIntervalMs)
                        wake.trySend(Unit)
                    }
                }
            }
            sync(full = true)
            for (signal in wake) {
                // Coalesce bursts (parallel requests finishing together) into one fetch.
                delay(EVENT_COALESCE_MS)
                sync(full = false)
            }
        }

    /** Pull-to-refresh / retry: full reload of stats and the newest rows. */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                sync(full = true)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun toggleExpanded(id: Long) {
        _uiState.update { it.copy(expanded = if (id in it.expanded) it.expanded - id else it.expanded + id) }
    }

    private suspend fun sync(full: Boolean) =
        fetchLock.withLock {
            try {
                val stats = api.activityStats()
                val state = _uiState.value
                val newestId = state.entries.maxOfOrNull { it.id } ?: 0
                // total_requests is MAX(id). Below what we show means llama-swap restarted with an
                // in-memory store and its ids started over: everything shown is gone.
                val restarted = stats.totalRequests < newestId
                val incremental = !full && state.hasSnapshot && newestId > 0 && !restarted
                val page = api.activity(limit = MAX_ROWS, minId = if (incremental) newestId + 1 else 0)
                _uiState.update {
                    val entries = mergeActivity(if (incremental) it.entries else emptyList(), page.data, MAX_ROWS)
                    val ids = entries.map { e -> e.id }.toSet()
                    it.copy(
                        entries = entries,
                        stats = stats,
                        hasSnapshot = true,
                        loadError = null,
                        expanded = it.expanded.filterTo(mutableSetOf()) { id -> id in ids },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(loadError = e.message ?: e::class.simpleName) }
            }
        }

    companion object {
        /** Rows kept and shown; llama-swap's in-memory store keeps 1000 by default. */
        const val MAX_ROWS = 100
        const val POLL_INTERVAL_MS = 15_000L
        private const val EVENT_COALESCE_MS = 300L
    }
}
