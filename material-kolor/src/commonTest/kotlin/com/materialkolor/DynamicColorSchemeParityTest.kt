package com.materialkolor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.ktx.contrastRatio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs unchanged on JVM and native targets, including mingwX64. */
class DynamicColorSchemeParityTest {
    private val seed = Color(0xFF6750A4)

    @Test
    fun tonalSpotMatchesReferenceVectors() {
        val light = dynamicColorScheme(seedColor = seed, isDark = false)
        assertEquals(0xFF65558F.toInt(), light.primary.toArgb())
        assertEquals(0xFF625B71.toInt(), light.secondary.toArgb())
        assertEquals(0xFF7E5260.toInt(), light.tertiary.toArgb())
        assertEquals(0xFFFDF7FF.toInt(), light.surface.toArgb())
        assertEquals(0xFF1D1B20.toInt(), light.onSurface.toArgb())

        val dark = dynamicColorScheme(seedColor = seed, isDark = true)
        assertEquals(0xFFCFBDFE.toInt(), dark.primary.toArgb())
        assertEquals(0xFFCBC2DB.toInt(), dark.secondary.toArgb())
        assertEquals(0xFFEFB8C8.toInt(), dark.tertiary.toArgb())
        assertEquals(0xFF141218.toInt(), dark.surface.toArgb())
        assertEquals(0xFFE6E0E9.toInt(), dark.onSurface.toArgb())
    }

    @Test
    fun everyPaletteStyleMaintainsTextContrast() {
        PaletteStyle.entries.forEach { style ->
            listOf(false, true).forEach { isDark ->
                val scheme = dynamicColorScheme(
                    seedColor = seed,
                    isDark = isDark,
                    style = style,
                )
                val pairs = listOf(
                    scheme.onPrimary to scheme.primary,
                    scheme.onPrimaryContainer to scheme.primaryContainer,
                    scheme.onSecondary to scheme.secondary,
                    scheme.onSecondaryContainer to scheme.secondaryContainer,
                    scheme.onTertiary to scheme.tertiary,
                    scheme.onTertiaryContainer to scheme.tertiaryContainer,
                    scheme.onError to scheme.error,
                    scheme.onErrorContainer to scheme.errorContainer,
                    scheme.onSurface to scheme.surface,
                )
                pairs.forEach { (foreground, background) ->
                    assertTrue(
                        foreground.contrastRatio(background) >= 4.5,
                        "$style dark=$isDark produced insufficient contrast for " +
                            "${foreground.toArgb().toUInt().toString(16)} on " +
                            background.toArgb().toUInt().toString(16),
                    )
                }
            }
        }
    }

    @Test
    fun amoledOverridesOnlyDarkSurfaces() {
        val normal = dynamicColorScheme(seedColor = seed, isDark = true)
        val amoled = dynamicColorScheme(seedColor = seed, isDark = true, isAmoled = true)
        assertEquals(Color.Black, amoled.background)
        assertEquals(Color.Black, amoled.surface)
        assertEquals(Color.White, amoled.onBackground)
        assertEquals(Color.White, amoled.onSurface)
        assertEquals(normal.primary, amoled.primary)
        assertEquals(normal.secondary, amoled.secondary)

        val light = dynamicColorScheme(seedColor = seed, isDark = false, isAmoled = true)
        assertTrue(light.background != Color.Black)
        assertTrue(light.surface != Color.Black)
    }

    @Test
    fun callerModificationIsAppliedLast() {
        val replacement = Color(0xFF123456)
        val scheme = dynamicColorScheme(
            seedColor = seed,
            isDark = false,
            modifyColorScheme = { it.copy(primary = replacement) },
        )

        assertEquals(replacement, scheme.primary)
    }
}
