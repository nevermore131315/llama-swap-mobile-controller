package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import io.github.nevermore131315.llamaswapcontroller.ui.llamaswap.ActivityFormat.AgeUnit
import io.github.nevermore131315.llamaswapcontroller.ui.llamaswap.ActivityFormat.RelativeAge
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class ActivityFormatTest {
    private val now = Instant.parse("2026-10-24T22:51:56Z").toEpochMilli()
    private val chicago = ZoneId.of("America/Chicago")

    @Test
    fun `relative age buckets`() {
        assertEquals(RelativeAge(AgeUnit.JUST_NOW, 0), ActivityFormat.relativeAge(now, now - 9_999))
        assertEquals(RelativeAge(AgeUnit.SECONDS, 10), ActivityFormat.relativeAge(now, now - 10_000))
        assertEquals(RelativeAge(AgeUnit.SECONDS, 59), ActivityFormat.relativeAge(now, now - 59_999))
        assertEquals(RelativeAge(AgeUnit.MINUTES, 2), ActivityFormat.relativeAge(now, now - 150_000))
        assertEquals(RelativeAge(AgeUnit.HOURS, 1), ActivityFormat.relativeAge(now, now - 3_600_000))
        assertEquals(RelativeAge(AgeUnit.DAYS, 3), ActivityFormat.relativeAge(now, now - 3 * 86_400_000L - 5))
    }

    @Test
    fun `future timestamps from clock skew read as just now`() {
        assertEquals(AgeUnit.JUST_NOW, ActivityFormat.relativeAge(now, now + 120_000).unit)
    }

    @Test
    fun `compact counts`() {
        assertEquals("0", ActivityFormat.compactCount(0))
        assertEquals("0", ActivityFormat.compactCount(-1))
        assertEquals("999", ActivityFormat.compactCount(999))
        assertEquals("1k", ActivityFormat.compactCount(1_000))
        assertEquals("1.2k", ActivityFormat.compactCount(1_234))
        assertEquals("10k", ActivityFormat.compactCount(9_960))
        assertEquals("68k", ActivityFormat.compactCount(67_685))
        assertEquals("1M", ActivityFormat.compactCount(999_600))
        assertEquals("1.5M", ActivityFormat.compactCount(1_499_999))
        assertEquals("12M", ActivityFormat.compactCount(12_345_678))
    }

    @Test
    fun `full counts are grouped per locale`() {
        assertEquals("67,685", ActivityFormat.fullCount(67_685, Locale.US))
        assertEquals("67.685", ActivityFormat.fullCount(67_685, Locale.GERMANY))
    }

    @Test
    fun `speeds keep one decimal below 100`() {
        assertEquals("41.3", ActivityFormat.speed(41.27))
        assertEquals("99.9", ActivityFormat.speed(99.94))
        assertEquals("1235", ActivityFormat.speed(1234.5))
    }

    @Test
    fun `draft percent rounds`() {
        assertEquals(53, ActivityFormat.percent(45.0 / 85.0))
        assertEquals(100, ActivityFormat.percent(1.0))
    }

    @Test
    fun `durations`() {
        assertEquals("850 ms", ActivityFormat.duration(850))
        assertEquals("0 ms", ActivityFormat.duration(-5))
        assertEquals("4.2 s", ActivityFormat.duration(4_210))
        assertEquals("1m 00s", ActivityFormat.duration(60_025))
        assertEquals("12m 05s", ActivityFormat.duration(725_000))
        assertEquals("1h 02m", ActivityFormat.duration(3_725_000))
    }

    @Test
    fun `short time shows seconds today, date otherwise`() {
        val today = Instant.parse("2026-10-24T22:40:02Z").toEpochMilli()
        val earlierThisYear = Instant.parse("2026-10-23T22:40:02Z").toEpochMilli()
        val lastYear = Instant.parse("2025-12-31T23:40:02Z").toEpochMilli()

        assertEquals("17:40:02", ActivityFormat.shortTime(today, now, chicago, Locale.US))
        assertEquals("Oct 23 17:40", ActivityFormat.shortTime(earlierThisYear, now, chicago, Locale.US))
        assertEquals("2025-12-31 17:40", ActivityFormat.shortTime(lastYear, now, chicago, Locale.US))
    }

    @Test
    fun `full time is local with offset`() {
        val t = Instant.parse("2026-10-24T22:40:02Z").toEpochMilli()
        assertEquals("2026-10-24 17:40:02 -05:00", ActivityFormat.fullTime(t, chicago))
        assertEquals("2026-10-24 22:40:02 +00:00", ActivityFormat.fullTime(t, ZoneId.of("UTC")))
    }
}
