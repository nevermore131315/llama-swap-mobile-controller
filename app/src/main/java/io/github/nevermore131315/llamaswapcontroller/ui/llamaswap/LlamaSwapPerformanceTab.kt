package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nevermore131315.llamaswapcontroller.R
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.GpuSeries
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.SysSample
import io.github.nevermore131315.llamaswapcontroller.theme.LocalHermesStatusColors
import io.github.nevermore131315.llamaswapcontroller.theme.LocalSpacing
import io.github.nevermore131315.llamaswapcontroller.ui.common.EmptyState
import io.github.nevermore131315.llamaswapcontroller.ui.common.ErrorState
import io.github.nevermore131315.llamaswapcontroller.ui.common.SkeletonListState
import io.github.nevermore131315.llamaswapcontroller.ui.common.listContentPadding
import io.github.nevermore131315.llamaswapcontroller.ui.common.listItemSpacing
import kotlinx.coroutines.delay
import java.time.ZoneId

@Composable
internal fun LlamaSwapPerformanceTab(
    state: LlamaSwapPerformanceUiState,
    onRetry: () -> Unit,
) {
    val history = state.history
    when {
        !state.hasSnapshot && state.loadError != null -> {
            ErrorState(
                message = stringResource(R.string.llamaswap_perf_error_load, state.baseUrl, state.loadError),
                onRetry = onRetry,
            )
        }

        !state.hasSnapshot -> {
            SkeletonListState()
        }

        state.enabled == false -> {
            EmptyState(
                title = stringResource(R.string.llamaswap_perf_disabled_title),
                subtitle = stringResource(R.string.llamaswap_perf_disabled_desc, state.baseUrl),
                icon = Icons.Filled.Speed,
                actionLabel = stringResource(R.string.llamaswap_perf_refresh),
                onAction = onRetry,
            )
        }

        history.isEmpty -> {
            EmptyState(
                title = stringResource(R.string.llamaswap_perf_empty_title),
                subtitle = stringResource(R.string.llamaswap_perf_empty_desc, state.baseUrl),
                icon = Icons.Filled.Insights,
                actionLabel = stringResource(R.string.llamaswap_perf_refresh),
                onAction = onRetry,
            )
        }

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = listContentPadding,
                verticalArrangement = listItemSpacing,
            ) {
                item(key = "status") { PerfStatusLine(state) }
                items(history.gpus, key = { "gpu-${it.id}" }) { GpuCard(it) }
                if (history.sys.isNotEmpty()) {
                    item(key = "sys") { SystemCard(history.sys) }
                }
            }
        }
    }
}

/** Wall-clock epoch millis, re-read every [CLOCK_TICK_MS] so "updated Xs ago" stays current. */
@Composable
private fun rememberTickingClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(CLOCK_TICK_MS)
        }
    }
    return now
}

private const val CLOCK_TICK_MS = 5_000L
private const val SPARK_POINTS = 120

@Composable
private fun PerfStatusLine(state: LlamaSwapPerformanceUiState) {
    val spacing = LocalSpacing.current
    val status = LocalHermesStatusColors.current
    val now = rememberTickingClock()
    val locale = LocalConfiguration.current.locales[0]
    val zone = remember { ZoneId.systemDefault() }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        state.history.newestInstant?.let { newest ->
            val ms = newest.toEpochMilli()
            Text(
                text =
                    stringResource(
                        R.string.llamaswap_perf_updated,
                        ageText(ActivityFormat.relativeAge(now, ms)),
                        ActivityFormat.shortTime(ms, now, zone, locale),
                    ),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Text(
            text =
                stringResource(
                    R.string.llamaswap_perf_polling,
                    (LlamaSwapPerformanceViewModel.POLL_INTERVAL_MS / 1000).toInt(),
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // With data on screen a failed poll must not hide it; say so instead.
        state.loadError?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = status.warning, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(spacing.sm))
                Text(
                    text = stringResource(R.string.llamaswap_activity_refresh_failed, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PerfCard(content: @Composable () -> Unit) {
    val spacing = LocalSpacing.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            content()
        }
    }
}

@Composable
private fun GpuCard(gpu: GpuSeries) {
    val latest = gpu.latest ?: return
    val first = gpu.samples.first()
    val span = PerformanceFormat.spanMinutes(first.instant.toEpochMilli(), latest.instant.toEpochMilli())
    val windowLabel = stringResource(R.string.llamaswap_perf_legend_window, span)
    PerfCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.llamaswap_perf_gpu_title, gpu.id, gpu.name),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (latest.tempC > 0) {
                Text(
                    text =
                        if (latest.vramTempC > 0) {
                            stringResource(
                                R.string.llamaswap_perf_temp_vram,
                                PerformanceFormat.whole(latest.tempC),
                                PerformanceFormat.whole(latest.vramTempC),
                            )
                        } else {
                            stringResource(R.string.llamaswap_perf_temp, PerformanceFormat.whole(latest.tempC))
                        },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell(
                R.string.llamaswap_perf_util,
                stringResource(R.string.llamaswap_perf_percent, PerformanceFormat.percent(latest.utilPct)),
            )
            StatCell(
                R.string.llamaswap_perf_fan,
                stringResource(R.string.llamaswap_perf_percent, PerformanceFormat.percent(latest.fanPct)),
            )
            if (gpu.hasPower) {
                StatCell(
                    R.string.llamaswap_perf_power,
                    stringResource(R.string.llamaswap_perf_watts, PerformanceFormat.whole(latest.powerW)),
                )
            }
        }
        UsageBar(R.string.llamaswap_perf_vram, latest.memUsedMb, latest.memTotalMb)
        val primary = MaterialTheme.colorScheme.primary
        val secondary = MaterialTheme.colorScheme.tertiary
        Sparkline(
            series =
                listOf(
                    gpu.samples.map { it.utilPct } to primary,
                    gpu.samples.map { it.memPct } to secondary,
                ),
            description = stringResource(R.string.llamaswap_perf_chart_gpu_desc, windowLabel),
        )
        Legend(
            listOf(
                stringResource(R.string.llamaswap_perf_util) to primary,
                stringResource(R.string.llamaswap_perf_vram) to secondary,
            ),
            windowLabel,
        )
    }
}

@Composable
private fun SystemCard(samples: List<SysSample>) {
    val latest = samples.last()
    val span =
        PerformanceFormat.spanMinutes(samples.first().instant.toEpochMilli(), latest.instant.toEpochMilli())
    val windowLabel = stringResource(R.string.llamaswap_perf_legend_window, span)
    val unknown = stringResource(R.string.llamaswap_activity_unknown)
    PerfCard {
        Text(
            text = stringResource(R.string.llamaswap_perf_system),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell(
                R.string.llamaswap_perf_cpu,
                latest.cpuPct?.let { stringResource(R.string.llamaswap_perf_percent, PerformanceFormat.percent(it)) }
                    ?: unknown,
                caption =
                    if (latest.cores > 0) {
                        pluralStringResource(R.plurals.llamaswap_perf_cores, latest.cores, latest.cores)
                    } else {
                        null
                    },
            )
            StatCell(
                R.string.llamaswap_perf_load,
                stringResource(
                    R.string.llamaswap_perf_load_value,
                    PerformanceFormat.load(latest.load1),
                    PerformanceFormat.load(latest.load5),
                    PerformanceFormat.load(latest.load15),
                ),
                weight = 2f,
            )
        }
        UsageBar(R.string.llamaswap_perf_ram, latest.memUsedMb, latest.memTotalMb)
        if (latest.swapTotalMb > 0) UsageBar(R.string.llamaswap_perf_swap, latest.swapUsedMb, latest.swapTotalMb)
        val primary = MaterialTheme.colorScheme.primary
        val secondary = MaterialTheme.colorScheme.tertiary
        Sparkline(
            series =
                listOf(
                    samples.map { it.cpuPct ?: 0.0 } to primary,
                    samples.map { it.memPct } to secondary,
                ),
            description = stringResource(R.string.llamaswap_perf_chart_sys_desc, windowLabel),
        )
        Legend(
            listOf(
                stringResource(R.string.llamaswap_perf_cpu) to primary,
                stringResource(R.string.llamaswap_perf_ram) to secondary,
            ),
            windowLabel,
        )
    }
}

@Composable
private fun RowScope.StatCell(
    labelRes: Int,
    value: String,
    caption: String? = null,
    weight: Float = 1f,
) {
    Column(modifier = Modifier.weight(weight)) {
        Text(text = value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(
            text = caption?.let { "${stringResource(labelRes)} · $it" } ?: stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UsageBar(
    labelRes: Int,
    usedMb: Double,
    totalMb: Double,
) {
    val spacing = LocalSpacing.current
    val fraction = PerformanceFormat.fraction(usedMb, totalMb)
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text =
                    stringResource(
                        R.string.llamaswap_perf_used_total,
                        PerformanceFormat.gib(usedMb),
                        PerformanceFormat.gib(totalMb),
                        PerformanceFormat.percent(fraction * 100.0),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

/** Lines of 0..100 values over the sample window; one colour per series, fixed 0..100 scale. */
@Composable
private fun Sparkline(
    series: List<Pair<List<Double>, Color>>,
    description: String,
) {
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val reduced = remember(series) { series.map { (v, c) -> PerformanceFormat.downsample(v, SPARK_POINTS) to c } }
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .semantics { contentDescription = description },
    ) {
        val stroke = 1.5.dp.toPx()
        drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        for ((values, color) in reduced) {
            if (values.size < 2) continue
            val step = size.width / (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, v ->
                val y = size.height - (v.coerceIn(0.0, 100.0) / 100.0).toFloat() * (size.height - stroke) - stroke / 2
                if (i == 0) path.moveTo(0f, y) else path.lineTo(i * step, y)
            }
            drawPath(path, color, style = Stroke(width = stroke))
        }
    }
}

@Composable
private fun Legend(
    entries: List<Pair<String, Color>>,
    windowLabel: String,
) {
    val spacing = LocalSpacing.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        entries.forEach { (label, color) ->
            Box(Modifier.size(width = 12.dp, height = 3.dp).background(color))
            Spacer(Modifier.width(spacing.xs))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(spacing.md))
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = windowLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
