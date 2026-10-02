package de.dhsn.stundenplan

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.provideContent
import de.dhsn.stundenplan.data.TimetableRepository
import de.dhsn.stundenplan.data.WidgetConfigStore
import de.dhsn.stundenplan.ui.LocalWidgetId
import de.dhsn.stundenplan.ui.WidgetContent
import java.time.LocalDate

class StundenplanWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // GlanceAppWidgetManager.getAppWidgetId() resolves this GlanceId
        // back to the underlying system AppWidget id, which the repository
        // uses to look up the user's configured class id and which the
        // widget UI uses to wire long-press / open-config actions.
        val widgetId: Int = GlanceAppWidgetManager(context).getAppWidgetId(id)
        // The user can navigate ±N days from today via the prev / next
        // buttons in the header. The offset is persisted per widget id so
        // each widget instance remembers its own selected day.
        val dayOffset = WidgetConfigStore(context).getDayOffset(widgetId)
        val displayDate = LocalDate.now().plusDays(dayOffset.toLong())
        val slots = TimetableRepository.day(context, widgetId, displayDate)
        val lastFetched = TimetableRepository.lastFetchedLabel(context, widgetId)
        provideContent {
            GlanceTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalWidgetId provides widgetId) {
                    WidgetContent(
                        slots = slots,
                        today = displayDate,
                        dayOffset = dayOffset,
                        lastFetched = lastFetched,
                    )
                }
            }
        }
    }
}