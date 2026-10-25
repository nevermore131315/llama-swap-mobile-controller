package io.github.nevermore131315.llamaswapcontroller.theme

import io.github.nevermore131315.llamaswapcontroller.theme.presets.AmoledTheme
import io.github.nevermore131315.llamaswapcontroller.theme.presets.CatppuccinTheme
import io.github.nevermore131315.llamaswapcontroller.theme.presets.DefaultTheme
import io.github.nevermore131315.llamaswapcontroller.theme.presets.GarnetTheme
import io.github.nevermore131315.llamaswapcontroller.theme.presets.GruvboxTheme
import io.github.nevermore131315.llamaswapcontroller.theme.presets.MonochromeTheme
import io.github.nevermore131315.llamaswapcontroller.theme.presets.NordTheme

internal data class ThemeDefinition(
    val preset: ThemePreset,
    val palette: ThemePalette,
)

// Regression theme-contract: selection, resolution, and tests share this registry.
internal val ThemeRegistry =
    listOf(
        ThemeDefinition(ThemePreset.DEFAULT, DefaultTheme),
        ThemeDefinition(ThemePreset.MONOCHROME, MonochromeTheme),
        ThemeDefinition(ThemePreset.GRUVBOX, GruvboxTheme),
        ThemeDefinition(ThemePreset.CATPPUCCIN, CatppuccinTheme),
        ThemeDefinition(ThemePreset.AMOLED, AmoledTheme),
        ThemeDefinition(ThemePreset.NORD, NordTheme),
        ThemeDefinition(ThemePreset.GARNET, GarnetTheme),
    )

internal fun ThemePreset.palette(): ThemePalette = ThemeRegistry.single { it.preset == this }.palette
