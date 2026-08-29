package com.avi.journal.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avi.journal.data.pleasantnessLabel
import com.avi.journal.ui.theme.AppTheme

/**
 * One tap, five steps.
 *
 * This replaced a two-axis pad (pleasantness × energy). The pad encoded more,
 * but it cost more at exactly the wrong moment: a journal's failure mode is not
 * shallow data, it is not opening the app, so the daily action has to stay
 * cheap. Two problems in particular:
 *
 *  · Energy is a much harder question to answer than pleasantness, and an
 *    inconsistently-reported axis adds noise to the sleep/steps comparisons
 *    rather than sharpening them — which was the whole reason for adding it.
 *  · A continuous pad implies a precision nobody has. Nobody can really mean
 *    3.7 rather than 3.4. Five discrete steps are honest about the resolution
 *    of the underlying judgement, and are far easier to fill in from memory
 *    when catching up on an earlier day.
 *
 * `Entry.energy` is kept in the schema so anything logged with the old pad
 * survives and can still be shown — this control simply never sets it.
 */
@Composable
fun MoodPicker(
    pleasantness: Float?,
    onSelect: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = AppTheme.palette
    val haptics = LocalHapticFeedback.current
    val selected = pleasantness?.let { Math.round(it).coerceIn(1, 5) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (1..5).forEach { step ->
                val isOn = selected == step
                val color = palette.moodRamp[step - 1]
                val label = pleasantnessLabel(step.toFloat())

                // Grows a little when chosen, so the selection is legible from
                // colour AND size — colour alone would fail for anyone who
                // can't separate the two ends of the ramp.
                val dot by animateDpAsState(
                    targetValue = if (isOn) 30.dp else 22.dp,
                    animationSpec = spring(),
                    label = "dot",
                )
                val ringAlpha by animateFloatAsState(
                    targetValue = if (isOn) 0.22f else 0f,
                    animationSpec = spring(),
                    label = "ring",
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.RadioButton,
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Tapping the current step clears it, so a mood
                            // logged by accident doesn't need a separate control.
                            onSelect(if (isOn) null else step.toFloat())
                        }
                        .semantics {
                            this.selected = isOn
                            contentDescription = label
                        }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.size(44.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (ringAlpha > 0f) {
                            Box(
                                modifier = Modifier
                                    .size(dot + 12.dp)
                                    .clip(CircleShape)
                                    .background(color.copy(alpha = ringAlpha)),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(dot)
                                .clip(CircleShape)
                                .background(if (isOn) color else Color.Transparent)
                                .border(
                                    width = if (isOn) 0.dp else 2.dp,
                                    // Unselected rings carry a muted version of
                                    // their OWN step rather than a uniform grey,
                                    // so the scale is visible before anything is
                                    // chosen — five identical grey rings say
                                    // nothing about which end is which.
                                    color = if (isOn) Color.Transparent else color.copy(alpha = 0.45f),
                                    shape = CircleShape,
                                ),
                        )
                    }

                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isOn) color else palette.subtleText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        if (selected == null) {
            Text(
                text = "Tap to note how today felt",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.subtleText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        } else {
            Box(Modifier.height(20.dp))
        }
    }
}
