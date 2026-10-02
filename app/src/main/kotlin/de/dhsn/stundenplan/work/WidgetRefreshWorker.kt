package de.dhsn.stundenplan.work

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.dhsn.stundenplan.StundenplanWidget
import de.dhsn.stundenplan.data.TimetableRepository

/**
 * One-shot Worker that re-fetches the timetable and pushes the new state to
 * every active widget instance.
 *
 * Scheduled by `RefreshAction` when the user taps the refresh button on a
 * widget, and by the application periodic worker so the widget eventually
 * self-heals even when the user never opens the app.
 *
 * Refreshes every distinct class id configured across all active widgets
 * (plus the default), so multi-widget setups with different class ids all
 * get fresh data.
 */
class WidgetRefreshWorker(
    private val appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // Resolve every active widget instance to its underlying integer appWidgetId,
// which is what the repository uses to look up the configured class id.
            val widgetIds = try {
                val manager = GlanceAppWidgetManager(appContext)
                val glanceIds = manager.getGlanceIds(StundenplanWidget::class.java)
                glanceIds.map { manager.getAppWidgetId(it) }
            } catch (_: Throwable) {
                emptyList<Int>()
            }
            try {
                TimetableRepository.refreshAll(appContext, widgetIds)
            } catch (e: Throwable) {
                android.util.Log.w(
                    "WidgetRefreshWorker",
                    "refreshAll failed: ${e.message}",
                )
            }
            StundenplanWidget().updateAll(appContext)
            Result.success()
        } catch (_: Throwable) {
            // Don't retry on network errors: the next tap or periodic refresh
            // will just try again.
            Result.success()
        }
    }
}