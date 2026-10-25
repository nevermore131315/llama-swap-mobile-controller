package io.github.nevermore131315.llamaswapcontroller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.nevermore131315.llamaswapcontroller.theme.AppTheme
import io.github.nevermore131315.llamaswapcontroller.ui.llamaswap.LlamaSwapScreen
import io.github.nevermore131315.llamaswapcontroller.ui.llamaswap.LlamaSwapSettingsPage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                var showSettings by rememberSaveable { mutableStateOf(false) }
                if (showSettings) {
                    BackHandler { showSettings = false }
                    LlamaSwapSettingsPage(onBack = { showSettings = false })
                } else {
                    LlamaSwapScreen(onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}
