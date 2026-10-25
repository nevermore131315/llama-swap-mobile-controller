package io.github.nevermore131315.llamaswapcontroller.data.llamaswap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * GET /api/performance (`handleAPIPerformance` in internal/server/apigroup.go, types in
 * internal/perf/types.go). The body is the server's whole ring buffer (default one hour at one
 * sample per `performance.every`, 5s by default); `?after=<RFC3339>` returns only newer samples.
 * GPU rows from several cards are interleaved, one row per card per tick.
 *
 * When monitoring is disabled the server answers 503 with just `{"enabled":false}`.
 * Every field has a default so a missing or renamed one degrades instead of failing the response.
 * Numbers are decoded as Double even where Go uses int, so a backend that sends fractions still parses.
 */
@Serializable
data class LlamaSwapPerformance(
    val enabled: Boolean = false,
    @SerialName("gpu_stats") val gpuStats: List<LlamaSwapGpuStat> = emptyList(),
    @SerialName("sys_stats") val sysStats: List<LlamaSwapSysStat> = emptyList(),
)

@Serializable
data class LlamaSwapGpuStat(
    /** RFC 3339 with offset and nanoseconds (Go `time.Time`). */
    val timestamp: String = "",
    val id: Int = 0,
    val name: String = "",
    val uuid: String = "",
    /** 0 means not reported (Go zero value). */
    @SerialName("temp_c") val tempC: Double = 0.0,
    @SerialName("vram_temp_c") val vramTempC: Double = 0.0,
    @SerialName("gpu_util_pct") val gpuUtilPct: Double = 0.0,
    @SerialName("mem_util_pct") val memUtilPct: Double = 0.0,
    @SerialName("mem_used_mb") val memUsedMb: Double = 0.0,
    @SerialName("mem_total_mb") val memTotalMb: Double = 0.0,
    @SerialName("fan_speed_pct") val fanSpeedPct: Double = 0.0,
    /** 0 means not reported. */
    @SerialName("power_draw_w") val powerDrawW: Double = 0.0,
)

@Serializable
data class LlamaSwapSysStat(
    val timestamp: String = "",
    @SerialName("cpu_util_per_core") val cpuUtilPerCore: List<Double> = emptyList(),
    @SerialName("mem_total_mb") val memTotalMb: Double = 0.0,
    @SerialName("mem_used_mb") val memUsedMb: Double = 0.0,
    @SerialName("mem_free_mb") val memFreeMb: Double = 0.0,
    @SerialName("swap_total_mb") val swapTotalMb: Double = 0.0,
    @SerialName("swap_used_mb") val swapUsedMb: Double = 0.0,
    @SerialName("load_avg_1") val loadAvg1: Double = 0.0,
    @SerialName("load_avg_5") val loadAvg5: Double = 0.0,
    @SerialName("load_avg_15") val loadAvg15: Double = 0.0,
    // net_io (cumulative per-interface byte counters) is ignored: the tab shows basics only.
)

/** One GPU reading, reduced to what the Performance tab shows. */
data class GpuSample(
    val instant: Instant,
    val tempC: Double,
    val vramTempC: Double,
    val utilPct: Double,
    val memUsedMb: Double,
    val memTotalMb: Double,
    val fanPct: Double,
    val powerW: Double,
) {
    /** VRAM used as 0..100, from used/total (mem_util_pct is the same ratio but may be absent). */
    val memPct: Double get() = if (memTotalMb > 0) (memUsedMb / memTotalMb * 100).coerceIn(0.0, 100.0) else 0.0
}

/** All readings of one card (grouped by `id`), oldest first. */
data class GpuSeries(
    val id: Int,
    val name: String,
    val samples: List<GpuSample>,
) {
    val latest: GpuSample? get() = samples.lastOrNull()

    /** Power is a Go zero value on backends that do not report it. */
    val hasPower: Boolean get() = samples.any { it.powerW > 0 }
}

data class SysSample(
    val instant: Instant,
    /** Mean over cores, 0..100; null when the sample has no per-core data. */
    val cpuPct: Double?,
    val cores: Int,
    val memUsedMb: Double,
    val memTotalMb: Double,
    val swapUsedMb: Double,
    val swapTotalMb: Double,
    val load1: Double,
    val load5: Double,
    val load15: Double,
) {
    val memPct: Double get() = if (memTotalMb > 0) (memUsedMb / memTotalMb * 100).coerceIn(0.0, 100.0) else 0.0
}

/**
 * Client-side copy of the server's history. [newestTimestamp] is the raw server string of the
 * newest sample, sent back verbatim as `?after=` so no precision is lost on the round trip.
 */
data class PerformanceHistory(
    val gpus: List<GpuSeries> = emptyList(),
    val sys: List<SysSample> = emptyList(),
    val newestTimestamp: String? = null,
    val newestInstant: Instant? = null,
) {
    val isEmpty: Boolean get() = gpus.isEmpty() && sys.isEmpty()
}

internal fun parseRfc3339Instant(value: String): Instant? {
    if (value.isBlank()) return null
    return try {
        OffsetDateTime.parse(value.trim()).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun LlamaSwapGpuStat.toSample(instant: Instant) =
    GpuSample(
        instant = instant,
        tempC = tempC,
        vramTempC = vramTempC,
        utilPct = gpuUtilPct,
        memUsedMb = memUsedMb,
        memTotalMb = memTotalMb,
        fanPct = fanSpeedPct,
        powerW = powerDrawW,
    )

private fun LlamaSwapSysStat.toSample(instant: Instant) =
    SysSample(
        instant = instant,
        cpuPct = cpuUtilPerCore.takeIf { it.isNotEmpty() }?.average(),
        cores = cpuUtilPerCore.size,
        memUsedMb = memUsedMb,
        memTotalMb = memTotalMb,
        swapUsedMb = swapUsedMb,
        swapTotalMb = swapTotalMb,
        load1 = loadAvg1,
        load5 = loadAvg5,
        load15 = loadAvg15,
    )

/**
 * Merges a /api/performance response into [current]: groups GPU rows by id (sorted by id), drops
 * duplicate timestamps and rows with unparseable timestamps, sorts each series oldest first, and
 * keeps only samples within [windowMs] of the newest one. Pass an empty [current] for a full reload.
 */
fun mergePerformance(
    current: PerformanceHistory,
    fresh: LlamaSwapPerformance,
    windowMs: Long,
): PerformanceHistory {
    var newestTs = current.newestTimestamp
    var newest = current.newestInstant

    fun note(
        raw: String,
        instant: Instant,
    ) {
        if (newest == null || instant.isAfter(newest)) {
            newest = instant
            newestTs = raw
        }
    }

    val gpuNames = current.gpus.associate { it.id to it.name }.toMutableMap()
    val gpuSamples =
        current.gpus
            .associate { it.id to it.samples }
            .mapValues { it.value.toMutableList() }
            .toMutableMap()
    for (row in fresh.gpuStats) {
        val instant = parseRfc3339Instant(row.timestamp) ?: continue
        note(row.timestamp, instant)
        if (row.name.isNotBlank()) gpuNames[row.id] = row.name
        gpuSamples.getOrPut(row.id) { mutableListOf() } += row.toSample(instant)
    }
    val sysSamples = current.sys.toMutableList()
    for (row in fresh.sysStats) {
        val instant = parseRfc3339Instant(row.timestamp) ?: continue
        note(row.timestamp, instant)
        sysSamples += row.toSample(instant)
    }

    val cutoff = newest?.minusMillis(windowMs)

    fun keep(i: Instant) = cutoff == null || !i.isBefore(cutoff)
    val gpus =
        gpuSamples
            .map { (id, samples) ->
                GpuSeries(
                    id = id,
                    name = gpuNames[id].orEmpty(),
                    samples = samples.filter { keep(it.instant) }.distinctBy { it.instant }.sortedBy { it.instant },
                )
            }.filter { it.samples.isNotEmpty() }
            .sortedBy { it.id }
    val sys = sysSamples.filter { keep(it.instant) }.distinctBy { it.instant }.sortedBy { it.instant }
    return PerformanceHistory(gpus = gpus, sys = sys, newestTimestamp = newestTs, newestInstant = newest)
}
