package de.dhsn.stundenplan.config

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import de.dhsn.stundenplan.StundenplanWidget
import de.dhsn.stundenplan.StundenplanWidgetReceiver
import de.dhsn.stundenplan.data.TimetableRepository
import de.dhsn.stundenplan.data.WidgetConfigStore
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Settings / configuration screen for the Stundenplan widget.
 *
 * Lists every currently active widget instance and lets the user view / edit
 * the class id (e.g. "3it24-1") stored for that widget, refresh the data
 * on demand, or remove the configuration.
 *
 * State is intentionally local: there is no shared ViewModel because the
 * underlying [WidgetConfigStore] and [TimetableRepository] are themselves
 * the single source of truth.
 */
class MainActivity : ComponentActivity() {

    private val rows = mutableStateListOf<WidgetRowState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SettingsScreen(
                    rows = rows,
                    onAddWidget = { requestPinWidget() },
                    onSaveClassId = { id, value -> saveClassId(id, value) },
                    onDelete = { id -> deleteRow(id) },
                    onRefresh = { id -> refreshRow(id) },
                )
            }
        }

        // Initial population of the list of active widgets.
        reload()
    }

    override fun onResume() {
        super.onResume()
        // The user may have added or removed a widget from the launcher
        // while the app was in the background. Re-querying is cheap.
        reload()
    }

    /**
     * Re-query the system for the currently pinned widget instances and
     * rebuild the per-row state. Called on first frame, on resume, and
     * after deletions.
     */
    private fun reload() {
        lifecycleScope.launch {
            val mgr = GlanceAppWidgetManager(this@MainActivity)
            val glanceIds = mgr.getGlanceIds(StundenplanWidget::class.java)
            val configStore = WidgetConfigStore(applicationContext)

            val newRows = glanceIds.map { glanceId ->
                val appWidgetId = mgr.getAppWidgetId(glanceId)
                val stored = configStore.getClassId(appWidgetId)
                val initialText = stored?.takeIf { it.isNotBlank() }
                    ?: TimetableRepository.DEFAULT_CLASS_ID
                val lastFetched = TimetableRepository.lastFetched(applicationContext, appWidgetId)
                WidgetRowState(
                    appWidgetId = appWidgetId,
                    classIdText = initialText,
                    lastFetched = lastFetched,
                    isRefreshing = false,
                    isConfigured = !stored.isNullOrBlank(),
                )
            }
            // Replace the contents of the state list atomically so the
            // composable only recomposes once.
            rows.clear()
            rows.addAll(newRows)
        }
    }

    private fun saveClassId(appWidgetId: Int, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        val index = rows.indexOfFirst { it.appWidgetId == appWidgetId }
        if (index < 0) return

        lifecycleScope.launch {
            WidgetConfigStore(applicationContext).saveClassId(appWidgetId, trimmed)
            // Mark as configured (used for the "(nicht konfiguriert)" badge).
            rows[index] = rows[index].copy(
                classIdText = trimmed,
                isConfigured = true,
            )
        }
    }

    private fun deleteRow(appWidgetId: Int) {
        lifecycleScope.launch {
            WidgetConfigStore(applicationContext).delete(appWidgetId)
            reload()
        }
    }

    private suspend fun refreshRow(appWidgetId: Int): Boolean {
        val index = rows.indexOfFirst { it.appWidgetId == appWidgetId }
        if (index < 0) return false
        rows[index] = rows[index].copy(isRefreshing = true)

        val result = TimetableRepository.refresh(applicationContext, appWidgetId)
        // Re-read the timestamp from disk so we get the persisted value
        // (and not just whatever the in-memory cache produced).
        val newStamp = TimetableRepository.lastFetched(applicationContext, appWidgetId)
        val stillThere = rows.indexOfFirst { it.appWidgetId == appWidgetId }
        if (stillThere >= 0) {
            rows[stillThere] = rows[stillThere].copy(
                lastFetched = newStamp,
                isRefreshing = false,
            )
        }
        return result != null
    }

    /**
     * Trigger the system "pin widget" dialog if the launcher supports it.
     * Silently no-ops otherwise.
     */
    private fun requestPinWidget() {
        val mgr = AppWidgetManager.getInstance(this)
        if (!mgr.isRequestPinAppWidgetSupported) return
        val provider = ComponentName(this, StundenplanWidgetReceiver::class.java)
        mgr.requestPinAppWidget(provider, null, null)
    }
}

/**
 * Per-widget state shown in one [Card]. Kept as a plain data class so the
 * parent can hold a `SnapshotStateList` of them and individual fields can
 * be updated without recomposing the rest of the list.
 */
private data class WidgetRowState(
    val appWidgetId: Int,
    val classIdText: String,
    val lastFetched: LocalDateTime?,
    val isRefreshing: Boolean,
    val isConfigured: Boolean,
)

/** Top-level settings screen composable. */
@Composable
private fun SettingsScreen(
    rows: List<WidgetRowState>,
    onAddWidget: () -> Unit,
    onSaveClassId: (Int, String) -> Unit,
    onDelete: (Int) -> Unit,
    onRefresh: suspend (Int) -> Boolean,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header()
            AddWidgetButton(onClick = onAddWidget)

            HorizontalDivider()

            if (rows.isEmpty()) {
                EmptyState()
            } else {
                rows.forEach { row ->
                    WidgetCard(
                        row = row,
                        onSaveClassId = { onSaveClassId(row.appWidgetId, it) },
                        onDelete = { onDelete(row.appWidgetId) },
                        onRefresh = {
                            scope.launch {
                                val ok = onRefresh(row.appWidgetId)
                                if (!ok) {
                                    snackbarHostState.showSnackbar(
                                        "Aktualisieren fehlgeschlagen – prüfe deine Internetverbindung.",
                                    )
                                }
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Header() {
    Text(
        text = "Widget-Einstellungen",
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = "Seminargruppen können pro Widget festgelegt werden.",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun AddWidgetButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Widget zum Homescreen hinzufügen")
    }
}

@Composable
private fun EmptyState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Keine aktiven Widgets",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Es ist momentan kein Stundenplan-Widget auf deinem " +
                    "Homescreen platziert. Füge eines über den Widget-Picker " +
                    "deines Launchers hinzu, um hier die Klassen-Kennung " +
                    "einzustellen.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun WidgetCard(
    row: WidgetRowState,
    onSaveClassId: (String) -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
) {
    // Local mirror of the class-id text so the OutlinedTextField can show
    // what the user is typing immediately. The source of truth is the
    // WidgetRowState; we push back to it on focus loss / IME action.
    var draft by remember(row.appWidgetId) { mutableStateOf(row.classIdText) }
    var hasFocus by remember(row.appWidgetId) { mutableStateOf(false) }
    val focusRequester = remember(row.appWidgetId) { FocusRequester() }

    // Keep the draft in sync if the underlying row changes (e.g. after a
    // delete + reload produces a new row for the same id), but never
    // overwrite while the user is actively editing it.
    LaunchedEffect(row.classIdText) {
        if (!hasFocus) draft = row.classIdText
    }

    fun commit() {
        val trimmed = draft.trim()
        if (trimmed.isNotEmpty() && trimmed != row.classIdText) {
            onSaveClassId(trimmed)
        } else if (trimmed.isEmpty()) {
            // Don't persist an empty value; revert the draft to the
            // last-known good value.
            draft = row.classIdText
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Widget #${row.appWidgetId}",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!row.isConfigured) {
                    Text(
                        text = "(nicht konfiguriert)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Klassen-Kennung") },
                placeholder = { Text(TimetableRepository.DEFAULT_CLASS_ID) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        val wasFocused = hasFocus
                        hasFocus = state.isFocused
                        if (wasFocused && !state.isFocused) {
                            commit()
                        }
                    },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        commit()
                        focusRequester.freeFocus()
                    },
                ),
                supportingText = {
                    Text(
                        text = if (row.isConfigured) {
                            "Wird als URL-Parameter aktwert=… verwendet."
                        } else {
                            "Noch nicht gespeichert – Default ist " +
                                "${TimetableRepository.DEFAULT_CLASS_ID}."
                        },
                    )
                },
            )

            Text(
                text = "Zuletzt aktualisiert: ${formatStamp(row.lastFetched)}",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRefresh,
                    enabled = !row.isRefreshing,
                    modifier = Modifier.weight(1f),
                ) {
                    if (row.isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(if (row.isRefreshing) "Aktualisiere …" else "Aktualisieren")
                }

                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Löschen")
                }
            }
        }
    }
}

private val STAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMAN)

private fun formatStamp(stamp: LocalDateTime?): String =
    stamp?.format(STAMP_FORMATTER) ?: "–"