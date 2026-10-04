package de.dhsn.stundenplan.ui

import android.os.Build
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.dhsn.stundenplan.data.DayPlan
import de.dhsn.stundenplan.data.Lesson
import de.dhsn.stundenplan.data.LessonSlot
import de.dhsn.stundenplan.data.TimetableRepository
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * In-app timeline view of the stundenplan.
 *
 * Shows the week containing [initialMonday] (or "this week" if null) as a
 * single vertical timeline: each day is one section, and within a day the
 * lesson slots are stacked top-to-bottom with "Pause Xmin" labels in
 * between.
 *
 * Navigation lives on top of the timeline:
 *   `[←] [Heute] [→]`   Wochen-Range   `[↻]`
 *
 * The internal weekOffset controls how many weeks away from "this week"
 * we are. 0 = current week, -1 = previous, +1 = next. Bounds are
 * ±[WEEK_LIMIT].
 */
@Composable
fun TimelineScreen(
    appWidgetId: Int? = null,
    initialMonday: LocalDate? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Always start on "this week" — [initialMonday] is only useful for
    // previews/tests.
    var weekOffset by remember { mutableStateOf(0) }
    var days by remember { mutableStateOf<List<DayPlan>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val mondayOfThisWeek = remember {
        LocalDate.now().with(WeekFields.of(Locale.GERMAN).firstDayOfWeek)
    }
    val displayMonday = (initialMonday ?: mondayOfThisWeek)
        .plusWeeks(weekOffset.toLong())

    // Refetch whenever the offset changes (or on first composition).
    LaunchedEffect(weekOffset, appWidgetId) {
        isLoading = true
        errorMessage = null
        try {
            val fetched = TimetableRepository.week(
                context = context,
                appWidgetId = appWidgetId,
                monday = displayMonday,
            )
            days = fetched
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Coroutine was cancelled (e.g. user tapped Next before this
            // load finished) - re-throw so the LaunchedEffect actually
            // exits and a new effect for the new offset can take over.
            throw e
        } catch (e: Throwable) {
            errorMessage = "Woche konnte nicht geladen werden: " +
                (e.message ?: e.javaClass.simpleName)
        } finally {
            isLoading = false
        }
    }

    // Surface load errors via snackbar; the screen still shows whatever
    // (possibly empty) data we already have.
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            errorMessage = null
        }
    }

    // Auto-skip: if "this week" (offset = 0) is completely empty – no
    // lessons Mon–Fri, e.g. weekend-only or semester break – jump to
    // next week so the user sees data immediately. Triggered on app
    // start, on Heute-tap (which sets offset = 0), and after Refresh.
    val autoSkipTrigger = remember { mutableStateOf(0) }
    LaunchedEffect(days, autoSkipTrigger.value) {
        if (weekOffset == 0 && days.isNotEmpty() && !isLoading &&
            days.all { it.slots.isEmpty() }
        ) {
            Log.i(TAG, "current week is empty, jumping to next week")
            weekOffset = 1
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            TimelineHeader(
                displayMonday = displayMonday,
                weekOffset = weekOffset,
                isCurrentWeek = weekOffset == 0,
                isLoading = isLoading,
                canGoBack = weekOffset > -WEEK_LIMIT,
                canGoForward = weekOffset < WEEK_LIMIT,
                onPrev = { weekOffset-- },
                onNext = { weekOffset++ },
                onToday = {
                    weekOffset = 0
                    // Re-trigger the auto-skip check so it runs even if
                    // the weekOffset change alone wouldn't re-fire the
                    // LaunchedEffect (because `days` is unchanged).
                    autoSkipTrigger.value++
                },
                onRefresh = {
                    scope.launch {
                        isLoading = true
                        // Wipe offset back to today before refresh so the
                        // user sees a freshly-fetched "this week".
                        weekOffset = 0
                        autoSkipTrigger.value++
                        try {
                            TimetableRepository.refresh(context, appWidgetId)
                            days = TimetableRepository.week(
                                context = context,
                                appWidgetId = appWidgetId,
                                monday = LocalDate.now()
                                    .with(WeekFields.of(Locale.GERMAN).firstDayOfWeek),
                            )
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // User navigated away while refresh was in
                            // flight - re-throw so the launched coroutine
                            // actually exits.
                            throw e
                        } catch (e: Throwable) {
                            errorMessage = "Aktualisieren fehlgeschlagen: " +
                                (e.message ?: e.javaClass.simpleName)
                        } finally {
                            isLoading = false
                        }
                    }
                },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (isLoading && days.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                TimelineList(days = days, displayMonday = displayMonday)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun TimelineHeader(
    displayMonday: LocalDate,
    weekOffset: Int,
    isCurrentWeek: Boolean,
    isLoading: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onRefresh: () -> Unit,
) {
    val sunday = displayMonday.plusDays(4)
    val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN)
    val weekNumber = displayMonday.get(
        WeekFields.of(Locale.GERMAN).weekOfWeekBasedYear()
    )

    // Relative label for the common cases (this/next/prev week), and a
    // compact "KW X" for everything else. The full date range is only
    // shown when the user has navigated away from "this week" so the
    // current week doesn't waste vertical space on a range the user
    // already knows.
    val (title, showRange) = when (weekOffset) {
        0 -> "Diese Woche" to false
        1 -> "Nächste Woche" to true
        -1 -> "Vorherige Woche" to true
        else -> "KW $weekNumber" to true
    }
    val subtitle = if (showRange) {
        "${displayMonday.format(formatter)} – ${sunday.format(formatter)}"
    } else null

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrev, enabled = canGoBack) {
                        Icon(
                            imageVector = NavIcons.ChevronLeft,
                            contentDescription = "Vorherige Woche",
                        )
                    }
                    IconButton(onClick = onToday, enabled = !isCurrentWeek) {
                        Icon(
                            imageVector = NavIcons.Today,
                            contentDescription = "Heute",
                        )
                    }
                    IconButton(onClick = onNext, enabled = canGoForward) {
                        Icon(
                            imageVector = NavIcons.ChevronRight,
                            contentDescription = "Nächste Woche",
                        )
                    }
                }
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = NavIcons.Refresh,
                            contentDescription = "Aktualisieren",
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Timeline list
// ---------------------------------------------------------------------------

@Composable
private fun TimelineList(days: List<DayPlan>, displayMonday: LocalDate) {
    val today = LocalDate.now()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(days, key = { it.date.toEpochDay() }) { day ->
            DaySection(
                day = day,
                isToday = day.date == today,
            )
        }
    }
}

@Composable
private fun DaySection(
    day: DayPlan,
    isToday: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DayHeader(day = day, isToday = isToday)
        Spacer(modifier = Modifier.height(8.dp))
        if (day.slots.isEmpty()) {
            EmptyDayCard(date = day.date)
        } else {
            TimelineForDay(slots = day.slots)
        }
    }
}

@Composable
private fun DayHeader(day: DayPlan, isToday: Boolean) {
    val formatter = DateTimeFormatter.ofPattern("EEEE, dd.MM.", Locale.GERMAN)
    val label = day.date.format(formatter)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (isToday) "Heute · $label"
                   else label.replaceFirstChar { it.uppercase(Locale.GERMAN) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isToday) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "KW " + day.date.get(
                WeekFields.of(Locale.GERMAN).weekOfWeekBasedYear()
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyDayCard(date: LocalDate) {
    // Weekend vs. weekday phrasing.
    val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY ||
        date.dayOfWeek == DayOfWeek.SUNDAY
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = if (isWeekend) "Wochenende – keine Stunden."
                   else "Keine Stunden an diesem Tag.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Vertical timeline (continuous rail + content) for a single day
// ---------------------------------------------------------------------------

@Composable
private fun TimelineForDay(slots: List<LessonSlot>) {
    // Build 90-min blocks per lesson, then collapse parallel modules
    // (same visible time + same originalEnd) into single [BlockGroup]s
    // so the UI can render one card with two side-by-side columns.
    val groups = remember(slots) { groupBlocks(buildBlocks(slots)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            if (groups.isEmpty()) {
                // Defensive: shouldn't happen because the caller only
                // calls us when slots is non-empty.
            } else {
                TimelineColumn(groups = groups)
            }
        }
    }
}

/**
 * A single 90-min chunk of a lesson.
 *
 * `start`/`end` are the visible time range of the card. `originalEnd`
 * is the time the lesson actually ends in the BA-Dresden page; for a
 * single 90-min slot it equals `end`, but for a lesson that was
 * stretched across multiple adjacent slots (e.g. a 4-hour `VSIT
 * 07:45–11:15`) `originalEnd` is the end of the *last* slot the lesson
 * occupied. The card shows an `{i}` button that reveals `originalEnd`
 * so the user can confirm the source page data.
 */
private data class Block(
    val lesson: Lesson,
    val start: LocalTime,
    val end: LocalTime,
    val originalEnd: LocalTime,
)

/**
 * One row on the timeline: all [Lesson]s that share the same visible
 * `(start, end, originalEnd)` chunk. A `BlockGroup` always renders as
 * a single card; if `lessons.size == 1` it looks like the pre-card
 * design, if `lessons.size > 1` the lessons are laid out side-by-side.
 *
 * This is the unit the UI consumes (the previous per-lesson `Block`
 * list is collapsed into [BlockGroup]s by [groupBlocks]).
 */
private data class BlockGroup(
    val start: LocalTime,
    val end: LocalTime,
    val originalEnd: LocalTime,
    val lessons: List<Lesson>,
)

/**
 * Take the per-lesson [Block] list produced by [buildBlocks] and collapse
 * any consecutive entries that share the same visible time range AND
 * the same `originalEnd` into a single [BlockGroup]. Two blocks sharing
 * `(start, end)` but having different `originalEnd`s are kept separate
 * because they belong to two different long lessons (e.g. a 4-hour
 * `VSIT` and a 4-hour `EVSA` that happen to start and end on the same
 * 90-min boundaries — each needs its own `{i}` disclosure).
 */
private fun groupBlocks(blocks: List<Block>): List<BlockGroup> {
    if (blocks.isEmpty()) return emptyList()
    val result = mutableListOf<BlockGroup>()
    var i = 0
    while (i < blocks.size) {
        val cur = blocks[i]
        val lessons = mutableListOf(cur.lesson)
        var j = i + 1
        while (j < blocks.size &&
            blocks[j].start == cur.start &&
            blocks[j].end == cur.end &&
            blocks[j].originalEnd == cur.originalEnd
        ) {
            lessons.add(blocks[j].lesson)
            j++
        }
        result.add(
            BlockGroup(
                start = cur.start,
                end = cur.end,
                originalEnd = cur.originalEnd,
                lessons = lessons.toList(),
            )
        )
        i = j
    }
    return result
}

/**
 * Coalesce [slots] into a flat list of 90-min [Block]s suitable for the
 * timeline.
 *
 * The scraper emits one `Lesson` per slot it occupies (the BA page uses
 * HTML `rowspan` to merge the cells visually but the parser walks row
 * by row). Each `Lesson` therefore has `start = slot.start` and
 * `end = slot.end`. We:
 *
 *   1. Group consecutive slots whose lessons have the same
 *      (subject, teacher, room, kind) — those are the continuation rows
 *      of a single stretched lesson. The merged block spans from the
 *      first slot's start to the last slot's end.
 *   2. Split each merged range into 90-min chunks; the last chunk may
 *      be shorter.
 *   3. Use the merged range's end as `originalEnd`, so a card that
 *      spans multiple chunks shows the true source range when the user
 *      taps `{i}`.
 */
private fun buildBlocks(slots: List<LessonSlot>): List<Block> {
    // Step 1: walk slots in order and merge consecutive entries that
    // look like the same lesson. Each "merged lesson" remembers the
    // first slot it came from (for the original start) and the last
    // (for the original end).
    val merged = mutableListOf<Merged>()
    for (slot in slots) {
        for (lesson in slot.lessons) {
            val key = LessonKey(lesson)
            val tail = merged.lastOrNull()
            if (tail != null &&
                tail.key == key &&
                tail.lessonEnd == slot.start
            ) {
                // Continuation: extend the previous merged lesson.
                tail.lessonEnd = slot.end
            } else {
                merged.add(Merged(key, lesson, slot.start, slot.end))
            }
        }
    }

    // Step 2+4: split each merged lesson into 90-min chunks.
    return merged.flatMap { m ->
        val chunks = splitIntoBlocks(m.lessonStart, m.lessonEnd, BLOCK_MINUTES)
        chunks.map { (s, e) ->
            Block(lesson = m.lesson, start = s, end = e, originalEnd = m.lessonEnd)
        }
    }
}

private data class LessonKey(
    val subject: String,
    val teacher: String,
    val room: String,
    val kind: String,
)

private fun LessonKey(lesson: Lesson): LessonKey = LessonKey(
    subject = lesson.subject,
    teacher = lesson.teacher,
    room = lesson.room,
    kind = lesson.kind,
)

private class Merged(
    val key: LessonKey,
    val lesson: Lesson,
    val lessonStart: LocalTime,
    var lessonEnd: LocalTime,
)

/**
 * Split the time range `[from, to)` into chunks of [minutes]. The last
 * chunk may be shorter. Returns `[(start, end)]` pairs where `end` is
 * exclusive (matches BA-Dresden semantics: `11:45–13:15` covers
 * `[11:45, 13:15)`).
 */
private fun splitIntoBlocks(
    from: LocalTime,
    to: LocalTime,
    minutes: Long,
): List<Pair<LocalTime, LocalTime>> {
    if (!to.isAfter(from)) return emptyList()
    val totalMinutes = Duration.between(from, to).toMinutes()
    if (totalMinutes <= minutes) return listOf(from to to)
    val result = mutableListOf<Pair<LocalTime, LocalTime>>()
    var cursor = from
    while (true) {
        val next = cursor.plusMinutes(minutes)
        if (!next.isBefore(to)) {
            result.add(cursor to to)
            break
        }
        result.add(cursor to next)
        cursor = next
    }
    return result
}

private const val BLOCK_MINUTES = 90L

/**
 * The whole-day column: rail-with-dots on the left, lesson cards on
 * the right.
 *
 * The rail is drawn with `drawBehind` so it spans the full height of
 * the content column on the right without gaps. Dots are placed on the
 * rail at the vertical offset of the matching card using
 * `Modifier.layout { marker{ … } }` so they line up pixel-perfect.
 *
 * Deduplication: parallel lessons share one start time and, so we
 * render exactly **one** dot per unique `start` value. Each parallel
 * card still gets its own time-label in the gutter, just without a
 * second dot.
 *
 * Pause labels are inserted between rows when there's a gap.
 */
@Composable
private fun TimelineColumn(groups: List<BlockGroup>) {
    // Pre-compute the set of "first-occurrence" start times – those
    // are the moments that should get a dot on the rail.
    val firstStartTimes = remember(groups) {
        val seen = mutableSetOf<LocalTime>()
        groups.map { group ->
            val isFirst = seen.add(group.start)
            group.start to isFirst
        }
    }

    // Single outer Column with the rail line drawn behind every child.
    // Each [TimelineRow] is a Row that internally has its own gutter +
    // content slot, so the dot is always aligned with its lesson card.
    val railColor = MaterialTheme.colorScheme.outline
    val dotColor = MaterialTheme.colorScheme.primary
    val secondaryLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val railWidthPx = with(LocalDensity.current) { 2.dp.toPx() }
    val railLeftPx = with(LocalDensity.current) { 36.dp.toPx() }
    val railTopOffsetPx = with(LocalDensity.current) { 8.dp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 12.dp, bottom = 12.dp)
            .drawBehind {
                drawLine(
                    color = railColor,
                    start = androidx.compose.ui.geometry.Offset(
                        x = railLeftPx,
                        y = railTopOffsetPx,
                    ),
                    end = androidx.compose.ui.geometry.Offset(
                        x = railLeftPx,
                        y = size.height,
                    ),
                    strokeWidth = railWidthPx,
                )
            },
    ) {
        groups.forEachIndexed { index, group ->
            val previousEnd = groups.getOrNull(index - 1)?.end
            val previousStart = groups.getOrNull(index - 1)?.start
            val isParallel = previousEnd != null &&
                previousStart != null &&
                previousStart == group.start
            if (!isParallel &&
                previousEnd != null &&
                group.start.isAfter(previousEnd)
            ) {
                // Pause label spans the full row width so the rail
                // line passes through the empty space above the next
                // dot.
                PauseLabel(from = previousEnd, to = group.start)
            }
            TimelineRow(
                group = group,
                showDot = firstStartTimes[index].second,
                dotColor = dotColor,
                secondaryLabelColor = secondaryLabelColor,
            )
        }
    }
}

/**
 * One timeline row: a 72-dp gutter on the left (with the time-label
 * and a centered dot, sitting on the rail line drawn behind the outer
 * Column) and the lesson card on the right.
 */
@Composable
private fun TimelineRow(
    group: BlockGroup,
    showDot: Boolean,
    dotColor: Color,
    secondaryLabelColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        // Gutter slot: 72 dp wide so the text + dot don't touch the
        // rail line drawn at x = 36 dp by the outer Column's
        // drawBehind.
        Column(
            modifier = Modifier
                .width(72.dp)
                .padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = group.start.format(
                    DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
                ),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (showDot) dotColor else secondaryLabelColor,
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (showDot) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(dotColor, CircleShape),
                )
            } else {
                // Reserve the same vertical slot for parallel rows so
                // the dot on the rail above doesn't move when a
                // second card shares the same start time.
                Spacer(modifier = Modifier.size(12.dp))
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        // Content: the lesson card (single column if one lesson,
        // side-by-side columns if there are parallel modules).
        Column(modifier = Modifier.fillMaxWidth()) {
            LessonCard(group = group)
        }
    }
}

@Composable
private fun PauseLabel(from: LocalTime, to: LocalTime) {
    val minutes = Duration.between(from, to).toMinutes().toInt()
    val label = when {
        minutes <= 0 -> ""
        minutes >= 60 -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0) "Pause ${h}h" else "Pause ${h}h ${m}min"
        }
        else -> "Pause ${minutes}min"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 72-dp spacer matches the gutter so the label aligns with the
        // lesson cards on the right.
        Spacer(modifier = Modifier.width(72.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun LessonCard(group: BlockGroup) {
    val rangeLabel = "${group.start.format(
        DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
    )} – ${group.end.format(
        DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
    )}"
    // Card-level disclosure: needed iff the visible end differs from
    // ANY lesson's originalEnd in the group. For the (single-lesson)
    // case this collapses to the old block.heuristic.
    val isSplit = group.lessons.any { it.end != group.originalEnd }

    // Local expand state for the original-time disclosure.
    var expanded by remember(group.start, group.end, group.originalEnd) {
        mutableStateOf(false)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Card header: time range on the left, optional {i} disclosure
        // on the right. (When the group has only one lesson, we keep
        // the original single-line layout — but we always render the
        // time at card level so the UI is consistent regardless of the
        // number of parallel modules.)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = rangeLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (isSplit) {
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = NavIcons.InfoOutline,
                        contentDescription = if (expanded) {
                            "Original-Zeitfenster ausblenden"
                        } else {
                            "Original-Zeitfenster anzeigen"
                        },
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // Body: one column per lesson (side-by-side when there are
        // parallel modules), each showing subject / teacher / room /
        // kind / remark.
        if (group.lessons.size == 1) {
            LessonColumn(lesson = group.lessons[0])
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                group.lessons.forEach { lesson ->
                    LessonColumn(
                        lesson = lesson,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (isSplit && expanded) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Original: ${
                    group.lessons.joinToString(separator = " / ") { lesson ->
                        "${lesson.start.format(
                            DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
                        )}–${
                            lesson.end.format(
                                DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
                            )
                        }"
                    }
                }",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun LessonColumn(
    lesson: Lesson,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = lesson.subject.ifBlank { "(kein Fach)" },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (lesson.teacher.isNotBlank()) {
            Text(
                text = lesson.teacher,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        if (lesson.room.isNotBlank()) {
            Text(
                text = "Raum ${lesson.room}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        if (lesson.kind.isNotBlank()) {
            Text(
                text = lesson.kind,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
        }
        if (lesson.remark.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = lesson.remark,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}



private const val WEEK_LIMIT = 12 // ±12 weeks covers a full semester comfortably

private const val TAG = "TimelineScreen"

// ---------------------------------------------------------------------------
// Icons
//
// Inline `ImageVector`s so we don't pull in `material-icons-extended`
// (which adds ~7 MB to the APK for a handful of glyphs). Compose's
// `painterResource(android.R.drawable.…)` doesn't accept the system
// drawables because they aren't vectors, which is why we declare our own.
// The path data is copied from the Material Symbols / Icons set under
// the Apache-2.0 license.
// ---------------------------------------------------------------------------

private object NavIcons {
    val ChevronLeft: ImageVector = ImageVector.Builder(
        name = "ChevronLeft",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(15f, 18f)
            lineTo(9f, 12f)
            lineTo(15f, 6f)
        }
    }.build()

    val ChevronRight: ImageVector = ImageVector.Builder(
        name = "ChevronRight",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(9f, 18f)
            lineTo(15f, 12f)
            lineTo(9f, 6f)
        }
    }.build()

    val Today: ImageVector = ImageVector.Builder(
        name = "Today",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = null,
            strokeLineWidth = 0f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
            strokeLineMiter = 4f,
            pathFillType = PathFillType.NonZero,
        ) {
            // Outer rounded square
            moveTo(19f, 3f)
            horizontalLineTo(18f)
            verticalLineTo(1f)
            horizontalLineTo(16f)
            verticalLineTo(3f)
            horizontalLineTo(8f)
            verticalLineTo(1f)
            horizontalLineTo(6f)
            verticalLineTo(3f)
            horizontalLineTo(5f)
            curveTo(3.89f, 3f, 3f, 3.9f, 3f, 5f)
            verticalLineTo(19f)
            curveTo(3f, 20.1f, 3.89f, 21f, 5f, 21f)
            horizontalLineTo(19f)
            curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
            verticalLineTo(5f)
            curveTo(21f, 3.9f, 20.1f, 3f, 19f, 3f)
            close()
            moveTo(19f, 19f)
            horizontalLineTo(5f)
            verticalLineTo(8f)
            horizontalLineTo(19f)
            verticalLineTo(19f)
            close()
            // Centre dot
            moveTo(7f, 10f)
            horizontalLineTo(12f)
            verticalLineTo(15f)
            horizontalLineTo(7f)
            close()
        }
    }.build()

    val Refresh: ImageVector = ImageVector.Builder(
        name = "Refresh",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = null,
            strokeLineWidth = 0f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
            strokeLineMiter = 4f,
            pathFillType = PathFillType.NonZero,
        ) {
            // Circular arrow – two arcs forming a "refresh" loop.
            moveTo(17.65f, 6.35f)
            curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
            curveTo(7.58f, 4f, 4f, 7.58f, 4f, 12f)
            curveTo(4f, 16.42f, 7.58f, 20f, 12f, 20f)
            curveTo(15.73f, 20f, 18.84f, 17.45f, 19.73f, 14f)
            horizontalLineTo(17.65f)
            curveTo(16.83f, 16.33f, 14.61f, 18f, 12f, 18f)
            curveTo(8.69f, 18f, 6f, 15.31f, 6f, 12f)
            curveTo(6f, 8.69f, 8.69f, 6f, 12f, 6f)
            curveTo(13.66f, 6f, 15.14f, 6.69f, 16.22f, 7.78f)
            lineTo(13f, 11f)
            horizontalLineTo(20f)
            verticalLineTo(4f)
            close()
        }
    }.build()

    val InfoOutline: ImageVector = ImageVector.Builder(
        name = "InfoOutline",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Filled circle minus a smaller circle gives the classic "info"
        // glyph: outer ring + dot at the bottom + a vertical bar above
        // the dot.
        path(
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = null,
            strokeLineWidth = 0f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
            strokeLineMiter = 4f,
            pathFillType = PathFillType.EvenOdd,
        ) {
            moveTo(11f, 7f)
            horizontalLineTo(13f)
            verticalLineTo(9f)
            horizontalLineTo(11f)
            close()
            moveTo(11f, 11f)
            horizontalLineTo(13f)
            verticalLineTo(17f)
            horizontalLineTo(11f)
            close()
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
            curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
            curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
            close()
            moveTo(12f, 20f)
            curveTo(7.59f, 20f, 4f, 16.41f, 4f, 12f)
            curveTo(4f, 7.59f, 7.59f, 4f, 12f, 4f)
            curveTo(16.41f, 4f, 20f, 7.59f, 20f, 12f)
            curveTo(20f, 16.41f, 16.41f, 20f, 12f, 20f)
            close()
        }
    }.build()
}

// ---------------------------------------------------------------------------
// Theme
// ---------------------------------------------------------------------------

/**
 * Wraps [content] in a Material 3 [MaterialTheme] whose color scheme is
 * derived from the user's wallpaper ("Material You") on Android 12+
 * (API 31) and falls back to the standard M3 schemes on older devices.
 * Light vs. dark is chosen from the system setting
 * ([isSystemInDarkTheme]).
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}