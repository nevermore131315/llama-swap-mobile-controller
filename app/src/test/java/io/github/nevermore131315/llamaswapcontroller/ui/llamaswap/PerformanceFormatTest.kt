package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceFormatTest {
    @Test
    fun `gib formats mebibytes with one decimal`() {
        assertEquals("24.0", PerformanceFormat.gib(24576.0))
        assertEquals("19.7", PerformanceFormat.gib(20127.0))
        assertEquals("0.0", PerformanceFormat.gib(-5.0))
    }

    @Test
    fun `percent rounds and clamps`() {
        assertEquals(95, PerformanceFormat.percent(94.62))
        assertEquals(0, PerformanceFormat.percent(-1.0))
        assertEquals(100, PerformanceFormat.percent(100.4))
    }

    @Test
    fun `whole load and fraction`() {
        assertEquals(31, PerformanceFormat.whole(30.995))
        assertEquals("8.85", PerformanceFormat.load(8.85))
        assertEquals("17.50", PerformanceFormat.load(17.5))
        assertEquals(0.5f, PerformanceFormat.fraction(512.0, 1024.0), 0f)
        assertEquals(0f, PerformanceFormat.fraction(512.0, 0.0), 0f)
        assertEquals(1f, PerformanceFormat.fraction(2048.0, 1024.0), 0f)
    }

    @Test
    fun `downsample averages buckets and keeps short series`() {
        assertEquals(listOf(1.0, 2.0), PerformanceFormat.downsample(listOf(1.0, 2.0), 5))
        assertEquals(listOf(1.5, 3.5), PerformanceFormat.downsample(listOf(1.0, 2.0, 3.0, 4.0), 2))
        val big = List(720) { it.toDouble() }
        val reduced = PerformanceFormat.downsample(big, 120)
        assertEquals(120, reduced.size)
        assertEquals(2.5, reduced.first(), 0.0)
        assertEquals(716.5, reduced.last(), 0.0)
        assertEquals(emptyList<Double>(), PerformanceFormat.downsample(big, 0))
    }

    @Test
    fun `span minutes rounds to nearest minute`() {
        assertEquals(0, PerformanceFormat.spanMinutes(1000, 1000))
        assertEquals(1, PerformanceFormat.spanMinutes(0, 5_000))
        assertEquals(60, PerformanceFormat.spanMinutes(0, 3_595_000))
    }
}
