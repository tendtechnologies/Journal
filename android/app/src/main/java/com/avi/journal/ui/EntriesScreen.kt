package com.avi.journal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.avi.journal.JournalViewModel
import com.avi.journal.UiState
import com.avi.journal.data.Entry
import com.avi.journal.data.moodPhrase
import com.avi.journal.data.pleasantnessLabel
import com.avi.journal.ui.components.HealthContextRow
import com.avi.journal.ui.components.SearchIcon
import com.avi.journal.ui.components.StarIcon
import com.avi.journal.ui.theme.AppTheme
import com.avi.journal.ui.theme.moodColor
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val CardDate = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")

@Composable
fun EntriesScreen(
    state: UiState,
    viewModel: JournalViewModel,
    onOpenDay: (LocalDate) -> Unit,
) {
    val palette = AppTheme.palette
    val query = state.search.trim().lowercase()

    val visible = state.entries.values
        .filter { entry ->
            if (query.isEmpty()) return@filter true
            (entry.title + " " + entry.plain + " " + entry.tags.joinToString(" ") + " " + entry.place.orEmpty())
                .lowercase()
                .contains(query)
        }
        .sortedByDescending { it.date }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("Entries", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "${visible.size} ${if (visible.size == 1) "entry" else "entries"}",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.subtleText,
        )

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.padSurface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchIcon(tint = palette.subtleText)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = state.search,
                onValueChange = viewModel::setSearch,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (state.search.isEmpty()) {
                        Text(
                            "Search your entries…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.subtleText,
                        )
                    }
                    inner()
                },
            )
        }

        Spacer(Modifier.height(14.dp))

        if (visible.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (query.isEmpty()) "No entries yet" else "Nothing matches",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (query.isEmpty()) {
                        "Head to Today and put down a line."
                    } else {
                        "Try a different search."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.subtleText,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(visible, key = { it.date.toString() }) { entry ->
                    EntryCard(
                        entry = entry,
                        health = state.health[entry.date],
                        onClick = { onOpenDay(entry.date) },
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun EntryCard(
    entry: Entry,
    health: com.avi.journal.data.DayHealth?,
    onClick: () -> Unit,
) {
    val palette = AppTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(palette.cardSurface)
            .border(1.dp, palette.hairline, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The mood dot carries the same colour the pad used, so the list
            // scans as a mood history without needing a legend.
            if (entry.pleasantness != null) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(moodColor(entry.pleasantness)),
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(
                text = entry.date.format(CardDate),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            if (entry.favorite) {
                StarIcon(filled = true, tint = palette.accent, size = 15.dp)
            }
        }

        if (entry.pleasantness != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                // Entries from the old two-axis pad still carry an energy
                // value; show the fuller phrase for those, the plain label
                // for everything else.
                text = entry.energy
                    ?.let { moodPhrase(entry.pleasantness, it) }
                    ?: pleasantnessLabel(entry.pleasantness),
                style = MaterialTheme.typography.labelMedium,
                color = moodColor(entry.pleasantness),
            )
        }

        if (entry.title.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
        }

        if (entry.plain.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = entry.plain,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.subtleText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!entry.place.isNullOrBlank() || entry.tags.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                entry.place?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = palette.subtleText)
                }
                entry.tags.take(3).forEach {
                    Text("#$it", style = MaterialTheme.typography.labelMedium, color = palette.subtleText)
                }
            }
        }

        if (health != null) {
            Spacer(Modifier.height(10.dp))
            HealthContextRow(reading = health)
        }
    }
}
