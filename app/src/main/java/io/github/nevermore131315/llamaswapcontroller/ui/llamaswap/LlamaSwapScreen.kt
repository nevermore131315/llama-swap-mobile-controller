package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nevermore131315.llamaswapcontroller.R
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapSettings
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapSettingsStore
import io.github.nevermore131315.llamaswapcontroller.ui.common.AppScaffold
import io.github.nevermore131315.llamaswapcontroller.ui.common.EmptyState
import io.github.nevermore131315.llamaswapcontroller.ui.common.NavIcon
import kotlinx.coroutines.flow.map

/** Tabs mirror llama-swap's own web UI sections. */
enum class LlamaSwapTab(
    @param:StringRes val labelRes: Int,
) {
    ACTIVITY(R.string.llamaswap_tab_activity),
    PLAYGROUND(R.string.llamaswap_tab_playground),
    MODELS(R.string.llamaswap_tab_models),
    LOGS(R.string.llamaswap_tab_logs),
    PERFORMANCE(R.string.llamaswap_tab_performance),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlamaSwapScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val baseUrl by LlamaSwapSettingsStore.get(context).baseUrl.collectAsStateWithLifecycle()
    val configured = LlamaSwapSettings.isConfigured(baseUrl)
    val viewModel: LlamaSwapModelsViewModel =
        viewModel { LlamaSwapModelsViewModel(LlamaSwapSettingsStore.get(context)) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Shares the Models view model's event stream (one /api/events connection for the screen).
    val activityViewModel: LlamaSwapActivityViewModel =
        viewModel {
            LlamaSwapActivityViewModel(
                settings = LlamaSwapSettingsStore.get(context),
                activityEvents = viewModel.activityEvents,
                connection = viewModel.uiState.map { it.connection },
            )
        }
    val activityState by activityViewModel.uiState.collectAsStateWithLifecycle()
    val logsViewModel: LlamaSwapLogsViewModel =
        viewModel { LlamaSwapLogsViewModel(LlamaSwapSettingsStore.get(context)) }
    val logsState by logsViewModel.uiState.collectAsStateWithLifecycle()
    val perfViewModel: LlamaSwapPerformanceViewModel =
        viewModel { LlamaSwapPerformanceViewModel(LlamaSwapSettingsStore.get(context)) }
    val perfState by perfViewModel.uiState.collectAsStateWithLifecycle()
    // Chats with the models the Models view model lists, so both see the same live state.
    val playgroundViewModel: LlamaSwapPlaygroundViewModel =
        viewModel {
            LlamaSwapPlaygroundViewModel(
                settings = LlamaSwapSettingsStore.get(context),
                models = viewModel.uiState.map { it.models },
            )
        }
    val playgroundState by playgroundViewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(LlamaSwapTab.MODELS.ordinal) }
    val tab = LlamaSwapTab.entries[selectedTab]

    // The event stream lives only while the screen is visible (STARTED); it reconnects on return.
    LifecycleStartEffect(viewModel, configured) {
        if (configured) viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }
    // Activity fetches only while its tab is showing and the screen is visible.
    LifecycleStartEffect(activityViewModel, tab, configured) {
        if (configured && tab == LlamaSwapTab.ACTIVITY) activityViewModel.start()
        onStopOrDispose { activityViewModel.stop() }
    }
    // Logs stream only while its tab is showing and the screen is visible.
    LifecycleStartEffect(logsViewModel, tab, configured) {
        if (configured && tab == LlamaSwapTab.LOGS) logsViewModel.start()
        onStopOrDispose { logsViewModel.stop() }
    }
    // Performance polls only while its tab is showing and the screen is visible.
    LifecycleStartEffect(perfViewModel, tab, configured) {
        if (configured && tab == LlamaSwapTab.PERFORMANCE) perfViewModel.start()
        onStopOrDispose { perfViewModel.stop() }
    }

    AppScaffold(
        title = { Text(stringResource(R.string.screen_llamaswap)) },
        isRefreshing =
            when {
                !configured -> false
                tab == LlamaSwapTab.MODELS -> state.isRefreshing
                tab == LlamaSwapTab.ACTIVITY -> activityState.isRefreshing
                tab == LlamaSwapTab.PERFORMANCE -> perfState.isRefreshing
                else -> false
            },
        onRefresh =
            when {
                !configured -> null
                tab == LlamaSwapTab.MODELS -> viewModel::refresh
                tab == LlamaSwapTab.ACTIVITY -> activityViewModel::refresh
                tab == LlamaSwapTab.PERFORMANCE -> perfViewModel::refresh
                else -> null
            },
        modifier = modifier,
        actions = {
            IconButton(onClick = { onOpenSettings() }) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.llamaswap_action_settings))
            }
        },
    ) {
        if (!configured) {
            EmptyState(
                title = stringResource(R.string.llamaswap_unconfigured_title),
                subtitle = stringResource(R.string.llamaswap_unconfigured_desc),
                icon = Icons.Filled.LinkOff,
                actionLabel = stringResource(R.string.llamaswap_unconfigured_action),
                onAction = { onOpenSettings() },
                modifier = Modifier.fillMaxSize(),
            )
            return@AppScaffold
        }
        Column(modifier = Modifier.fillMaxSize()) {
            PrimaryScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
                LlamaSwapTab.entries.forEach { t ->
                    Tab(
                        selected = t.ordinal == selectedTab,
                        onClick = { selectedTab = t.ordinal },
                        text = { Text(stringResource(t.labelRes)) },
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (tab) {
                    LlamaSwapTab.MODELS -> {
                        LlamaSwapModelsTab(
                            state = state,
                            onAction = viewModel::request,
                            onConfirm = viewModel::confirm,
                            onDismissConfirm = viewModel::dismissConfirm,
                            onRetry = viewModel::refresh,
                            onClearActionError = viewModel::clearActionError,
                        )
                    }

                    LlamaSwapTab.ACTIVITY -> {
                        LlamaSwapActivityTab(
                            state = activityState,
                            displayName = state::displayName,
                            onRetry = activityViewModel::refresh,
                            onToggle = activityViewModel::toggleExpanded,
                        )
                    }

                    LlamaSwapTab.LOGS -> {
                        LlamaSwapLogsTab(
                            state = logsState,
                            onSelectSource = logsViewModel::selectSource,
                            onSetFollow = logsViewModel::setFollow,
                            onClear = logsViewModel::clear,
                        )
                    }

                    LlamaSwapTab.PERFORMANCE -> {
                        LlamaSwapPerformanceTab(state = perfState, onRetry = perfViewModel::refresh)
                    }

                    LlamaSwapTab.PLAYGROUND -> {
                        LlamaSwapPlaygroundTab(
                            state = playgroundState,
                            onPickModel = playgroundViewModel::pickModel,
                            onInput = playgroundViewModel::setInput,
                            onSend = playgroundViewModel::send,
                            onStop = playgroundViewModel::stop,
                            onNewChat = playgroundViewModel::newChat,
                            onConfirm = playgroundViewModel::confirmSend,
                            onDismissConfirm = playgroundViewModel::dismissConfirm,
                        )
                    }
                }
            }
        }
    }
}
