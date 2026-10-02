package de.dhsn.stundenplan.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import de.dhsn.stundenplan.data.Lesson

@Composable
fun WidgetContent(lessons: List<Lesson>) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(16.dp)
            .background(GlanceTheme.colors.primaryContainer)
    ) {
        Text(
            text = "Heute",
            style = TextStyle(
                color = GlanceTheme.colors.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        )
        if (lessons.isEmpty()) {
            Text(
                text = "Keine Stunden",
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                    fontSize = 13.sp
                )
            )
        } else {
            lessons.take(4).forEach { lesson ->
                Text(
                    text = "${lesson.time}  ${lesson.subject}  ${lesson.room}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimaryContainer,
                        fontSize = 13.sp
                    )
                )
            }
        }
    }
}
