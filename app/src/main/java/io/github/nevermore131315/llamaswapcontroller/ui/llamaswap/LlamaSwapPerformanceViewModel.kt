package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapHttpClient
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapPerformanceApi
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapSettings
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.PerformanceHistory
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.mergePerformance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LlamaSwapPerformanceUiState(
    val baseUrl: String = "",
    val history: PerformanceHistory = PerformanceHistory(),
    /** null until the first successful response; false when llama-swap has monitoring disabled. */
    val enabled: Boolean? = null,
    val isRefreshing: Boolean = false,
    /** Last fetch failure; cleared by the next successful fetch. */
    val loadError: String? = null,
) {
    val hasSnapshot: Boolean get() = enabled != null
}

/**
 * GPU and system stats for the Llama-swap screen. Only issues GET requests.
 *
 * llama-swap has no performance event on /api/events, so this polls every [pollIntervalMs] while
 * the tab is visible. The first fetch downloads the server's whole buffer (about 1.5 MB for an
 * hour of 3 GPUs); later polls send `?after=<newest timestamp>` and receive only new samples.
 */
class LlamaSwapPerformanceViewModel(
    private val settings: LlamaSwapSettings,
    private val api: LlamaSwapPerformanceApi = LlamaSwapHttpClient(baseUrl = { settings.baseUrl.value }),
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LlamaSwapPerformanceUiState(baseUrl = settings.baseUrl.value))
    val uiState: StateFlow<LlamaSwapPerformanceUiState> = _uiState.asStateFlow()

    private var job: Job? = null
    private val fetchLock = Mutex()

    /** Starts polling. Call while the Performance tab is visible. */
    fun start() {
        if (job?.isActive == true) return
        job =
            viewModelScope.launch {
                settings.baseUrl.collectLatest { url ->
                    if (url != _uiState.value.baseUrl) {
                        _uiState.value = LlamaSwapPerformanceUiState(baseUrl = url)
                    }
                    if (!LlamaSwapSettings.isConfigured(url)) return@collectLatest
                    while (true) {
                        sync(full = false)
                        delay(pollIntervalMs)
                    }
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Pull-to-refresh / retry: reloads the full history. */
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

    private suspend fun sync(full: Boolean) =
        fetchLock.withLock {
            try {
                val state = _uiState.value
                val after = if (full || state.enabled != true) null else state.history.newestTimestamp
                val response = api.performance(after = after)
                _uiState.update {
                    if (!response.enabled) {
                        it.copy(enabled = false, history = PerformanceHistory(), loadError = null)
                    } else {
                        val base = if (after == null) PerformanceHistory() else it.history
                        it.copy(
                            enabled = true,
                            history = mergePerformance(base, response, WINDOW_MS),
                            loadError = null,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(loadError = e.message ?: e::class.simpleName) }
            }
        }

    companion object {
        const val POLL_INTERVAL_MS = 15_000L

        /** llama-swap's ring buffer holds one hour; keep the same span client-side. */
        const val WINDOW_MS = 60 * 60 * 1000L
    }
}
