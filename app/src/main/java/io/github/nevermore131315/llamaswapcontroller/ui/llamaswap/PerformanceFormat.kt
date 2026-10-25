package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import java.util.Locale
import kotlin.math.roundToInt

/** Pure formatting for the Performance tab, kept free of Android types so it is unit-testable. */
internal object PerformanceFormat {
    private const val MB_PER_GIB = 1024.0

    /** Mebibytes as GiB with one decimal: 24576 -> "24.0". */
    fun gib(mb: Double): String = String.format(Locale.ROOT, "%.1f", mb.coerceAtLeast(0.0) / MB_PER_GIB)

    /** Whole percent of a 0..100 value, clamped: 94.6 -> 95. */
    fun percent(pct: Double): Int = pct.coerceIn(0.0, 100.0).roundToInt()

    /** Whole number for temperatures and watts: 30.995 -> 31. */
    fun whole(x: Double): Int = x.roundToInt()

    /** Load average with two decimals: 8.85 -> "8.85". */
    fun load(x: Double): String = String.format(Locale.ROOT, "%.2f", x)

    /** Fraction 0..1 of [used] over [total] for a progress bar; 0 when [total] is unknown. */
    fun fraction(
        used: Double,
        total: Double,
    ): Float = if (total > 0) (used / total).coerceIn(0.0, 1.0).toFloat() else 0f

    /**
     * Reduces [values] to at most [maxPoints] by averaging equal-sized consecutive buckets, so a
     * sparkline draws one point per bucket instead of hundreds per pixel. Order is preserved.
     */
    fun downsample(
        values: List<Double>,
        maxPoints: Int,
    ): List<Double> {
        if (maxPoints <= 0) return emptyList()
        if (values.size <= maxPoints) return values
        val bucket = values.size.toDouble() / maxPoints
        return List(maxPoints) { i ->
            val from = (i * bucket).toInt()
            val to = ((i + 1) * bucket).toInt().coerceAtMost(values.size).coerceAtLeast(from + 1)
            values.subList(from, to).average()
        }
    }

    /** Whole minutes spanned by a window of samples, at least 1 when there is any span. */
    fun spanMinutes(
        firstMs: Long,
        lastMs: Long,
    ): Int {
        val span = (lastMs - firstMs).coerceAtLeast(0)
        return if (span == 0L) 0 else ((span + 30_000) / 60_000).toInt().coerceAtLeast(1)
    }
}
