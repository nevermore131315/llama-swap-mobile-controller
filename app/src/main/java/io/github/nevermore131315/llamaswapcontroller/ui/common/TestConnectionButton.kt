package io.github.nevermore131315.llamaswapcontroller.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nevermore131315.llamaswapcontroller.R

@Composable
internal fun TestConnectionButton(
    isTesting: Boolean,
    onTest: () -> Unit,
) {
    OutlinedButton(
        onClick = onTest,
        enabled = !isTesting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isTesting) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(R.string.settings_action_test_connection))
        }
    }
}
