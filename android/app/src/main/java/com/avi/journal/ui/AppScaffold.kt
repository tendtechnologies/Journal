package com.avi.journal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.avi.journal.JournalViewModel
import com.avi.journal.UiState
import com.avi.journal.ui.theme.AppTheme

enum class AppTab(val label: String) {
    TODAY("Today"),
    ENTRIES("Entries"),
    INSIGHTS("Insights"),
    SETTINGS("Settings"),
}

/**
 * Four screens and a bar, with tab state held here rather than in a navigation
 * graph — the same call WeightTracker makes, for the same reason: no back
 * stack, no deep links and no arguments to pass.
 */
@Composable
fun AppScaffold(
    state: UiState,
    viewModel: JournalViewModel,
    onRequestHealthPermissions: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = AppTheme.palette
    var tab by rememberSaveable { mutableStateOf(AppTab.TODAY) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Undo for the trash: any soft-delete (delete button, or emptying a day)
    // offers one-tap restore here, matching the web app's undo toast.
    LaunchedEffect(state.lastDeleted) {
        if (state.lastDeleted == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Entry moved to trash",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete()
        else viewModel.dismissDeletedMessage()
    }

    Column(modifier = modifier.fillMaxSize().background(palette.screenBackground)) {
        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                AppTab.TODAY -> WriteScreen(
                    state = state,
                    viewModel = viewModel,
                    onRequestLocationPermission = onRequestLocationPermission,
                )

                AppTab.ENTRIES -> EntriesScreen(
                    state = state,
                    viewModel = viewModel,
                    onOpenDay = { date ->
                        viewModel.selectDate(date)
                        tab = AppTab.TODAY
                    },
                )

                AppTab.INSIGHTS -> InsightsScreen(
                    state = state,
                    onRequestHealthPermissions = onRequestHealthPermissions,
                )

                AppTab.SETTINGS -> SettingsScreen(
                    state = state,
                    viewModel = viewModel,
                    onRequestHealthPermissions = onRequestHealthPermissions,
                    onSignIn = onSignIn,
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        BottomBar(current = tab, onSelect = { tab = it })
    }
}

@Composable
private fun BottomBar(current: AppTab, onSelect: (AppTab) -> Unit) {
    val palette = AppTheme.palette

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.cardSurface)
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.hairline))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppTab.entries.forEach { option ->
                val selected = option == current
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) palette.accent else Color.Transparent)
                        .clickable { onSelect(option) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else palette.subtleText,
                    )
                }
            }
        }
    }
}
