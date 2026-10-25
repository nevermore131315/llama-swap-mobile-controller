package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapApi
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapHttpClient
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapSettings
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LlamaSwapTestResult {
    data class Success(
        val version: LlamaSwapVersion,
    ) : LlamaSwapTestResult

    data class Failure(
        val message: String,
    ) : LlamaSwapTestResult
}

data class LlamaSwapSettingsUiState(
    val draft: String = "",
    val saved: String = "",
    val isTesting: Boolean = false,
    val testResult: LlamaSwapTestResult? = null,
    val justSaved: Boolean = false,
) {
    val normalizedDraft: String? get() = LlamaSwapSettings.normalize(draft)
    val isDraftValid: Boolean get() = normalizedDraft != null
    val canSave: Boolean get() = normalizedDraft != null && normalizedDraft != saved
}

class LlamaSwapSettingsViewModel(
    private val settings: LlamaSwapSettings,
    private val apiFor: (String) -> LlamaSwapApi = { url -> LlamaSwapHttpClient(baseUrl = { url }) },
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(LlamaSwapSettingsUiState(draft = settings.baseUrl.value, saved = settings.baseUrl.value))
    val uiState: StateFlow<LlamaSwapSettingsUiState> = _uiState.asStateFlow()

    private var testJob: Job? = null

    fun onDraftChange(value: String) {
        _uiState.update { it.copy(draft = value, testResult = null, justSaved = false) }
    }

    fun save() {
        val url = _uiState.value.normalizedDraft ?: return
        settings.setBaseUrl(url)
        _uiState.update { it.copy(draft = url, saved = url, justSaved = true) }
    }

    /** GET /api/version against the URL in the field (saved or not). */
    fun testConnection() {
        val url = _uiState.value.normalizedDraft ?: return
        testJob?.cancel()
        _uiState.update { it.copy(isTesting = true, testResult = null) }
        testJob =
            viewModelScope.launch {
                val result =
                    try {
                        LlamaSwapTestResult.Success(apiFor(url).version())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LlamaSwapTestResult.Failure(e.message ?: e::class.simpleName.orEmpty())
                    }
                _uiState.update { it.copy(isTesting = false, testResult = result) }
            }
    }
}
