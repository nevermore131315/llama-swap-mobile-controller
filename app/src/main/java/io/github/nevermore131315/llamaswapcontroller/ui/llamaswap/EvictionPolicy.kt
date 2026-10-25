package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapModel

/** A user-requested change to what llama-swap is running. */
sealed interface LlamaSwapAction {
    data class Load(
        val modelId: String,
    ) : LlamaSwapAction

    data class Unload(
        val modelId: String,
    ) : LlamaSwapAction

    data object UnloadAll : LlamaSwapAction
}

/**
 * Which running models an action would stop.
 *
 * All configured models share one llama-swap group with `swap: true, exclusive: true`, so
 * loading any model evicts every other active one, and unloading kills the target. Peer
 * models are remote (another llama-swap) and are never evicted by local actions.
 */
object EvictionPolicy {
    fun evictedBy(
        action: LlamaSwapAction,
        models: List<LlamaSwapModel>,
    ): List<LlamaSwapModel> {
        val active = models.filter { it.isActive && !it.isPeer }
        return when (action) {
            is LlamaSwapAction.Load -> active.filter { it.id != action.modelId }
            is LlamaSwapAction.Unload -> active.filter { it.id == action.modelId }
            LlamaSwapAction.UnloadAll -> active
        }
    }
}
