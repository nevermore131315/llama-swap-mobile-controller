package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapLogSource
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapLogsApi
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapLogsHttpClient
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LlamaSwapLogsUiState(
    val baseUrl: String = "",
    val source: LlamaSwapLogSource = LlamaSwapLogSource.ALL,
    /** Oldest first, at most [LlamaSwapLogsViewModel.MAX_LINES]. */
    val lines: List<String> = emptyList(),
    val connected: Boolean = false,
    /** Last stream failure; cleared once the stream reconnects. */
    val error: String? = null,
    /** When true the list stays pinned to the newest line. */
    val follow: Boolean = true,
)

/**
 * Live tail of llama-swap's logs. One stream at a time; switching source restarts it and clears
 * the buffer (llama-swap replays its own history on connect). Reconnects with a fixed back-off.
 */
class LlamaSwapLogsViewModel(
    private val settings: LlamaSwapSettings,
    private val api: LlamaSwapLogsApi = LlamaSwapLogsHttpClient(baseUrl = { settings.baseUrl.value }),
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LlamaSwapLogsUiState(baseUrl = settings.baseUrl.value))
    val uiState: StateFlow<LlamaSwapLogsUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    /** Starts following the selected source. Call while the Logs tab is visible. */
    fun start() {
        if (job?.isActive == true) return
        job =
            viewModelScope.launch {
                if (!LlamaSwapSettings.isConfigured(settings.baseUrl.value)) return@launch
                while (true) {
                    val source = _uiState.value.source
                    _uiState.update { it.copy(lines = emptyList(), baseUrl = settings.baseUrl.value) }
                    try {
                        api.logLines(source).collect { line ->
                            _uiState.update { s ->
                                val next =
                                    if (s.lines.size >=
                                        MAX_LINES
                                    ) {
                                        s.lines.drop(s.lines.size - MAX_LINES + 1)
                                    } else {
                                        s.lines
                                    }
                                s.copy(lines = next + line, connected = true, error = null)
                            }
                        }
                        _uiState.update { it.copy(connected = false) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _uiState.update { it.copy(connected = false, error = e.message ?: e.javaClass.simpleName) }
                    }
                    delay(retryDelayMs)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        _uiState.update { it.copy(connected = false) }
    }

    fun selectSource(source: LlamaSwapLogSource) {
        if (source == _uiState.value.source) return
        _uiState.update { it.copy(source = source, lines = emptyList(), follow = true) }
        if (job != null) {
            stop()
            start()
        }
    }

    fun setFollow(follow: Boolean) {
        _uiState.update { it.copy(follow = follow) }
    }

    fun clear() {
        _uiState.update { it.copy(lines = emptyList()) }
    }

    companion object {
        const val MAX_LINES = 2000
        const val RETRY_DELAY_MS = 3_000L
    }
}
