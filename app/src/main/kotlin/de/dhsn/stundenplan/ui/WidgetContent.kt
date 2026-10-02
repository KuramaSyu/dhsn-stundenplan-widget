package de.dhsn.stundenplan.ui

import android.appwidget.AppWidgetManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.SquareIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import android.content.Intent
import androidx.compose.runtime.compositionLocalOf
import de.dhsn.stundenplan.config.WidgetConfigActivity
import de.dhsn.stundenplan.data.Lesson
import de.dhsn.stundenplan.data.LessonSlot
import de.dhsn.stundenplan.data.WidgetConfigStore
import de.dhsn.stundenplan.work.NextDayAction
import de.dhsn.stundenplan.work.PrevDayAction
import de.dhsn.stundenplan.work.RefreshAction
import de.dhsn.stundenplan.work.TodayAction
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Top-level Glance widget content.
 *
 * Layout (top to bottom):
 *   1. Header row — weekday abbrev + date · "Zuletzt HH:MM" +
 *      [<] [Heute] [>] [↻] [⚙] buttons
 *   2. Slots list — one block per time slot. Parallel modules are placed
 *      side-by-side; gaps between slots are rendered as a thin pause row
 *      (with the duration in minutes). The active slot also shows the
 *      "now line" inside it.
 *
 * `dayOffset` is the number of days away from `LocalDate.now()` that the
 * widget is currently displaying (0 = today, -1 = yesterday, +1 =
 * tomorrow). It is persisted per widget instance via
 * [WidgetConfigStore][de.dhsn.stundenplan.data.WidgetConfigStore] and
 * surfaced here so the header can disable navigation when the user has
 * reached the supported range.
 */
@Composable
fun WidgetContent(
    slots: List<LessonSlot>,
    today: LocalDate = LocalDate.now(),
    dayOffset: Int = 0,
    lastFetched: String = "–",
) {
    val now = LocalTime.now()

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(12.dp)
            .background(GlanceTheme.colors.primaryContainer),
    ) {
        HeaderRow(today = today, dayOffset = dayOffset, lastFetched = lastFetched)

        Spacer(modifier = GlanceModifier.height(8.dp))

        if (slots.isEmpty()) {
            // Distinguish "no lessons on this day" from "no data loaded
            // yet" by including the displayed date so the user knows the
            // widget is talking about a specific day (which may not be
            // today).
            EmptyState(today = today, dayOffset = dayOffset)
        } else {
            SlotList(slots = slots, now = now, today = today)
        }
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun HeaderRow(today: LocalDate, dayOffset: Int, lastFetched: String) {
    val weekdayShort = today.format(DateTimeFormatter.ofPattern("E", Locale.GERMAN))
        .replace(".", "") // java.time uses "Mo." – strip the dot
        .let { abbrev -> WEEKDAY_ABBREV[abbrev] ?: abbrev }
    val dateShort = today.format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN))

    // The day-navigation cluster (< Heute >) sits between the title
    // column and the refresh/settings buttons. SquareIconButton gives us
    // the 48 dp touch-target for free; the today button uses a regular
    // Button because we want to render a short German label inside it.
    val canGoBack = dayOffset > -WidgetConfigStore.DAY_OFFSET_LIMIT
    val canGoForward = dayOffset < WidgetConfigStore.DAY_OFFSET_LIMIT

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = "$weekdayShort $dateShort",
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                ),
            )
            Text(
                text = "Zuletzt $lastFetched",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                ),
            )
        }
        // Prev-day button. Uses the framework's ic_media_previous icon
        // (skip-to-previous glyph) tinted via the widget's on-color.
        SquareIconButton(
            imageProvider = ImageProvider(android.R.drawable.ic_media_previous),
            contentDescription = "Vorheriger Tag",
            onClick = actionRunCallback<PrevDayAction>(),
            enabled = canGoBack,
            modifier = GlanceModifier.size(40.dp),
        )
        Spacer(modifier = GlanceModifier.width(2.dp))
        // Today button. A short German label reads better than another
        // icon and makes it discoverable. Disabled the day we are
        // already on today so taps don't trigger no-op re-renders.
        Button(
            text = "Heute",
            onClick = actionRunCallback<TodayAction>(),
            enabled = dayOffset != 0,
            modifier = GlanceModifier.height(40.dp),
        )
        Spacer(modifier = GlanceModifier.width(2.dp))
        // Next-day button. Mirrors PrevDayAction but uses ic_media_next.
        SquareIconButton(
            imageProvider = ImageProvider(android.R.drawable.ic_media_next),
            contentDescription = "Nächster Tag",
            onClick = actionRunCallback<NextDayAction>(),
            enabled = canGoForward,
            modifier = GlanceModifier.size(40.dp),
        )
        Spacer(modifier = GlanceModifier.width(4.dp))
        // Icon-only refresh button. SquareIconButton renders a tappable
        // 48 dp square with the icon centered, which matches the Material
        // touch-target guideline and is much easier to hit than the old
        // 28 dp text button.
        SquareIconButton(
            imageProvider = ImageProvider(android.R.drawable.ic_popup_sync),
            contentDescription = "Aktualisieren",
            onClick = actionRunCallback<RefreshAction>(),
            modifier = GlanceModifier.size(48.dp),
        )
        Spacer(modifier = GlanceModifier.width(4.dp))
        val widgetId = LocalWidgetId.current
        val openConfigIntent = Intent(LocalContext.current, WidgetConfigActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        Button(
            text = "⚙",
            onClick = actionStartActivity(openConfigIntent),
            modifier = GlanceModifier.height(28.dp).width(28.dp),
        )
    }
}

private val WEEKDAY_ABBREV = mapOf(
    "Mo" to "Mo", "Di" to "Di", "Mi" to "Mi",
    "Do" to "Do", "Fr" to "Fr", "Sa" to "Sa", "So" to "So",
)

// ---------------------------------------------------------------------------
// Slots list — blocks with pauses and parallel modules
// ---------------------------------------------------------------------------

@Composable
private fun SlotList(slots: List<LessonSlot>, now: LocalTime, today: LocalDate) {
    // LazyColumn translates to a real ListView inside the widget, so the
    // day scrolls even when the widget is resized smaller than the full
    // schedule. Each slot is one list item; the "pause" spacers are
    // computed inline per item to preserve the previous layout behaviour.
    LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
        items(slots, itemId = { it.start.toSecondOfDay().toLong() }) { slot ->
            val index = slots.indexOf(slot)
            val previousEnd = slots.getOrNull(index - 1)?.end
            if ((previousEnd != null) && slot.start.isAfter(previousEnd)) {
                // Only show a "Pause Xmin" label when the user is currently
                // sitting in this pause (between previousEnd and slot.start).
                // For past and future pauses we leave only vertical whitespace
                // so the day still flows visually.
                val userInThisPause = !now.isBefore(previousEnd) && now.isBefore(slot.start)
                if (userInThisPause) {
                    PauseRow(from = previousEnd, to = slot.start)
                } else {
                    Spacer(modifier = GlanceModifier.height(8.dp))
                }
            }
            SlotBlock(slot = slot, now = now, today = today)
        }
    }
}

@Composable
private fun PauseRow(from: LocalTime, to: LocalTime) {
    val minutes = Duration.between(from, to).toMinutes().toInt()
    val label = when {
        minutes >= 60 -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0) "Pause ${h}h" else "Pause ${h}h ${m}min"
        }
        minutes <= 0 -> ""
        else -> "Pause ${minutes}min"
    }
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

@Composable
private fun SlotBlock(slot: LessonSlot, now: LocalTime, today: LocalDate) {
    val isActive = isActive(slot, now, today)
    val isPast = isPast(slot, now, today)

    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        // ----- Parallel lesson modules, side-by-side -----
        // Time is now rendered INSIDE the card itself (top-right corner)
        // so the user sees "DVS 07:45-09:15" as one cohesive block.
        if (slot.lessons.size == 1) {
            LessonCard(
                lesson = slot.lessons[0],
                slotStart = slot.start,
                slotEnd = slot.end,
                isPast = isPast,
                isActive = isActive,
                showParallelBadge = false,
                modifier = GlanceModifier.fillMaxWidth(),
            )
        } else {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                slot.lessons.forEachIndexed { i, lesson ->
                    LessonCard(
                        lesson = lesson,
                        slotStart = slot.start,
                        slotEnd = slot.end,
                        isPast = isPast,
                        isActive = isActive,
                        showParallelBadge = i == 0,
                        modifier = GlanceModifier
                            .defaultWeight()
                            .padding(end = if (i == slot.lessons.lastIndex) 0.dp else 4.dp),
                    )
                }
            }
        }

        // ----- "Now line" inside the active slot -----
        if (isActive) {
            Spacer(modifier = GlanceModifier.height(4.dp))
            NowLine(now)
        }
    }
}

@Composable
private fun LessonCard(
    lesson: Lesson,
    slotStart: LocalTime,
    slotEnd: LocalTime,
    isPast: Boolean,
    isActive: Boolean,
    showParallelBadge: Boolean,
    modifier: GlanceModifier = GlanceModifier,
) {
    // Colors tuned for the card body background (tertiaryContainer).
    // `onTertiaryContainer` is the M3 on-color pair for tertiaryContainer and
    // gives the strongest contrast; for past slots we dim both to
    // onSurfaceVariant which is still readable on the tertiary tint.
    val primaryColor =
        if (isPast) GlanceTheme.colors.onSurfaceVariant
        else GlanceTheme.colors.onTertiaryContainer
    val secondaryColor =
        if (isPast) GlanceTheme.colors.outline
        else GlanceTheme.colors.onTertiaryContainer

    Column(
        modifier = modifier
            // 1 dp outline ring: outer box painted in outline color, with 1 dp
            // padding so the inner card body shows a visible border.
            .background(GlanceTheme.colors.outline)
            .padding(1.dp)
            // tertiaryContainer sits further along the M3 tonal palette than
            // secondaryContainer, giving the card a clearly distinct hue/
            // lightness against the widget's primaryContainer backdrop.
            .background(GlanceTheme.colors.tertiaryContainer)
            .cornerRadius(6.dp)
            .padding(8.dp),
    ) {
        // ----- Top row: subject (left) · time + kind badge (right) -----
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = lesson.subject,
                style = TextStyle(
                    color = primaryColor,
                    fontWeight = when {
                        isActive -> FontWeight.Bold
                        isPast -> FontWeight.Normal
                        else -> FontWeight.Medium
                    },
                    fontSize = 13.sp,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            // Time chip: rendered INSIDE the card, top-right.
            Text(
                text = formatRange(slotStart, slotEnd),
                style = TextStyle(
                    color = secondaryColor,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                ),
            )
            if (lesson.kind.isNotEmpty()) {
                Spacer(modifier = GlanceModifier.width(4.dp))
                KindBadge(lesson.kind, dimmed = isPast)
            }
        }
        if (showParallelBadge) {
            Text(
                text = "parallel",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 10.sp,
                ),
            )
        }
        Text(
            text = buildSubLine(lesson),
            style = TextStyle(
                color = secondaryColor,
                fontSize = 11.sp,
            ),
        )
    }
}

private fun buildSubLine(lesson: Lesson): String = buildString {
    if (lesson.teacher.isNotEmpty()) append(lesson.teacher)
    if (lesson.teacher.isNotEmpty() && lesson.room.isNotEmpty()) append("  ·  ")
    if (lesson.room.isNotEmpty()) append(lesson.room)
    if (lesson.remark.isNotEmpty()) {
        if (isNotEmpty()) append("  ·  ")
        append(lesson.remark)
    }
}

@Composable
private fun KindBadge(kind: String, dimmed: Boolean = false) {
    val bg = if (dimmed) GlanceTheme.colors.surfaceVariant
             else GlanceTheme.colors.tertiaryContainer
    val fg = if (dimmed) GlanceTheme.colors.outline
             else GlanceTheme.colors.onTertiaryContainer
    Box(
        modifier = GlanceModifier
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = kind,
            style = TextStyle(
                color = fg,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
            ),
        )
    }
}

@Composable
private fun NowLine(now: LocalTime) {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.secondaryContainer)
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "── ${now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN))} ──",
            style = TextStyle(
                color = GlanceTheme.colors.onSecondaryContainer,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            ),
            modifier = GlanceModifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// Empty state / helpers
// ---------------------------------------------------------------------------

@Composable
private fun EmptyState(today: LocalDate, dayOffset: Int) {
    // Distinguish three states so the user always knows what they're
    // looking at: (1) empty day, (2) navigated to a date outside the
    // cached window, (3) never fetched before.
    val text = when {
        dayOffset == 0 -> "Heute keine Stunden."
        dayOffset == -1 -> "Gestern keine Stunden."
        dayOffset == 1 -> "Morgen keine Stunden."
        else -> {
            val fmt = DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy", Locale.GERMAN)
            "Keine Stunden am ${today.format(fmt)}."
        }
    }
    Box(
        modifier = GlanceModifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = GlanceTheme.colors.onPrimaryContainer,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

private fun isActive(slot: LessonSlot, now: LocalTime, today: LocalDate): Boolean {
    if (today != LocalDate.now()) return false
    return !now.isBefore(slot.start) && now.isBefore(slot.end)
}

private fun isPast(slot: LessonSlot, now: LocalTime, today: LocalDate): Boolean {
    if (today != LocalDate.now()) return false
    return now >= slot.end
}

private fun formatRange(start: LocalTime, end: LocalTime): String {
    val fmt = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
    return "${start.format(fmt)}–${end.format(fmt)}"
}

// ---------------------------------------------------------------------------
// Open-config helper
// ---------------------------------------------------------------------------

/**
 * Typed [ActionParameters.Key] used to forward the widget's appWidgetId when
 * launching [WidgetConfigActivity] from a widget action.
 */
/**
 * Composition local carrying the underlying system [AppWidgetManager] id of
 * the widget currently being composed. Set by [StundenplanWidget.provideGlance].
 * Widget UI uses this so the open-config action targets the right widget
 * instance even when multiple widgets are pinned.
 */
val LocalWidgetId = compositionLocalOf { AppWidgetManager.INVALID_APPWIDGET_ID }

// currentWidgetId() was a placeholder for the widget id during composition.
// The real value is now provided via LocalWidgetId (set by StundenplanWidget).