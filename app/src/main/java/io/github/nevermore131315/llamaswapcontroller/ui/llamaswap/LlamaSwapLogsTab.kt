package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nevermore131315.llamaswapcontroller.R
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapLogSource

@Composable
fun LlamaSwapLogsTab(
    state: LlamaSwapLogsUiState,
    onSelectSource: (LlamaSwapLogSource) -> Unit,
    onSetFollow: (Boolean) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Keep pinned to the newest line while following.
    LaunchedEffect(state.lines.size, state.follow) {
        if (state.follow && state.lines.isNotEmpty()) listState.scrollToItem(state.lines.lastIndex)
    }
    // Scrolling away from the bottom pauses following; returning to it resumes.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to atBottom }.collect { (scrolling, bottom) ->
            if (scrolling) onSetFollow(bottom)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LlamaSwapLogSource.entries.forEach { src ->
                FilterChip(
                    selected = state.source == src,
                    onClick = { onSelectSource(src) },
                    label = { Text(stringResource(src.labelRes())) },
                )
            }
            TextButton(onClick = { onSetFollow(!state.follow) }) {
                Text(
                    stringResource(if (state.follow) R.string.llamaswap_logs_pause else R.string.llamaswap_logs_follow),
                )
            }
            TextButton(onClick = onClear) { Text(stringResource(R.string.llamaswap_logs_clear)) }
        }
        Text(
            text =
                when {
                    state.error != null -> stringResource(R.string.llamaswap_logs_error, state.error)
                    state.connected -> stringResource(R.string.llamaswap_conn_live)
                    else -> stringResource(R.string.llamaswap_conn_connecting)
                },
            style = MaterialTheme.typography.labelSmall,
            color =
                if (state.error !=
                    null
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (state.lines.isEmpty()) {
                Text(
                    text = stringResource(R.string.llamaswap_logs_empty, state.baseUrl),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(state.lines) { _, line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                color = line.logColor(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun LlamaSwapLogSource.labelRes(): Int =
    when (this) {
        LlamaSwapLogSource.ALL -> R.string.llamaswap_logs_src_all
        LlamaSwapLogSource.PROXY -> R.string.llamaswap_logs_src_proxy
        LlamaSwapLogSource.UPSTREAM -> R.string.llamaswap_logs_src_upstream
        LlamaSwapLogSource.HTTP -> R.string.llamaswap_logs_src_http
    }

@Composable
private fun String.logColor() =
    when {
        contains("[ERROR]") -> MaterialTheme.colorScheme.error
        contains("[WARN]") -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
