package de.dhsn.stundenplan

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import de.dhsn.stundenplan.data.TimetableRepository
import de.dhsn.stundenplan.ui.WidgetContent

class StundenplanWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val lessons = TimetableRepository.today(context)
        provideContent {
            GlanceTheme { WidgetContent(lessons = lessons) }
        }
    }
}
