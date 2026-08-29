package com.avi.journal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.avi.journal.UiState
import com.avi.journal.data.Finding
import com.avi.journal.data.HealthAvailability
import com.avi.journal.data.Insights
import com.avi.journal.data.pleasantnessLabel
import com.avi.journal.ui.components.BarRow
import com.avi.journal.ui.components.SectionCard
import com.avi.journal.ui.components.StatTile
import com.avi.journal.ui.theme.AppTheme
import com.avi.journal.ui.theme.moodColor
import java.time.LocalDate

@Composable
fun InsightsScreen(
    state: UiState,
    onRequestHealthPermissions: () -> Unit,
) {
    val palette = AppTheme.palette
    val entries = state.entries.values
    val today = LocalDate.now()
    val streaks = Insights.streaks(state.entryDates, today)

    val moods = entries.mapNotNull { it.pleasantness }
    val averageMood = if (moods.isEmpty()) null else moods.average()
    val totalWords = entries.sumOf { it.words }

    val sleepFinding = Insights.sleepVsMood(entries, state.health)
    val stepsFinding = Insights.stepsVsMood(entries, state.health)
    val shortfall = Insights.daysUntilFindings(entries, state.health)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 12.dp, bottom = 28.dp),
    ) {
        Text("Insights", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Across ${entries.size} ${if (entries.size == 1) "entry" else "entries"}",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.subtleText,
        )

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile(
                value = streaks.current.toString(),
                label = "Current streak",
                accent = palette.accent,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = streaks.longest.toString(),
                label = "Longest streak",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile(
                value = averageMood?.let { "%.1f".format(it) } ?: "—",
                label = "Avg pleasantness",
                accent = averageMood?.let { moodColor(it.toFloat()) },
                modifier = Modifier.weight(1f),
            )
            StatTile(
                value = "%,d".format(totalWords),
                label = "Total words",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(14.dp))

        // ── Correlations ─────────────────────────────────────────────────
        when {
            state.healthAvailability != HealthAvailability.AVAILABLE -> {
                SectionCard(title = "Mood and health") {
                    Text(
                        text = "Health Connect isn't available on this device, so steps " +
                            "and sleep can't be read.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.subtleText,
                    )
                }
            }

            !state.healthGranted -> {
                SectionCard(title = "Mood and health") {
                    Text(
                        text = "Connect steps and sleep to see how they line up with how " +
                            "your days feel. Nothing is uploaded — the numbers stay on your phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.subtleText,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Connect Health Connect",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(palette.accent)
                            .clickable(onClick = onRequestHealthPermissions)
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }

            sleepFinding == null && stepsFinding == null -> {
                // Deliberately explicit about WHY there's nothing here. An empty
                // card reads as broken; a number to work toward doesn't.
                SectionCard(title = "Mood and health") {
                    Text(
                        text = if (shortfall > 0) {
                            "Not enough overlap yet. Log a mood on $shortfall more " +
                                "${if (shortfall == 1) "day" else "days"} that also have steps " +
                                "or sleep, and comparisons will show up here."
                        } else {
                            "No clear pattern between your mood and your steps or sleep so far."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.subtleText,
                    )
                }
            }

            else -> {
                SectionCard(title = "Mood and health") {
                    sleepFinding?.let { FindingRow(it) }
                    if (sleepFinding != null && stepsFinding != null) {
                        Spacer(Modifier.height(14.dp))
                    }
                    stepsFinding?.let { FindingRow(it) }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "These compare averages, not causes — a good week can raise " +
                            "both numbers at once.",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.subtleText,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Mood distribution ────────────────────────────────────────────
        if (moods.isNotEmpty()) {
            val buckets = (1..5).map { step ->
                step to moods.count { Math.round(it).coerceIn(1, 5) == step }
            }
            val max = buckets.maxOf { it.second }.coerceAtLeast(1)

            SectionCard(title = "How often each mood") {
                buckets.forEach { (step, count) ->
                    BarRow(
                        label = pleasantnessLabel(step.toFloat()),
                        fraction = count.toFloat() / max,
                        value = count.toString(),
                        color = moodColor(step.toFloat()),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── Weekday rates ────────────────────────────────────────────────
        val weekdays = Insights.weekdayRates(state.entryDates, today)
        if (state.entries.isNotEmpty()) {
            SectionCard(title = "Which days you write") {
                Text(
                    text = "Share of each weekday you've journaled on, since your first entry.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.subtleText,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                val max = weekdays.maxOf { it.rate }.coerceAtLeast(0.0001f)
                weekdays.forEach { day ->
                    BarRow(
                        label = day.dayName,
                        fraction = day.rate / max,
                        value = "${Math.round(day.rate * 100)}%",
                        color = palette.accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun FindingRow(finding: Finding) {
    val palette = AppTheme.palette
    Column {
        Text(finding.headline, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = finding.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.subtleText,
        )
    }
}
