package de.dhsn.stundenplan.config

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import de.dhsn.stundenplan.StundenplanWidget
import de.dhsn.stundenplan.data.TimetableRepository
import de.dhsn.stundenplan.data.WidgetConfigStore
import kotlinx.coroutines.launch

class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Default result: cancelled (e.g. user backs out).
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // Pre-fill with the existing class id (if any) so the user can see
        // and edit it when re-opening from the widget's settings.
        lifecycleScope.launch {
            val initial = WidgetConfigStore(applicationContext)
                .getClassId(appWidgetId)
                ?: TimetableRepository.DEFAULT_CLASS_ID

            setContent {
                MaterialTheme {
                    ConfigScreen(initialClassId = initial) { classId ->
                        lifecycleScope.launch {
                            WidgetConfigStore(applicationContext)
                                .saveClassId(appWidgetId, classId)
                            // Warm the cache for this specific widget so the user
                            // doesn't see the dummy placeholder on first render.
                            // Network errors fall back to dummyFor() in the repo.
                            try {
                                TimetableRepository.refresh(applicationContext, appWidgetId)
                            } catch (e: Throwable) {
                                android.util.Log.w(
                                    "WidgetConfigActivity",
                                    "warm-cache refresh failed: ${e.message}",
                                )
                            }
                            StundenplanWidget().updateAll(applicationContext)

                            val resultValue = Intent().apply {
                                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            }
                            setResult(RESULT_OK, resultValue)
                            finish()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigScreen(initialClassId: String, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initialClassId) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Stundenplan-Widget konfigurieren",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Gib das Kürzel deiner Seminargruppe ein (z. B. 3it24-1). ",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            label = { Text("Seminargruppe") },
            isError = error != null,
            supportingText = {
                error?.let { Text(it) }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                val trimmed = text.trim()
                if (trimmed.isEmpty()) {
                    error = "Bitte etwas eingeben"
                } else {
                    onSave(trimmed)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Speichern")
        }
    }
}