package com.avi.journal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.avi.journal.ui.theme.AppTheme
import com.avi.journal.ui.theme.JournalTheme

/**
 * Shown when someone taps through from Health Connect to ask what this app
 * wants their data for. Health Connect requires it, and it is the one place
 * where the answer has to be plain rather than marketing.
 */
class PermissionsRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JournalTheme {
                val palette = AppTheme.palette
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Top,
                ) {
                    Text("Why Journal asks for this", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))

                    Paragraph(
                        "Journal reads two things from Health Connect: your daily step " +
                            "count, and how long you slept."
                    )
                    Paragraph(
                        "They are shown quietly next to what you wrote that day, and used " +
                            "to compare how your days feel against how you slept and moved — " +
                            "for example, whether you tend to rate days better after a longer " +
                            "night."
                    )
                    Paragraph(
                        "Both are optional. If you say no, every other part of the app works " +
                            "exactly the same; the comparisons simply don't appear."
                    )
                    Paragraph(
                        "Journal never writes anything to Health Connect, and never copies " +
                            "your health data to its own servers. It is read on your phone, " +
                            "used on your phone, and not stored anywhere else."
                    )
                    Paragraph(
                        "You can withdraw access at any time from Health Connect's own " +
                            "settings."
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Paragraph(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(bottom = 14.dp),
    )
}
