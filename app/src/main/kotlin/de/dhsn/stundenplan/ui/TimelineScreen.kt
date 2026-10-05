package de.dhsn.stundenplan.ui

import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.core.net.toUri
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
import java.time.temporal.ChronoUnit
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
 *   `[←] [Heute] [→]`   Wochen-Range
 *
 * Pull-to-refresh at the top of the list handles refreshes, replacing
 * the former `↻` button in the header.
 *
 * The internal weekOffset controls how many weeks away from "this week"
 * we are. 0 = current week, -1 = previous, +1 = next. Bounds are
 * ±[WEEK_LIMIT].
 *
 * PullToRefreshBox is gated by ExperimentalMaterial3Api, so the whole
 * composable opts in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    appWidgetId: Int? = null,
    initialMonday: LocalDate? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // On Sunday we want to open the app already on the UPCOMING week
    // (KW 41 instead of KW 40), so the user immediately sees the
    // next school day. The auto-skip handles "current week is empty"
    // but doesn't apply here because on Sunday the current week still
    // has Mon–Fri content from last week — the user just wants to look
    // forward. We use a remember block so the decision is made once at
    // composition (opening the app is when the rule matters most).
    val initialWeekOffset = remember {
        if (LocalDate.now().dayOfWeek == DayOfWeek.SUNDAY) 1 else 0
    }
    // Always start on "this week" — [initialMonday] is only useful for
    // previews/tests.
    var weekOffset by remember { mutableStateOf(initialWeekOffset) }
    var days by remember { mutableStateOf<List<DayPlan>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Settings (3-dot) dialog state. The dialog edits the seminargruppe
    // used by the in-app screen (TimelineActivity passes
    // appWidgetId = null, so the repository falls back to the global
    // classId stored in DataStore via saveGlobalClassId / readGlobalClassId)
    // AND exposes the optional per-module color toggle.
    var showSettings by remember { mutableStateOf(false) }
    var globalClassId by remember {
        mutableStateOf(TimetableRepository.DEFAULT_CLASS_ID)
    }
    // "Distinct color per module" toggle. Persisted in the same
    // `widget_fetch` DataStore as the global class id because both are
    // app-global preferences rather than per-widget settings. Default:
    // off.
    var moduleColorsEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        moduleColorsEnabled = TimetableRepository.readModuleColorsEnabled(context)
    }
    LaunchedEffect(showSettings) {
        // Re-read whenever the dialog opens so a setting changed in
        // another process / activity (WidgetConfigActivity) shows up.
        if (showSettings) {
            globalClassId = TimetableRepository.readGlobalClassId(context)
                ?: TimetableRepository.DEFAULT_CLASS_ID
            moduleColorsEnabled =
                TimetableRepository.readModuleColorsEnabled(context)
        }
    }

    // User-triggered pull-to-refresh state. Separate from [isLoading]
    // so the header's small [CircularProgressIndicator] keeps its own
    // state machine for programmatic refreshes (e.g. triggered by the
    // widget sync) while pull-to-refresh gets its own spinner-and-fade
    // cycle. Both flows end up calling [TimetableRepository.refresh],
    // so the on-screen result is the same; the split is purely so the
    // indicators don't blink.
    var userPullRefreshing by remember { mutableStateOf(false) }

    // Validation-in-progress flag for the settings dialog "Speichern"
// button. While true, the confirmButton is disabled and shows a
    // spinner so the user can't double-tap while we're testing the
    // entered seminargruppe against the BA server.
    var isValidating by remember { mutableStateOf(false) }
    // Last validation error, if any. Rendered as the OutlinedTextField
    // `supportingText` so the user sees it inline next to the field.
    var validationError by remember { mutableStateOf<String?>(null) }

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

    // Helper used by the settings dialog's "Speichern" handler to
    // commit the entered seminargruppe + module-colors toggle and
    // close the dialog. Pulled out so the three "save succeeded"
    // branches (Valid, Offline-after-save, ...) share one path.
    // Declared after [autoSkipTrigger] because it touches
    // `autoSkipTrigger.value` to re-fire the auto-skip LaunchedEffect.
    suspend fun persistAndClose(trimmed: String, newModuleColors: Boolean) {
        TimetableRepository.saveGlobalClassId(context = context, classId = trimmed)
        TimetableRepository.saveModuleColorsEnabled(context = context, enabled = newModuleColors)
        globalClassId = trimmed
        moduleColorsEnabled = newModuleColors
        // Reset to "this week" so the freshly-picked seminargruppe
        // loads immediately.
        weekOffset = 0
        autoSkipTrigger.value++
        showSettings = false
    }

    // LazyColumn state shared between the list and the "Heute" action.
    // The header bumps [scrollToTodayTrigger] when the user taps the
    // today button; the LaunchedEffect below runs once per bump and
    // scrolls today's row into view.
    //
    // We do NOT reset the trigger inside the effect — doing so would
    // re-key this LaunchedEffect and cancel the in-flight
    // `animateScrollToItem` coroutine before it finishes, leaving the
    // scroll stuck at its start position. The trigger stays at its
    // last value between taps; the next bump (1→2, 2→3, …) re-fires
    // the effect because the key changes.
    val listState = rememberLazyListState()
    var scrollToTodayTrigger by remember { mutableIntStateOf(0) }

    // "Effective today" — the date the timeline treats as today for
    // highlighting and scroll-targeting. Always equal to actual today;
    // we deliberately do NOT override it on Sunday to "next Monday".
    // The next-Monday highlight on Sunday is expressed through the
    // `Morgen` label (see `isTomorrow` below), so the user sees
    // "Morgen · Montag, 05.10." with the highlight instead of
    // "Heute · Montag, 05.10." — which more accurately reflects that
    // today is Sunday, not Monday.
    var effectiveToday by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(days) {
        // Re-read actual today whenever the data reloads (e.g. after a
        // refresh or week navigation) so a long-running app stays in
        // sync with the user's clock.
        effectiveToday = LocalDate.now()
    }

    // The week the Heute button should land in. Determined purely by
    // actual today, NOT by what's currently loaded — the user might
    // have navigated to a future week, but the today button should
    // always return them to the highlighted day.
    //
    //   * Mon–Fri: current week (weekOffset = 0) — "Heute" is today.
    //   * Sat–Sun: next week (weekOffset = +1) — "Morgen" is next
    //     Monday, which lives in the upcoming week.
    //
    // When the user taps Heute we set `weekOffset` to this value; the
    // data reloads; then the scroll-to-today effect below finds the
    // highlighted day in the freshly-loaded [days] list.
    val todayButtonWeekOffset: Int = remember(effectiveToday) {
        computeTodayButtonWeekOffset(effectiveToday)
    }

    LaunchedEffect(scrollToTodayTrigger, days) {
        if (scrollToTodayTrigger == 0) return@LaunchedEffect
        if (days.isEmpty() || isLoading) return@LaunchedEffect
        // Find the highlighted day in [days]: "Heute" wins over
        // "Morgen", and "Morgen" wins over index 0. The rule mirrors
        // what the user actually sees on screen.
        val todayIndex = days.indexOfFirst {
            it.date == effectiveToday && it.slots.isNotEmpty()
        }
        val tomorrowIndex = if (todayIndex < 0) {
            days.indexOfFirst {
                it.date == effectiveToday.plusDays(1) &&
                    it.slots.isNotEmpty()
            }
        } else -1
        val targetIndex = when {
            todayIndex >= 0 -> todayIndex
            tomorrowIndex >= 0 -> tomorrowIndex
            else -> 0
        }
        listState.animateScrollToItem(targetIndex)
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
                isLoading = isLoading,
                canGoBack = weekOffset > -WEEK_LIMIT,
                canGoForward = weekOffset < WEEK_LIMIT,
                onPrev = { weekOffset-- },
                onNext = { weekOffset++ },
                onToday = {
                    // Jump to the week that contains the highlighted
                    // day. Computed from actual today alone (not from
                    // the currently-loaded list) so the user is always
                    // returned to the right week, even after they've
                    // navigated to a different one.
                    if (weekOffset != todayButtonWeekOffset) {
                        weekOffset = todayButtonWeekOffset
                        // Re-trigger the auto-skip check so it runs
                        // even if the weekOffset change alone wouldn't
                        // re-fire the LaunchedEffect (because `days`
                        // is unchanged).
                        autoSkipTrigger.value++
                    }
                    // Always scroll the highlighted row into view. The
                    // LaunchedEffect above ignores this trigger while
                    // `days` is empty / loading, so it harmlessly
                    // fires both for same-week and cross-week taps.
                    scrollToTodayTrigger++
                },
                onOpenSettings = { showSettings = true },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (isLoading && days.isEmpty()) {
                // weight(1f) keeps the spinner centred while leaving
                // room for the CreditsFooter below. Without it, the
                // spinner stretches to fill the whole parent and the
                // footer gets zero vertical space.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                // Wrap the timeline list in PullToRefreshBox so the
                // user can drag down at the top to trigger a refresh.
                // The box's default indicator is a rotating circle that
                // always completes at least one full rotation — which
                // is exactly the behaviour we want (no flicker on short
                // fetches). We deliberately keep [isLoading] /
                // [userPullRefreshing] separate so the header's small
                // progress indicator doesn't mirror the pull-to-refresh
                // spinner.
                // weight(1f) makes the box take the remaining
                // vertical space inside the Column, while leaving
                // room below for the CreditsFooter. fillMaxSize()
                // would otherwise stretch the box to consume the
                // whole parent and squeeze the footer to zero height.
                PullToRefreshBox(
                    isRefreshing = userPullRefreshing,
                    onRefresh = {
                        if (userPullRefreshing) return@PullToRefreshBox
                        scope.launch {
                            userPullRefreshing = true
                            try {
                                weekOffset = 0
                                autoSkipTrigger.value++
                                TimetableRepository.refresh(context, appWidgetId)
                                days = TimetableRepository.week(
                                    context = context,
                                    appWidgetId = appWidgetId,
                                    monday = LocalDate.now()
                                        .with(WeekFields.of(Locale.GERMAN).firstDayOfWeek),
                                )
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                errorMessage = "Aktualisieren fehlgeschlagen: " +
                                    (e.message ?: e.javaClass.simpleName)
                            } finally {
                                userPullRefreshing = false
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    TimelineList(
                        days = days,
                        displayMonday = displayMonday,
                        listState = listState,
                        effectiveToday = effectiveToday,
                        moduleColorsEnabled = moduleColorsEnabled,
                    )
                }
            }

            // No credits footer in the main app body — the maintenance
            // notice lives inside the Settings dialog (see
            // [SettingsDialog]) per design.

            if (showSettings) {
                SettingsDialog(
                    currentClassId = globalClassId,
                    moduleColorsEnabled = moduleColorsEnabled,
                    validationError = validationError,
                    isValidating = isValidating,
                    onDismiss = {
                        // Don't close while we're mid-validate to avoid
                        // leaving the dialog in a "saved?" indeterminate
                        // state. The Abbrechen button is also gated
                        // by [isValidating] so this only triggers when
                        // the user taps outside the dialog.
                        if (!isValidating) showSettings = false
                    },
                    onSave = { newId, newModuleColors ->
                        val trimmed = newId.trim()
                        scope.launch {
                            isValidating = true
                            validationError = null
                            // Hit the BA server exactly once to make
                            // sure the entered seminargruppe actually
                            // returns a plan. We deliberately DON'T
                            // write to [weeksCache] / [memoryCache]
                            // here so a failed validation doesn't
                            // corrupt the user's previously-saved
                            // class id's cache.
                            val result = TimetableRepository.validateClassId(trimmed)
                            isValidating = false
                            when (result) {
                                is TimetableRepository.ValidationResult.Valid -> {
                                    persistAndClose(
                                        trimmed = trimmed,
                                        newModuleColors = newModuleColors,
                                    )
                                }
                                is TimetableRepository.ValidationResult.InvalidServerResponse -> {
                                    validationError =
                                        "Keine gültige Seminargruppe — Server lieferte keinen Stundenplan."
                                }
                                is TimetableRepository.ValidationResult.Offline -> {
                                    // Offline / no network → accept the
                                    // input anyway per product spec.
                                    persistAndClose(
                                        trimmed = trimmed,
                                        newModuleColors = newModuleColors,
                                    )
                                    errorMessage =
                                        "Seminargruppe gespeichert (offline — konnte URL nicht testen)."
                                }
                            }
                        }
                    },
                )
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
    isLoading: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val sunday = displayMonday.plusDays(4)
    val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN)
    val weekNumber = displayMonday.get(
        WeekFields.of(Locale.GERMAN).weekOfWeekBasedYear()
    )
    // KW label and date range are now packed into a single row together
    // with the navigation + refresh + overflow controls. The old "Diese
    // Woche / Nächste Woche / Vorherige Woche" title line is gone: KW
    // + the date range already convey the same information.
    // Date range is rendered on TWO lines so the second date isn't
    // truncated on narrow screens. The first line shows the Monday of
    // the week, the second line shows "– <Friday>" indented to align.
    val mondayLabel = displayMonday.format(formatter)
    val fridayLabel = "– ${sunday.format(formatter)}"
    val weekLabel = "KW $weekNumber"

    // The header uses `colorScheme.background` (same as the Scaffold's
    // containerColor) and zero tonal elevation so it blends seamlessly
    // with the area behind it and matches the system status-bar tint.
    Surface(
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // KW badge — the "selection" headline at the left edge.
            Text(
                text = weekLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            )
            // Two-line date column. We give the column a flexible width
            // so it can grow on tablets but shrink on narrow phones.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
            ) {
                Text(
                    text = mondayLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = fridayLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onPrev, enabled = canGoBack) {
                Icon(
                    imageVector = NavIcons.ChevronLeft,
                    contentDescription = "Vorherige Woche",
                )
            }
            // Today button: always enabled (within the current week it
            // scrolls today's row into view; from another week it also
            // resets the offset to 0). Tinted with `colorScheme.primary`
            // so it stands out from the surrounding neutral icons —
            // same accent colour the day-header uses for its "Heute"
            // label, giving the screen a single visual "primary" cue.
            IconButton(
                onClick = onToday,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
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
            // The header used to show a ↻ icon here as a manual
            // refresh button, but pull-to-refresh makes that
            // redundant. We still surface [isLoading] with a small
            // spinner so the user gets feedback while a programmatic
            // refresh (e.g. triggered by the widget sync) is in
            // flight.
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(20.dp)
                        .padding(horizontal = 4.dp),
                    strokeWidth = 2.dp,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = NavIcons.MoreVert,
                    contentDescription = "Einstellungen",
                )
            }
        }
    }
}

/**
 * The overflow (3-dot) menu rendered as an [AlertDialog]. Lets the user:
 *
 *   - Edit the seminargruppe used by the in-app viewer. The entered
 *     id is validated against the BA-Dresden PlanServlet on save; if
 *     the server returns a usable plan the new id is persisted, if it
 *     returns garbage the user sees an inline error, if the device is
 *     offline the input is accepted anyway (per product spec).
 *   - Toggle the optional per-module accent-color feature.
 *
 * The dialog also surfaces a small maintenance notice ("Ungewartet
 * ab 04/2027") and a compact GitHub link at the very bottom. Both
 * live ONLY here, not in the main app body, per design.
 */
@Composable
private fun SettingsDialog(
    currentClassId: String,
    moduleColorsEnabled: Boolean,
    validationError: String?,
    isValidating: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Boolean) -> Unit,
) {
    // Local mirror of the input so the text field shows what the user is
    // typing immediately. We push the trimmed value back through [onSave]
    // on confirm; if the user backs out we discard.
    var draft by remember(currentClassId) { mutableStateOf(currentClassId) }
    var emptyInputError by remember(currentClassId) { mutableStateOf<String?>(null) }
    // Mirror of the toggle so flipping the switch updates instantly
    // without re-persisting on every change. The saved value goes
    // through [onSave] on confirm; cancelling discards both drafts.
    var moduleColorsDraft by remember(moduleColorsEnabled) {
        mutableStateOf(moduleColorsEnabled)
    }
    val focusRequester = remember(currentClassId) { FocusRequester() }
    LaunchedEffect(currentClassId) {
        focusRequester.requestFocus()
    }
    // The OutlinedTextField's `isError` / `supportingText` flags
    // combine two error states: "the field is empty" (local) and "the
    // server rejected the value" (parent-owned, comes from
    // [validationError]). Whichever is non-null wins.
    val effectiveError: String? = emptyInputError ?: validationError

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Einstellungen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Seminargruppe",
                    style = MaterialTheme.typography.titleSmall,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        emptyInputError = null
                    },
                    label = { Text("z. B. 3it24-1") },
                    placeholder = { Text(TimetableRepository.DEFAULT_CLASS_ID) },
                    singleLine = true,
                    isError = effectiveError != null,
                    supportingText = effectiveError?.let { { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val trimmed = draft.trim()
                            if (trimmed.isEmpty()) {
                                emptyInputError = "Bitte etwas eingeben"
                            } else {
                                onSave(trimmed, moduleColorsDraft)
                            }
                        },
                    ),
                )
                Text(
                    text = "Wird für die Stundenplan URL verwendet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // --- Per-module color toggle ---
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Farbige Module",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "Jedes Modul bekommt eine eigene Farbe. " +
                                "Parallele Module werden als Verlauf " +
                                "dargestellt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = moduleColorsDraft,
                        onCheckedChange = { moduleColorsDraft = it },
                    )
                }
                // --- Maintenance notice + GitHub link (tiny, at the end) ---
                val ctx = LocalContext.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = "Ungewartet ab 04/2027",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                "https://github.com/KuramaSyu/dhsn-stundenplan-widget".toUri(),
                            )
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { ctx.startActivity(intent) }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Icon(
                            imageVector = NavIcons.GitHub,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "KuramaSyu/dhsn-stundenplan-widget",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = draft.trim()
                    if (trimmed.isEmpty()) {
                        emptyInputError = "Bitte etwas eingeben"
                    } else {
                        onSave(trimmed, moduleColorsDraft)
                    }
                },
                enabled = !isValidating,
            ) {
                if (isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Speichern")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isValidating) {
                Text("Abbrechen")
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Timeline list
// ---------------------------------------------------------------------------

@Composable
private fun TimelineList(
    days: List<DayPlan>,
    displayMonday: LocalDate,
    listState: LazyListState,
    effectiveToday: LocalDate,
    moduleColorsEnabled: Boolean,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(days, key = { it.date.toEpochDay() }) { day ->
            // "Tomorrow" is the calendar day after effectiveToday, but
            // only labelled as such when that day actually has lessons.
            // On Sunday this means the upcoming Monday gets the label
            // (since Monday is in the loaded Mon–Fri list and has
            // slots), while on weekdays the label still flags the
            // next school day. Days that are calendar-tomorrow but
            // have no slots (e.g. a holiday Wed) deliberately don't
            // get the highlight, which keeps "Morgen" meaningful as
            // "next day with something on it".
            // "Heute" and "Morgen" labels are about the CALENDAR, not
            // about the data: today always wins, and tomorrow is
            // (effectiveToday + 1) regardless of whether that day has
            // lessons. Showing "Morgen · Freitag, 14.10." on an empty
            // Friday makes the marker meaningful in isolation — the
            // user knows they're staring at tomorrow even when nothing
            // is scheduled.
            DaySection(
                day = day,
                isToday = day.date == effectiveToday,
                isTomorrow = day.date == effectiveToday.plusDays(1),
                moduleColorsEnabled = moduleColorsEnabled,
            )
        }
    }
}

@Composable
private fun DaySection(
    day: DayPlan,
    isToday: Boolean,
    isTomorrow: Boolean = false,
    moduleColorsEnabled: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DayHeader(day = day, isToday = isToday, isTomorrow = isTomorrow)
        Spacer(modifier = Modifier.height(8.dp))
        if (day.slots.isEmpty()) {
            EmptyDayCard(date = day.date)
        } else {
            TimelineForDay(slots = day.slots, moduleColorsEnabled = moduleColorsEnabled)
        }
    }
}

@Composable
private fun DayHeader(
    day: DayPlan,
    isToday: Boolean,
    isTomorrow: Boolean = false,
) {
    val formatter = DateTimeFormatter.ofPattern("EEEE, dd.MM.", Locale.GERMAN)
    val label = day.date.format(formatter)
    // Both "Heute" and "Morgen" get the primary tint so the next two
    // days stand out from the rest of the week in the same visual
    // language. `isToday` wins when both somehow match (it can't, but
    // the order keeps the intent explicit).
    val highlighted = isToday || isTomorrow
    val prefix = when {
        isToday -> "Heute"
        isTomorrow -> "Morgen"
        else -> null
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (prefix != null) "$prefix · $label"
                   else label.replaceFirstChar { it.uppercase(Locale.GERMAN) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (highlighted) MaterialTheme.colorScheme.primary
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
private fun TimelineForDay(
    slots: List<LessonSlot>,
    moduleColorsEnabled: Boolean = false,
) {
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
                TimelineColumn(groups = groups, moduleColorsEnabled = moduleColorsEnabled)
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
 * Compute the `weekOffset` (relative to "this week") that the Heute
 * button should land in.
 *
 *   * Mon–Fri → current week (offset = 0). The highlighted "Heute" is
 *     today, which is in the current week.
 *   * Sat–Sun → next week (offset = +1). Today is in the weekend and
 *     not in the loaded Mon–Fri list; the highlighted "Morgen" is
 *     next Monday, which lives in the upcoming week.
 *
 * The function is independent of the currently-loaded `days` list —
 * it derives purely from the calendar — so the user is always
 * returned to the right week even after navigating to a different one.
 */
private fun computeTodayButtonWeekOffset(today: LocalDate): Int = when (today.dayOfWeek) {
    DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> 1
    else -> 0
}

/**
 * The whole-day column. Each [BlockGroup] becomes one row: a "Pause
 * Xmin" label is inserted between rows when there's a gap, otherwise
 * the rows stack directly.
 *
 * The previous design used a left rail with dots aligned to each
 * card's start time. We dropped the rail because the start time is
 * already shown inside each card, and the rail consumed ~72 dp of
 * horizontal space on phones for no information gain.
 */
@Composable
private fun TimelineColumn(
    groups: List<BlockGroup>,
    moduleColorsEnabled: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 12.dp)) {
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
                PauseLabel(from = previousEnd, to = group.start)
            }
            LessonCard(group = group, moduleColorsEnabled = moduleColorsEnabled)
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
    // Just the label centered on the row — no more 72 dp gutter spacer,
    // because the rail was removed.
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        textAlign = TextAlign.Center,
    )
}

/**
 * One lesson card. Shows, top to bottom:
 *
 *   - **Time row**: a clock icon + the visible 90-min time range, with
 *     an optional `(i)` button on the trailing edge.
 *   - **Lesson body**: one or more [LessonColumn]s (side-by-side for
 *     parallel modules). Each column renders subject, then an info row
 *     with teacher and room as `AssistChip`s followed by `kind` and
 *     `remark` separated by middle dots.
 *
 * The `(i)` disclosure appears only on the **first** 90-min chunk of a
 * lesson whose original end differs from the visible end — i.e. a
 * lesson that was emitted as multiple `rowspan`-merged slots by the BA
 * page parser. Tap-to-reveal shows `"Original: 7:45–13:15 · UES"`,
 * giving the user the true time window the rowspans internally so the
 * one-chunk-on-screen presentation isn't a surprise.
 */
@Composable
private fun LessonCard(
    group: BlockGroup,
    moduleColorsEnabled: Boolean = false,
) {
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
    val rangeLabel = "${group.start.format(timeFmt)} – ${group.end.format(timeFmt)}"

    // Rowspan-aware disclosure: the (i) only appears on the LEADING
    // 90-min chunk of a multi-chunk lesson. We identify the leading
    // chunk by "this lesson's first slot (`lesson.start`) coincides
    // with this chunk's start" AND we know it's actually a multi-chunk
    // lesson by "the merged-lesson end (`group.originalEnd`) is past the
    // visible chunk end (`group.end`).
    //
    // Note: `lesson.start` / `lesson.end` here are the very first slot's
    // times (the [Lesson] object stored in each [Block] is the original
    // one emitted by the scraper and is never rewritten when the merged
    // lesson is extended — only `tail.lessonEnd` on the [Merged] is
    // updated, and `originalEnd` reflects that updated end).
    val isSplitFirstChunk = group.lessons.any { lesson ->
        lesson.start == group.start && group.originalEnd != group.end
    }

    // Local expand state for the original-time disclosure.
    var expanded by remember(group.start, group.end, group.originalEnd) {
        mutableStateOf(false)
    }

    // Per-lesson container colors. When [moduleColorsEnabled] is false
    // we fall back to the static `secondaryContainer` for every lesson —
    // the result is identical to the pre-feature layout.
    //
    // When enabled, each lesson gets a container color derived from a
    // stable hue rotation of the base `secondaryContainer` color. For
    // a single-lesson card we apply it directly. For a multi-lesson
    // card we build a horizontal gradient whose stops land at the
    // midpoint between adjacent lessons, so each module owns its own
    // band of color.
    val baseContainer = MaterialTheme.colorScheme.secondaryContainer
    val defaultOnContainer = MaterialTheme.colorScheme.onSecondaryContainer
    val perLessonContainers: List<Color> = remember(
        group.lessons,
        moduleColorsEnabled,
        baseContainer,
    ) {
        if (!moduleColorsEnabled) {
            group.lessons.map { baseContainer }
        } else {
            group.lessons.map { lesson ->
                val hue = ModuleColors.hueDegreesFor(lesson) ?: 0f
                ModuleColors.rotateHue(baseContainer, hue)
            }
        }
    }
    // The "ink" color used for the time row + body text. We pick it
    // from the first lesson's container so a card with multiple
    // modules uses a single readable on-color across its whole header.
    val onContainer = remember(perLessonContainers) {
        perLessonContainers.firstOrNull()
            ?.let { ModuleColors.onColorFor(it) }
            ?: defaultOnContainer
    }

    // The card background. For a single-lesson group it's a flat
    // color; for a parallel group it's a horizontal gradient with one
    // band per lesson. We split each band evenly so the transitions
    // between modules land exactly at the midpoint between adjacent
    // lessons.
//
// Implementation note: Brush.horizontalGradient requires strictly
// increasing stop positions in `[0f, 1f]`. We use the simplest
// approach — pass `perLessonContainers` as the gradient's color list
// and let Compose distribute them at evenly-spaced stops. This
// produces a smooth gradient that crosses each band, which is what
// the user asked for ("add gradients to modules which are at the
// same time"). Trying to force hard colour edges would mean duplicate
// stops, which Compose's brush API rejects with IllegalArgumentException.
    val cardBackgroundModifier: Modifier = if (group.lessons.size <= 1) {
        Modifier.background(color = perLessonContainers.first(), shape = RoundedCornerShape(8.dp))
    } else {
        Modifier.background(
            brush = Brush.horizontalGradient(perLessonContainers),
            shape = RoundedCornerShape(8.dp),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .then(cardBackgroundModifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Card time row: clock icon + range, (i) disclosure trailing.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = NavIcons.Schedule,
                    contentDescription = null,
                    tint = onContainer,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = rangeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                )
            }
            if (isSplitFirstChunk) {
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
                        tint = onContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Body: one column per lesson (side-by-side when there are
        // parallel modules). Each column shows subject, then the
        // chip+separator info row.
        if (group.lessons.size == 1) {
            LessonColumn(
                lesson = group.lessons[0],
                onContainer = onContainer,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                group.lessons.forEachIndexed { index, lesson ->
                    // Each side of the card has its own on-color picked
                    // from THAT lesson's container, so contrast holds
                    // even if the parallel modules have very different
                    // hues (e.g. a dark teal next to a pale yellow).
                    val columnOn = ModuleColors.onColorFor(
                        perLessonContainers[index],
                    )
                    LessonColumn(
                        lesson = lesson,
                        onContainer = columnOn,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (isSplitFirstChunk && expanded) {
            Spacer(modifier = Modifier.height(4.dp))
            // Build the disclosure text: "Original: 7:45–13:15 · UES"
            // for each lesson in the leading chunk.
            val subjectList = group.lessons.joinToString(" · ") { it.subject }
            val timeRange = group.lessons.joinToString(" · ") { lesson ->
                "${lesson.start.format(timeFmt)}–${lesson.end.format(timeFmt)}"
            }
            Text(
                text = "Original: $timeRange · $subjectList",
                style = MaterialTheme.typography.labelSmall,
                color = onContainer.copy(alpha = 0.85f),
            )
        }
    }
}



/**
 * Renders the per-module body of a [LessonCard]. Layout, top-to-bottom:
 *
 *   1. **Subject** in title-small, bold.
 *   2. **Chips row**: teacher (`Person` icon) and room (`DoorFront` icon)
 *      as `AssistChip`s. Skipped silently when the field is blank.
 *   3. **Tail row**: remaining info (`kind`, `remark`) joined by a
 *      centered middle dot (` · `). NOT a chip, just plain text so the
 *      chips above stay visually distinct from the descriptors.
 *
 * `kind` and `remark` are joined together with everything else that
 * isn't a chip (so the last line is always exactly one line, not three
 * one-word lines).
 */
@Composable
private fun LessonColumn(
    lesson: Lesson,
    modifier: Modifier = Modifier,
    onContainer: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Column(modifier = modifier) {
        Text(
            text = lesson.subject.ifBlank { "(kein Fach)" },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = onContainer,
        )
        Spacer(modifier = Modifier.height(4.dp))
        // Chip row: teacher + room side-by-side.
        if (lesson.teacher.isNotBlank() || lesson.room.isNotBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (lesson.teacher.isNotBlank()) {
                    InfoChip(
                        icon = NavIcons.Person,
                        label = lesson.teacher,
                        onContainer = onContainer,
                    )
                }
                if (lesson.room.isNotBlank()) {
                    InfoChip(
                        icon = NavIcons.DoorFront,
                        label = lesson.room,
                        onContainer = onContainer,
                    )
                }
            }
        }
        // Tail row: everything else (kind, remark) as a single dot-
        // separated line.
        val tail = buildList {
            if (lesson.kind.isNotBlank()) add(lesson.kind)
            if (lesson.remark.isNotBlank()) add(lesson.remark)
        }
        if (tail.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = tail.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = onContainer.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * A compact, non-interactive info chip used inside a [LessonCard] to
 * surface teacher + room at full color contrast.
 *
 * We don't use Material 3's `AssistChip` / `SuggestionChip` here
 * because:
 *
 *   1. They default to 32 dp tall and a fully-rounded pill shape
 *      (`RoundedCornerShape(16.dp)`), which is too prominent for an
 *      inline label inside a card body.
 *   2. Disabling them (which we have to do — these are pure labels,
 *      not actions) applies a 0.38 alpha on top of whatever colors
 *      we pass via `assistChipColors()`, so the chip text looks like
 *      secondary content even though we want full opacity.
 *
 * This implementation gives us a ~24 dp tall, mildly rounded
 * (4 dp corner radius) chip with full-opacity `onTertiaryContainer`
 * text + icon on a `tertiaryContainer` background.
 */
@Composable
private fun InfoChip(
    icon: ImageVector,
    label: String,
    onContainer: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    // The chip background uses the lesson's container color (rather
    // than the theme's static `tertiaryContainer`) so each module's
    // chip belongs visually to its own band of color. Text + icon
    // pick up the matching on-color so contrast stays correct.
    Row(
        modifier = Modifier
            .background(
                color = onContainer.copy(alpha = 0.18f),
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = onContainer,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = onContainer,
        )
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

    /**
     * Round clock face with hour + minute hands. Material Symbols
     * `schedule` glyph, rendered as two open paths so the hour/minute
     * hands and the circular outline share one [ImageVector].
     */
    val Schedule: ImageVector = ImageVector.Builder(
        name = "Schedule",
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
            moveTo(12f, 6f)
            verticalLineTo(12f)
            lineTo(16f, 14f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(21f, 12f)
            curveTo(21f, 16.97f, 16.97f, 21f, 12f, 21f)
            curveTo(7.03f, 21f, 3f, 16.97f, 3f, 12f)
            curveTo(3f, 7.03f, 7.03f, 3f, 12f, 3f)
            curveTo(16.97f, 3f, 21f, 7.03f, 21f, 12f)
            close()
        }
    }.build()

    /**
     * Person silhouette: round head + tapered body. Material Symbols
     * `person` glyph.
     */
    val Person: ImageVector = ImageVector.Builder(
        name = "Person",
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
            moveTo(12f, 12f)
            curveTo(14.21f, 12f, 16f, 10.21f, 16f, 8f)
            curveTo(16f, 5.79f, 14.21f, 4f, 12f, 4f)
            curveTo(9.79f, 4f, 8f, 5.79f, 8f, 8f)
            curveTo(8f, 10.21f, 9.79f, 12f, 12f, 12f)
            close()
            moveTo(12f, 14f)
            curveTo(7.58f, 14f, 4f, 16.69f, 4f, 19f)
            verticalLineTo(20f)
            horizontalLineTo(20f)
            verticalLineTo(19f)
            curveTo(20f, 16.69f, 16.42f, 14f, 12f, 14f)
            close()
        }
    }.build()

    /**
     * Door + frame. Material Symbols `door_front` glyph.
     */
    val DoorFront: ImageVector = ImageVector.Builder(
        name = "DoorFront",
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
            moveTo(19f, 19f)
            verticalLineTo(5f)
            curveTo(19f, 3.9f, 18.1f, 3f, 17f, 3f)
            horizontalLineTo(7f)
            curveTo(5.9f, 3f, 5f, 3.9f, 5f, 5f)
            verticalLineTo(19f)
            horizontalLineTo(3f)
            verticalLineTo(21f)
            horizontalLineTo(21f)
            verticalLineTo(19f)
            horizontalLineTo(19f)
            close()
            moveTo(17f, 19f)
            horizontalLineTo(7f)
            verticalLineTo(5f)
            horizontalLineTo(17f)
            verticalLineTo(19f)
            close()
            moveTo(14f, 11f)
            verticalLineTo(13f)
            horizontalLineTo(16f)
            verticalLineTo(11f)
            horizontalLineTo(14f)
            close()
        }
    }.build()

    /**
     * Three vertical dots. Material Symbols `more_vert` glyph.
     */
    val MoreVert: ImageVector = ImageVector.Builder(
        name = "MoreVert",
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
            moveTo(12f, 8f)
            curveTo(13.1f, 8f, 14f, 7.1f, 14f, 6f)
            curveTo(14f, 4.9f, 13.1f, 4f, 12f, 4f)
            curveTo(10.9f, 4f, 10f, 4.9f, 10f, 6f)
            curveTo(10f, 7.1f, 10.9f, 8f, 12f, 8f)
            close()
            moveTo(12f, 14f)
            curveTo(13.1f, 14f, 14f, 13.1f, 14f, 12f)
            curveTo(14f, 10.9f, 13.1f, 10f, 12f, 10f)
            curveTo(10.9f, 10f, 10f, 10.9f, 10f, 12f)
            curveTo(10f, 13.1f, 10.9f, 14f, 12f, 14f)
            close()
            moveTo(12f, 20f)
            curveTo(13.1f, 20f, 14f, 19.1f, 14f, 18f)
            curveTo(14f, 16.9f, 13.1f, 16f, 12f, 16f)
            curveTo(10.9f, 16f, 10f, 16.9f, 10f, 18f)
            curveTo(10f, 19.1f, 10.9f, 20f, 12f, 20f)
            close()
        }
    }.build()

    /**
     * GitHub mark (octocat silhouette). Path data copied from the
     * GitHub Logos repo under the MIT/CC0 licence — a single closed
     * shape, fill-only. Used as the leading icon on the "open the
     * repo" button inside [SettingsDialog].
     */
    val GitHub: ImageVector = ImageVector.Builder(
        name = "GitHub",
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
            // Standard GitHub mark outline + filled body.
            moveTo(12f, 2f)
            curveTo(6.477f, 2f, 2f, 6.484f, 2f, 12.017f)
            curveTo(2f, 16.435f, 4.865f, 20.169f, 8.839f, 21.505f)
            curveTo(9.339f, 21.605f, 9.521f, 21.286f, 9.521f, 21.006f)
            curveTo(9.521f, 20.751f, 9.512f, 20.014f, 9.508f, 19.222f)
            curveTo(6.726f, 19.806f, 6.139f, 17.978f, 6.139f, 17.978f)
            curveTo(5.685f, 16.852f, 5.013f, 16.533f, 5.013f, 16.533f)
            curveTo(4.071f, 15.892f, 5.077f, 15.906f, 5.077f, 15.906f)
            curveTo(6.109f, 15.97f, 6.692f, 16.957f, 6.692f, 16.957f)
            curveTo(7.625f, 18.476f, 9.121f, 18.012f, 9.541f, 17.749f)
            curveTo(9.631f, 17.161f, 9.85f, 16.764f, 10.095f, 16.547f)
            curveTo(7.815f, 16.302f, 5.421f, 15.466f, 5.421f, 11.758f)
            curveTo(5.421f, 10.694f, 5.795f, 9.829f, 6.397f, 9.155f)
            curveTo(6.297f, 8.92f, 5.972f, 7.997f, 6.495f, 6.726f)
            curveTo(6.495f, 6.726f, 7.297f, 6.466f, 9.502f, 7.789f)
            curveTo(10.293f, 7.585f, 11.139f, 7.483f, 11.978f, 7.479f)
            curveTo(12.817f, 7.483f, 13.663f, 7.585f, 14.455f, 7.789f)
            curveTo(16.658f, 6.466f, 17.459f, 6.726f, 17.459f, 6.726f)
            curveTo(17.984f, 7.997f, 17.659f, 8.92f, 17.559f, 9.155f)
            curveTo(18.163f, 9.829f, 18.535f, 10.694f, 18.535f, 11.758f)
            curveTo(18.535f, 15.478f, 16.138f, 16.299f, 13.852f, 16.539f)
            curveTo(14.146f, 16.792f, 14.41f, 17.302f, 14.41f, 18.084f)
            curveTo(14.41f, 19.177f, 14.399f, 20.057f, 14.399f, 20.273f)
            curveTo(14.399f, 20.555f, 14.578f, 20.878f, 15.084f, 20.773f)
            curveTo(19.058f, 19.439f, 21.918f, 15.704f, 21.918f, 12.017f)
            curveTo(21.918f, 6.484f, 17.438f, 2f, 12f, 2f)
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