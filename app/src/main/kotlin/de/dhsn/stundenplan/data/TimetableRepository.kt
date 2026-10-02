package de.dhsn.stundenplan.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One concrete lesson entry (subject, teacher, room, …).
 *
 * A `Lesson` always lives inside a [LessonSlot] which carries the canonical
 * time range for that time-block. Several lessons can share one slot (i.e.
 * Doppelbelegung / parallel modules).
 */
data class Lesson(
    val start: LocalTime,
    val end: LocalTime,
    val subject: String,
    val teacher: String,
    val room: String,
    val kind: String,
    val remark: String,
)

/**
 * Fetches the BA-Dresden PlanServlet HTML. Extracted as an interface so the
 * repository can be unit-tested without hitting the network.
 */
fun interface TimetableFetcher {
    /** Returns the HTML body for [url] or throws on failure. */
    fun fetch(url: String): String
}

/** Default fetcher: blocking HttpURLConnection with a friendly User-Agent. */
class HttpUrlConnectionFetcher(
    private val userAgent: String = "dhsn-stundenplan-widget/0.1 (Android)",
) : TimetableFetcher {
    override fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "text/html,*/*")
            setRequestProperty("Accept-Language", "de")
        }
        try {
            val code = conn.responseCode
            Log.d(TAG, "HTTP $code for $url")
            if (code !in 200..299) error("HTTP $code from $url")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
    private companion object {
        const val TAG = "TimetableRepo"
    }
}

/**
 * Single source of truth for the widget.
 *
 * Owns:
 *   - building the PlanServlet URL for a widget id (incl. configured class id)
 *   - fetching the HTML and parsing it via [TimetableScraper]
 *   - a tiny in-memory + DataStore-backed cache for "today's lessons" and
 *     the "last fetched" timestamp so the widget can render something useful
 *     even when the device is offline.
 */
object TimetableRepository {

    private const val TAG = "TimetableRepo"

    /** Default class id used when the user hasn't configured one yet. */
    const val DEFAULT_CLASS_ID = "3it24-1"

    /** Persisted cache for fetch timestamps, kept per widget class id. */
    private val Context.fetchDataStore: DataStore<Preferences>
            by preferencesDataStore(name = "widget_fetch")

    /**
     * Active fetcher. Replace in tests via [setFetcherForTesting]; the
     * default is the blocking HTTP fetcher used in production.
     */
    @Volatile
    private var fetcher: TimetableFetcher = HttpUrlConnectionFetcher()

    /** Inject a fake fetcher. Intended for unit tests only. */
    fun setFetcherForTesting(f: TimetableFetcher?) {
        fetcher = f ?: HttpUrlConnectionFetcher()
    }

    /**
     * Build the timetable URL for the given widget instance.
     *
     * Falls back to a default class id if the user hasn't configured one yet
     * (e.g. when WorkManager refreshes without an explicit widget id).
     */
    suspend fun buildUrl(context: Context, appWidgetId: Int?, defaultClassId: String = DEFAULT_CLASS_ID): String {
        val classId = appWidgetId?.let { id ->
            WidgetConfigStore(context).getClassId(id)
        } ?: defaultClassId

        val url = "https://stundenplan.ba-dresden.de/stundenplan/PlanServlet" +
            "?akttyp=1&aktwert=$classId&legendefach=on"
        Log.d(TAG, "buildUrl(widgetId=$appWidgetId) -> $url")
        return url
    }

    /**
     * Return the slots for [date] for the given widget id (defaults to today).
     *
     * Tries (in order):
     *   1. an in-memory cache keyed by classId whose date matches exactly
     *   2. an on-disk cache (DataStore) whose date matches exactly
     *   3. a fresh network fetch + parse, looking up [date] across the
     *      8-week page returned by the server
     *   4. any cached snapshot for the same classId (date-mismatch is OK —
     *      "always fall back to the old one if new one failed")
     *   5. dummy data as a last resort so the widget never renders empty
     */
    suspend fun day(context: Context, appWidgetId: Int? = null, date: LocalDate = LocalDate.now()): List<LessonSlot> {
        val classId = resolveClassId(context, appWidgetId)
        val today = LocalDate.now()

        memoryCache[classId]?.let { cached ->
            if (cached.date == date) {
                Log.d(TAG, "day($date) memory hit classId=$classId slots=${cached.slots.size}")
                return cached.slots
            }
        }

        readPersisted(context, classId)?.let { onDisk ->
            if (onDisk.date == date) {
                Log.d(TAG, "day($date) disk hit classId=$classId slots=${onDisk.slots.size}")
                memoryCache[classId] = onDisk
                return onDisk.slots
            }
        }

        val url = buildUrl(context, appWidgetId, classId)
        Log.i(TAG, "day($date) cache miss — fetching $url")
        return try {
            val html = withContext(Dispatchers.IO) { fetcher.fetch(url) }
            Log.d(TAG, "fetched ${html.length} bytes")
            val weeks = TimetableScraper.parseWeeks(html)
            Log.d(TAG, "parsed ${weeks.size} week(s)")
            // Look up the requested date in any of the parsed weeks; this
            // also gives us a chance to update the snapshot for [today] so
            // that a navigation back to "today" reuses fresh data without
            // hitting the network again.
            val dayPlan = TimetableScraper.parseDay(html, date)
            if (dayPlan != null) {
                val slots = dayPlan.slots
                Log.i(TAG, "day($date) hit slots=${slots.size} lessons=${slots.sumOf { it.lessons.size }}")
                val snap = DaySnapshot(date, slots)
                memoryCache[classId] = snap
                persist(context, classId, snap)
                slots
            } else {
                Log.w(TAG, "day($date) not in fetched weeks ($date)")
                // The fetched page covers a different date range. If [date]
                // is "today", try to find today's dayPlan inside the page
                // so we still update the cache for subsequent calls.
                if (date == today) {
                    val todayPlan = TimetableScraper.parseDay(html, today)
                    if (todayPlan != null) {
                        val snap = DaySnapshot(today, todayPlan.slots)
                        memoryCache[classId] = snap
                        persist(context, classId, snap)
                    }
                }
                emptyList()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "day($date) fetch/parse failed: ${e.javaClass.simpleName}: ${e.message}", e)
            // Fall back to the most-recent cached snapshot for this
            // classId, regardless of its date. The user explicitly asked
            // for "always fall back to the old one if new one failed" so
            // we never throw away data we already have on disk / in
            // memory unless the cache is genuinely empty.
            val anyCached = memoryCache[classId] ?: readPersisted(context, classId)
            if (anyCached != null) {
                Log.w(TAG, "day($date) serving stale cache from ${anyCached.date}")
                anyCached.slots
            } else {
                Log.w(TAG, "day($date) no cache available — falling back to dummy")
                dummyFor(date.dayOfWeek.value)
            }
        }
    }

    /**
     * Convenience: today's slots. Same as `day(date = LocalDate.now())`
     * — kept for backwards compatibility with existing callers.
     */
    suspend fun today(context: Context, appWidgetId: Int? = null): List<LessonSlot> =
        day(context, appWidgetId, LocalDate.now())

    /**
     * Force a network fetch (ignoring caches). Returns the slots that were
     * fetched (which may be empty if today has no lessons), or null on
     * network / parse failure so the caller can keep showing cached data.
     */
    suspend fun refresh(context: Context, appWidgetId: Int? = null): List<LessonSlot>? {
        val classId = resolveClassId(context, appWidgetId)
        val url = buildUrl(context, appWidgetId, classId)
        Log.i(TAG, "refresh() starting classId=$classId url=$url")
        return try {
            val html = withContext(Dispatchers.IO) { fetcher.fetch(url) }
            Log.d(TAG, "refresh() fetched ${html.length} bytes")
            val today = LocalDate.now()
            val dayPlan = TimetableScraper.parseDay(html, today)
            if (dayPlan != null) {
                val snap = DaySnapshot(today, dayPlan.slots)
                memoryCache[classId] = snap
                persist(context, classId, snap)
                Log.i(TAG, "refresh() ok slots=${dayPlan.slots.size}")
                dayPlan.slots
            } else {
                // Page returned no day plan for today (e.g. semester break).
                // Cache an empty snapshot for today so we don't re-fetch
                // uselessly until the date rolls over.
                val snap = DaySnapshot(today, emptyList())
                memoryCache[classId] = snap
                persist(context, classId, snap)
                Log.i(TAG, "refresh() ok (no lessons today) slots=0")
                emptyList()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "refresh() failed: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    /**
     * Refresh every distinct classId that appears across the given list of
     * active widget instances, plus the default ("3it24-1") if it isn't
     * represented. Used by the periodic/one-shot worker so widgets with
     * custom class ids are also kept up to date.
     *
     * Returns the number of class ids that were refreshed successfully.
     */
    suspend fun refreshAll(context: Context, widgetIds: List<Int>): Int {
        val distinct = widgetIds
            .mapNotNull { id -> resolveClassIdOrNull(context, id) }
            .distinct()
            .ifEmpty { listOf(DEFAULT_CLASS_ID) }
        Log.i(TAG, "refreshAll() widgetIds=$widgetIds -> distinct=$distinct")
        var ok = 0
        for (classId in distinct) {
            val res = try {
                refreshFor(context, classId)
            } catch (e: Throwable) {
                Log.e(TAG, "refreshAll() failed for $classId: ${e.message}", e)
                null
            }
            if (res != null) ok++
        }
        return ok
    }

    private suspend fun refreshFor(context: Context, classId: String): List<LessonSlot>? {
        val url = buildUrl(context, null, classId)
        return try {
            val html = withContext(Dispatchers.IO) { fetcher.fetch(url) }
            val today = LocalDate.now()
            val dayPlan = TimetableScraper.parseDay(html, today)
            if (dayPlan != null) {
                val snap = DaySnapshot(today, dayPlan.slots)
                memoryCache[classId] = snap
                persist(context, classId, snap)
                dayPlan.slots
            } else {
                val snap = DaySnapshot(today, emptyList())
                memoryCache[classId] = snap
                persist(context, classId, snap)
                emptyList()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "refreshFor($classId) failed: ${e.message}", e)
            null
        }
    }

    private suspend fun resolveClassIdOrNull(context: Context, appWidgetId: Int): String? =
        try {
            WidgetConfigStore(context).getClassId(appWidgetId)
        } catch (e: Throwable) {
            Log.w(TAG, "resolveClassIdOrNull($appWidgetId) failed: ${e.message}")
            null
        }

    /**
     * Returns the human-readable time of the last successful fetch for this
     * classId, or "–" if we never fetched. Used by the widget header.
     */
    suspend fun lastFetchedLabel(context: Context, appWidgetId: Int? = null): String {
        val classId = resolveClassId(context, appWidgetId)
        val stamp = readStamp(context, classId) ?: return "–"
        return stamp.format(DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN))
    }

    /**
     * Returns the last successful refresh's [LocalDateTime], or null if we
     * never fetched. Used by the settings UI.
     */
    suspend fun lastFetched(context: Context, appWidgetId: Int? = null): LocalDateTime? {
        val classId = resolveClassId(context, appWidgetId)
        return readStamp(context, classId)
    }

    // -----------------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------------

    private suspend fun resolveClassId(context: Context, appWidgetId: Int?): String =
        appWidgetId?.let { id ->
            WidgetConfigStore(context).getClassId(id)
        } ?: DEFAULT_CLASS_ID

    /** In-memory cache: one entry per configured classId. */
    private val memoryCache = mutableMapOf<String, DaySnapshot>()

    private data class DaySnapshot(val date: LocalDate, val slots: List<LessonSlot>)

    private fun snapshotKey(classId: String) = stringPreferencesKey("lessons_$classId")
    private fun stampKey(classId: String) = stringPreferencesKey("stamp_$classId")

    private suspend fun readPersisted(context: Context, classId: String): DaySnapshot? {
        return try {
            val raw = context.fetchDataStore.data
                .map { it[snapshotKey(classId)] }
                .first()
            raw ?: return null.also {
                Log.d(TAG, "readPersisted($classId) no entry in DataStore")
            }
            val parts = raw.split("|", limit = 2)
            if (parts.size != 2) return null
            val date = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return null
            // Layout: date | slotStart;lessonEnd;lesson1;lesson2;...|slot2;lesson4;...
            // Slot boundaries encoded with two consecutive `;;` markers.
            val slotsRaw = parts[1].split("|")
            val slots = slotsRaw.mapNotNull(::decodeSlot)
            DaySnapshot(date, slots)
        } catch (e: Throwable) {
            Log.w(TAG, "readPersisted($classId) failed: ${e.message}")
            null
        }
    }

    private suspend fun persist(context: Context, classId: String, snapshot: DaySnapshot) {
        try {
            context.fetchDataStore.edit { prefs ->
                prefs[stampKey(classId)] = LocalDateTime.now().toString()
                val payload = buildString {
                    append(snapshot.date.toString())
                    append("|")
                    snapshot.slots.joinTo(this, separator = "|") { encodeSlot(it) }
                }
                prefs[snapshotKey(classId)] = payload
            }
        } catch (e: Throwable) {
            Log.w(TAG, "persist($classId) failed: ${e.message}")
        }
    }

    private suspend fun readStamp(context: Context, classId: String): LocalDateTime? = try {
        val raw = context.fetchDataStore.data
            .map { it[stampKey(classId)] }
            .first()
        raw ?: return null
        runCatching { LocalDateTime.parse(raw) }.getOrNull()
    } catch (e: Throwable) {
        Log.w(TAG, "readStamp($classId) failed: ${e.message}")
        null
    }

    /**
     * Encoding scheme for one [LessonSlot]:
     *   `slotStart; slotEnd; encodedLesson1; encodedLesson2; ...`
     * where each `encodedLesson` is `start|end|subject|teacher|room|kind|remark`
     * (7 fields, joined by `|`).
     *
     * Special characters in lesson fields are escaped on encoding
     * (see [encodeLesson]) so a `;` or `|` inside a field never collides
     * with the structural delimiters.
     */
    internal fun encodeSlot(slot: LessonSlot): String =
        slot.start.toString() + ";" + slot.end.toString() + ";" +
            slot.lessons.joinToString(";") { encodeLesson(it) }

    internal fun decodeSlot(s: String): LessonSlot? {
        if (s.isEmpty()) return null
        val parts = s.split(";")
        // Need at least: slotStart;slotEnd;oneLesson  (3 parts).
        if (parts.size < 3) return null
        return runCatching {
            val start = LocalTime.parse(parts[0])
            val end = LocalTime.parse(parts[1])
            // parts[2..] are individual lessons, each encoded as 7 pipe-
            // separated fields (see encodeSlot).
            val lessons = parts.drop(2).mapNotNull(::decodeLesson)
            if (lessons.isEmpty()) null else LessonSlot(start, end, lessons)
        }.getOrNull()
    }

    private fun encodeLesson(l: Lesson): String = listOf(
        l.start.toString(),
        l.end.toString(),
        l.subject.replace("|", "/").replace(";", ","),
        l.teacher.replace("|", "/").replace(";", ","),
        l.room.replace("|", "/").replace(";", ","),
        l.kind.replace("|", "/").replace(";", ","),
        l.remark.replace("|", "/").replace(";", ","),
    ).joinToString("|")

    private fun decodeLesson(s: String): Lesson? {
        val parts = s.split("|")
        if (parts.size < 7) return null
        return runCatching {
            Lesson(
                start = LocalTime.parse(parts[0]),
                end = LocalTime.parse(parts[1]),
                subject = parts[2],
                teacher = parts[3],
                room = parts[4],
                kind = parts[5],
                remark = parts[6],
            )
        }.getOrNull()
    }

    /** Built-in placeholder used only when we cannot fetch or read any cache. */
    private fun dummyFor(day: Int): List<LessonSlot> = when (day) {
        1 -> listOf(
            LessonSlot(
                LocalTime.of(8, 0), LocalTime.of(9, 30),
                listOf(
                    Lesson(
                        LocalTime.of(8, 0), LocalTime.of(9, 30),
                        "Mathe", "Muster", "A101", "V", "",
                    ),
                ),
            ),
        )
        else -> emptyList()
    }
}