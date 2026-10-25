package io.github.nevermore131315.llamaswapcontroller.ui.llamaswap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nevermore131315.llamaswapcontroller.R
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapActivityEntry
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapActivityStats
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapHistogram
import io.github.nevermore131315.llamaswapcontroller.data.llamaswap.LlamaSwapStatusKind
import io.github.nevermore131315.llamaswapcontroller.theme.LocalAppStatusColors
import io.github.nevermore131315.llamaswapcontroller.theme.LocalSpacing
import io.github.nevermore131315.llamaswapcontroller.ui.common.EmptyState
import io.github.nevermore131315.llamaswapcontroller.ui.common.ErrorState
import io.github.nevermore131315.llamaswapcontroller.ui.common.SkeletonListState
import io.github.nevermore131315.llamaswapcontroller.ui.common.StatusBadge
import io.github.nevermore131315.llamaswapcontroller.ui.common.StatusBadgeType
import io.github.nevermore131315.llamaswapcontroller.ui.common.listContentPadding
import io.github.nevermore131315.llamaswapcontroller.ui.common.listItemSpacing
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.util.Locale

@Composable
internal fun LlamaSwapActivityTab(
    state: LlamaSwapActivityUiState,
    /** Friendly alias for a model id (from the Models tab's /v1/models data); falls back to the id. */
    displayName: (String) -> String,
    onRetry: () -> Unit,
    onToggle: (Long) -> Unit,
) {
    val now = rememberWallClock()
    val locale = LocalConfiguration.current.locales[0]
    val zone = remember { ZoneId.systemDefault() }

    Column(modifier = Modifier.fillMaxSize()) {
        ActivityStatusBar(state)
        when {
            !state.hasSnapshot && state.loadError != null -> {
                ErrorState(
                    message = stringResource(R.string.llamaswap_activity_error_load, state.baseUrl, state.loadError),
                    onRetry = onRetry,
                )
            }

            !state.hasSnapshot -> {
                SkeletonListState()
            }

            state.entries.isEmpty() -> {
                state.stats?.let {
                    SummaryCard(it, shown = 0, modifier = Modifier.padding(listContentPadding))
                }
                EmptyState(
                    title = stringResource(R.string.llamaswap_activity_empty_title),
                    subtitle = stringResource(R.string.llamaswap_activity_empty_desc, state.baseUrl),
                    icon = Icons.Filled.History,
                    actionLabel = stringResource(R.string.llamaswap_activity_refresh),
                    onAction = onRetry,
                    modifier = Modifier.weight(1f),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    state.stats?.let { stats ->
                        item(key = "summary") { SummaryCard(stats, shown = state.entries.size) }
                    }
                    items(state.entries, key = { it.id }) { entry ->
                        ActivityRow(
                            entry = entry,
                            name = displayName(entry.model),
                            expanded = entry.id in state.expanded,
                            now = now,
                            zone = zone,
                            locale = locale,
                            onToggle = { onToggle(entry.id) },
                        )
                    }
                }
            }
        }
    }
}

/** Wall-clock epoch millis, re-read every [AGE_TICK_MS] so relative ages stay current. */
@Composable
private fun rememberWallClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(AGE_TICK_MS)
        }
    }
    return now
}

private const val AGE_TICK_MS = 15_000L

@Composable
private fun ActivityStatusBar(state: LlamaSwapActivityUiState) {
    val spacing = LocalSpacing.current
    val status = LocalAppStatusColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (state.mode) {
                ActivityUpdateMode.LIVE -> {
                    Icon(Icons.Filled.Sensors, null, tint = status.success, modifier = Modifier.size(18.dp))
                }

                ActivityUpdateMode.POLLING -> {
                    Icon(Icons.Filled.CloudOff, null, tint = status.warning, modifier = Modifier.size(18.dp))
                }

                ActivityUpdateMode.PAUSED -> {
                    Icon(
                        Icons.Filled.PauseCircle,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(spacing.sm))
            Text(
                text =
                    when (state.mode) {
                        ActivityUpdateMode.LIVE -> {
                            stringResource(R.string.llamaswap_activity_mode_live)
                        }

                        ActivityUpdateMode.POLLING -> {
                            stringResource(
                                R.string.llamaswap_activity_mode_polling,
                                (LlamaSwapActivityViewModel.POLL_INTERVAL_MS / 1000).toInt(),
                            )
                        }

                        ActivityUpdateMode.PAUSED -> {
                            stringResource(R.string.llamaswap_activity_mode_paused)
                        }
                    },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
        }
        // With data on screen a failed refresh must not hide it; say so instead.
        if (state.hasSnapshot && state.loadError != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = spacing.xs)) {
                Icon(Icons.Filled.Warning, null, tint = status.warning, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(spacing.sm))
                Text(
                    text = stringResource(R.string.llamaswap_activity_refresh_failed, state.loadError),
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
private fun SummaryCard(
    stats: LlamaSwapActivityStats,
    shown: Int,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell(R.string.llamaswap_activity_stat_requests, ActivityFormat.compactCount(stats.totalRequests))
                StatCell(R.string.llamaswap_activity_stat_prompt, ActivityFormat.compactCount(stats.totalInputTokens))
                StatCell(R.string.llamaswap_activity_stat_cached, ActivityFormat.compactCount(stats.totalCacheTokens))
                StatCell(
                    R.string.llamaswap_activity_stat_generated,
                    ActivityFormat.compactCount(stats.totalOutputTokens),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SpeedLine(R.string.llamaswap_activity_speed_prefill, stats.promptHistogram)
            SpeedLine(R.string.llamaswap_activity_speed_decode, stats.genHistogram)
            if (shown > 0) {
                Text(
                    text = pluralStringResource(R.plurals.llamaswap_activity_showing, shown, shown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RowScope.StatCell(
    labelRes: Int,
    value: String,
) {
    Column(modifier = Modifier.weight(1f)) {
        Text(text = value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Percentiles from llama-swap's speed histogram (tokens/second; rows without a speed are excluded). */
@Composable
private fun SpeedLine(
    labelRes: Int,
    histogram: LlamaSwapHistogram?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text =
                if (histogram == null) {
                    stringResource(R.string.llamaswap_activity_speed_none)
                } else {
                    stringResource(
                        R.string.llamaswap_activity_speed_percentiles,
                        ActivityFormat.speed(histogram.p50),
                        ActivityFormat.speed(histogram.p95),
                        ActivityFormat.speed(histogram.p99),
                    )
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActivityRow(
    entry: LlamaSwapActivityEntry,
    name: String,
    expanded: Boolean,
    now: Long,
    zone: ZoneId,
    locale: Locale,
    onToggle: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val toggleLabel =
        stringResource(if (expanded) R.string.llamaswap_activity_collapse else R.string.llamaswap_activity_expand)
    val epochMs = entry.epochMs
    val tokens = entry.tokens
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = toggleLabel, onClick = onToggle),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name.ifBlank { stringResource(R.string.llamaswap_activity_unknown) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(spacing.sm))
                StatusChip(entry.respStatusCode)
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = spacing.xs).size(20.dp),
                )
            }
            MetaText(
                listOfNotNull(
                    epochMs?.let { ageText(ActivityFormat.relativeAge(now, it)) },
                    epochMs?.let {
                        ActivityFormat.shortTime(
                            it,
                            now,
                            zone,
                            locale,
                        )
                    } ?: entry.timestamp.ifBlank { null },
                    ActivityFormat.duration(entry.durationMs),
                ).joinToString(" · "),
            )
            if (tokens.isEmpty) {
                // Typical for 499 (client went away): say why instead of a row of zeros.
                MetaText(entry.errorMsg.ifBlank { stringResource(R.string.llamaswap_activity_no_tokens) })
            } else {
                Text(
                    text =
                        stringResource(
                            R.string.llamaswap_activity_tokens,
                            ActivityFormat.compactCount(tokens.inputTokens),
                            ActivityFormat.compactCount(tokens.outputTokens),
                            ActivityFormat.compactCount(tokens.cacheTokens),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val unknown = stringResource(R.string.llamaswap_activity_unknown)
                if (tokens.promptSpeedOrNull != null || tokens.genSpeedOrNull != null) {
                    MetaText(
                        stringResource(
                            R.string.llamaswap_activity_speed_row,
                            tokens.promptSpeedOrNull?.let(ActivityFormat::speed) ?: unknown,
                            tokens.genSpeedOrNull?.let(ActivityFormat::speed) ?: unknown,
                        ),
                    )
                }
                tokens.draftAcceptance?.let { rate ->
                    MetaText(
                        stringResource(
                            R.string.llamaswap_activity_draft_row,
                            ActivityFormat.percent(rate),
                            tokens.draftAccTokens.toInt(),
                            tokens.draftTokens.toInt(),
                        ),
                    )
                }
                if (entry.errorMsg.isNotBlank()) MetaText(entry.errorMsg)
            }
            if (expanded) ActivityDetails(entry, zone, locale)
        }
    }
}

@Composable
internal fun ageText(age: ActivityFormat.RelativeAge): String {
    val v = age.value.toInt()
    return when (age.unit) {
        ActivityFormat.AgeUnit.JUST_NOW -> stringResource(R.string.llamaswap_activity_age_now)
        ActivityFormat.AgeUnit.SECONDS -> stringResource(R.string.llamaswap_activity_age_s, v)
        ActivityFormat.AgeUnit.MINUTES -> stringResource(R.string.llamaswap_activity_age_m, v)
        ActivityFormat.AgeUnit.HOURS -> stringResource(R.string.llamaswap_activity_age_h, v)
        ActivityFormat.AgeUnit.DAYS -> stringResource(R.string.llamaswap_activity_age_d, v)
    }
}

@Composable
private fun MetaText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun StatusChip(code: Int) {
    val (label, type) =
        when (LlamaSwapStatusKind.of(code)) {
            LlamaSwapStatusKind.OK -> {
                stringResource(R.string.llamaswap_activity_status_ok, code) to StatusBadgeType.SUCCESS
            }

            LlamaSwapStatusKind.CLIENT_DISCONNECTED -> {
                stringResource(R.string.llamaswap_activity_status_disconnected) to StatusBadgeType.WARNING
            }

            LlamaSwapStatusKind.ERROR -> {
                stringResource(R.string.llamaswap_activity_status_error, code) to StatusBadgeType.ERROR
            }

            LlamaSwapStatusKind.UNKNOWN -> {
                stringResource(R.string.llamaswap_activity_status_unknown) to StatusBadgeType.NEUTRAL
            }
        }
    StatusBadge(text = label, status = type)
}

@Composable
private fun ActivityDetails(
    entry: LlamaSwapActivityEntry,
    zone: ZoneId,
    locale: Locale,
) {
    val spacing = LocalSpacing.current
    val t = entry.tokens
    val unknown = stringResource(R.string.llamaswap_activity_unknown)

    fun count(n: Long) = if (n < 0) unknown else ActivityFormat.fullCount(n, locale)
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DetailLine(R.string.llamaswap_activity_d_id, entry.id.toString())
        DetailLine(
            R.string.llamaswap_activity_d_time,
            entry.epochMs?.let { ActivityFormat.fullTime(it, zone) } ?: entry.timestamp.ifBlank { unknown },
        )
        DetailLine(R.string.llamaswap_activity_d_model, entry.model.ifBlank { unknown }, mono = true)
        DetailLine(R.string.llamaswap_activity_d_path, entry.reqPath.ifBlank { unknown }, mono = true)
        DetailLine(
            R.string.llamaswap_activity_d_status,
            if (entry.respStatusCode > 0) entry.respStatusCode.toString() else unknown,
        )
        if (entry.errorMsg.isNotBlank()) DetailLine(R.string.llamaswap_activity_d_error, entry.errorMsg)
        DetailLine(R.string.llamaswap_activity_d_content_type, entry.respContentType.ifBlank { unknown }, mono = true)
        DetailLine(R.string.llamaswap_activity_d_duration, ActivityFormat.duration(entry.durationMs))
        DetailLine(R.string.llamaswap_activity_d_input, count(t.inputTokens))
        DetailLine(R.string.llamaswap_activity_d_cached, count(t.cacheTokens))
        DetailLine(R.string.llamaswap_activity_d_output, count(t.outputTokens))
        if (t.draftTokens > 0) {
            DetailLine(R.string.llamaswap_activity_d_drafted, count(t.draftTokens))
            DetailLine(
                R.string.llamaswap_activity_d_draft_accepted,
                count(t.draftAccTokens) + (t.draftAcceptance?.let { " (${ActivityFormat.percent(it)}%)" } ?: ""),
            )
        }
        DetailLine(R.string.llamaswap_activity_d_prefill, speedText(t.promptSpeedOrNull, unknown))
        DetailLine(R.string.llamaswap_activity_d_decode, speedText(t.genSpeedOrNull, unknown))
        DetailLine(
            R.string.llamaswap_activity_d_capture,
            stringResource(if (entry.hasCapture) R.string.llamaswap_activity_yes else R.string.llamaswap_activity_no),
        )
        entry.metadata.toSortedMap().forEach { (k, v) -> DetailLine(k, v, mono = true) }
        DetailLine(R.string.llamaswap_activity_d_src, entry.src.ifBlank { unknown }, mono = true)
        Text(
            text = stringResource(R.string.llamaswap_activity_src_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun speedText(
    tokensPerSecond: Double?,
    unknown: String,
): String =
    tokensPerSecond?.let { stringResource(R.string.llamaswap_activity_tps, ActivityFormat.speed(it)) } ?: unknown

@Composable
private fun DetailLine(
    labelRes: Int,
    value: String,
    mono: Boolean = false,
) = DetailLine(stringResource(labelRes), value, mono)

@Composable
private fun DetailLine(
    label: String,
    value: String,
    mono: Boolean = false,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(0.58f),
        )
    }
}
