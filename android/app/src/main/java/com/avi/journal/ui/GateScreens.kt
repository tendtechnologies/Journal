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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avi.journal.ui.components.CloseIcon
import com.avi.journal.ui.theme.AppTheme

@Composable
fun LoadingScreen() {
    val palette = AppTheme.palette
    Box(
        modifier = Modifier.fillMaxSize().background(palette.screenBackground),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = palette.accent, strokeWidth = 2.5.dp)
    }
}

@Composable
fun SignInScreen(
    configured: Boolean,
    signingIn: Boolean,
    message: String?,
    onSignIn: () -> Unit,
) {
    val palette = AppTheme.palette
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.screenBackground)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Journal", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "A quiet place to write. Your entries sync with the web app.",
            style = MaterialTheme.typography.bodyLarge,
            color = palette.subtleText,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        if (!configured) {
            // Sign-in genuinely cannot work, so say why rather than showing a
            // button that will always fail.
            // Deliberately not phrased as a failure: the app built and ran
            // fine, it just has no sign-in client yet. The earlier wording read
            // like a build error and was mistaken for one.
            Text(
                text = "Sign-in isn't set up on this build yet",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Register this app in the Firebase console and add " +
                    "app/google-services.json, then rebuild.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.subtleText,
                textAlign = TextAlign.Center,
            )
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.accent)
                    .clickable(enabled = !signingIn, onClick = onSignIn)
                    .padding(horizontal = 26.dp, vertical = 15.dp),
            ) {
                Text(
                    text = if (signingIn) "Signing in…" else "Continue with Google",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        if (message != null) {
            Spacer(Modifier.height(18.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The PIN screen, verifying the same record the web app writes.
 *
 * Setting or clearing a PIN deliberately lives only in the browser, so there is
 * one place that owns the format; this screen can only unlock.
 */
@Composable
fun LockScreen(
    length: Int,
    onSubmit: (String) -> Boolean,
) {
    val palette = AppTheme.palette
    var buffer by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    fun press(digit: String) {
        if (buffer.length >= length) return
        error = false
        buffer += digit
        if (buffer.length == length) {
            if (!onSubmit(buffer)) {
                error = true
                buffer = ""
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.screenBackground)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Enter PIN", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (error) "Incorrect PIN" else "Unlock your journal",
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else palette.subtleText,
        )

        Spacer(Modifier.height(26.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(length) { index ->
                Box(
                    modifier = Modifier
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(if (index < buffer.length) palette.accent else Color.Transparent)
                        .border(1.5.dp, palette.hairline, CircleShape),
                )
            }
        }

        Spacer(Modifier.height(34.dp))
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("", "0", "⌫"),
        ).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(if (key.isBlank()) Color.Transparent else palette.padSurface)
                            .clickable(enabled = key.isNotBlank()) {
                                if (key == "⌫") {
                                    buffer = buffer.dropLast(1)
                                    error = false
                                } else {
                                    press(key)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key == "⌫") {
                            CloseIcon(tint = palette.subtleText, contentDescription = "Backspace")
                        } else if (key.isNotBlank()) {
                            Text(key, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}
