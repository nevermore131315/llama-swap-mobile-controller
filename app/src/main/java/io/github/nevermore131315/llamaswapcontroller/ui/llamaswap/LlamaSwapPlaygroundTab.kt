package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nevermore131315.llamaswapcontroller.R
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapModel
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapState
import io.github.nevermore131315.llamaswapcontroller.theme.LocalAppStatusColors
import io.github.nevermore131315.llamaswapcontroller.theme.LocalSpacing
import io.github.nevermore131315.llamaswapcontroller.ui.chat.MarkdownText
import io.github.nevermore131315.llamaswapcontroller.ui.common.EmptyState

@Composable
internal fun LlamaSwapPlaygroundTab(
    state: PlaygroundUiState,
    onPickModel: (String) -> Unit,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onConfirm: () -> Unit,
    onDismissConfirm: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val now = rememberTickingNow(active = state.messages.any { it.awaitingFirstToken })

    state.confirm?.let { c ->
        EvictionConfirmDialog(
            title = stringResource(R.string.llamaswap_confirm_load_title, state.displayName(c.modelId)),
            evicted = c.evicted,
            confirmLabel = stringResource(R.string.llamaswap_playground_confirm_send),
            onConfirm = onConfirm,
            onDismiss = onDismissConfirm,
        )
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = spacing.md, end = spacing.xs, top = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ModelPicker(state = state, onPick = onPickModel)
            }
            IconButton(onClick = onNewChat, enabled = state.messages.isNotEmpty()) {
                Icon(
                    Icons.Filled.AddComment,
                    contentDescription = stringResource(R.string.llamaswap_playground_new_chat),
                )
            }
        }

        val listState = rememberLazyListState()
        val last = state.messages.lastOrNull()
        // Follow the reply as it grows.
        LaunchedEffect(state.messages.size, last?.content?.length, last?.reasoning?.length, last?.status) {
            if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (state.messages.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.llamaswap_playground_empty_title),
                    subtitle =
                        stringResource(
                            R.string.llamaswap_playground_empty_desc,
                            state.selectedModel?.displayName ?: stringResource(R.string.llamaswap_playground_no_models),
                        ),
                    icon = Icons.Filled.Forum,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    items(state.messages, key = { it.id }) { m ->
                        when (m.role) {
                            PlaygroundRole.USER -> UserBubble(m)
                            PlaygroundRole.ASSISTANT -> AssistantMessage(m, state, now)
                        }
                    }
                }
            }
        }

        Composer(state = state, onInput = onInput, onSend = onSend, onStop = onStop)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(
    state: PlaygroundUiState,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = !state.isStreaming && state.pickable.isNotEmpty()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = state.selectedModel?.displayName ?: stringResource(R.string.llamaswap_playground_no_models),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.llamaswap_playground_model)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.pickable.forEach { m ->
                DropdownMenuItem(
                    text = { PickerItem(m) },
                    onClick = {
                        onPick(m.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PickerItem(model: LlamaSwapModel) {
    Column {
        Text(model.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val stateLabel =
            when (model.state.lowercase()) {
                LlamaSwapState.READY -> stringResource(R.string.llamaswap_state_ready)
                LlamaSwapState.STARTING -> stringResource(R.string.llamaswap_state_starting)
                LlamaSwapState.STOPPING -> stringResource(R.string.llamaswap_state_stopping)
                else -> null
            }
        Text(
            text = if (stateLabel != null) "$stateLabel · ${model.id}" else model.id,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color =
                if (model.state == LlamaSwapState.READY) {
                    LocalAppStatusColors.current.success
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UserBubble(message: PlaygroundMessage) {
    val spacing = LocalSpacing.current
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    message: PlaygroundMessage,
    state: PlaygroundUiState,
    now: Long,
) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = state.displayName(message.modelId),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (message.awaitingFirstToken) WaitingIndicator(message, state, now)
        if (message.reasoning.isNotEmpty()) Reasoning(message)
        if (message.content.isNotEmpty()) {
            SelectionContainer {
                MarkdownText(
                    text = message.content,
                    textColor = MaterialTheme.colorScheme.onBackground,
                    isStreaming = message.status == PlaygroundStatus.STREAMING,
                )
            }
        }
        when (message.status) {
            PlaygroundStatus.STOPPED -> {
                Text(
                    text = stringResource(R.string.llamaswap_playground_stopped),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PlaygroundStatus.ERROR -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(spacing.xs))
                    Text(
                        text = stringResource(R.string.llamaswap_playground_error, message.error.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            else -> {}
        }
    }
}

/** Until the first token: the Models tab's loading display if the model is cold, else a timer. */
@Composable
private fun WaitingIndicator(
    message: PlaygroundMessage,
    state: PlaygroundUiState,
    now: Long,
) {
    val model = state.models.firstOrNull { it.id == message.modelId }
    val elapsedMs = (now - message.startedAtMs).coerceAtLeast(0)
    if (model == null || model.state != LlamaSwapState.READY) {
        Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)) {
            Text(
                text = stringResource(R.string.llamaswap_playground_loading_model, state.displayName(message.modelId)),
                style = MaterialTheme.typography.bodyMedium,
            )
            LoadingProgress(model = model ?: LlamaSwapModel(id = message.modelId), elapsedMs = elapsedMs)
        }
    } else {
        Text(
            text = stringResource(R.string.llamaswap_playground_waiting, formatElapsed(elapsedMs)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Thinking output, collapsed by default. */
@Composable
private fun Reasoning(message: PlaygroundMessage) {
    val spacing = LocalSpacing.current
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val live = message.status == PlaygroundStatus.STREAMING && message.content.isEmpty()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(spacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(if (live) THINKING_LIVE else THINKING),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(if (expanded) HIDE_THINKING else SHOW_THINKING),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                SelectionContainer {
                    Text(
                        text = message.reasoning.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.xs),
                    )
                }
            } else if (live) {
                // Collapsed but live: show the tail so progress is visible.
                Text(
                    text = message.reasoning.trim().takeLast(REASONING_PREVIEW_CHARS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
        }
    }
}

private const val REASONING_PREVIEW_CHARS = 160
private val THINKING = R.string.llamaswap_playground_thinking
private val THINKING_LIVE = R.string.llamaswap_playground_thinking_live
private val SHOW_THINKING = R.string.llamaswap_playground_show_thinking
private val HIDE_THINKING = R.string.llamaswap_playground_hide_thinking

@Composable
private fun Composer(
    state: PlaygroundUiState,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(spacing.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = state.input,
            onValueChange = onInput,
            placeholder = { Text(stringResource(R.string.llamaswap_playground_input_hint)) },
            maxLines = 6,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(spacing.sm))
        if (state.isStreaming) {
            FilledIconButton(
                onClick = onStop,
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                modifier = Modifier.padding(bottom = spacing.xs),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.llamaswap_playground_stop))
            }
        } else {
            FilledIconButton(
                onClick = onSend,
                enabled = state.canSend,
                modifier = Modifier.padding(bottom = spacing.xs),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.llamaswap_playground_send),
                )
            }
        }
    }
}
