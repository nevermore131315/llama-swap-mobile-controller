package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Pure formatting for the Activity tab, kept free of Android types so it is unit-testable. */
internal object ActivityFormat {
    enum class AgeUnit { JUST_NOW, SECONDS, MINUTES, HOURS, DAYS }

    data class RelativeAge(
        val unit: AgeUnit,
        val value: Long,
    )

    private const val JUST_NOW_S = 10L
    private const val MINUTE_S = 60L
    private const val HOUR_S = 3_600L
    private const val DAY_S = 86_400L

    /** Age of [thenMs] at [nowMs] (both epoch millis). Future times (clock skew) count as just now. */
    fun relativeAge(
        nowMs: Long,
        thenMs: Long,
    ): RelativeAge {
        val s = (nowMs - thenMs) / 1000
        return when {
            s < JUST_NOW_S -> RelativeAge(AgeUnit.JUST_NOW, 0)
            s < MINUTE_S -> RelativeAge(AgeUnit.SECONDS, s)
            s < HOUR_S -> RelativeAge(AgeUnit.MINUTES, s / MINUTE_S)
            s < DAY_S -> RelativeAge(AgeUnit.HOURS, s / HOUR_S)
            else -> RelativeAge(AgeUnit.DAYS, s / DAY_S)
        }
    }

    /** "999", "1.2k", "68k", "1.5M". Negative (unknown) values format as "0". */
    fun compactCount(n: Long): String {
        val v = n.coerceAtLeast(0)
        return when {
            v < 1_000 -> v.toString()

            // Thresholds sit at the rounding boundary so 999_600 is "1M", not "1000k".
            v < 9_950 -> oneDecimal(v / 1_000.0) + "k"

            v < 999_500 -> (v / 1_000.0).roundToInt().toString() + "k"

            v < 9_950_000 -> oneDecimal(v / 1_000_000.0) + "M"

            else -> (v / 1_000_000.0).roundToInt().toString() + "M"
        }
    }

    private fun oneDecimal(x: Double): String = String.format(Locale.ROOT, "%.1f", x).removeSuffix(".0")

    /** Grouped full count, e.g. "67,685" in en-US. */
    fun fullCount(
        n: Long,
        locale: Locale,
    ): String = NumberFormat.getIntegerInstance(locale).format(n)

    /** Tokens/second without unit: one decimal below 100 ("42.1"), whole above ("1234"). */
    fun speed(tokensPerSecond: Double): String =
        if (tokensPerSecond >= 100) {
            tokensPerSecond.roundToInt().toString()
        } else {
            String.format(Locale.ROOT, "%.1f", tokensPerSecond)
        }

    /** Whole percent of a 0..1 fraction. */
    fun percent(fraction: Double): Int = (fraction * 100).roundToInt()

    /** "850 ms", "12.3 s", "1m 05s", "1h 02m". */
    fun duration(ms: Long): String {
        val v = ms.coerceAtLeast(0)
        return when {
            v < 1_000 -> "$v ms"
            v < 60_000 -> String.format(Locale.ROOT, "%.1f s", v / 1000.0)
            v < 3_600_000 -> String.format(Locale.ROOT, "%dm %02ds", v / 60_000, (v / 1000) % 60)
            else -> String.format(Locale.ROOT, "%dh %02dm", v / 3_600_000, (v / 60_000) % 60)
        }
    }

    /** Short local time: "17:40:02" today, "Oct 23 17:40" this year, else "2025-12-31 17:40". */
    fun shortTime(
        epochMs: Long,
        nowMs: Long,
        zone: ZoneId,
        locale: Locale,
    ): String {
        val then = Instant.ofEpochMilli(epochMs).atZone(zone)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val pattern =
            when {
                then.toLocalDate() == now.toLocalDate() -> "HH:mm:ss"
                then.year == now.year -> "MMM d HH:mm"
                else -> "yyyy-MM-dd HH:mm"
            }
        return DateTimeFormatter.ofPattern(pattern, locale).format(then)
    }

    /** Full local timestamp with offset for the details view: "2026-10-24 17:40:02 -05:00". */
    fun fullTime(
        epochMs: Long,
        zone: ZoneId,
    ): String =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT)
            .format(Instant.ofEpochMilli(epochMs).atZone(zone))
}
