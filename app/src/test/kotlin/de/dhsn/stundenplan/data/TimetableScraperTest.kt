package de.dhsn.stundenplan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    // Next week (KW 41, 5.10.2026 - 11.10.2026)
    //
    // Regression tests for the bug where the rowspans emitted by the BA
    // server overlap with subsequent cells. Before the fix, the scraper
    // reset the weekday cursor for every row, so cells that should have
    // landed on Wed/Thu/Fri in row 3 and row 4 of the table were
    // misassigned to Mon (because Mon was the only non-empty slot when
    // the cursor was reset). The fix tracks which columns are still being
    // carried over from a previous rowspan, and skips them so the next
    // cell in the row's <td> list lands in the first non-carried column.
    // -----------------------------------------------------------------------

    @Test
    fun `next week Monday 5 Oct 2026 has 5 slots with the right subjects`() {
        // Layout (KW 41 Mon):
        //   - 07:45-09:15 VSIT 2.015 (Winkl, L) + EVSA 2.119 (Nind, L) [rowspan=2]
        //   - 09:45-11:15 VSIT + EVSA (carried)
        //   - 11:45-13:15 DSDS Lund 2.234 (V) [rowspan=3]
        //   - 13:45-15:15 DSDS Lund (carried)
        //   - 15:30-17:00 DSDS Lund (carried)
        val monday = dayOn(html, LocalDate.of(2026, 10, 5))
        assertNotNull("Monday 2026-10-05 plan must exist", monday)
        assertEquals(5, monday!!.slots.size)
        assertEquals(
            listOf(
                LocalTime.of(7, 45) to LocalTime.of(9, 15),
                LocalTime.of(9, 45) to LocalTime.of(11, 15),
                LocalTime.of(11, 45) to LocalTime.of(13, 15),
                LocalTime.of(13, 45) to LocalTime.of(15, 15),
                LocalTime.of(15, 30) to LocalTime.of(17, 0),
            ),
            monday.slots.map { it.start to it.end },
        )
        val subjects = monday.slots.map { it.lessons.map(Lesson::subject) }
        assertEquals(
            listOf(
                listOf("VSIT", "EVSA"),
                listOf("VSIT", "EVSA"),
                listOf("DSDS"),
                listOf("DSDS"),
                listOf("DSDS"),
            ),
            subjects,
        )
    }

    @Test
    fun `next week Tuesday 6 Oct 2026 has 5 slots with the right subjects`() {
        // Layout (KW 41 Tue):
        //   - 07:45-09:15 UES Püst 1.201 [rowspan=3]
        //   - 09:45-11:15 UES Püst (carried)
        //   - 11:45-13:15 UES Püst (carried, last row of rowspan=3)
        //   - 13:45-15:15 UES Lund 3.204 (V) [rowspan=2]
        //   - 15:30-17:00 UES Lund (carried)
        // Critical: the DSDS Lund cell which the server emits in the row-3
        // <td> list (col 2 of the source) must NOT bleed into Tuesday;
        // it must stay in the Wed slot.
        val tuesday = dayOn(html, LocalDate.of(2026, 10, 6))
        assertNotNull("Tuesday 2026-10-06 plan must exist", tuesday)
        assertEquals(5, tuesday!!.slots.size)
        val subjects = tuesday.slots.map { it.lessons.map(Lesson::subject) }
        assertEquals(
            listOf(
                listOf("UES"),
                listOf("UES"),
                listOf("UES"),
                listOf("UES"),
                listOf("UES"),
            ),
            subjects,
        )
        val teachers = tuesday.slots.map { it.lessons.map(Lesson::teacher) }
        assertEquals(
            listOf(
                listOf("Püst"),
                listOf("Püst"),
                listOf("Püst"),
                listOf("Lund"),
                listOf("Lund"),
            ),
            teachers,
        )
    }

    @Test
    fun `next week Wednesday 7 Oct 2026 carries Wed slots over both column shifts`() {
        // Layout (KW 41 Wed):
        //   - 07:45-09:15 DSDS Bode 2.234 (IT+MI) [rowspan=2]
        //   - 09:45-11:15 DSDS Bode (carried)
        //   - 11:45-13:15 DSDS Lund 2.234 (V) [rowspan=3] (the second <td>
        //     in row 3 of the source HTML; it visually sits at Wed because
        //     Tue is being carried from UES Püst's rowspan=3)
        //   - 13:45-15:15 DSDS Lund (carried)
        //   - 15:30-17:00 DSDS Lund (carried)
        val wednesday = dayOn(html, LocalDate.of(2026, 10, 7))
        assertNotNull("Wednesday 2026-10-07 plan must exist", wednesday)
        assertEquals(5, wednesday!!.slots.size)
        val subjects = wednesday.slots.map { it.lessons.map(Lesson::subject) }
        assertEquals(
            listOf(
                listOf("DSDS"),
                listOf("DSDS"),
                listOf("DSDS"),
                listOf("DSDS"),
                listOf("DSDS"),
            ),
            subjects,
        )
        val teachers = wednesday.slots.map { it.lessons.map(Lesson::teacher) }
        assertEquals(
            listOf(
                listOf("Bode"),
                listOf("Bode"),
                listOf("Lund"),
                listOf("Lund"),
                listOf("Lund"),
            ),
            teachers,
        )
    }

    @Test
    fun `next week Thursday 8 Oct 2026 has EVSA-VSIT and VSIT-Buech blocks`() {
        // Layout (KW 41 Thu):
        //   - 07:45-09:15 EVSA 1.201 (Nind, Ü) + VSIT 1.202 (Winkl, V) [rowspan=2]
        //   - 09:45-11:15 EVSA + VSIT (carried)
        //   - 11:45-13:15 VSIT Büch 1.201 (IT-Compliance WPF) [rowspan=2]
        //     (third <td> in row 3 of source; lands at Thu because Wed and
        //     Tue are being carried, Mon took row-3 cell 1 and Fri is
        //     carried from row 1)
        //   - 13:45-15:15 VSIT Büch (carried)
        val thursday = dayOn(html, LocalDate.of(2026, 10, 8))
        assertNotNull("Thursday 2026-10-08 plan must exist", thursday)
        assertEquals(4, thursday!!.slots.size)
        val slots = thursday.slots
        // Slot 1: EVSA + VSIT parallel block
        val first = slots[0]
        assertEquals(LocalTime.of(7, 45), first.start)
        assertEquals(2, first.lessons.size)
        assertEquals("EVSA", first.lessons[0].subject)
        assertEquals("Nind", first.lessons[0].teacher)
        assertEquals("1.201", first.lessons[0].room)
        assertEquals("Ü", first.lessons[0].kind)
        assertEquals("VSIT", first.lessons[1].subject)
        assertEquals("Winkl", first.lessons[1].teacher)
        assertEquals("1.202", first.lessons[1].room)
        assertEquals("V", first.lessons[1].kind)
        // Slot 2: carried parallel block (same content, different time)
        assertEquals(LocalTime.of(9, 45), slots[1].start)
        assertEquals(2, slots[1].lessons.size)
        assertEquals("EVSA", slots[1].lessons[0].subject)
        assertEquals("VSIT", slots[1].lessons[1].subject)
        // Slot 3: VSIT Büch at 1.201
        val third = slots[2]
        assertEquals(LocalTime.of(11, 45), third.start)
        assertEquals("VSIT", third.lessons.single().subject)
        assertEquals("Büch", third.lessons.single().teacher)
        assertEquals("1.201", third.lessons.single().room)
        assertEquals("IT-Compliance WPF", third.lessons.single().remark)
        // Slot 4: carried VSIT Büch
        assertEquals(LocalTime.of(13, 45), slots[3].start)
        assertEquals("VSIT", slots[3].lessons.single().subject)
        assertEquals("Büch", slots[3].lessons.single().teacher)
    }

    @Test
    fun `next week Friday 9 Oct 2026 has two distinct DVS rooms`() {
        // Layout (KW 41 Fri):
        //   - 07:45-09:15 DVS Hänel 2.119 (L) [rowspan=3]
        //   - 09:45-11:15 DVS Hänel 2.119 (carried)
        //   - 11:45-13:15 DVS Hänel 2.119 (carried, last row of rowspan=3)
        //   - 13:45-15:15 DVS Hänel 2.015 (L) [rowspan=2]
        //     (second <td> in row 4 of source; lands at Fri because Mon,
        //     Tue and Wed are carried)
        //   - 15:30-17:00 DVS Hänel 2.015 (carried)
        val friday = dayOn(html, LocalDate.of(2026, 10, 9))
        assertNotNull("Friday 2026-10-09 plan must exist", friday)
        assertEquals(5, friday!!.slots.size)
        val rooms = friday.slots.map { it.lessons.single().room }
        assertEquals(
            listOf("2.119", "2.119", "2.119", "2.015", "2.015"),
            rooms,
        )
        val starts = friday.slots.map { it.start }
        assertEquals(
            listOf(
                LocalTime.of(7, 45),
                LocalTime.of(9, 45),
                LocalTime.of(11, 45),
                LocalTime.of(13, 45),
                LocalTime.of(15, 30),
            ),
            starts,
        )
    }

    @Test
    fun `next week does not bleed row 3 and row 4 cells into Monday`() {
        // The original bug: the weekday cursor was reset to Monday at the
        // start of every row, so the row-4 cell UES Lund (3.204) and the
        // row-4 cell DVS Hänel (2.015) were misassigned to Monday in
        // addition to their correct weekday. This left Monday with seven
        // slots instead of five (two of which were the Tue/Fri cells).
        val monday = dayOn(html, LocalDate.of(2026, 10, 5))!!
        // Every lesson on Monday must come from Monday's actual cells in
        // the source. That excludes UES (which is only on Tue) and any
        // DVS / VSIT-Büch cells that start on Wednesday / Thursday / Friday.
        for (slot in monday.slots) {
            for (lesson in slot.lessons) {
                assertTrue(
                    "Monday must not contain '${lesson.subject}' (was '$lesson')",
                    lesson.subject != "UES",
                )
                assertTrue(
                    "Monday must not contain DVS at room 2.015 (Fri's afternoon cell): '$lesson'",
                    !(lesson.subject == "DVS" && lesson.room == "2.015"),
                )
            }
        }
    }

    @Test
    fun `KW 42 Monday 12 Oct 2026 carries VSIT-EVSA through 3 rows and lands DSDS only on rows 4-5`() {
        // Regression test for KW 42 (week 2 of the fixture) where the
        // original cursor-reset bug also produced duplicate UES-Püst
        // slots on Monday. Layout (KW 42 Mon):
        //   - 07:45-09:15 VSIT + EVSA [rowspan=3]
        //   - 09:45-11:15 VSIT + EVSA (carried)
        //   - 11:45-13:15 VSIT + EVSA (carried, last row of rowspan=3)
        //   - 13:45-15:15 DSDS Lund 2.234 (V) [rowspan=2]
        //   - 15:30-17:00 DSDS Lund (carried)
        val monday = dayOn(html, LocalDate.of(2026, 10, 12))!!
        assertEquals(5, monday.slots.size)
        val subjects = monday.slots.map { it.lessons.map(Lesson::subject) }
        assertEquals(
            listOf(
                listOf("VSIT", "EVSA"),
                listOf("VSIT", "EVSA"),
                listOf("VSIT", "EVSA"),
                listOf("DSDS"),
                listOf("DSDS"),
            ),
            subjects,
        )
        // Make sure no UES Püst leaks into Monday (Wed UES Püst's cells
        // would otherwise be placed at Mon by the buggy cursor).
        for (slot in monday.slots) {
            for (lesson in slot.lessons) {
                assertNotEquals(
                    "Monday KW 42 must not contain '${lesson.subject}'",
                    "UES",
                    lesson.subject,
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private fun dayOn(html: String, date: LocalDate): DayPlan? =
        TimetableScraper.parseWeeks(html)
            .firstNotNullOfOrNull { week -> week.days.values.firstOrNull { it.date == date } }
}