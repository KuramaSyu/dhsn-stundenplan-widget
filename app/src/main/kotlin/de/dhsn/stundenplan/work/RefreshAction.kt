package de.dhsn.stundenplan.work

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.UUID

/**
 * Glance [ActionCallback] that the "Aktualisieren" button on the widget is
 * wired to.
 *
 * On click it enqueues a one-shot [WidgetRefreshWorker] (which fetches the
 * timetable and updates the widget). We run the actual network call inside
 * the worker because ActionCallback has limited execution time, and we want
 * the spinner / "Lädt…" state to be observable to the user.
 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        // Use a unique work name so consecutive taps don't collapse into
        // each other and so the most recent tap "wins".
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
        private const val UNIQUE_NAME = "widget-refresh-on-tap"
    }
}