package de.dhsn.stundenplan.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.configDataStore by preferencesDataStore(name = "widget_configs")

/**
 * Stores a class identifier (e.g. "3it24-1") and the user-selected day
 * offset per widget instance.
 *
 * Keyed by the AppWidgetId so a user can have multiple widgets with
 * different classes — and now each widget also remembers which day the
 * user last navigated to, independently of the others.
 *
 * The day offset is the number of days away from today that the widget
 * is currently displaying (0 = today, -1 = yesterday, +1 = tomorrow,
 * clamped to ±[DAY_OFFSET_LIMIT]).
 */
class WidgetConfigStore(private val context: Context) {

    private fun classKey(appWidgetId: Int) = stringPreferencesKey("class_id_$appWidgetId")
    private fun dayOffsetKey(appWidgetId: Int) = intPreferencesKey("day_offset_$appWidgetId")

    suspend fun saveClassId(appWidgetId: Int, classId: String) {
        context.configDataStore.edit { prefs ->
            prefs[classKey(appWidgetId)] = classId.trim()
        }
    }

    suspend fun getClassId(appWidgetId: Int): String? =
        context.configDataStore.data
            .map { it[classKey(appWidgetId)] }
            .first()

    /**
     * Returns the day offset that the widget should display, clamped to
     * ±[DAY_OFFSET_LIMIT]. Defaults to 0 ("today") if the user hasn't
     * navigated yet.
     */
    suspend fun getDayOffset(appWidgetId: Int): Int {
        val raw = context.configDataStore.data
            .map { it[dayOffsetKey(appWidgetId)] }
            .first()
        return (raw ?: 0).coerceIn(-DAY_OFFSET_LIMIT, DAY_OFFSET_LIMIT)
    }

    /**
     * Persists the new day offset. Clamped to ±[DAY_OFFSET_LIMIT] so the
     * user can't navigate past the supported range.
     */
    suspend fun saveDayOffset(appWidgetId: Int, offset: Int) {
        val clamped = offset.coerceIn(-DAY_OFFSET_LIMIT, DAY_OFFSET_LIMIT)
        context.configDataStore.edit { prefs ->
            prefs[dayOffsetKey(appWidgetId)] = clamped
        }
    }

    suspend fun delete(appWidgetId: Int) {
        context.configDataStore.edit { prefs ->
            prefs.remove(classKey(appWidgetId))
            prefs.remove(dayOffsetKey(appWidgetId))
        }
    }

    companion object {
        /**
         * Maximum number of days the user can navigate away from today in
         * either direction. Generous (±one year) so the widget can show
         * past or upcoming slots whenever the BA page contains them; the
         * BA-Dresden PlanServlet serves 8 weeks at a time, so anything
         * further than ~5 weeks usually needs a refresh before any data
         * appears.
         */
        const val DAY_OFFSET_LIMIT = 365
    }
}