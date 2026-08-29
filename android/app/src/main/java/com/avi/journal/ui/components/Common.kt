package com.avi.journal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.avi.journal.data.DayHealth
import com.avi.journal.ui.theme.AppTheme

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val palette = AppTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(palette.cardSurface)
            .border(1.dp, palette.hairline, RoundedCornerShape(22.dp))
            .padding(18.dp),
    ) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = palette.subtleText,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        content()
    }
}

@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    val palette = AppTheme.palette
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(palette.cardSurface)
            .border(1.dp, palette.hairline, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = palette.subtleText,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/**
 * The day's steps and sleep, shown beside what was written.
 *
 * Deliberately quiet: small, muted, no goal rings and no encouragement. The
 * health data is context for the writing, not a second app competing with it —
 * and a journal that congratulates you on your step count on a bad day would be
 * getting the priority exactly backwards.
 *
 * Renders nothing at all when there is nothing to say, rather than showing
 * dashes, which would read as "you did zero" instead of "not recorded".
 */
@Composable
fun HealthContextRow(
    reading: DayHealth?,
    modifier: Modifier = Modifier,
) {
    val palette = AppTheme.palette
    val steps = reading?.steps
    val sleep = reading?.sleepHours
    if (steps == null && sleep == null) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (steps != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepsIcon(tint = palette.subtleText, contentDescription = "Steps", size = 15.dp)
                Text(
                    text = "%,d".format(steps),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.subtleText,
                )
            }
        }
        if (sleep != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MoonIcon(tint = palette.subtleText, contentDescription = "Sleep", size = 15.dp)
                Text(
                    text = "%.1f h".format(sleep),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.subtleText,
                )
            }
        }
    }
}

@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = AppTheme.palette
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) palette.accent else Color.Transparent)
            .border(
                1.dp,
                if (selected) palette.accent else palette.hairline,
                RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else palette.subtleText,
        )
    }
}

/** Horizontal bar used by the weekday and mood-distribution rows. */
@Composable
fun BarRow(
    label: String,
    fraction: Float,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val palette = AppTheme.palette
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.subtleText,
            modifier = Modifier.width(88.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(palette.padSurface),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = palette.subtleText,
            modifier = Modifier.width(44.dp),
        )
    }
}
