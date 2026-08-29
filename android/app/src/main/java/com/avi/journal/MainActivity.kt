package com.avi.journal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.health.connect.client.PermissionController
import com.avi.journal.data.LockState
import com.avi.journal.data.ThemeChoice
import com.avi.journal.ui.AppScaffold
import com.avi.journal.ui.LoadingScreen
import com.avi.journal.ui.LockScreen
import com.avi.journal.ui.SignInScreen
import com.avi.journal.ui.theme.JournalTheme

class MainActivity : ComponentActivity() {

    // Held at the activity so onPause can reach the same instance the
    // composition uses, without a ViewModelProvider lookup at the call site.
    private val model: JournalViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by model.state.collectAsState()

            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (state.theme) {
                ThemeChoice.SYSTEM -> systemDark
                ThemeChoice.LIGHT -> false
                ThemeChoice.DARK -> true
            }

            // The system draws the bars, so it has to be told which way the app
            // went. Without this, forcing Light on a dark phone leaves white
            // status icons on a white header.
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        TRANSPARENT_SCRIM,
                        TRANSPARENT_SCRIM,
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        TRANSPARENT_SCRIM,
                        TRANSPARENT_SCRIM,
                    ) { darkTheme },
                )
            }

            JournalTheme(darkTheme = darkTheme) {

                // Health Connect's own contract, not a plain permission request:
                // on Android 14+ these are platform permissions, and on 13 and
                // below they are granted inside the Health Connect app.
                val healthLauncher = rememberLauncherForActivityResult(
                    PermissionController.createRequestPermissionResultContract()
                ) { model.refreshHealth() }

                val locationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    // Only act on success. A denial has already been answered by
                    // the system dialog; re-explaining it here would be nagging.
                    if (granted) model.fetchPlace()
                }

                when {
                    state.loading -> LoadingScreen()

                    state.user == null -> SignInScreen(
                        configured = state.firebaseConfigured,
                        signingIn = state.signingIn,
                        message = state.message,
                        onSignIn = { model.signIn(this@MainActivity) },
                    )

                    state.locked -> LockScreen(
                        length = (state.lock as? LockState.Pin)?.length ?: 4,
                        onSubmit = model::submitPin,
                    )

                    else -> AppScaffold(
                        state = state,
                        viewModel = model,
                        onRequestHealthPermissions = {
                            healthLauncher.launch(model.healthPermissions())
                        },
                        onRequestLocationPermission = {
                            locationLauncher.launch(model.locationPermission())
                        },
                        onSignIn = { model.signIn(this@MainActivity) },
                    )
                }
            }
        }
    }

    /**
     * Health permissions can be granted or revoked from outside the app, so the
     * grant state is re-read on every resume rather than trusted from launch.
     */
    override fun onResume() {
        super.onResume()
        if (model.state.value.user != null && !model.state.value.locked) {
            model.refreshHealth()
        }
    }

    /**
     * Anything in the editor is written when the app leaves the foreground.
     *
     * Autosave already runs on a short debounce, but a process killed in the
     * background would never get to fire it — and losing the last sentence
     * someone typed is exactly the failure a journal cannot have.
     */
    override fun onPause() {
        super.onPause()
        model.commitNow()
    }

    private companion object {
        const val TRANSPARENT_SCRIM = android.graphics.Color.TRANSPARENT
    }
}
