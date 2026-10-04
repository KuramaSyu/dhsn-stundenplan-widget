package de.dhsn.stundenplan.config

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import de.dhsn.stundenplan.ui.AppTheme
import de.dhsn.stundenplan.ui.TimelineScreen

/**
 * Launcher activity: shows the in-app vertical timeline of the
 * stundenplan. The actual composable lives in
 * [TimelineScreen][de.dhsn.stundenplan.ui.TimelineScreen]; this activity
 * is just a thin host so the OS can render the Compose tree inside an
 * Activity context (which is what Scaffold / LocalContext etc. expect).
 *
 * We don't pass any specific widget id, so the timeline uses the default
 * class id (`TimetableRepository.DEFAULT_CLASS_ID`). Users who configured
 * a custom class id on a widget still see that one in the widget; this
 * activity is the quick "I just want to see my week" entry point.
 *
 * Theming goes through [AppTheme] which picks the Material-You dynamic
 * color scheme on Android 12+ and the static M3 schemes on older OS
 * versions, plus the system light/dark setting.
 */
class TimelineActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                TimelineScreen(appWidgetId = null)
            }
        }
    }
}