package de.dhsn.stundenplan.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.dhsn.stundenplan.StundenplanWidget

class WidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        StundenplanWidget().updateAll(appContext)
        return Result.success()
    }
}
