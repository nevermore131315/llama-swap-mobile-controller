package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nevermore131315.llamaswapcontroller.R
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapSettingsStore
import io.github.nevermore131315.llamaswapcontroller.theme.LocalHermesStatusColors
import io.github.nevermore131315.llamaswapcontroller.ui.common.HermesScaffold
import io.github.nevermore131315.llamaswapcontroller.ui.common.NavIcon
import io.github.nevermore131315.llamaswapcontroller.ui.common.TestConnectionButton

/** Settings drill-down for the llama-swap server URL (reachable from Settings and the screen's top bar). */
@Composable
fun LlamaSwapSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val viewModel: LlamaSwapSettingsViewModel =
        viewModel { LlamaSwapSettingsViewModel(LlamaSwapSettingsStore.get(context)) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    HermesScaffold(
        title = { Text(stringResource(R.string.llamaswap_settings_title)) },
        navigationIcon = NavIcon.Back(onBack),
        drawerGesturesEnabled = false,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.llamaswap_settings_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.draft,
                onValueChange = viewModel::onDraftChange,
                label = { Text(stringResource(R.string.llamaswap_settings_url_label)) },
                placeholder = { Text(stringResource(R.string.llamaswap_settings_url_placeholder)) },
                singleLine = true,
                isError = state.draft.isNotBlank() && !state.isDraftValid,
                supportingText =
                    if (state.draft.isNotBlank() && !state.isDraftValid) {
                        { Text(stringResource(R.string.llamaswap_settings_url_invalid)) }
                    } else if (state.justSaved) {
                        { Text(stringResource(R.string.llamaswap_settings_saved)) }
                    } else {
                        null
                    },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                Button(onClick = viewModel::save, enabled = state.canSave) {
                    Text(stringResource(R.string.llamaswap_settings_save))
                }
            }
            TestConnectionButton(
                isTesting = state.isTesting,
                onTest = { if (state.isDraftValid) viewModel.testConnection() },
            )
            state.testResult?.let { TestResult(it) }
        }
    }
}

@Composable
private fun TestResult(result: LlamaSwapTestResult) {
    val status = LocalHermesStatusColors.current
    val (icon, tint, text) =
        when (result) {
            is LlamaSwapTestResult.Success -> {
                Triple(
                    Icons.Filled.CheckCircle,
                    status.success,
                    stringResource(
                        R.string.llamaswap_settings_test_ok,
                        result.version.version,
                        result.version.commit,
                        result.version.buildDate,
                    ),
                )
            }

            is LlamaSwapTestResult.Failure -> {
                Triple(
                    Icons.Filled.Error,
                    status.error,
                    stringResource(R.string.llamaswap_settings_test_failed, result.message),
                )
            }
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
