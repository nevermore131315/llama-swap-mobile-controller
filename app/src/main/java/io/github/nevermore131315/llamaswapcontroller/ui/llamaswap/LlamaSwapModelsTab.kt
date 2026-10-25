package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nevermore131315.llamaswapcontroller.R
import androidx.compose.material3.AlertDialog
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapModel
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapState
import io.github.nevermore131315.llamaswapcontroller.theme.LocalAppStatusColors
import io.github.nevermore131315.llamaswapcontroller.theme.LocalSpacing
import io.github.nevermore131315.llamaswapcontroller.ui.common.EmptyState
import io.github.nevermore131315.llamaswapcontroller.ui.common.ErrorState
import io.github.nevermore131315.llamaswapcontroller.ui.common.SkeletonListState
import io.github.nevermore131315.llamaswapcontroller.ui.common.StatusBadge
import io.github.nevermore131315.llamaswapcontroller.ui.common.StatusBadgeType
import io.github.nevermore131315.llamaswapcontroller.ui.common.ToastEffect
import io.github.nevermore131315.llamaswapcontroller.ui.common.listContentPadding
import io.github.nevermore131315.llamaswapcontroller.ui.common.listItemSpacing
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun LlamaSwapModelsTab(
    state: LlamaSwapModelsUiState,
    onAction: (LlamaSwapAction) -> Unit,
    onConfirm: () -> Unit,
    onDismissConfirm: () -> Unit,
    onRetry: () -> Unit,
    onClearActionError: () -> Unit,
) {
    val now = rememberTickingNow(active = state.needsTicker())

    ToastEffect(
        toastMessage = state.actionError?.let { actionErrorText(state, it) },
        onClearToast = onClearActionError,
        isError = true,
    )
    state.confirm?.let { ConfirmDialog(state, it, onConfirm, onDismissConfirm) }

    Column(modifier = Modifier.fillMaxSize()) {
        ConnectionBar(state = state, now = now, onUnloadAll = { onAction(LlamaSwapAction.UnloadAll) })
        if (state.playgroundStreaming) PlaygroundLockNote()
        when {
            !state.hasSnapshot && state.loadError != null -> {
                ErrorState(
                    message = stringResource(R.string.llamaswap_error_load, state.baseUrl, state.loadError),
                    onRetry = onRetry,
                )
            }

            !state.hasSnapshot -> {
                SkeletonListState()
            }

            state.visibleModels.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.llamaswap_empty_title),
                    subtitle = stringResource(R.string.llamaswap_empty_desc, state.baseUrl),
                    icon = Icons.Filled.Memory,
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    items(state.visibleModels, key = { it.id }) { model ->
                        ModelCard(model = model, state = state, now = now, onAction = onAction)
                    }
                }
            }
        }
    }
}

private fun LlamaSwapModelsUiState.needsTicker(): Boolean =
    loadingSinceMs.isNotEmpty() || connection is LlamaSwapConnection.Retrying

/** Current [LlamaSwapClock] time, re-read every second while [active]. */
@Composable
internal fun rememberTickingNow(active: Boolean): Long {
    var now by remember { mutableLongStateOf(LlamaSwapClock.nowMs()) }
    LaunchedEffect(active) {
        now = LlamaSwapClock.nowMs()
        while (active) {
            delay(TICK_MS)
            now = LlamaSwapClock.nowMs()
        }
    }
    return now
}

private const val TICK_MS = 1_000L

@Composable
private fun ConnectionBar(
    state: LlamaSwapModelsUiState,
    now: Long,
    onUnloadAll: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val status = LocalAppStatusColors.current
    val connection = state.connection
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = spacing.md, end = spacing.sm, top = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (connection) {
            LlamaSwapConnection.Connecting -> {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }

            LlamaSwapConnection.Live -> {
                Icon(Icons.Filled.Sensors, null, tint = status.success, modifier = Modifier.size(18.dp))
            }

            LlamaSwapConnection.Idle -> {
                Icon(
                    Icons.Filled.PauseCircle,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            is LlamaSwapConnection.Retrying -> {
                Icon(Icons.Filled.CloudOff, null, tint = status.warning, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text =
                    when (connection) {
                        LlamaSwapConnection.Connecting -> {
                            stringResource(R.string.llamaswap_conn_connecting)
                        }

                        LlamaSwapConnection.Live -> {
                            stringResource(R.string.llamaswap_conn_live)
                        }

                        LlamaSwapConnection.Idle -> {
                            stringResource(R.string.llamaswap_conn_idle)
                        }

                        is LlamaSwapConnection.Retrying -> {
                            val secs = ((connection.retryAtMs - now + 999) / 1000).toInt()
                            if (secs > 0) {
                                stringResource(R.string.llamaswap_conn_retrying, secs)
                            } else {
                                stringResource(R.string.llamaswap_conn_retrying_now)
                            }
                        }
                    },
                style = MaterialTheme.typography.labelLarge,
            )
            val detail = (connection as? LlamaSwapConnection.Retrying)?.reason ?: state.baseUrl
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = onUnloadAll,
            enabled = state.anyActive && !state.unloadAllPending && !state.playgroundStreaming,
        ) {
            Text(stringResource(R.string.llamaswap_action_unload_all))
        }
    }
}

@Composable
private fun ModelCard(
    model: LlamaSwapModel,
    state: LlamaSwapModelsUiState,
    now: Long,
    onAction: (LlamaSwapAction) -> Unit,
) {
    val spacing = LocalSpacing.current
    val pendingLoad = model.id in state.pendingLoads
    val pendingUnload = model.id in state.pendingUnloads || (state.unloadAllPending && model.isActive)
    val loadingSince = state.loadingSinceMs[model.id]
    val locked = state.playgroundStreaming
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model.displayName != model.id) {
                        Text(
                            text = model.id,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(spacing.sm))
                StateChip(model.state)
            }
            if (model.state == LlamaSwapState.STARTING || (pendingLoad && !model.isActive)) {
                LoadingProgress(model = model, elapsedMs = loadingSince?.let { (now - it).coerceAtLeast(0) })
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when {
                    pendingUnload -> {
                        OutlinedButton(onClick = {}, enabled = false) {
                            Text(stringResource(R.string.llamaswap_action_unloading))
                        }
                    }

                    model.isActive -> {
                        OutlinedButton(onClick = { onAction(LlamaSwapAction.Unload(model.id)) }, enabled = !locked) {
                            Text(stringResource(R.string.llamaswap_action_unload))
                        }
                    }

                    pendingLoad -> {
                        FilledTonalButton(onClick = {}, enabled = false) {
                            Text(stringResource(R.string.llamaswap_action_requesting))
                        }
                    }

                    else -> {
                        FilledTonalButton(onClick = { onAction(LlamaSwapAction.Load(model.id)) }, enabled = !locked) {
                            Text(stringResource(R.string.llamaswap_action_load))
                        }
                    }
                }
            }
        }
    }
}

/** Real progress when the upstream reports it; otherwise an elapsed timer. Never an ETA. */
@Composable
internal fun LoadingProgress(
    model: LlamaSwapModel,
    elapsedMs: Long?,
) {
    val elapsed = formatElapsed(elapsedMs ?: 0)
    val progress = model.loadingProgress?.coerceIn(0.0, 1.0)
    val progressDesc = stringResource(R.string.llamaswap_loading_progress_desc)
    Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)) {
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.toFloat() },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = progressDesc },
            )
            Text(
                text = stringResource(R.string.llamaswap_loading_percent, (progress * 100).toInt(), elapsed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = progressDesc },
            )
            Text(
                text = stringResource(R.string.llamaswap_loading_elapsed, elapsed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (model.loadingMessage.isNotBlank()) {
            Text(
                text = model.loadingMessage,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StateChip(state: String) {
    val (label, type) =
        when (state.lowercase()) {
            LlamaSwapState.READY -> stringResource(R.string.llamaswap_state_ready) to StatusBadgeType.SUCCESS
            LlamaSwapState.STARTING -> stringResource(R.string.llamaswap_state_starting) to StatusBadgeType.INFO
            LlamaSwapState.STOPPING -> stringResource(R.string.llamaswap_state_stopping) to StatusBadgeType.WARNING
            LlamaSwapState.STOPPED -> stringResource(R.string.llamaswap_state_stopped) to StatusBadgeType.NEUTRAL
            LlamaSwapState.SHUTDOWN -> stringResource(R.string.llamaswap_state_shutdown) to StatusBadgeType.NEUTRAL
            else -> state to StatusBadgeType.NEUTRAL
        }
    StatusBadge(text = label, status = type)
}

@Composable
private fun ConfirmDialog(
    state: LlamaSwapModelsUiState,
    confirm: PendingConfirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val action = confirm.action
    val title =
        when (action) {
            is LlamaSwapAction.Load -> {
                stringResource(R.string.llamaswap_confirm_load_title, state.displayName(action.modelId))
            }

            is LlamaSwapAction.Unload -> {
                stringResource(R.string.llamaswap_confirm_unload_title, state.displayName(action.modelId))
            }

            LlamaSwapAction.UnloadAll -> {
                stringResource(R.string.llamaswap_confirm_unload_all_title)
            }
        }
    EvictionConfirmDialog(
        title = title,
        evicted = confirm.evicted,
        confirmLabel =
            stringResource(
                if (action is LlamaSwapAction.Load) {
                    R.string.llamaswap_confirm_load
                } else {
                    R.string.llamaswap_confirm_unload
                },
            ),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Warns which running models an action will stop. Shared by the Models and Playground tabs. */
@Composable
internal fun EvictionConfirmDialog(
    title: String,
    evicted: List<LlamaSwapModel>,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val count = evicted.size
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                Text(pluralStringResource(R.plurals.llamaswap_confirm_evicts, count, count))
                evicted.forEach { m ->
                    Column {
                        Text(m.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = m.id,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.llamaswap_confirm_cancel)) }
        },
    )
}

@Composable
private fun PlaygroundLockNote() {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = LocalAppStatusColors.current.info,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(spacing.sm))
        Text(
            text = stringResource(R.string.llamaswap_playground_lock_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun actionErrorText(
    state: LlamaSwapModelsUiState,
    error: LlamaSwapActionError,
): String =
    when (val a = error.action) {
        is LlamaSwapAction.Load -> {
            stringResource(R.string.llamaswap_error_action_load, state.displayName(a.modelId), error.message)
        }

        is LlamaSwapAction.Unload -> {
            stringResource(R.string.llamaswap_error_action_unload, state.displayName(a.modelId), error.message)
        }

        LlamaSwapAction.UnloadAll -> {
            stringResource(R.string.llamaswap_error_action_unload_all, error.message)
        }
    }

/** "0:23", "12:05", "1:02:03". */
internal fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h >
        0
    ) {
        String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}
