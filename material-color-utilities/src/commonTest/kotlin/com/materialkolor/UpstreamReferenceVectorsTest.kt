/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.materialkolor

import com.materialkolor.contrast.Contrast
import com.materialkolor.dislike.DislikeAnalyzer
import com.materialkolor.hct.Hct
import com.materialkolor.score.Score
import com.materialkolor.temperature.TemperatureCache
import com.materialkolor.utils.ColorUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Platform-neutral ports of the upstream Material Color Utilities reference tests.
 *
 * These execute the same committed vectors on JVM and Kotlin/Native. In particular, the MinGW
 * test binary now exercises exhaustive HCT round trips, color-space inverses, temperature theory,
 * dislike correction, contrast edge cases, and every upstream generated scoring scenario.
 */
class UpstreamReferenceVectorsTest {
    @Test
    fun hctRoundTripPreserves512RgbSamples() {
        for (red in 0 until 296 step 37) {
            for (green in 0 until 296 step 37) {
                for (blue in 0 until 296 step 37) {
                    val argb = ColorUtils.argbFromRgb(
                        red.coerceAtMost(255),
                        green.coerceAtMost(255),
                        blue.coerceAtMost(255),
                    )
                    val hct = Hct.fromInt(argb)
                    assertEquals(
                        argb,
                        Hct.from(hct.hue, hct.chroma, hct.tone).toInt(),
                        "round trip for ${argb.toUInt().toString(16)}",
                    )
                }
            }
        }
    }

    @Test
    fun yAndLstarAreMutualInversesAcrossTheirFullDomains() {
        for (step in 0..1000) {
            val y = step / 10.0
            assertEquals(y, ColorUtils.yFromLstar(ColorUtils.lstarFromY(y)), 1e-8)

            val lstar = step / 10.0
            assertEquals(lstar, ColorUtils.lstarFromY(ColorUtils.yFromLstar(lstar)), 1e-8)
        }
    }

    @Test
    fun colorSpaceReferenceValuesMatchUpstream() {
        val yVectors = listOf(
            0.0 to 0.0,
            0.1 to 0.0110705,
            0.5 to 0.0553528,
            1.0 to 0.1107056,
            5.0 to 0.5535282,
            8.0 to 0.8856451,
            10.0 to 1.1260199,
            25.0 to 4.4154767,
            50.0 to 18.4186518,
            75.0 to 48.2781046,
            95.0 to 87.6183294,
            100.0 to 100.0,
        )
        yVectors.forEach { (lstar, expectedY) ->
            assertEquals(expectedY, ColorUtils.yFromLstar(lstar), 1e-5, "L* $lstar")
        }

        assertEquals(0xFFFFFFFF.toInt(), ColorUtils.argbFromRgb(255, 255, 255))
        assertEquals(0xFF000000.toInt(), ColorUtils.argbFromRgb(0, 0, 0))
        assertEquals(0xFF3296FA.toInt(), ColorUtils.argbFromRgb(50, 150, 250))
    }

    @Test
    fun contrastRejectsImpossibleAndOutOfRangeRequests() {
        // The Java/Kotlin reference implementation accepts valid tones here; unlike the separate
        // TypeScript API, it intentionally does not clamp ratioOfTones inputs.
        assertEquals(21.0, Contrast.ratioOfTones(0.0, 100.0), 0.001)
        assertEquals(-1.0, Contrast.lighter(90.0, 10.0), 0.001)
        assertEquals(-1.0, Contrast.lighter(110.0, 2.0), 0.001)
        assertEquals(-1.0, Contrast.lighter(-10.0, 2.0), 0.001)
        assertEquals(100.0, Contrast.lighterUnsafe(100.0, 2.0), 0.001)
        assertEquals(-1.0, Contrast.darker(10.0, 20.0), 0.001)
        assertEquals(-1.0, Contrast.darker(110.0, 2.0), 0.001)
        assertEquals(-1.0, Contrast.darker(-10.0, 2.0), 0.001)
        assertEquals(0.0, Contrast.darkerUnsafe(0.0, 2.0), 0.001)
    }

    @Test
    fun rawTemperatureMatchesUpstream() {
        val vectors = listOf(
            0xFF0000FF.toInt() to -1.393,
            0xFFFF0000.toInt() to 2.351,
            0xFF00FF00.toInt() to -0.267,
            0xFFFFFFFF.toInt() to -0.5,
            0xFF000000.toInt() to -0.5,
        )
        vectors.forEach { (argb, expected) ->
            assertEquals(expected, TemperatureCache.rawTemperature(Hct.fromInt(argb)), 0.001)
        }
    }

    @Test
    fun relativeTemperatureMatchesUpstream() {
        val vectors = listOf(
            0xFF0000FF.toInt() to 0.0,
            0xFFFF0000.toInt() to 1.0,
            0xFF00FF00.toInt() to 0.467,
            0xFFFFFFFF.toInt() to 0.5,
            0xFF000000.toInt() to 0.5,
        )
        vectors.forEach { (argb, expected) ->
            val input = Hct.fromInt(argb)
            assertEquals(expected, TemperatureCache(input).getRelativeTemperature(input), 0.001)
        }
    }

    @Test
    fun complementsMatchUpstream() {
        val vectors = listOf(
            0xFF0000FF.toInt() to 0xFF9D0002.toInt(),
            0xFFFF0000.toInt() to 0xFF007BFC.toInt(),
            0xFF00FF00.toInt() to 0xFFFFD2C9.toInt(),
            0xFFFFFFFF.toInt() to 0xFFFFFFFF.toInt(),
            0xFF000000.toInt() to 0xFF000000.toInt(),
        )
        vectors.forEach { (argb, expected) ->
            assertEquals(expected, TemperatureCache(Hct.fromInt(argb)).complement.toInt())
        }
    }

    @Test
    fun analogousColorsMatchUpstream() {
        val vectors = mapOf(
            0xFF0000FF.toInt() to listOf(
                0xFF00590C.toInt(),
                0xFF00564E.toInt(),
                0xFF0000FF.toInt(),
                0xFF6700CC.toInt(),
                0xFF81009F.toInt(),
            ),
            0xFFFF0000.toInt() to listOf(
                0xFFF60082.toInt(),
                0xFFFC004C.toInt(),
                0xFFFF0000.toInt(),
                0xFFD95500.toInt(),
                0xFFAF7200.toInt(),
            ),
            0xFF00FF00.toInt() to listOf(
                0xFFCEE900.toInt(),
                0xFF92F500.toInt(),
                0xFF00FF00.toInt(),
                0xFF00FD6F.toInt(),
                0xFF00FAB3.toInt(),
            ),
            0xFF000000.toInt() to List(5) { 0xFF000000.toInt() },
            0xFFFFFFFF.toInt() to List(5) { 0xFFFFFFFF.toInt() },
        )
        vectors.forEach { (argb, expected) ->
            val actual = TemperatureCache(Hct.fromInt(argb)).analogousColors.map(Hct::toInt)
            assertEquals(expected, actual, "analogous colors for ${argb.toUInt().toString(16)}")
        }
    }

    @Test
    fun dislikeAnalyzerAcceptsMonkSkinToneScale() {
        val colors = listOf(
            0xFFF6EDE4,
            0xFFF3E7DB,
            0xFFF7EAD0,
            0xFFEADABA,
            0xFFD7BD96,
            0xFFA07E56,
            0xFF825C43,
            0xFF604134,
            0xFF3A312A,
            0xFF292420,
        )
        colors.forEach { color ->
            assertFalse(DislikeAnalyzer.isDisliked(Hct.fromInt(color.toInt())))
        }
    }

    @Test
    fun dislikeAnalyzerCorrectsEveryBileReferenceColor() {
        val colors = listOf(0xFF95884B, 0xFF716B40, 0xFFB08E00, 0xFF4C4308, 0xFF464521)
        colors.forEach { color ->
            val hct = Hct.fromInt(color.toInt())
            assertTrue(DislikeAnalyzer.isDisliked(hct))
            assertFalse(DislikeAnalyzer.isDisliked(DislikeAnalyzer.fixIfDisliked(hct)))
        }
    }

    @Test
    fun scorePrioritizesChromaAndFallsBackToGoogleBlue() {
        assertScore(
            mapOf(0xFF000000.toInt() to 1, 0xFFFFFFFF.toInt() to 1, 0xFF0000FF.toInt() to 1),
            listOf(0xFF0000FF.toInt()),
            desired = 4,
        )
        assertScore(
            mapOf(0xFFFF0000.toInt() to 1, 0xFF00FF00.toInt() to 1, 0xFF0000FF.toInt() to 1),
            listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()),
            desired = 4,
        )
        assertScore(
            mapOf(0xFF000000.toInt() to 1),
            listOf(0xFF4285F4.toInt()),
            desired = 4,
        )
    }

    @Test
    fun scoreDeduplicatesNearbyHues() =
        assertScore(
            colors = mapOf(0xFF008772.toInt() to 1, 0xFF318477.toInt() to 1),
            expected = listOf(0xFF008772.toInt()),
            desired = 4,
        )

    @Test
    fun scoreMaximizesHueDistance() =
        assertScore(
            colors = mapOf(
                0xFF008772.toInt() to 1,
                0xFF008587.toInt() to 1,
                0xFF007EBC.toInt() to 1,
            ),
            expected = listOf(0xFF007EBC.toInt(), 0xFF008772.toInt()),
            desired = 2,
        )

    @Test
    fun scoreGeneratedScenarioOne() =
        assertScore(
            colors = mapOf(0xFF7EA16D.toInt() to 67, 0xFFD8CCAE.toInt() to 67, 0xFF835C0D.toInt() to 49),
            expected = listOf(0xFF7EA16D.toInt(), 0xFFD8CCAE.toInt(), 0xFF835C0D.toInt()),
            desired = 3,
            fallback = 0xFF8D3819.toInt(),
            filter = false,
        )

    @Test
    fun scoreGeneratedScenarioTwo() =
        assertScore(
            colors = mapOf(
                0xFFD33881.toInt() to 14,
                0xFF3205CC.toInt() to 77,
                0xFF0B48CF.toInt() to 36,
                0xFFA08F5D.toInt() to 81,
            ),
            expected = listOf(0xFF3205CC.toInt(), 0xFFA08F5D.toInt(), 0xFFD33881.toInt()),
            desired = 4,
            fallback = 0xFF7D772B.toInt(),
        )

    @Test
    fun scoreGeneratedScenarioThree() =
        assertScore(
            colors = mapOf(
                0xFFBE94A6.toInt() to 23,
                0xFFC33FD7.toInt() to 42,
                0xFF899F36.toInt() to 90,
                0xFF94C574.toInt() to 82,
            ),
            expected = listOf(0xFF94C574.toInt(), 0xFFC33FD7.toInt(), 0xFFBE94A6.toInt()),
            desired = 3,
            fallback = 0xFFAA79A4.toInt(),
        )

    @Test
    fun scoreGeneratedScenarioFour() =
        assertScore(
            colors = mapOf(
                0xFFDF241C.toInt() to 85,
                0xFF685859.toInt() to 44,
                0xFFD06D5F.toInt() to 34,
                0xFF561C54.toInt() to 27,
                0xFF713090.toInt() to 88,
            ),
            expected = listOf(0xFFDF241C.toInt(), 0xFF561C54.toInt()),
            desired = 5,
            fallback = 0xFF58C19C.toInt(),
            filter = false,
        )

    @Test
    fun scoreGeneratedScenarioFive() =
        assertScore(
            colors = mapOf(
                0xFFBE66F8.toInt() to 41,
                0xFF4BBDA9.toInt() to 88,
                0xFF80F6F9.toInt() to 44,
                0xFFAB8017.toInt() to 43,
                0xFFE89307.toInt() to 65,
            ),
            expected = listOf(0xFFAB8017.toInt(), 0xFF4BBDA9.toInt(), 0xFFBE66F8.toInt()),
            desired = 3,
            fallback = 0xFF916691.toInt(),
            filter = false,
        )

    @Test
    fun scoreGeneratedScenarioSix() =
        assertScore(
            colors = mapOf(
                0xFF18EA8F.toInt() to 93,
                0xFF327593.toInt() to 18,
                0xFF066A18.toInt() to 53,
                0xFFFA8A23.toInt() to 74,
                0xFF04CA1F.toInt() to 62,
            ),
            expected = listOf(0xFF18EA8F.toInt(), 0xFFFA8A23.toInt()),
            desired = 2,
            fallback = 0xFF4C377A.toInt(),
            filter = false,
        )

    @Test
    fun scoreGeneratedScenarioSeven() =
        assertScore(
            colors = mapOf(
                0xFF2E05ED.toInt() to 23,
                0xFF153E55.toInt() to 90,
                0xFF9AB220.toInt() to 23,
                0xFF153379.toInt() to 66,
                0xFF68BCC3.toInt() to 81,
            ),
            expected = listOf(0xFF2E05ED.toInt(), 0xFF9AB220.toInt()),
            desired = 2,
            fallback = 0xFFF588DC.toInt(),
        )

    @Test
    fun scoreGeneratedScenarioEight() =
        assertScore(
            colors = mapOf(
                0xFF816EC5.toInt() to 24,
                0xFF6DCB94.toInt() to 19,
                0xFF3CAE91.toInt() to 98,
                0xFF5B542F.toInt() to 25,
            ),
            expected = listOf(0xFF3CAE91.toInt()),
            desired = 1,
            fallback = 0xFF84B0FD.toInt(),
            filter = false,
        )

    @Test
    fun scoreGeneratedScenarioNine() =
        assertScore(
            colors = mapOf(
                0xFF206F86.toInt() to 52,
                0xFF4A620D.toInt() to 96,
                0xFFF51401.toInt() to 85,
                0xFF2B8EBF.toInt() to 3,
                0xFF277766.toInt() to 59,
            ),
            expected = listOf(0xFFF51401.toInt(), 0xFF4A620D.toInt(), 0xFF2B8EBF.toInt()),
            desired = 3,
            fallback = 0xFF02B415.toInt(),
        )

    @Test
    fun scoreGeneratedScenarioTen() =
        assertScore(
            colors = mapOf(
                0xFF8B1D99.toInt() to 54,
                0xFF27EFFE.toInt() to 43,
                0xFF6F558D.toInt() to 2,
                0xFF77FDF2.toInt() to 78,
            ),
            expected = listOf(0xFF27EFFE.toInt(), 0xFF8B1D99.toInt(), 0xFF6F558D.toInt()),
            desired = 4,
            fallback = 0xFF5E7A10.toInt(),
        )

    private fun assertScore(
        colors: Map<Int, Int>,
        expected: List<Int>,
        desired: Int,
        fallback: Int = 0xFF4285F4.toInt(),
        filter: Boolean = true,
    ) {
        assertEquals(
            expected,
            Score.score(
                colorsToPopulation = colors,
                desired = desired,
                fallbackColorArgb = fallback,
                filter = filter,
            ),
        )
    }
}
