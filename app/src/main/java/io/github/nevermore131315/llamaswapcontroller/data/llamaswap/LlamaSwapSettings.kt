package io.github.nevermore131315.llamaswapcontroller.data.llamaswap

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The llama-swap server address, independent of the Hermes Agent connection profiles. */
interface LlamaSwapSettings {
    val baseUrl: StateFlow<String>

    fun setBaseUrl(url: String)

    companion object {
        /**
         * No server until the user sets one; the screen shows a setup prompt meanwhile.
         * Cleartext is permitted app-wide (network_security_config.xml).
         */
        const val DEFAULT_BASE_URL = ""

        /** True once [url] is a usable http(s) server address. */
        fun isConfigured(url: String): Boolean = normalize(url) != null

        /** Trims and drops trailing slashes; returns null unless the result is an http(s) URL. */
        fun normalize(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val parsed = trimmed.toHttpUrlOrNull() ?: return null
            return if (parsed.host.isNotEmpty()) trimmed else null
        }
    }
}

/**
 * SharedPreferences-backed [LlamaSwapSettings]. Kept out of the versioned ServerStore on
 * purpose: the llama-swap server is independent of the Hermes Agent connection profiles.
 */
class LlamaSwapSettingsStore private constructor(
    private val prefs: SharedPreferences,
) : LlamaSwapSettings {
    private val _baseUrl =
        MutableStateFlow(prefs.getString(KEY_BASE_URL, null) ?: LlamaSwapSettings.DEFAULT_BASE_URL)
    override val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    override fun setBaseUrl(url: String) {
        prefs.edit { putString(KEY_BASE_URL, url) }
        _baseUrl.value = url
    }

    companion object {
        private const val PREFS_NAME = "llamaswap_settings"
        private const val KEY_BASE_URL = "base_url"

        @Volatile private var instance: LlamaSwapSettingsStore? = null

        fun get(context: Context): LlamaSwapSettingsStore =
            instance ?: synchronized(this) {
                instance ?: LlamaSwapSettingsStore(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                ).also { instance = it }
            }
    }
}

class InMemoryLlamaSwapSettings(
    initial: String,
) : LlamaSwapSettings {
    private val _baseUrl = MutableStateFlow(initial)
    override val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    override fun setBaseUrl(url: String) {
        _baseUrl.value = url
    }
}
