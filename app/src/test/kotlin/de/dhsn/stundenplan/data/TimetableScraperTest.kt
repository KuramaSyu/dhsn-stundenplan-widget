package de.dhsn.stundenplan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [TimetableScraper].
 *
 * Tests use a real response captured from the BA-Dresden PlanServlet for
 * class "3it24-1" (see `fixtures/ba_dresden_plan.html`). The HTML is a
 * verbatim copy of what the server returned on 2026-10-02 and contains 8
 * weeks of data.
 *
 * The fix verified by these tests is the TD_CELL regex bug that used to
 * discard the opening `<td>` tag (so cells with `class="Vorlesung"` were
 * never detected and lessons were silently dropped).
 */
class TimetableScraperTest {

    private lateinit var html: String

    @Before
    fun loadFixture() {
        val res = javaClass.classLoader!!.getResource("fixtures/ba_dresden_plan.html")
            ?: error("fixture fixtures/ba_dresden_plan.html not found on classpath")
        html = res.readText(Charsets.UTF_8)
    }

    @Test
    fun `parses eight weeks from the real BA-Dresden page`() {
        val weeks = TimetableScraper.parseWeeks(html)
        // The fixture contains exactly 8 <table class="Plan"> entries
        // (KW 40 through KW 47).
        assertEquals(8, weeks.size)
    }

    @Test
    fun `week 2 starts on Monday 5 October 2026 and carries weekday dates`() {
        val weeks = TimetableScraper.parseWeeks(html)
        val week2 = weeks[1]
        assertEquals(LocalDate.of(2026, 10, 5), week2.weekStart)
        assertEquals("Monday present", LocalDate.of(2026, 10, 5), week2.days[DayOfWeek.MONDAY.value]?.date)
        assertEquals(LocalDate.of(2026, 10, 9), week2.days[DayOfWeek.FRIDAY.value]?.date)
    }

    @Test
    fun `Monday 7_45 slot has two parallel lessons on the user's class`() {
        // The user's example: "7:45 - 9:15 VSIT … / 7:45 - 9:15 EVSA …".
        // Both should land in the same LessonSlot (Doppelbelegung).
        val monday = dayOn(html, LocalDate.of(2026, 10, 5))
        assertNotNull("Monday plan must exist", monday)

        val firstSlot = monday!!.slots.first { it.start == LocalTime.of(7, 45) }
        assertEquals(LocalTime.of(9, 15), firstSlot.end)
        assertEquals("Doppelbelegung must contain exactly 2 lessons", 2, firstSlot.lessons.size)

        val bySubject = firstSlot.lessons.associateBy { it.subject }
        assertTrue("VSIT must be present", "VSIT" in bySubject)
        assertTrue("EVSA must be present", "EVSA" in bySubject)

        val vsit = bySubject.getValue("VSIT")
        assertEquals("2.015", vsit.room)
        assertEquals("Winkl", vsit.teacher)
        assertEquals("L", vsit.kind)

        val evsa = bySubject.getValue("EVSA")
        assertEquals("2.119", evsa.room)
        assertEquals("Nind", evsa.teacher)
        assertEquals("L", evsa.kind)
    }

    @Test
    fun `Monday DSDS rowspan=3 expands into three 90-min slots`() {
        // The fixture's Monday has DSDS in row 3 (11:45-13:15) with
        // rowspan="3", meaning the HTML grid spans it across rows 3, 4 and 5
        // (11:45-13:15, 13:45-15:15, 15:30-17:00) but only renders one <td>.
        // Our scraper expands rowspans into one slot per spanned row using
        // each row's own <th class="zeit"> time, so we should see THREE
        // slots for Monday DSDS, each carrying the same lessons (Lund, 2.234).
        val monday = dayOn(html, LocalDate.of(2026, 10, 5))!!
        val dsdsSlots = monday.slots.filter { it.lessons.any { l -> l.subject == "DSDS" } }
        assertEquals(
            "DSDS appears in exactly three time slots on Monday (rowspan=3)",
            3,
            dsdsSlots.size,
        )
        val expectedStarts = listOf(
            LocalTime.of(11, 45),
            LocalTime.of(13, 45),
            LocalTime.of(15, 30),
        )
        val expectedEnds = listOf(
            LocalTime.of(13, 15),
            LocalTime.of(15, 15),
            LocalTime.of(17, 0),
        )
        dsdsSlots.forEachIndexed { i, slot ->
            assertEquals(expectedStarts[i], slot.start)
            assertEquals(expectedEnds[i], slot.end)
            val dsds = slot.lessons.single()
            assertEquals("Lund", dsds.teacher)
            assertEquals("2.234", dsds.room)
            assertEquals("V", dsds.kind)
        }
    }

    @Test
    fun `Monday parallel VSIT EVSA rowspan=2 expands into two parallel slots`() {
        // Monday's VSIT/EVSA cell has rowspan="2", so the scraper should
        // emit two slots (07:45-09:15 and 09:45-11:15) — each carrying both
        // lessons as parallel modules (Doppelbelegung).
        val monday = dayOn(html, LocalDate.of(2026, 10, 5))!!
        val parallelSlots = monday.slots.filter {
            it.lessons.size > 1 &&
                it.lessons.any { l -> l.subject == "VSIT" } &&
                it.lessons.any { l -> l.subject == "EVSA" }
        }
        assertEquals(
            "VSIT/EVSA parallel block expands into 2 slots",
            2,
            parallelSlots.size,
        )
        assertEquals(LocalTime.of(7, 45), parallelSlots[0].start)
        assertEquals(LocalTime.of(9, 15), parallelSlots[0].end)
        assertEquals(LocalTime.of(9, 45), parallelSlots[1].start)
        assertEquals(LocalTime.of(11, 15), parallelSlots[1].end)
        parallelSlots.forEach { slot ->
            val bySubject = slot.lessons.associateBy { it.subject }
            assertEquals("Winkl", bySubject.getValue("VSIT").teacher)
            assertEquals("2.015", bySubject.getValue("VSIT").room)
            assertEquals("Nind", bySubject.getValue("EVSA").teacher)
            assertEquals("2.119", bySubject.getValue("EVSA").room)
        }
    }

    @Test
    fun `all lessons have a subject, room, teacher and optional kind`() {
        val weeks = TimetableScraper.parseWeeks(html)
        var total = 0
        for (week in weeks) {
            for (day in week.days.values) {
                for (slot in day.slots) {
                    for (lesson in slot.lessons) {
                        total++
                        assertTrue(
                            "lesson must have non-empty subject (was '${lesson.subject}')",
                            lesson.subject.isNotEmpty(),
                        )
                        assertTrue(
                            "lesson must have non-empty room (was '${lesson.room}')",
                            lesson.room.isNotEmpty(),
                        )
                        // teacher may be empty for some lesson types but at
                        // least one of (teacher, remark) is typically present
                        assertTrue(
                            "lesson must have at least teacher or remark",
                            lesson.teacher.isNotEmpty() || lesson.remark.isNotEmpty(),
                        )
                    }
                }
            }
        }
        assertTrue("expected at least one lesson in fixture", total > 0)
    }

    @Test
    fun `HTML entities in teacher names are decoded`() {
        // The fixture uses `H&auml;nel` and `B&uuml;ch`. Verify both are
        // decoded to umlauts.
        val weeks = TimetableScraper.parseWeeks(html)
        val allTeachers = weeks.flatMap { it.days.values }
            .flatMap { it.slots }
            .flatMap { it.lessons }
            .map { it.teacher }
            .toSet()
        assertTrue("Hänel should appear (H&auml;nel decoded)", "Hänel" in allTeachers)
        assertTrue("Büch should appear (B&uuml;ch decoded)", "Büch" in allTeachers)
        assertFalse(
            "raw &uuml; must never leak through",
            allTeachers.any { it.contains("&") },
        )
    }

    @Test
    fun `null and blank HTML return empty list`() {
        assertTrue(TimetableScraper.parseWeeks(null).isEmpty())
        assertTrue(TimetableScraper.parseWeeks("").isEmpty())
        assertTrue(TimetableScraper.parseWeeks("   \n  ").isEmpty())
    }

    @Test
    fun `parseDay returns null when the date is not in the page`() {
        // The fixture covers KW 40..47 of 2026. Anything outside should miss.
        val result = TimetableScraper.parseDay(html, LocalDate.of(2025, 1, 1))
        assertNull(result)
    }

    @Test
    fun `parseDay finds Monday 5 Oct 2026 by date`() {
        val day = TimetableScraper.parseDay(html, LocalDate.of(2026, 10, 5))
        assertNotNull(day)
        assertEquals(LocalDate.of(2026, 10, 5), day!!.date)
        assertFalse("Monday should have lessons", day.slots.isEmpty())
    }

    @Test
    fun `Friday 2 Oct 2026 has both the DVS slot and the VSIT slot`() {
        // Regression test for the scraper bug where empty `<td> </td>`
        // cells (with no class attribute) caused the weekday cursor to
        // skip a column, so the Friday afternoon VSIT was silently
        // dropped. The fixture's Friday (KW 40) has:
        //   - 07:45-09:15 DVS (Hänel, 3.005)
        //   - 11:45-13:15 VSIT (Büch, 1.201, "IT-Compliance WPF")
        val friday = dayOn(html, LocalDate.of(2026, 10, 2))
        assertNotNull("Friday 2026-10-02 plan must exist", friday)

        val morning = friday!!.slots.firstOrNull { it.start == LocalTime.of(7, 45) }
        assertNotNull("Friday morning DVS slot must be present", morning)
        assertEquals(LocalTime.of(9, 15), morning!!.end)
        assertEquals("DVS", morning.lessons.single().subject)
        assertEquals("Hänel", morning.lessons.single().teacher)
        assertEquals("3.005", morning.lessons.single().room)

        val afternoon = friday.slots.firstOrNull { it.start == LocalTime.of(11, 45) }
        assertNotNull("Friday afternoon VSIT slot must be present", afternoon)
        assertEquals(1, afternoon!!.lessons.size)
        val vsit = afternoon.lessons.single()
        assertEquals("VSIT", vsit.subject)
        assertEquals("1.201", vsit.room)
        assertEquals("Büch", vsit.teacher)
        assertEquals("IT-Compliance WPF", vsit.remark)
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private fun dayOn(html: String, date: LocalDate): DayPlan? =
        TimetableScraper.parseWeeks(html)
            .firstNotNullOfOrNull { week -> week.days.values.firstOrNull { it.date == date } }
}