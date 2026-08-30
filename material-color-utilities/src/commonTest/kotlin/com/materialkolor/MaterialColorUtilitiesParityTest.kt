package com.materialkolor

import com.materialkolor.blend.Blend
import com.materialkolor.contrast.Contrast
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Cam16
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.scheme.SchemeTonalSpot
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reference-vector and invariant tests shared by JVM and Kotlin/Native.
 *
 * The exact vectors come from material-color-utilities at the gitlink revision carried by this
 * repository. Running the same source on mingwX64 catches floating-point, collection, and integer
 * conversion divergences that a successful KLIB compilation alone cannot detect.
 */
class MaterialColorUtilitiesParityTest {
    @Test
    fun cam16ReferencePrimaries() {
        assertCam(
            argb = 0xFFFF0000.toInt(),
            hue = 27.408,
            chroma = 113.358,
            j = 46.445,
            m = 89.494,
            s = 91.890,
            q = 105.989,
        )
        assertCam(
            argb = 0xFF00FF00.toInt(),
            hue = 142.140,
            chroma = 108.410,
            j = 79.332,
            m = 85.588,
            s = 78.605,
            q = 138.520,
        )
        assertCam(
            argb = 0xFF0000FF.toInt(),
            hue = 282.788,
            chroma = 87.231,
            j = 25.466,
            m = 68.867,
            s = 93.675,
            q = 78.481,
        )
    }

    @Test
    fun blueTonalPaletteMatchesReference() {
        val palette = TonalPalette.fromInt(0xFF0000FF.toInt())
        val expected = listOf(
            100 to 0xFFFFFFFF.toInt(),
            95 to 0xFFF1EFFF.toInt(),
            90 to 0xFFE0E0FF.toInt(),
            80 to 0xFFBEC2FF.toInt(),
            70 to 0xFF9DA3FF.toInt(),
            60 to 0xFF7C84FF.toInt(),
            50 to 0xFF5A64FF.toInt(),
            40 to 0xFF343DFF.toInt(),
            30 to 0xFF0000EF.toInt(),
            20 to 0xFF0001AC.toInt(),
            10 to 0xFF00006E.toInt(),
            0 to 0xFF000000.toInt(),
        )

        expected.forEach { (tone, argb) ->
            assertEquals(argb, palette.tone(tone), "tone $tone")
        }
    }

    @Test
    fun harmonizeMatchesReference() {
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        val blue = 0xFF0000FF.toInt()
        val yellow = 0xFFFFFF00.toInt()
        val expected = listOf(
            Triple(red, blue, 0xFFFB0057.toInt()),
            Triple(red, green, 0xFFD85600.toInt()),
            Triple(red, yellow, 0xFFD85600.toInt()),
            Triple(blue, green, 0xFF0047A3.toInt()),
            Triple(blue, red, 0xFF5700DC.toInt()),
            Triple(blue, yellow, 0xFF0047A3.toInt()),
            Triple(green, blue, 0xFF00FC94.toInt()),
            Triple(green, red, 0xFFB1F000.toInt()),
            Triple(green, yellow, 0xFFB1F000.toInt()),
            Triple(yellow, blue, 0xFFEBFFBA.toInt()),
            Triple(yellow, green, 0xFFEBFFBA.toInt()),
            Triple(yellow, red, 0xFFFFF6E3.toInt()),
        )

        expected.forEach { (designColor, sourceColor, result) ->
            assertEquals(result, Blend.harmonize(designColor, sourceColor))
        }
    }

    @Test
    fun quantizerPreservesExactPopulations() {
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        val blue = 0xFF0000FF.toInt()
        val result = QuantizerCelebi.quantize(
            pixels = intArrayOf(red, red, green, green, green, blue),
            maxColors = 128,
        )

        assertEquals(mapOf(red to 2, green to 3, blue to 1), result)
    }

    @Test
    fun hctSolverCoversHueChromaToneGrid() {
        for (hue in 15 until 360 step 30) {
            for (chroma in 0..100 step 10) {
                for (tone in 20..80 step 10) {
                    val color = Hct.from(hue.toDouble(), chroma.toDouble(), tone.toDouble())
                    if (chroma > 0) {
                        assertTrue(abs(color.hue - hue) <= 4.0, "hue=$hue chroma=$chroma tone=$tone")
                    }
                    assertTrue(color.chroma in 0.0..(chroma + 2.5))
                    if (color.chroma < chroma - 2.5) {
                        assertTrue(isOnRgbBoundary(color.toInt()))
                    }
                    assertTrue(abs(color.tone - tone) <= 0.5)
                }
            }
        }
    }

    @Test
    fun yellowTone99UsesAveragedArgb() {
        listOf(105.0, 110.0, 115.0, 120.0, 124.0).forEach { hue ->
            val palette = TonalPalette.fromHueAndChroma(hue, 50.0)
            val expected = Hct.fromInt(palette.tone(99))
            val actual = palette.getHct(99.0)
            assertEquals(expected.toInt(), actual.toInt(), "hue $hue")
        }
    }

    @Test
    fun dynamicRolesMeetContrastAcrossModes() {
        val colors = MaterialDynamicColors()
        val rolePairs = listOf(
            colors.onPrimary() to colors.primary(),
            colors.onSecondary() to colors.secondary(),
            colors.onTertiary() to colors.tertiary(),
            colors.onError() to colors.error(),
            colors.onSurface() to colors.surface(),
        )
        val seeds = listOf(0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF0000FF)

        seeds.forEach { seed ->
            listOf(-1.0, 0.0, 1.0).forEach { contrastLevel ->
                listOf(false, true).forEach { isDark ->
                    val scheme = SchemeTonalSpot(Hct.fromInt(seed.toInt()), isDark, contrastLevel)
                    val minimum = if (contrastLevel < 0.0) 3.0 else 4.5
                    rolePairs.forEach { (foreground, background) ->
                        assertRoleContrast(foreground, background, scheme, minimum)
                    }
                }
            }
        }
    }

    private fun assertCam(
        argb: Int,
        hue: Double,
        chroma: Double,
        j: Double,
        m: Double,
        s: Double,
        q: Double,
    ) {
        val cam = Cam16.fromInt(argb)
        assertEquals(hue, cam.hue, 0.001)
        assertEquals(chroma, cam.chroma, 0.001)
        assertEquals(j, cam.j, 0.001)
        assertEquals(m, cam.m, 0.001)
        assertEquals(s, cam.s, 0.001)
        assertEquals(q, cam.q, 0.001)
        assertEquals(argb, cam.toInt())
    }

    private fun assertRoleContrast(
        foreground: DynamicColor,
        background: DynamicColor,
        scheme: SchemeTonalSpot,
        minimum: Double,
    ) {
        val ratio = Contrast.ratioOfTones(
            foreground.getHct(scheme).tone,
            background.getHct(scheme).tone,
        )
        assertTrue(
            ratio >= minimum,
            "${foreground.name} on ${background.name}: $ratio < $minimum " +
                "(dark=${scheme.isDark}, contrast=${scheme.contrastLevel})",
        )
    }

    private fun isOnRgbBoundary(argb: Int): Boolean {
        val red = argb shr 16 and 0xFF
        val green = argb shr 8 and 0xFF
        val blue = argb and 0xFF
        return red == 0 || red == 255 || green == 0 || green == 255 || blue == 0 || blue == 255
    }
}
