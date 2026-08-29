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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.avi.journal.JournalViewModel
import com.avi.journal.UiState
import com.avi.journal.data.HealthAvailability
import com.avi.journal.data.LockState
import com.avi.journal.data.ThemeChoice
import com.avi.journal.ui.components.SectionCard
import com.avi.journal.ui.theme.AppTheme

@Composable
fun SettingsScreen(
    state: UiState,
    viewModel: JournalViewModel,
    onRequestHealthPermissions: () -> Unit,
    onSignIn: () -> Unit,
) {
    val palette = AppTheme.palette

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 12.dp, bottom = 28.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(14.dp))

        SectionCard(title = "Account") {
            Text(
                text = state.user?.displayName ?: state.user?.email ?: "Not signed in",
                style = MaterialTheme.typography.titleMedium,
            )
            state.user?.email?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.subtleText)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Entries sync with the web app — same account, same days.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.subtleText,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Sync now", filled = false) { viewModel.refresh() }
                if (state.user != null) {
                    ActionButton("Sign out", filled = false) { viewModel.signOut() }
                } else {
                    ActionButton("Sign in", filled = true, onClick = onSignIn)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(title = "Appearance") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Theme", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = when (state.theme) {
                            ThemeChoice.SYSTEM -> "Following your phone's setting"
                            ThemeChoice.LIGHT -> "Always light"
                            ThemeChoice.DARK -> "Always dark"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.subtleText,
                    )
                }
                ActionButton(state.theme.label, filled = false) { viewModel.cycleTheme() }
            }
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(title = "Steps and sleep") {
            val body = when {
                state.healthAvailability == HealthAvailability.UNAVAILABLE ->
                    "Health Connect isn't installed on this device."
                state.healthAvailability == HealthAvailability.UPDATE_REQUIRED ->
                    "Health Connect needs updating before it can share data."
                state.healthGranted ->
                    "Connected. Steps and sleep appear alongside each day and feed the " +
                        "comparisons in Insights."
                else ->
                    "Not connected. Both are optional — everything else works without them."
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = palette.subtleText)

            if (state.healthAvailability == HealthAvailability.AVAILABLE && !state.healthGranted) {
                Spacer(Modifier.height(14.dp))
                ActionButton("Connect Health Connect", filled = true, onClick = onRequestHealthPermissions)
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "Health data is read only, and never copied to the cloud — it stays " +
                    "on your phone in Health Connect, which already follows your Google " +
                    "account to a new device.",
                style = MaterialTheme.typography.labelMedium,
                color = palette.subtleText,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Sleep is counted against the day you woke up, not the day you went " +
                    "to bed.",
                style = MaterialTheme.typography.labelMedium,
                color = palette.subtleText,
            )
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(title = "Location") {
            Text(
                text = "Nothing is captured automatically. Tapping \"use my location\" on an " +
                    "entry looks up a coarse place name once and stores only the name — no " +
                    "coordinates, and no history.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.subtleText,
            )
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(title = "Privacy") {
            Text(
                text = when (state.lock) {
                    is LockState.Pin -> "PIN lock is on for your account. Change or remove it " +
                        "from the web app, which owns the PIN."
                    LockState.None -> "No PIN lock set. You can turn one on in the web app and " +
                        "it will apply here too."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = palette.subtleText,
            )
            Spacer(Modifier.height(12.dp))
            // Worth stating plainly rather than letting a lock icon imply more
            // than it delivers.
            Text(
                text = "The PIN is a screen lock, not encryption. Entries are stored " +
                    "unencrypted, so anyone with access to your Google account can read " +
                    "them. It stops someone picking up an unlocked phone.",
                style = MaterialTheme.typography.labelMedium,
                color = palette.subtleText,
            )
        }

        if (state.message != null) {
            Spacer(Modifier.height(12.dp))
            SectionCard {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(10.dp))
                ActionButton("Dismiss", filled = false) { viewModel.dismissMessage() }
            }
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val palette = AppTheme.palette
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) palette.accent else palette.padSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (filled) MaterialTheme.colorScheme.onPrimary else palette.subtleText,
        )
    }
}
