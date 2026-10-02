package de.dhsn.stundenplan.data

import android.content.Context
import java.time.LocalDate

data class Lesson(val time: String, val subject: String, val room: String)

object TimetableRepository {
    fun today(context: Context): List<Lesson> {
        val day = LocalDate.now().dayOfWeek.value
        return when (day) {
            1 -> listOf(
                Lesson("08:00", "Mathe", "A101"),
                Lesson("09:45", "Deutsch", "B202"),
                Lesson("11:30", "Englisch", "C303")
            )
            2 -> listOf(
                Lesson("08:00", "Physik", "A201"),
                Lesson("09:45", "Mathe", "A101"),
                Lesson("11:30", "Sport", "Halle")
            )
            else -> emptyList()
        }
    }
}
