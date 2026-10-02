package de.dhsn.stundenplan

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.dhsn.stundenplan.work.WidgetRefreshWorker
import java.util.concurrent.TimeUnit

class StundenplanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val wm = WorkManager.getInstance(this)

        // 1) Prime the cache once on app start, so the very first widget
        //    render shows real data instead of the dummy placeholder.
        val initial = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build()
        wm.enqueueUniqueWork(
            UNIQUE_INITIAL,
            ExistingWorkPolicy.KEEP, // if it's already enqueued / running, skip
            initial,
        )

        // 2) Keep it fresh every six hours in the background.
        val periodic = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(6, TimeUnit.HOURS).build()
        wm.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
    }

    private companion object {
        const val UNIQUE_INITIAL = "widget-refresh-initial"
        const val UNIQUE_PERIODIC = "widget-refresh-periodic"
    }
}