package de.dhsn.stundenplan.data

import java.time.LocalDate
import java.time.LocalTime

/**
 * One (weekday, slot) pair. A slot may hold several parallel lessons
 * ("Doppelbelegung"); that's why [lessons] is a list.
 */
data class LessonSlot(
    val start: LocalTime,
    val end: LocalTime,
    val lessons: List<Lesson>,
)

/**
 * One day of the timetable.
 */
data class DayPlan(
    val date: LocalDate,
    val slots: List<LessonSlot>,
) {
    /** Flat list of all lessons for the day, sorted by start. */
    val lessons: List<Lesson> get() = slots.flatMap { it.lessons }
}

/**
 * One week of the timetable, indexed by Java `DayOfWeek` (1 = Monday … 5 = Friday).
 * Weekends aren't shown by the BA page and are absent from the map.
 */
data class WeekPlan(
    val weekStart: LocalDate,
    val days: Map<Int, DayPlan>,
)

/**
 * Pure-Kotlin scraper for the BA-Dresden "PlanServlet" HTML page.
 *
 * The page is a series of `<table class="Plan">`, one per week:
 *   - a `<caption>` like `42. Woche vom 12.10.2026-18.10.2026`
 *   - a header row of weekday columns with `data-tooltip="12.Oct"`
 *   - 7 time rows (1.–7.), each starting with
 *     `<th class="zeit">N.<span class="vonbis"> HH:MM-<wbr>HH:MM</span></th>`
 *   - 5 `<td class="Vorlesung">` cells per row, each holding one or more
 *     lessons encoded as `<span class="fach">…</span> <span class="dozent">…</span>
 *     <span class="ort">…</span>` plus optional `<span class="typ">V|Ü|L</span>`
 *     or `<span class="bemerkung">…</span>`, separated by `<br />`.
 *
 * Lessons can be rendered with `rowspan="N"` so a single cell visually
 * covers N consecutive time rows; we treat each row's own `<th class="zeit">`
 * as the canonical time, so rowspan only affects how the cell is rendered
 * (not the slot time).
 *
 * We deliberately avoid Jsoup / kotlinx.html to keep the APK small. Plain
 * regex is fine because the structure is fixed.
 */
object TimetableScraper {

    /** Whole-week table; DOT_MATCHES_ALL so the colspan-ridden markup survives. */
    private val WEEK_TABLE = Regex(
        """<table\s+class="Plan"[^>]*>(.*?)</table>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /** A single `<tr>…</tr>` row; DOT_MATCHES_ALL so we can span lines. */
    private val ROW = Regex(
        """<tr\b[^>]*>(.*?)</tr>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /**
     * A single `<td …>…</td>` cell, capturing the FULL opening tag (with all
     * attributes) so we can detect `class="Vorlesung"` and read `rowspan`.
     *
     * Group 1 = the attribute list inside the opening tag (between `<td` and `>`).
     * Group 2 = the inner HTML (between `>` and `</td>`).
     */
    private val TD_CELL = Regex(
        """<td\b([^>]*)>(.*?)</td>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /** Captures week range out of a caption like "42. Woche vom 12.10.2026-18.10.2026". */
    private val WEEK_RANGE = Regex(
        """(\d{1,2})\.\s*Woche\s+vom\s*(\d{1,2})\.(\d{1,2})\.(\d{4})\s*-\s*(\d{1,2})\.(\d{1,2})\.(\d{4})""",
        RegexOption.IGNORE_CASE,
    )

    /** Captures a single date tooltip like "12.Oct", "5.Oct", "28.Sep". */
    private val DATE_TOOLTIP = Regex("""(\d{1,2})\.([A-Za-zÄÖÜäöü]+)""")

    /**
     * Captures the time range inside a `<span class="vonbis"> HH:MM-<wbr>HH:MM</span>`.
     * Tolerant of HTML noise between the two times (`-` + optional `<wbr>` / whitespace).
     */
    private val TIME_RANGE = Regex(
        """(\d{1,2}):(\d{2})\s*-\s*(?:<[^>]*>\s*)*(\d{1,2}):(\d{2})""",
        RegexOption.IGNORE_CASE,
    )

    /** Header row right after `<caption>`: contains the weekday Plankopf cells. */
    private val HEADER_ROW = Regex(
        """<th[^>]*class="Plankopf[^"]*"[^>]*>(.*?)</tr>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /**
     * Split a cell on `<br />` (also `<br>` / `<br/>`) into separate lesson chunks.
     */
    private val CELL_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    /** Reads a class attribute (possibly with multiple values like "Vorlesung typ"). */
    private val CLASS_RE = Regex("""class\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

    /** Reads a numeric attribute like `rowspan="2"`. */
    private val ROWSPAN_RE = Regex("""rowspan\s*=\s*"(\d+)"""", RegexOption.IGNORE_CASE)

    private val MONTHS_DE = mapOf(
        "Jan" to 1, "Feb" to 2, "Mär" to 3, "Mar" to 3,
        "Apr" to 4, "Mai" to 5, "May" to 5,
        "Jun" to 6, "Jul" to 7,
        "Aug" to 8, "Sep" to 9, "Sept" to 9,
        "Okt" to 10, "Oct" to 10,
        "Nov" to 11, "Dez" to 12, "Dec" to 12,
    )

    /**
     * Parse the full HTML page into weeks.
     *
     * Returns an empty list if [html] is null / blank or if no week table can
     * be parsed – the caller is expected to fall back to its dummy data.
     */
    fun parseWeeks(html: String?): List<WeekPlan> {
        if (html.isNullOrBlank()) return emptyList()
        return WEEK_TABLE.findAll(html).mapNotNull { parseWeek(it.groupValues[1]) }.toList()
    }

    /**
     * Convenience: return the day matching [date] across all parsed weeks.
     * Returns null if not found.
     */
    fun parseDay(html: String?, date: LocalDate): DayPlan? {
        val weeks = parseWeeks(html)
        return weeks.firstNotNullOfOrNull { week ->
            week.days.values.firstOrNull { it.date == date }
        }
    }

    // -----------------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------------

    internal fun parseWeek(tableBody: String): WeekPlan? {
        val captionMatch = WEEK_RANGE.find(tableBody) ?: return null
        val startYear = captionMatch.groupValues[4].toInt()
        val startMonth = captionMatch.groupValues[3].toInt()
        val weekStart = LocalDate.of(
            startYear,
            startMonth,
            captionMatch.groupValues[2].toInt(),
        )

        // weekday dates from the header row's data-tooltips
        val weekdayDates = parseWeekdayDates(tableBody, weekStart)

        // Collect every <tr> that contains a "zeit" cell along with its parsed
        // time so we can compute day indices even when a row has fewer <td>s
        // (because some cells were visually merged from the previous row via
        // `rowspan`).
        data class TimeRow(val start: LocalTime, val end: LocalTime, val rowHtml: String)
        val timeRows = mutableListOf<TimeRow>()
        for (rowMatch in ROW.findAll(tableBody)) {
            val row = rowMatch.groupValues[1]
            if (!row.contains("class=\"zeit")) continue
            val timeMatch = TIME_RANGE.find(row) ?: continue
            val start = LocalTime.of(timeMatch.groupValues[1].toInt(), timeMatch.groupValues[2].toInt())
            val end = LocalTime.of(timeMatch.groupValues[3].toInt(), timeMatch.groupValues[4].toInt())
            timeRows += TimeRow(start, end, row)
        }

        val days = mutableMapOf<Int, DayPlan>()
        // Per-column state for cells that were started in an earlier row and
        // are still being visually carried into the current row via `rowspan`.
        // `carryCount[c]` is the number of rows the carried cell still spans
        // (0 once the cell is done). `carryLessons[c]` is the lessons to
        // repeat for each carried row. We process the table top-to-bottom:
        // for every row we walk Mon..Fri; for each column we either emit a
        // carried slot (and decrement the counter) or pull the next `<td>`
        // out of the row's cell list (skipping empty `<td> </td>` placeholders).
        //
        // The BA server emits rows in two shapes:
        //   - "sparse" rows: fewer than 5 `<td>` entries, positioned 1:1 with
        //     the non-carried weekday columns; carried columns have no cell
        //     at all in the row.
        //   - "full" rows: exactly 5 `<td>` entries, one per weekday column;
        //     carried columns get an empty `<td> </td>` placeholder.
        // We detect the full shape by counting cells and switch alignment
        // so that both shapes are decoded correctly.
        val carryCount = IntArray(5)
        val carryLessons = arrayOfNulls<List<Lesson>>(5)

        fun ensureDay(col: Int): DayPlan? {
            val baseDate = weekdayDates.getOrNull(col) ?: return null
            return days.getOrPut(col + 1) { DayPlan(baseDate, mutableListOf()) }
        }

        for ((rowIndex, timeRow) in timeRows.withIndex()) {
            val cells = TD_CELL.findAll(timeRow.rowHtml).toList()
            val fullAlignment = cells.size == 5
            var cellIdx = 0
            for (col in 0 until 5) {
                if (carryCount[col] > 0) {
                    // Carried cell still has rows left. Emit a slot for THIS
                    // row using the carried lessons. In full alignment the
                    // matching `<td>` (if any) is an empty placeholder for
                    // the carried column, so we deliberately do NOT consume
                    // it.
                    val day = ensureDay(col) ?: continue
                    val dayList = day.slots as MutableList<LessonSlot>
                    dayList.add(LessonSlot(timeRow.start, timeRow.end, carryLessons[col]!!))
                    carryCount[col]--
                    continue
                }
                val cellMatch = if (fullAlignment) {
                    // cells[col] is the cell for column `col`.
                    if (col >= cells.size) continue
                    cells[col]
                } else if (cellIdx >= cells.size) {
                    continue
                } else {
                    val m = cells[cellIdx]
                    cellIdx++
                    m
                }

                val attrs = cellMatch.groupValues[1]
                val cellHtml = cellMatch.groupValues[2]
                if (!classNames(attrs).contains("Vorlesung")) continue
                if (!cellHtml.contains("fach")) continue

                val lessons = parseLessonsInCell(cellHtml, timeRow.start, timeRow.end)
                if (lessons.isEmpty()) continue

                // Rowspanned cell: emit ONE slot for the current row using
                // its own <th class="zeit"> as the canonical time, then
                // remember the lessons + remaining carry count so the
                // following (rowspan-1) rows emit a carried slot each.
                val rowspan = rowSpan(attrs).coerceAtLeast(1)
                val span = minOf(rowspan, timeRows.size - rowIndex)
                val day = ensureDay(col) ?: continue
                val dayList = day.slots as MutableList<LessonSlot>
                dayList.add(LessonSlot(timeRow.start, timeRow.end, lessons))
                if (span > 1) {
                    carryLessons[col] = lessons
                    carryCount[col] = span - 1
                }
            }
        }

        // sort slots in each day by start time
        days.values.forEach { day ->
            (day.slots as MutableList<LessonSlot>).sortBy { it.start }
        }

        return WeekPlan(weekStart, days)
    }

    private fun parseWeekdayDates(tableBody: String, weekStart: LocalDate): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        val headerRow = HEADER_ROW.find(tableBody)?.groupValues?.get(1) ?: return emptyList()
        val tooltips = DATE_TOOLTIP.findAll(headerRow).toList()
        for ((i, match) in tooltips.withIndex()) {
            if (i >= 5) break
            val day = match.groupValues[1].toInt()
            val monthName = match.groupValues[2]
            val month = MONTHS_DE[monthName]
                ?: MONTHS_DE.entries.firstOrNull {
                    it.key.equals(monthName, ignoreCase = true)
                }?.value
                ?: continue
            val year = weekStart.year
            runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let { dates += it }
        }
        while (dates.size < 5) dates += weekStart.plusDays(dates.size.toLong())
        return dates.take(5)
    }

    private fun parseLessonsInCell(
        cellHtml: String,
        slotStart: LocalTime,
        slotEnd: LocalTime,
    ): List<Lesson> {
        val blocks = CELL_BREAK.split(cellHtml)
        val out = mutableListOf<Lesson>()
        for (raw in blocks) {
            val clean = raw.replace("<wbr>", "").replace("&nbsp;", " ")
            val fach = spanText(clean, "fach") ?: continue
            val ort = spanText(clean, "ort") ?: continue
            val dozent = spanText(clean, "dozent").orEmpty()
            val typ = spanText(clean, "typ").orEmpty()
            val bemerkung = spanText(clean, "bemerkung").orEmpty()

            out += Lesson(
                start = slotStart,
                end = slotEnd,
                subject = decode(fach),
                teacher = decode(dozent),
                room = decode(ort),
                kind = decode(typ).trim(),
                remark = decode(bemerkung).trim(),
            )
        }
        return out
    }

    private fun spanText(html: String, name: String): String? {
        val pat = Regex("""class="$name"\s*>([^<]+)</span>""", RegexOption.IGNORE_CASE)
        return pat.find(html)?.groupValues?.get(1)?.trim()
    }

    /** Returns the whitespace-separated class names from a tag's attribute list. */
    private fun classNames(attrs: String): List<String> {
        val m = CLASS_RE.find(attrs) ?: return emptyList()
        return m.groupValues[1].split(Regex("""\s+"""))
    }

    /** Returns the integer rowspan value, defaulting to 1 if absent. */
    private fun rowSpan(attrs: String): Int =
        ROWSPAN_RE.find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    /** Decode the few HTML entities the BA server uses. */
    private fun decode(s: String): String =
        s.replace("&auml;", "ä")
            .replace("&Auml;", "Ä")
            .replace("&ouml;", "ö")
            .replace("&Ouml;", "Ö")
            .replace("&uuml;", "ü")
            .replace("&Uuml;", "Ü")
            .replace("&szlig;", "ß")
            .replace("&amp;", "&")
            .replace("&nbsp;", " ")
            .replace("&#xA0;", " ")
}