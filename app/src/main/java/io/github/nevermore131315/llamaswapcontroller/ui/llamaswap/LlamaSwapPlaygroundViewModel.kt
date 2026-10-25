package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapChatApi
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapChatDelta
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapChatHttpClient
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapChatMessage
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapModel
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapSettings
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PlaygroundRole { USER, ASSISTANT }

enum class PlaygroundStatus { STREAMING, DONE, STOPPED, ERROR }

data class PlaygroundMessage(
    val id: Long,
    val role: PlaygroundRole,
    val content: String = "",
    val reasoning: String = "",
    val status: PlaygroundStatus = PlaygroundStatus.DONE,
    val error: String? = null,
    /** Model that produced an assistant message. */
    val modelId: String = "",
    /** When the request was sent ([LlamaSwapClock] time), for the loading timer. */
    val startedAtMs: Long = 0,
) {
    /** Nothing received yet: the model is still loading or prompt processing. */
    val awaitingFirstToken: Boolean
        get() = status == PlaygroundStatus.STREAMING && content.isEmpty() && reasoning.isEmpty()
}

/** A send that would evict the models in [evicted]; waits for the user to confirm. */
data class PlaygroundConfirm(
    val modelId: String,
    val text: String,
    val evicted: List<LlamaSwapModel>,
)

data class PlaygroundUiState(
    val models: List<LlamaSwapModel> = emptyList(),
    /** The user's explicit pick; null follows whatever is running. */
    val pickedModelId: String? = null,
    val messages: List<PlaygroundMessage> = emptyList(),
    val input: String = "",
    val confirm: PlaygroundConfirm? = null,
) {
    /** Models offered in the picker: local, and unlisted ones only while they are running. */
    val pickable: List<LlamaSwapModel>
        get() = models.filter { !it.isPeer && (!it.unlisted || it.isActive) }

    /** The picked model if still offered, else the ready/starting one, else the first listed. */
    val selectedModelId: String?
        get() {
            val offered = pickable
            pickedModelId?.let { id -> if (offered.any { it.id == id }) return id }
            return offered.firstOrNull { it.state == LlamaSwapState.READY }?.id
                ?: offered.firstOrNull { it.isActive }?.id
                ?: offered.firstOrNull()?.id
        }

    val selectedModel: LlamaSwapModel? get() = selectedModelId?.let { id -> models.firstOrNull { it.id == id } }

    val isStreaming: Boolean get() = messages.any { it.status == PlaygroundStatus.STREAMING }

    val canSend: Boolean get() = !isStreaming && input.isNotBlank() && selectedModelId != null

    fun displayName(id: String): String = models.firstOrNull { it.id == id }?.displayName ?: id
}

/**
 * Basic streaming chat against llama-swap. History lives only here (no persistence).
 *
 * [models] is the Models tab's live list, so the picker and the eviction check see the same state.
 */
class LlamaSwapPlaygroundViewModel(
    settings: LlamaSwapSettings,
    models: Flow<List<LlamaSwapModel>>,
    private val api: LlamaSwapChatApi = LlamaSwapChatHttpClient(baseUrl = { settings.baseUrl.value }),
    private val lock: PlaygroundStreamLock = PlaygroundStreamLock.shared,
    private val clock: () -> Long = LlamaSwapClock::nowMs,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlaygroundUiState())
    val uiState: StateFlow<PlaygroundUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null
    private var nextId = 1L

    init {
        viewModelScope.launch { models.collect { list -> _uiState.update { it.copy(models = list) } } }
    }

    fun setInput(text: String) {
        _uiState.update { it.copy(input = text) }
    }

    fun pickModel(id: String) {
        if (_uiState.value.isStreaming) return
        _uiState.update { it.copy(pickedModelId = id) }
    }

    /** Sends the input to the selected model, asking first if that would stop a running model. */
    fun send() {
        val state = _uiState.value
        if (!state.canSend) return
        val modelId = state.selectedModelId ?: return
        val text = state.input.trim()
        val evicted = EvictionPolicy.evictedBy(LlamaSwapAction.Load(modelId), state.models)
        if (evicted.isEmpty()) {
            start(modelId, text)
        } else {
            _uiState.update { it.copy(confirm = PlaygroundConfirm(modelId, text, evicted)) }
        }
    }

    fun confirmSend() {
        val pending = _uiState.value.confirm ?: return
        // State may have moved while the dialog was open: never stop a model the user wasn't shown.
        val nowEvicted = EvictionPolicy.evictedBy(LlamaSwapAction.Load(pending.modelId), _uiState.value.models)
        val shown = pending.evicted.map { it.id }.toSet()
        if (nowEvicted.any { it.id !in shown }) {
            _uiState.update { it.copy(confirm = pending.copy(evicted = nowEvicted)) }
            return
        }
        _uiState.update { it.copy(confirm = null) }
        if (_uiState.value.isStreaming) return
        start(pending.modelId, pending.text)
    }

    fun dismissConfirm() {
        _uiState.update { it.copy(confirm = null) }
    }

    /** Cancels the in-flight request; the partial reply is kept and marked stopped. */
    fun stop() {
        streamJob?.cancel()
    }

    /** Stops any reply and clears the conversation. */
    fun newChat() {
        stop()
        streamJob = null
        _uiState.update { it.copy(messages = emptyList(), confirm = null) }
    }

    private fun start(
        modelId: String,
        text: String,
    ) {
        val now = clock()
        val user = PlaygroundMessage(id = nextId++, role = PlaygroundRole.USER, content = text)
        val reply =
            PlaygroundMessage(
                id = nextId++,
                role = PlaygroundRole.ASSISTANT,
                status = PlaygroundStatus.STREAMING,
                modelId = modelId,
                startedAtMs = now,
            )
        val history = historyFor(_uiState.value.messages) + LlamaSwapChatMessage("user", text)
        _uiState.update { it.copy(messages = it.messages + user + reply, input = "") }
        lock.acquire()
        streamJob =
            viewModelScope.launch {
                try {
                    api.streamChat(modelId, history).collect { delta ->
                        updateReply(reply.id) {
                            when (delta) {
                                is LlamaSwapChatDelta.Content -> it.copy(content = it.content + delta.text)
                                is LlamaSwapChatDelta.Reasoning -> it.copy(reasoning = it.reasoning + delta.text)
                            }
                        }
                    }
                    updateReply(reply.id) { it.copy(status = PlaygroundStatus.DONE) }
                } catch (e: CancellationException) {
                    updateReply(reply.id) { it.copy(status = PlaygroundStatus.STOPPED) }
                    throw e
                } catch (e: Exception) {
                    updateReply(reply.id) {
                        it.copy(status = PlaygroundStatus.ERROR, error = e.message ?: e::class.simpleName)
                    }
                } finally {
                    lock.release()
                }
            }
    }

    private fun updateReply(
        id: Long,
        change: (PlaygroundMessage) -> PlaygroundMessage,
    ) {
        _uiState.update { state ->
            state.copy(
                messages =
                    state.messages.map { m ->
                        // Only a streaming reply changes; a cleared or finished one is left alone.
                        if (m.id == id && m.status == PlaygroundStatus.STREAMING) change(m) else m
                    },
            )
        }
    }

    companion object {
        /**
         * Prior turns to send: each user message with its reply's text (partial replies included,
         * as that is what the user saw). A turn whose reply has no text (failed or stopped before
         * the first token) is left out so roles keep alternating, which strict chat templates
         * require. Reasoning is never sent back.
         */
        internal fun historyFor(messages: List<PlaygroundMessage>): List<LlamaSwapChatMessage> {
            val out = mutableListOf<LlamaSwapChatMessage>()
            messages.forEachIndexed { i, m ->
                if (m.role != PlaygroundRole.USER) return@forEachIndexed
                val reply = messages.getOrNull(i + 1)?.takeIf { it.role == PlaygroundRole.ASSISTANT }
                if (reply == null || reply.content.isBlank()) return@forEachIndexed
                out += LlamaSwapChatMessage("user", m.content)
                out += LlamaSwapChatMessage("assistant", reply.content)
            }
            return out
        }
    }
}
