package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * Whether the Playground is streaming a reply. While it is, the Models tab must not load or
 * unload anything: in the exclusive swap group that would kill the model mid-answer.
 *
 * Only guards this app's own actions; other llama-swap clients can still swap the model.
 */
class PlaygroundStreamLock {
    private val holders = MutableStateFlow(0)
    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    fun acquire() {
        _streaming.value = holders.updateAndGet { it + 1 } > 0
    }

    fun release() {
        _streaming.value = holders.updateAndGet { (it - 1).coerceAtLeast(0) } > 0
    }

    companion object {
        /** The app-wide instance shared by the Playground and Models view models. */
        val shared = PlaygroundStreamLock()
    }
}
