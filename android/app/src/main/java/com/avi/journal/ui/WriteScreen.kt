package com.avi.journal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.avi.journal.JournalViewModel
import com.avi.journal.UiState
import com.avi.journal.data.Entry
import com.avi.journal.ui.components.ArrowIcon
import com.avi.journal.ui.components.CloseIcon
import com.avi.journal.ui.components.HealthContextRow
import com.avi.journal.ui.components.MoodPicker
import com.avi.journal.ui.components.PinIcon
import com.avi.journal.ui.components.SectionCard
import com.avi.journal.ui.components.StarIcon
import com.avi.journal.ui.theme.AppTheme
import com.avi.journal.ui.theme.ReadingBody
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val HeaderFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM")

@Composable
fun WriteScreen(
    state: UiState,
    viewModel: JournalViewModel,
    onRequestLocationPermission: () -> Unit,
) {
    val palette = AppTheme.palette
    val draft = state.draft
    val isToday = draft.date == state.today
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 18.dp)
            .padding(top = 12.dp, bottom = 28.dp),
    ) {
        DayHeader(
            date = draft.date,
            isToday = isToday,
            onPrevious = { viewModel.selectDate(draft.date.minusDays(1)) },
            onNext = { if (!isToday) viewModel.selectDate(draft.date.plusDays(1)) },
            onToday = { viewModel.selectDate(state.today) },
        )

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "How was today") {
            // Tapping the selected step clears it, so no separate Clear control.
            MoodPicker(
                pleasantness = draft.pleasantness,
                onSelect = viewModel::updateMood,
            )
        }

        Spacer(Modifier.height(12.dp))

        SectionCard {
            BasicTextField(
                value = draft.title,
                onValueChange = viewModel::updateTitle,
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(palette.accent),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (draft.title.isEmpty()) {
                        Text(
                            "Title (optional)",
                            style = MaterialTheme.typography.titleLarge,
                            color = palette.subtleText,
                        )
                    }
                    inner()
                },
            )

            Spacer(Modifier.height(12.dp))

            BasicTextField(
                value = draft.plain,
                onValueChange = viewModel::updateText,
                textStyle = ReadingBody.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.fillMaxWidth().height(260.dp),
                decorationBox = { inner ->
                    if (draft.plain.isEmpty()) {
                        Text("Start writing…", style = ReadingBody, color = palette.subtleText)
                    }
                    inner()
                },
            )

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${draft.words} ${if (draft.words == 1) "word" else "words"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.subtleText,
                )
                Spacer(Modifier.weight(1f))
                if (state.saving) {
                    Text(
                        "Saving…",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.subtleText,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { viewModel.toggleFavorite() }
                        .padding(6.dp),
                ) {
                    StarIcon(
                        filled = draft.favorite,
                        tint = if (draft.favorite) palette.accent else palette.subtleText,
                        contentDescription = if (draft.favorite) "Remove favourite" else "Mark favourite",
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        PlaceCard(
            place = draft.place,
            locating = state.locatingPlace,
            onSet = viewModel::updatePlace,
            onUseLocation = {
                if (viewModel.hasLocationPermission()) viewModel.fetchPlace()
                else onRequestLocationPermission()
            },
        )

        Spacer(Modifier.height(12.dp))

        TagsCard(
            tags = draft.tags,
            onAdd = viewModel::addTag,
            onRemove = viewModel::removeTag,
        )

        // Health context sits last and unlabelled-as-a-feature: it is background
        // for the day, not something to act on.
        val reading = state.health[draft.date]
        if (reading != null) {
            Spacer(Modifier.height(16.dp))
            HealthContextRow(
                reading = reading,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun DayHeader(
    date: LocalDate,
    isToday: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    val palette = AppTheme.palette
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onPrevious)
                    .padding(8.dp),
            ) {
                ArrowIcon(pointingLeft = true, tint = palette.subtleText, contentDescription = "Previous day")
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (isToday) "Today" else date.format(HeaderFormat),
                    style = MaterialTheme.typography.headlineMedium,
                )
                if (isToday) {
                    Text(
                        text = date.format(HeaderFormat),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.subtleText,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(enabled = !isToday, onClick = onNext)
                    .padding(8.dp),
            ) {
                ArrowIcon(
                    pointingLeft = false,
                    tint = if (isToday) palette.hairline else palette.subtleText,
                    contentDescription = "Next day",
                )
            }
        }

        if (!isToday) {
            Text(
                text = "Back to today",
                style = MaterialTheme.typography.labelLarge,
                color = palette.accent,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onToday)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun PlaceCard(
    place: String?,
    locating: Boolean,
    onSet: (String?) -> Unit,
    onUseLocation: () -> Unit,
) {
    val palette = AppTheme.palette
    var text by remember(place) { mutableStateOf(place.orEmpty()) }

    SectionCard(title = "Place") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PinIcon(tint = palette.subtleText)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    onSet(it)
                },
                textStyle = LocalTextStyle.current.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                ),
                cursorBrush = SolidColor(palette.accent),
                singleLine = true,
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(
                            "Where were you?",
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.subtleText,
                        )
                    }
                    inner()
                },
            )
            if (text.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            text = ""
                            onSet(null)
                        }
                        .padding(6.dp),
                ) {
                    CloseIcon(tint = palette.subtleText, contentDescription = "Clear place", size = 16.dp)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = if (locating) "Finding you…" else "Use my location",
            style = MaterialTheme.typography.labelLarge,
            color = if (locating) palette.subtleText else palette.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(enabled = !locating, onClick = onUseLocation)
                .padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun TagsCard(
    tags: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val palette = AppTheme.palette
    var input by remember { mutableStateOf("") }

    SectionCard(title = "Tags") {
        if (tags.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tags.take(6).forEach { tag ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(palette.accentSoft)
                            .clickable { onRemove(tag) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "#$tag",
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.accent,
                        )
                    }
                }
            }
        }

        BasicTextField(
            value = input,
            onValueChange = { input = it },
            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(palette.accent),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = {
                    onAdd(input)
                    input = ""
                },
            ),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (input.isEmpty()) {
                    Text(
                        "Add a tag…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.subtleText,
                    )
                }
                inner()
            },
        )
    }
}
