package io.github.nevermore131315.llamaswapcontroller.data.llamaswap

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.nevermore131315.llamaswapcontroller.theme.ThemePreference
import io.github.nevermore131315.llamaswapcontroller.theme.ThemePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** SharedPreferences-backed appearance settings: light/dark mode, color preset, dynamic color. */
class ThemeSettingsStore private constructor(
    private val prefs: SharedPreferences,
) {
    private val _preference = MutableStateFlow(readEnum(KEY_MODE, ThemePreference.SYSTEM))
    val preference: StateFlow<ThemePreference> = _preference.asStateFlow()

    private val _preset = MutableStateFlow(readEnum(KEY_PRESET, ThemePreset.DEFAULT))
    val preset: StateFlow<ThemePreset> = _preset.asStateFlow()

    private val _dynamicColors = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC, false))
    val dynamicColors: StateFlow<Boolean> = _dynamicColors.asStateFlow()

    fun setPreference(value: ThemePreference) {
        prefs.edit { putString(KEY_MODE, value.name) }
        _preference.value = value
    }

    fun setPreset(value: ThemePreset) {
        prefs.edit { putString(KEY_PRESET, value.name) }
        _preset.value = value
    }

    fun setDynamicColors(value: Boolean) {
        prefs.edit { putBoolean(KEY_DYNAMIC, value) }
        _dynamicColors.value = value
    }

    private inline fun <reified E : Enum<E>> readEnum(
        key: String,
        default: E,
    ): E = prefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    companion object {
        private const val PREFS_NAME = "llamaswap_theme"
        private const val KEY_MODE = "mode"
        private const val KEY_PRESET = "preset"
        private const val KEY_DYNAMIC = "dynamic_colors"

        @Volatile private var instance: ThemeSettingsStore? = null

        fun get(context: Context): ThemeSettingsStore =
            instance ?: synchronized(this) {
                instance ?: ThemeSettingsStore(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                ).also { instance = it }
            }
    }
}
