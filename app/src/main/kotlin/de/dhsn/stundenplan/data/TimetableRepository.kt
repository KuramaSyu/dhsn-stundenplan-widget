package de.dhsn.stundenplan.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
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
 *
 * ## Caching model
 *
 * The BA server returns up to 8 weeks of data in one HTML response. To avoid
 * the classic "concurrent `week()` calls race on a single-date cache" bug we
 * cache the **full** 8-week parse per classId in `weeksCache`. A single
 * network fetch therefore populates every day in the visible range at once,
 * and a subsequent `week()` for an adjacent week reuses the same in-memory
 * parse instead of triggering another HTTP request.
 *
 * On-disk we still persist exactly one `DaySnapshot` per classId (today's
 * slots), because the widget needs to render something quickly after a cold
 * start before any coroutine has had a chance to fetch.
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

        Log.i(TAG, "day($date) cache miss — fetching classId=$classId")
        val parsedWeeks = fetchAndCacheWeeks(context, appWidgetId)
        if (parsedWeeks != null) {
            val dayPlan = findDayPlan(parsedWeeks, date)
            if (dayPlan != null) {
                val slots = dayPlan.slots
                Log.i(TAG, "day($date) hit slots=${slots.size} lessons=${slots.sumOf { it.lessons.size }}")
                val snap = DaySnapshot(date, slots)
                memoryCache[classId] = snap
                persist(context, classId, snap)
                return slots
            }
            // The fetched page covers a different date range. If [date]
            // is "today", try to find today's dayPlan inside the page
            // so we still update the cache for subsequent calls.
            if (date == today) {
                val todayPlan = findDayPlan(parsedWeeks, today)
                if (todayPlan != null) {
                    val snap = DaySnapshot(today, todayPlan.slots)
                    memoryCache[classId] = snap
                    persist(context, classId, snap)
                }
            }
            Log.w(TAG, "day($date) not in fetched weeks")
            return emptyList()
        }

        // Fetch failed — fall back to whatever we still have on disk / in
        // memory, regardless of its date. The user explicitly asked for
        // "always fall back to the old one if new one failed" so we never
        // throw away data we already have unless the cache is genuinely
        // empty.
        val anyCached = memoryCache[classId] ?: readPersisted(context, classId)
        return if (anyCached != null) {
            Log.w(TAG, "day($date) serving stale cache from ${anyCached.date}")
            anyCached.slots
        } else {
            Log.w(TAG, "day($date) no cache available — falling back to dummy")
            dummyFor(date.dayOfWeek.value)
        }
    }

    /**
     * Convenience: today's slots. Same as `day(date = LocalDate.now())`
     * — kept for backwards compatibility with existing callers.
     */
    suspend fun today(context: Context, appWidgetId: Int? = null): List<LessonSlot> =
        day(context, appWidgetId, LocalDate.now())

    /**
     * Returns the [DayPlan]s for the week containing [monday], Mon–Fri.
     *
     * The BA server returns up to 8 weeks of HTML in a single response, so we
     * fetch once and serve all five weekdays from the same parsed result.
     * This is important for two reasons:
     *
     *   - It halves the network traffic for first-time views of a week.
     *   - It eliminates the previous race where two concurrent `week()`
     *     calls each ran their own refresh and raced to write a per-date
     *     `DaySnapshot` — the last write won, sometimes from a cancelled
     *     call, leaving the screen showing the wrong week.
     *
     * Resolution order:
     *   1. If the in-memory `weeksCache` for this classId already contains
     *      all five weekdays, build `DayPlan`s from it (no network).
     *   2. Otherwise issue a single network fetch, parse all weeks, store
     *      the result in `weeksCache`, and build the five `DayPlan`s from
     *      it.
     *   3. On failure, fall back to per-date `day()` calls which can use
     *      stale cache / disk / dummy. This never throws — the widget
     *      always gets five `DayPlan`s back.
     */
    suspend fun week(
        context: Context,
        appWidgetId: Int? = null,
        monday: LocalDate,
    ): List<DayPlan> {
        val classId = resolveClassId(context, appWidgetId)
        val dates = (0..4).map { monday.plusDays(it.toLong()) }

        // Cheap path: every weekday is already in the in-memory 8-week
        // cache. No network needed; just stitch five DayPlans together.
        val cached = weeksCache[classId]
        if (cached != null && dates.all { d -> findDayPlan(cached.weeks, d) != null }) {
            Log.d(TAG, "week($monday) full cache hit classId=$classId")
            return dates.map { d -> findDayPlan(cached.weeks, d)!! }
        }

        // Cache miss (or partial miss). Fetch once and look up everything
        // we need inside the parsed weeks. This replaces the old
        // `runCatching { refresh(...) }` pattern which triggered a second
        // concurrent network call (and was the source of the race).
        Log.i(TAG, "week($monday) partial cache miss — fetching classId=$classId")
        val parsedWeeks = fetchAndCacheWeeks(context, appWidgetId)
        if (parsedWeeks != null) {
            val out = mutableListOf<DayPlan>()
            for (date in dates) {
                val plan = findDayPlan(parsedWeeks, date)
                if (plan != null) {
                    out.add(plan)
                } else {
                    // Date outside the server's returned range — build a
                    // synthetic empty DayPlan so the UI still gets 5
                    // entries.
                    out.add(DayPlan(date, emptyList()))
                }
            }
            return out
        }

        // Fetch failed: fall back to per-date `day()` calls. Each one can
        // still hit stale memory cache, stale disk, or dummy data — so the
        // user always sees something.
        Log.w(TAG, "week($monday) fetch failed — falling back to per-date day() calls")
        return dates.map { date -> DayPlan(date, day(context, appWidgetId, date)) }
    }

    /**
     * Force a network fetch (ignoring caches). Returns the slots that were
     * fetched (which may be empty if today has no lessons), or null on
     * network / parse failure so the caller can keep showing cached data.
     */
    suspend fun refresh(context: Context, appWidgetId: Int? = null): List<LessonSlot>? {
        val classId = resolveClassId(context, appWidgetId)
        Log.i(TAG, "refresh() starting classId=$classId")
        val parsedWeeks = fetchAndCacheWeeks(context, appWidgetId) ?: return null
        val today = LocalDate.now()
        val todayPlan = findDayPlan(parsedWeeks, today)
        return if (todayPlan != null) {
            val snap = DaySnapshot(today, todayPlan.slots)
            memoryCache[classId] = snap
            persist(context, classId, snap)
            Log.i(TAG, "refresh() ok slots=${todayPlan.slots.size}")
            todayPlan.slots
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "refreshAll() failed for $classId: ${e.message}", e)
                null
            }
            if (res != null) ok++
        }
        return ok
    }

    private suspend fun refreshFor(context: Context, classId: String): List<LessonSlot>? {
        Log.i(TAG, "refreshFor($classId) starting")
        val parsedWeeks = fetchAndCacheWeeks(context, null, classId) ?: return null
        val today = LocalDate.now()
        val todayPlan = findDayPlan(parsedWeeks, today)
        return if (todayPlan != null) {
            val snap = DaySnapshot(today, todayPlan.slots)
            memoryCache[classId] = snap
            persist(context, classId, snap)
            todayPlan.slots
        } else {
            val snap = DaySnapshot(today, emptyList())
            memoryCache[classId] = snap
            persist(context, classId, snap)
            emptyList()
        }
    }

    private suspend fun resolveClassIdOrNull(context: Context, appWidgetId: Int): String? =
        try {
            WidgetConfigStore(context).getClassId(appWidgetId)
        } catch (e: CancellationException) {
            throw e
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
        } ?: readGlobalClassId(context) ?: DEFAULT_CLASS_ID

    /**
     * In-app class-id preference, used when [TimelineActivity] (which has
     * no widget id) asks the repository for data. Stored in the same
     * `widget_fetch` DataStore as a single string preference, separate
     * from the per-widget entries so widget configuration continues to work
     * exactly as before.
     *
     * Returns `null` when the user hasn't picked one yet, in which case
     * [resolveClassId] falls back to [DEFAULT_CLASS_ID].
     */
    private val globalClassIdKey = stringPreferencesKey("global_class_id")

    /** Persist the seminargruppe used by the in-app timeline viewer. */
    suspend fun saveGlobalClassId(context: Context, classId: String) {
        val trimmed = classId.trim()
        if (trimmed.isEmpty()) return
        try {
            context.fetchDataStore.edit { prefs ->
                prefs[globalClassIdKey] = trimmed
            }
            // Bust the cache so a subsequent read re-fetches under the
            // new id even before the in-memory caches age out.
            memoryCache.remove(trimmed)
            weeksCache.remove(trimmed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "saveGlobalClassId($trimmed) failed: ${e.message}")
        }
    }

    suspend fun readGlobalClassId(context: Context): String? =
        try {
            context.fetchDataStore.data
                .map { it[globalClassIdKey] }
                .first()
                ?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "readGlobalClassId() failed: ${e.message}")
            null
        }

    /**
     * In-memory cache: one entry per configured classId. Stores the slots
     * for a SINGLE date so the widget can render quickly after a cold
     * start. The "today's slots" storage is intentionally separate from
     * [weeksCache] because the widget only ever needs one day at a time
     * and [DaySnapshot]'s on-disk format was designed for that case.
     */
    private val memoryCache = mutableMapOf<String, DaySnapshot>()

    /**
     * In-memory cache: one entry per configured classId. Stores the full
     * 8-week parse of the most recent HTML response so that [week] and
     * [day] can serve any weekday in O(1) lookups without a second
     * network round-trip. Populated by [fetchAndCacheWeeks].
     */
    private val weeksCache = mutableMapOf<String, WeekCache>()

    private data class DaySnapshot(val date: LocalDate, val slots: List<LessonSlot>)

    private data class WeekCache(
        val fetchedAt: LocalDateTime,
        val weeks: List<WeekPlan>,
    )

    /**
     * Fetch the BA PlanServlet HTML once for [classId], parse it into the
     * full 8-week structure, store it in [weeksCache] keyed by [classId],
     * and return the parsed weeks. Returns null on any failure (network,
     * parse, etc.) so the caller can decide on a fallback strategy.
     *
     * Cancellation is honoured: a [CancellationException] thrown while
     * fetching or parsing is re-raised so coroutine cancellation can
     * actually cancel the work — unlike `runCatching { … }` which would
     * swallow it and let a cancelled call still write into the cache.
     */
    private suspend fun fetchAndCacheWeeks(
        context: Context,
        appWidgetId: Int?,
        overrideClassId: String? = null,
    ): List<WeekPlan>? {
        val classId = overrideClassId ?: resolveClassId(context, appWidgetId)
        val url = buildUrl(context, appWidgetId, classId)
        Log.i(TAG, "fetchAndCacheWeeks($classId) starting url=$url")
        return try {
            val html = withContext(Dispatchers.IO) { fetcher.fetch(url) }
            val weeks = TimetableScraper.parseWeeks(html)
            Log.d(TAG, "fetched ${html.length} bytes, parsed ${weeks.size} week(s)")
            weeksCache[classId] = WeekCache(LocalDateTime.now(), weeks)
            weeks
        } catch (e: CancellationException) {
            // Honour coroutine cancellation — do NOT cache the partial
            // result and do NOT swallow the exception.
            Log.d(TAG, "fetchAndCacheWeeks($classId) cancelled")
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "fetchAndCacheWeeks($classId) failed: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    /**
     * Look up the day matching [date] across all parsed [weeks], or null if
     * no parsed week contains it. Centralised so [day] and [week] agree on
     * the matching rule.
     */
    private fun findDayPlan(weeks: List<WeekPlan>, date: LocalDate): DayPlan? =
        weeks.firstNotNullOfOrNull { week ->
            week.days.values.firstOrNull { it.date == date }
        }

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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
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
    } catch (e: CancellationException) {
        throw e
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