package de.dhsn.stundenplan.work

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import de.dhsn.stundenplan.StundenplanWidget
import de.dhsn.stundenplan.data.WidgetConfigStore
import java.util.UUID

/**
 * Glance [ActionCallback]s for the prev / today / next arrow buttons in
 * the widget header.
 *
 * Each action:
 *  1. Resolves the underlying integer appWidgetId from the supplied
 *     [GlanceId] (multiple widgets may share the same action callback).
 *  2. Updates the persisted day offset in [WidgetConfigStore].
 *  3. Calls [StundenplanWidget.updateAll] so the widget re-renders with
 *     the new date / slots.
 *  4. If the newly-selected day is outside the 8-week window that the
 *     BA-Dresden page serves, also enqueues a one-shot
 *     [WidgetRefreshWorker] so the cache gets re-fetched and the next
 *     widget render shows real data instead of "Heute keine Stunden."
 */
sealed class DayOffsetAction(
    private val delta: Int,
    private val shouldRefreshIfStale: Boolean,
) : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val widgetId = try {
            GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        } catch (e: Throwable) {
            Log.w(TAG, "could not resolve appWidgetId from glanceId: ${e.message}")
            return
        }
        if (widgetId == android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) return

        val store = WidgetConfigStore(context)
        val previous = store.getDayOffset(widgetId)
        // Clamp to the same limits the store uses so the action never
        // persists an out-of-range value.
        val newOffset = (previous + delta).coerceIn(
            -WidgetConfigStore.DAY_OFFSET_LIMIT,
            WidgetConfigStore.DAY_OFFSET_LIMIT,
        )
        if (newOffset == previous) {
            Log.d(TAG, "offset already at limit ($previous), nothing to do")
        } else {
            store.saveDayOffset(widgetId, newOffset)
            Log.i(TAG, "widget #$widgetId offset $previous -> $newOffset")
        }

        // Re-render so the header shows the new date immediately.
        StundenplanWidget().updateAll(context)

        // If the navigated-to date is plausibly outside the cached
        // 8-week window (more than ~28 days away), kick off a refresh
        // in the background so the next widget render has fresh data.
        // The widget still shows the empty state until the refresh
        // completes; the user can keep pressing arrows in the meantime.
        if (shouldRefreshIfStale && kotlin.math.abs(newOffset) > STALE_THRESHOLD_DAYS) {
            enqueueRefresh(context)
        }
    }

    private fun enqueueRefresh(context: Context) {
        val req = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME + ":" + UUID.randomUUID(),
            androidx.work.ExistingWorkPolicy.REPLACE,
            req,
        )
    }

    companion object {
        private const val TAG = "DayOffsetAction"

        /**
         * Number of days away from today that the BA-Dresden page covers
         * (it serves 8 weeks = 56 days, so anything past ~28 days from
         * either end is a safe threshold for "the page probably doesn't
         * contain this date yet").
         */
        private const val STALE_THRESHOLD_DAYS = 28

        private const val UNIQUE_NAME = "widget-refresh-on-nav"
    }
}

/** Action that moves the widget one day closer to today. */
class PrevDayAction : DayOffsetAction(delta = -1, shouldRefreshIfStale = false)

/** Action that moves the widget one day further from today. */
class NextDayAction : DayOffsetAction(delta = +1, shouldRefreshIfStale = true)

/** Action that snaps the widget back to "today". */
class TodayAction : DayOffsetAction(delta = 0, shouldRefreshIfStale = false) {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val widgetId = try {
            GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        } catch (e: Throwable) {
            Log.w(TAG, "could not resolve appWidgetId from glanceId: ${e.message}")
            return
        }
        if (widgetId == android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) return

        val store = WidgetConfigStore(context)
        val previous = store.getDayOffset(widgetId)
        if (previous != 0) {
            store.saveDayOffset(widgetId, 0)
            Log.i(TAG, "widget #$widgetId reset to today (was $previous)")
            StundenplanWidget().updateAll(context)
        }
    }

    private companion object {
        const val TAG = "TodayAction"
    }
}