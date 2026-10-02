package de.dhsn.stundenplan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [TimetableRepository]'s on-disk cache encoding.
 *
 * The encoding is round-trip critical because the widget must survive an
 * app restart (and render cached data while offline). We test the pure
 * functions [TimetableRepository.encodeSlot] / [TimetableRepository.decodeSlot]
 * directly so we don't need a [Context] / Robolectric.
 */
class TimetableRepositoryCacheTest {

    @Test
    fun `slot with single lesson round-trips`() {
        val slot = LessonSlot(
            start = LocalTime.of(8, 0),
            end = LocalTime.of(9, 30),
            lessons = listOf(
                Lesson(
                    start = LocalTime.of(8, 0),
                    end = LocalTime.of(9, 30),
                    subject = "Mathe",
                    teacher = "Muster",
                    room = "A101",
                    kind = "V",
                    remark = "",
                ),
            ),
        )

        val encoded = TimetableRepository.encodeSlot(slot)
        val decoded = TimetableRepository.decodeSlot(encoded)

        assertNotNull("decode must succeed", decoded)
        assertEquals(slot.start, decoded!!.start)
        assertEquals(slot.end, decoded.end)
        assertEquals(1, decoded.lessons.size)
        val lesson = decoded.lessons.single()
        assertEquals("Mathe", lesson.subject)
        assertEquals("Muster", lesson.teacher)
        assertEquals("A101", lesson.room)
        assertEquals("V", lesson.kind)
    }

    @Test
    fun `slot with parallel lessons round-trips`() {
        // Doppelbelegung: two lessons in the same time slot.
        val slot = LessonSlot(
            start = LocalTime.of(7, 45),
            end = LocalTime.of(9, 15),
            lessons = listOf(
                Lesson(
                    LocalTime.of(7, 45), LocalTime.of(9, 15),
                    "VSIT", "Winkl", "2.015", "L", "",
                ),
                Lesson(
                    LocalTime.of(7, 45), LocalTime.of(9, 15),
                    "EVSA", "Nind", "2.119", "L", "",
                ),
            ),
        )

        val encoded = TimetableRepository.encodeSlot(slot)
        val decoded = TimetableRepository.decodeSlot(encoded)

        assertNotNull(decoded)
        assertEquals(2, decoded!!.lessons.size)
        assertEquals("VSIT", decoded.lessons[0].subject)
        assertEquals("EVSA", decoded.lessons[1].subject)
    }

    @Test
    fun `typical German subject names round-trip exactly`() {
        // The encoding escapes `;` and `|` so any other character is safe
        // to round-trip. Use realistic German timetable text (which never
        // contains `;` or `|` in real life).
        val slot = LessonSlot(
            start = LocalTime.of(8, 0),
            end = LocalTime.of(9, 30),
            lessons = listOf(
                Lesson(
                    LocalTime.of(8, 0), LocalTime.of(9, 30),
                    subject = "Mathematik I (Analysis)",
                    teacher = "Prof. Dr. Müller-Schmidt",
                    room = "Hörsaal 2.015 (Bau A)",
                    kind = "V/Ü",
                    remark = "wöchentlich, 1. Gruppe",
                ),
            ),
        )
        val decoded = TimetableRepository.decodeSlot(TimetableRepository.encodeSlot(slot))
        assertNotNull(decoded)
        val lesson = decoded!!.lessons.single()
        assertEquals("Mathematik I (Analysis)", lesson.subject)
        assertEquals("Prof. Dr. Müller-Schmidt", lesson.teacher)
        assertEquals("Hörsaal 2.015 (Bau A)", lesson.room)
        assertEquals("V/Ü", lesson.kind)
        assertEquals("wöchentlich, 1. Gruppe", lesson.remark)
    }

    @Test
    fun `lesson fields containing delimiters are escaped to prevent collisions`() {
        // The encoding substitutes `;` -> `,` and `|` -> `/` so these
        // characters never collide with the structural delimiters when
        // round-tripping. This is documented behaviour: the encoded form
        // is lossy for `;` and `|`, but those characters essentially never
        // appear in real BA-Dresden timetable fields.
        val slot = LessonSlot(
            start = LocalTime.of(8, 0),
            end = LocalTime.of(9, 30),
            lessons = listOf(
                Lesson(
                    LocalTime.of(8, 0), LocalTime.of(9, 30),
                    subject = "Praxis; Projekt",
                    teacher = "Dr. Müller",
                    room = "R. 1|2",
                    kind = "V",
                    remark = "irrelevant",
                ),
            ),
        )
        val decoded = TimetableRepository.decodeSlot(TimetableRepository.encodeSlot(slot))
        assertNotNull(decoded)
        val lesson = decoded!!.lessons.single()
        // `;` becomes `,` and `|` becomes `/` (deliberate, documented).
        assertEquals("Praxis, Projekt", lesson.subject)
        assertEquals("R. 1/2", lesson.room)
    }

    @Test
    fun `malformed slot strings return null`() {
        assertNull(TimetableRepository.decodeSlot(""))
        assertNull(TimetableRepository.decodeSlot("not-a-time"))
        // Too few fields: needs at least start, end, and one 7-tuple lesson.
        assertNull(TimetableRepository.decodeSlot("08:00;09:30"))
    }

    @Test
    fun `DEFAULT_CLASS_ID is a non-empty BA-Dresden class key`() {
        // We can't exercise the full URL building path (it requires a
        // Context), but the default class id is the contract the BA
        // PlanServlet was scraped against. If it changes the scraper will
        // stop finding data.
        val id = TimetableRepository.DEFAULT_CLASS_ID
        assert(id.isNotBlank()) { "DEFAULT_CLASS_ID must be non-blank" }
        // BA class keys use letters, digits and dashes only.
        assert(id.matches(Regex("""[A-Za-z0-9-]+"""))) {
            "DEFAULT_CLASS_ID must be alphanumeric+dash, was '$id'"
        }
    }
}