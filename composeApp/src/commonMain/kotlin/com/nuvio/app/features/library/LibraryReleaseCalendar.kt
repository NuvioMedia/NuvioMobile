package com.nuvio.app.features.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.i18n.localizedMonthName
import com.nuvio.app.core.i18n.localizedShortMonthName
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.library_calendar_agenda
import nuvio.composeapp.generated.resources.library_calendar_available
import nuvio.composeapp.generated.resources.library_calendar_empty_message
import nuvio.composeapp.generated.resources.library_calendar_empty_title
import nuvio.composeapp.generated.resources.library_calendar_exact_dates_only
import nuvio.composeapp.generated.resources.library_calendar_loading
import nuvio.composeapp.generated.resources.library_calendar_next_month
import nuvio.composeapp.generated.resources.library_calendar_no_day_events_message
import nuvio.composeapp.generated.resources.library_calendar_no_day_events_title
import nuvio.composeapp.generated.resources.library_calendar_previous_month
import nuvio.composeapp.generated.resources.library_calendar_release_count
import nuvio.composeapp.generated.resources.library_calendar_release_count_single
import nuvio.composeapp.generated.resources.library_calendar_title
import nuvio.composeapp.generated.resources.library_calendar_today
import nuvio.composeapp.generated.resources.library_calendar_upcoming
import nuvio.composeapp.generated.resources.library_calendar_weekday_fri
import nuvio.composeapp.generated.resources.library_calendar_weekday_mon
import nuvio.composeapp.generated.resources.library_calendar_weekday_sat
import nuvio.composeapp.generated.resources.library_calendar_weekday_sun
import nuvio.composeapp.generated.resources.library_calendar_weekday_thu
import nuvio.composeapp.generated.resources.library_calendar_weekday_tue
import nuvio.composeapp.generated.resources.library_calendar_weekday_wed
import org.jetbrains.compose.resources.stringResource

/**
 * Library release calendar, ported from Nuvio Enhanced.
 *
 * Shows exact release dates for saved movies and for episodes of saved series.
 * Opened from the calendar icon on the Library screen.
 */

@Composable
internal fun LibraryReleaseCalendarHost(
    items: List<LibraryItem>,
    onDismiss: () -> Unit,
    onPosterClick: ((LibraryItem) -> Unit)?,
    onCalendarEpisodeClick: ((LibraryItem, Int?, Int?) -> Unit)?,
) {
    val cacheState by LibraryReleaseCalendarCache.state.collectAsStateWithLifecycle()
    val cacheKey = remember(items) { LibraryReleaseCalendarCache.cacheKeyFor(items) }
    val fallbackEvents = remember(items) { buildLibraryReleaseCalendarFallbackEvents(items) }
    val events = if (cacheState.cacheKey == cacheKey) cacheState.events else fallbackEvents
    val isLoading = cacheState.cacheKey == cacheKey && cacheState.isWarming
    val coroutineScope = rememberCoroutineScope()

    LibraryReleaseCalendarPanel(
        events = events,
        isLoading = isLoading,
        onDismiss = onDismiss,
        onPosterClick = onPosterClick,
        onCalendarEpisodeClick = onCalendarEpisodeClick,
        onMonthRequested = { month ->
            coroutineScope.launch {
                LibraryReleaseCalendarCache.ensureMonth(items, month.key)
            }
        },
    )
}

@Composable
internal fun LibraryReleaseCalendarPanel(
    events: List<LibraryCalendarEvent>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onPosterClick: ((LibraryItem) -> Unit)?,
    onCalendarEpisodeClick: ((LibraryItem, Int?, Int?) -> Unit)?,
    onMonthRequested: (LibraryCalendarMonth) -> Unit,
) {
    val today = remember { parseLibraryCalendarDate(CurrentDateProvider.todayIsoDate()) ?: LibraryCalendarDate(1970, 1, 1) }
    val todayIso = today.iso
    val initialMonth = remember { initialLibraryCalendarMonth() }
    var calendarSelection by remember(initialMonth, todayIso) {
        mutableStateOf(defaultLibraryCalendarSelection(events, initialMonth, todayIso))
    }
    var monthNavigationDirection by remember { mutableStateOf(1) }
    val visibleMonth = calendarSelection.month
    val selectedDateIso = calendarSelection.dateIso
    val monthEvents = remember(events, visibleMonth) {
        events
            .filter { event -> event.date.year == visibleMonth.year && event.date.month == visibleMonth.month }
            .sortedWith(compareBy<LibraryCalendarEvent> { it.date.iso }.thenBy { it.sortTitle.lowercase() })
    }
    val eventsByDate = remember(events) { events.groupBy { event -> event.date.iso } }

    val selectedEvents = eventsByDate[selectedDateIso].orEmpty()
    val handleEventClick: ((LibraryCalendarEvent) -> Unit)? = when {
        onCalendarEpisodeClick != null -> { event ->
            onDismiss()
            onCalendarEpisodeClick(event.item, event.seasonNumber, event.episodeNumber)
        }
        onPosterClick != null -> { event ->
            onDismiss()
            onPosterClick(event.item)
        }
        else -> null
    }
    val selectedDate = parseLibraryCalendarDate(selectedDateIso)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .padding(start = 16.dp, top = 16.dp, end = 16.dp),
        ) {
            val useHorizontalLayout = maxWidth >= 600.dp || maxWidth > maxHeight
            val panelHeight = if (useHorizontalLayout) {
                minOf(maxHeight, 500.dp)
            } else {
                minOf(maxHeight, 700.dp)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onDismiss),
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .height(panelHeight)
                    .clickable(onClick = {}),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 18.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                ) {
                    LibraryCalendarTopBar(
                        title = stringResource(Res.string.library_calendar_title),
                        subtitle = stringResource(Res.string.library_calendar_exact_dates_only),
                        onDismiss = onDismiss,
                    )

                    when {
                        events.isEmpty() && isLoading -> LibraryCalendarLoadingState()
                        events.isEmpty() -> LibraryCalendarEmptyState()
                        useHorizontalLayout -> {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                LazyColumn(modifier = Modifier.weight(1f)) {
                                    item {
                                        AnimatedContent(
                                            targetState = visibleMonth,
                                            transitionSpec = {
                                                val direction = monthNavigationDirection
                                                (slideInHorizontally { width -> direction * width } togetherWith
                                                    slideOutHorizontally { width -> -direction * width })
                                            },
                                            label = "library-calendar-month",
                                        ) { animatedMonth ->
                                        LibraryCalendarCard(
                                            month = animatedMonth,
                                            monthEventCount = monthEvents.size,
                                            eventsByDate = eventsByDate,
                                            selectedDateIso = selectedDateIso,
                                            todayIso = todayIso,
                                            onPrevious = {
                                                monthNavigationDirection = -1
                                                onMonthRequested(visibleMonth.previous())
                                                calendarSelection = defaultLibraryCalendarSelection(
                                                    events = events,
                                                    month = visibleMonth.previous(),
                                                    todayIso = todayIso,
                                                )
                                            },
                                            onNext = {
                                                monthNavigationDirection = 1
                                                onMonthRequested(visibleMonth.next())
                                                calendarSelection = defaultLibraryCalendarSelection(
                                                    events = events,
                                                    month = visibleMonth.next(),
                                                    todayIso = todayIso,
                                                )
                                            },
                                            onToday = {
                                                calendarSelection = LibraryCalendarSelection(
                                                    month = LibraryCalendarMonth(today.year, today.month),
                                                    dateIso = todayIso,
                                                )
                                            },
                                            onDateSelected = { date ->
                                                calendarSelection = calendarSelection.copy(dateIso = date.iso)
                                            },
                                        )
                                        }
                                    }
                                }
                                LazyColumn(modifier = Modifier.weight(1f)) {
                                    libraryCalendarAgendaContent(
                                        selectedDate = selectedDate,
                                        selectedEvents = selectedEvents,
                                        todayIso = todayIso,
                                        isLoading = isLoading,
                                        onEventClick = handleEventClick,
                                    )
                                }
                            }
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            ) {
                                item {
                                    AnimatedContent(
                                        targetState = visibleMonth,
                                        transitionSpec = {
                                            val direction = monthNavigationDirection
                                            (slideInHorizontally { width -> direction * width } togetherWith
                                                slideOutHorizontally { width -> -direction * width })
                                        },
                                        label = "library-calendar-month",
                                    ) { animatedMonth ->
                                    LibraryCalendarCard(
                                        month = animatedMonth,
                                        monthEventCount = monthEvents.size,
                                        eventsByDate = eventsByDate,
                                        selectedDateIso = selectedDateIso,
                                        todayIso = todayIso,
                                        onPrevious = {
                                            monthNavigationDirection = -1
                                            onMonthRequested(visibleMonth.previous())
                                            calendarSelection = defaultLibraryCalendarSelection(
                                                events = events,
                                                month = visibleMonth.previous(),
                                                todayIso = todayIso,
                                            )
                                        },
                                        onNext = {
                                            monthNavigationDirection = 1
                                            onMonthRequested(visibleMonth.next())
                                            calendarSelection = defaultLibraryCalendarSelection(
                                                events = events,
                                                month = visibleMonth.next(),
                                                todayIso = todayIso,
                                            )
                                        },
                                        onToday = {
                                            calendarSelection = LibraryCalendarSelection(
                                                month = LibraryCalendarMonth(today.year, today.month),
                                                dateIso = todayIso,
                                            )
                                        },
                                        onDateSelected = { date ->
                                            calendarSelection = calendarSelection.copy(dateIso = date.iso)
                                        },
                                    )
                                    }
                                }
                                libraryCalendarAgendaContent(
                                    selectedDate = selectedDate,
                                    selectedEvents = selectedEvents,
                                    todayIso = todayIso,
                                    isLoading = isLoading,
                                    onEventClick = handleEventClick,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.libraryCalendarAgendaContent(
    selectedDate: LibraryCalendarDate?,
    selectedEvents: List<LibraryCalendarEvent>,
    todayIso: String,
    isLoading: Boolean,
    onEventClick: ((LibraryCalendarEvent) -> Unit)?,
) {
    item {
        Spacer(modifier = Modifier.height(4.dp))
        LibraryCalendarAgendaHeader(
            selectedDate = selectedDate,
            eventCount = selectedEvents.size,
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
    if (selectedEvents.isEmpty()) {
        item { LibraryCalendarNoDayEvents() }
    } else {
        items(items = selectedEvents, key = { event -> event.key }) { event ->
            LibraryCalendarEventRow(
                event = event,
                todayIso = todayIso,
                onClick = onEventClick?.let { eventClick -> { eventClick(event) } },
            )
        }
    }
    if (isLoading) {
        item { LibraryCalendarInlineLoading() }
    }
    item { Spacer(modifier = Modifier.height(12.dp)) }
}

@Composable
private fun LibraryCalendarTopBar(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 22.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(Res.string.action_close),
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
internal fun LibraryCalendarGlyph(
    modifier: Modifier = Modifier,
    tint: Color,
    cutoutColor: Color,
) {
    Canvas(modifier = modifier) {
        val scale = size.minDimension / 14f
        fun x(value: Float) = value * scale
        fun y(value: Float) = value * scale

        drawRoundRect(
            color = tint,
            topLeft = Offset(x(1.5f), y(2.5f)),
            size = Size(x(11f), y(10f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(x(1.1f), y(1.1f)),
        )
        drawRect(
            color = cutoutColor,
            topLeft = Offset(x(2.6f), y(5.1f)),
            size = Size(x(8.8f), y(0.9f)),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(x(3.5f), y(1.3f)),
            size = Size(x(1.5f), y(3.1f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(x(0.7f), y(0.7f)),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(x(9f), y(1.3f)),
            size = Size(x(1.5f), y(3.1f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(x(0.7f), y(0.7f)),
        )

        val cell = x(1.15f)
        val gap = x(1.05f)
        val startX = x(4.1f)
        val startY = y(7.25f)
        repeat(3) { column ->
            repeat(2) { row ->
                drawRoundRect(
                    color = cutoutColor,
                    topLeft = Offset(startX + column * (cell + gap), startY + row * (cell + gap)),
                    size = Size(cell, cell),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(x(0.22f), y(0.22f)),
                )
            }
        }
    }
}

@Composable
private fun LibraryCalendarLoadingState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(Res.string.library_calendar_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LibraryCalendarInlineLoading() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
    }
}

@Composable
private fun LibraryCalendarEmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.library_calendar_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(Res.string.library_calendar_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibraryCalendarCard(
    month: LibraryCalendarMonth,
    monthEventCount: Int,
    eventsByDate: Map<String, List<LibraryCalendarEvent>>,
    selectedDateIso: String?,
    todayIso: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onDateSelected: (LibraryCalendarDate) -> Unit,
) {
    val swipeModifier = Modifier.pointerInput(month) {
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { totalDrag = 0f },
            onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount },
            onDragEnd = {
                when {
                    totalDrag <= -48f -> onNext()
                    totalDrag >= 48f -> onPrevious()
                }
            },
            onDragCancel = { totalDrag = 0f },
        )
    }
    Surface(
        modifier = swipeModifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onPrevious,
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        contentDescription = stringResource(Res.string.library_calendar_previous_month),
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                ) {
                    Text(
                        text = month.displayTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = libraryCalendarReleaseCountText(monthEventCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    modifier = Modifier.clickable(onClick = onToday),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = stringResource(Res.string.library_calendar_today),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                IconButton(
                    onClick = onNext,
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = stringResource(Res.string.library_calendar_next_month),
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            LibraryCalendarWeekdayHeader()
            LibraryCalendarMonthGrid(
                month = month,
                eventsByDate = eventsByDate,
                selectedDateIso = selectedDateIso,
                todayIso = todayIso,
                onDateSelected = onDateSelected,
            )
        }
    }
}

@Composable
private fun LibraryCalendarWeekdayHeader() {
    val labels = listOf(
        stringResource(Res.string.library_calendar_weekday_sun),
        stringResource(Res.string.library_calendar_weekday_mon),
        stringResource(Res.string.library_calendar_weekday_tue),
        stringResource(Res.string.library_calendar_weekday_wed),
        stringResource(Res.string.library_calendar_weekday_thu),
        stringResource(Res.string.library_calendar_weekday_fri),
        stringResource(Res.string.library_calendar_weekday_sat),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        labels.forEach { label ->
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LibraryCalendarMonthGrid(
    month: LibraryCalendarMonth,
    eventsByDate: Map<String, List<LibraryCalendarEvent>>,
    selectedDateIso: String?,
    todayIso: String,
    onDateSelected: (LibraryCalendarDate) -> Unit,
) {
    val cells = remember(month) { libraryCalendarCells(month) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cells.chunked(7).forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                        )
                    } else {
                        val dayEvents = eventsByDate[date.iso].orEmpty()
                        val hasEvents = dayEvents.isNotEmpty()
                        val isSelected = selectedDateIso == date.iso
                        val isToday = todayIso == date.iso
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable { onDateSelected(date) },
                            contentAlignment = Alignment.Center,
                        ) {
                            val dayColor = when {
                                isSelected -> MaterialTheme.colorScheme.onPrimary
                                hasEvents || isToday -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    )
                                    .then(
                                        if (!isSelected && isToday) {
                                            Modifier.border(
                                                BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary),
                                                RoundedCornerShape(13.dp),
                                            )
                                        } else {
                                            Modifier
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = date.day.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = dayColor,
                                    fontWeight = if (hasEvents) FontWeight.Bold else FontWeight.Normal,
                                    modifier = if (hasEvents && isSelected) {
                                        Modifier.padding(bottom = 6.dp)
                                    } else {
                                        Modifier
                                    },
                                )
                                if (hasEvents && !isToday) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = if (isSelected) 5.dp else 2.dp)
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) {
                                                    MaterialTheme.colorScheme.onPrimary
                                                } else {
                                                    MaterialTheme.colorScheme.primary
                                                },
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryCalendarAgendaHeader(
    selectedDate: LibraryCalendarDate?,
    eventCount: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = stringResource(Res.string.library_calendar_agenda),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = selectedDate?.let(::displayLibraryCalendarEventDate).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(50),
        ) {
            Text(
                text = libraryCalendarReleaseCountText(eventCount),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun libraryCalendarReleaseCountText(count: Int): String =
    if (count == 1) {
        stringResource(Res.string.library_calendar_release_count_single)
    } else {
        stringResource(Res.string.library_calendar_release_count, count)
    }

@Composable
private fun LibraryCalendarNoDayEvents() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(14.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    LibraryCalendarGlyph(
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        cutoutColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(Res.string.library_calendar_no_day_events_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.library_calendar_no_day_events_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LibraryCalendarEventRow(
    event: LibraryCalendarEvent,
    todayIso: String,
    onClick: (() -> Unit)?,
) {
    val isUpcoming = event.date.iso > todayIso
    val modifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    } else {
        Modifier.fillMaxWidth()
    }
    Surface(
        modifier = modifier.padding(bottom = 10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryCalendarEventArtwork(event = event)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                event.subtitle?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(
                        if (isUpcoming) {
                            Res.string.library_calendar_upcoming
                        } else {
                            Res.string.library_calendar_available
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LibraryCalendarEventArtwork(event: LibraryCalendarEvent) {
    Box(
        modifier = Modifier
            .size(width = 104.dp, height = 62.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (!event.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = event.imageUrl,
                contentDescription = event.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(14.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "${event.date.day} ${localizedShortMonthName(event.date.month)}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

internal data class LibraryCalendarEvent(
    val key: String,
    val date: LibraryCalendarDate,
    val rawReleaseInfo: String,
    val item: LibraryItem,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val sortTitle: String = title,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

private data class LibraryCalendarSelection(
    val month: LibraryCalendarMonth,
    val dateIso: String,
)

private data class LibraryCalendarDate(
    val year: Int,
    val month: Int,
    val day: Int,
) {
    val iso: String = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

internal data class LibraryCalendarMonth(
    val year: Int,
    val month: Int,
) {
    val key: String = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}"
    val displayTitle: String = "${localizedMonthName(month)} $year"

    fun previous(): LibraryCalendarMonth =
        if (month == 1) LibraryCalendarMonth(year - 1, 12) else copy(month = month - 1)

    fun next(): LibraryCalendarMonth =
        if (month == 12) LibraryCalendarMonth(year + 1, 1) else copy(month = month + 1)
}

internal data class LibraryReleaseCalendarCacheState(
    val cacheKey: String? = null,
    val events: List<LibraryCalendarEvent> = emptyList(),
    val isWarming: Boolean = false,
    val isReady: Boolean = false,
    val loadedMonthKeys: Set<String> = emptySet(),
)

/**
 * Keeps the nearby release window ready while Library is visible, so opening the calendar does
 * not wait for metadata requests. MetaDetailsRepository supplies the lower-level meta cache;
 * this cache stores the calendar-ready event projection for the previous, current, and next month.
 */
internal object LibraryReleaseCalendarCache {
    private val _state = MutableStateFlow(LibraryReleaseCalendarCacheState())
    val state: StateFlow<LibraryReleaseCalendarCacheState> = _state.asStateFlow()
    private var activeCacheKey: String? = null

    fun cacheKeyFor(items: List<LibraryItem>): String {
        val nearbyMonths = libraryCalendarWarmMonthKeys().joinToString(separator = ",")
        val itemFingerprint = items.joinToString(separator = "|") { item ->
            "${item.type}:${item.id}:${item.releaseInfo.orEmpty()}"
        }
        return "${ProfileRepository.activeProfileId}:$nearbyMonths:$itemFingerprint"
    }

    suspend fun warm(items: List<LibraryItem>) {
        val cacheKey = cacheKeyFor(items)
        if ((_state.value.cacheKey == cacheKey && _state.value.isReady) || activeCacheKey == cacheKey) return

        val persisted = LibraryReleaseSchedulePersistence.load(items)
        if (persisted != null && persisted.cacheKey == cacheKey && persisted.savedOnIsoDate == CurrentDateProvider.todayIsoDate()) {
            _state.value = LibraryReleaseCalendarCacheState(
                cacheKey = cacheKey,
                events = persisted.events,
                isReady = true,
                loadedMonthKeys = persisted.loadedMonthKeys,
            )
            return
        }

        activeCacheKey = cacheKey
        val fallbackEvents = buildLibraryReleaseCalendarFallbackEvents(items)
        val previousEpisodeEvents = (persisted?.events ?: _state.value.events)
            .filter { event -> event.key.startsWith("episode:") }
        _state.value = LibraryReleaseCalendarCacheState(
            cacheKey = cacheKey,
            events = (previousEpisodeEvents + fallbackEvents)
                .distinctBy { it.key }
                .sortedWith(compareBy<LibraryCalendarEvent> { it.date.iso }.thenBy { it.sortTitle.lowercase() }),
            isWarming = true,
        )

        try {
            val warmedEvents = buildLibraryReleaseCalendarEvents(
                items = items,
                targetMonthKeys = libraryCalendarWarmMonthKeys(),
            )
            if (activeCacheKey == cacheKey) {
                _state.value = LibraryReleaseCalendarCacheState(
                    cacheKey = cacheKey,
                    events = warmedEvents,
                    isReady = true,
                    loadedMonthKeys = libraryCalendarWarmMonthKeys(),
                )
                LibraryReleaseSchedulePersistence.save(_state.value)
            }
        } finally {
            if (activeCacheKey == cacheKey && _state.value.isWarming) {
                _state.value = _state.value.copy(isWarming = false)
            }
            if (activeCacheKey == cacheKey) {
                activeCacheKey = null
            }
        }
    }

    suspend fun ensureMonth(items: List<LibraryItem>, monthKey: String) {
        val cacheKey = cacheKeyFor(items)
        if (_state.value.cacheKey != cacheKey || monthKey in _state.value.loadedMonthKeys || activeCacheKey != null) return
        activeCacheKey = cacheKey
        _state.value = _state.value.copy(isWarming = true)
        try {
            val monthEvents = buildLibraryReleaseCalendarEvents(items, setOf(monthKey))
            if (activeCacheKey == cacheKey && _state.value.cacheKey == cacheKey) {
                val merged = (_state.value.events + monthEvents)
                    .distinctBy { it.key }
                    .sortedWith(compareBy<LibraryCalendarEvent> { it.date.iso }.thenBy { it.sortTitle.lowercase() })
                _state.value = _state.value.copy(
                    events = merged,
                    isWarming = false,
                    loadedMonthKeys = _state.value.loadedMonthKeys + monthKey,
                )
                LibraryReleaseSchedulePersistence.save(_state.value)
            }
        } finally {
            if (activeCacheKey == cacheKey) {
                activeCacheKey = null
                if (_state.value.isWarming) _state.value = _state.value.copy(isWarming = false)
            }
        }
    }
}

private fun libraryCalendarWarmMonthKeys(): Set<String> {
    val currentMonth = initialLibraryCalendarMonth()
    return setOf(
        currentMonth.previous().key,
        currentMonth.key,
        currentMonth.next().key,
    )
}

private suspend fun buildLibraryReleaseCalendarEvents(
    items: List<LibraryItem>,
    targetMonthKeys: Set<String>,
): List<LibraryCalendarEvent> {
    val fallbackEvents = buildLibraryReleaseCalendarFallbackEvents(items)
    val episodeEvents = buildLibraryEpisodeCalendarEvents(items, targetMonthKeys)
    val seriesWithEpisodeEvents = episodeEvents.map { it.item.id to it.item.type.lowercase() }.toSet()
    return (episodeEvents + fallbackEvents.filterNot { event ->
        event.date.iso.take(7) in targetMonthKeys &&
            event.item.isLibrarySeries() &&
            (event.item.id to event.item.type.lowercase()) in seriesWithEpisodeEvents
    })
        .distinctBy { it.key }
        .sortedWith(compareBy<LibraryCalendarEvent> { it.date.iso }.thenBy { it.sortTitle.lowercase() })
}

private fun buildLibraryReleaseCalendarFallbackEvents(items: List<LibraryItem>): List<LibraryCalendarEvent> =
    items
        .asSequence()
        .mapNotNull { item ->
            val rawReleaseInfo = item.releaseInfo?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val date = parseLibraryCalendarDate(rawReleaseInfo) ?: return@mapNotNull null
            LibraryCalendarEvent(
                key = "item:${item.type}:${item.id}:${date.iso}",
                date = date,
                rawReleaseInfo = rawReleaseInfo,
                item = item,
                title = item.name,
                imageUrl = item.banner ?: item.poster,
                sortTitle = item.name,
            )
        }
        .sortedWith(compareBy<LibraryCalendarEvent> { it.date.iso }.thenBy { it.item.name.lowercase() })
        .toList()

private suspend fun buildLibraryEpisodeCalendarEvents(
    items: List<LibraryItem>,
    targetMonthKeys: Set<String>,
): List<LibraryCalendarEvent> =
    coroutineScope {
        val events = mutableListOf<LibraryCalendarEvent>()
        items
            .filter(LibraryItem::isLibrarySeries)
            .chunked(4)
            .forEach { chunk ->
                events += chunk.map { item ->
                    async {
                        val details = MetaDetailsRepository.fetch(item.type, item.id) ?: return@async emptyList()
                        details.videos
                            .mapNotNull { video -> video.toLibraryCalendarEvent(item) }
                            .filter { event -> event.date.iso.take(7) in targetMonthKeys }
                    }
                }.awaitAll().flatten()
            }
        events
    }

private fun MetaVideo.toLibraryCalendarEvent(item: LibraryItem): LibraryCalendarEvent? {
    val rawReleaseInfo = released?.takeIf { it.isNotBlank() } ?: return null
    val date = parseLibraryCalendarDate(rawReleaseInfo) ?: return null
    val seasonNumber = season?.takeIf { it > 0 }
    val episodeNumber = episode?.takeIf { it > 0 }
    val episodeLabel = when {
        seasonNumber != null && episodeNumber != null -> "S${seasonNumber}E${episodeNumber}"
        episodeNumber != null -> "E$episodeNumber"
        else -> null
    }
    val subtitle = listOfNotNull(episodeLabel, title.takeIf { it.isNotBlank() })
        .joinToString(" - ")
        .takeIf { it.isNotBlank() }
    return LibraryCalendarEvent(
        key = "episode:${item.type}:${item.id}:${season ?: 0}:${episode ?: id}:${date.iso}",
        date = date,
        rawReleaseInfo = rawReleaseInfo,
        item = item,
        title = item.name,
        subtitle = subtitle,
        imageUrl = thumbnail ?: item.banner ?: item.poster,
        sortTitle = "${item.name} ${season ?: 0} ${episode ?: 0} $title",
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )
}

private fun LibraryItem.isLibrarySeries(): Boolean =
    type.equals("series", ignoreCase = true) ||
        type.equals("tv", ignoreCase = true) ||
        type.equals("show", ignoreCase = true) ||
        type.equals("tvshow", ignoreCase = true)

private fun parseLibraryCalendarDate(raw: String?): LibraryCalendarDate? {
    val datePart = raw
        ?.trim()
        ?.substringBefore('T')
        ?.takeIf { it.length == 10 }
        ?: return null
    val parts = datePart.split('-')
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull()?.takeIf { it in 1000..9999 } ?: return null
    val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = parts[2].toIntOrNull()?.takeIf { it in 1..daysInLibraryCalendarMonth(year, month) } ?: return null
    return LibraryCalendarDate(year, month, day)
}

private fun initialLibraryCalendarMonth(): LibraryCalendarMonth {
    val today = parseLibraryCalendarDate(CurrentDateProvider.todayIsoDate())
        ?: LibraryCalendarDate(1970, 1, 1)
    return LibraryCalendarMonth(today.year, today.month)
}

private fun defaultLibraryCalendarSelectedDate(
    monthEvents: List<LibraryCalendarEvent>,
    month: LibraryCalendarMonth,
    todayIso: String,
): String? {
    val isCurrentMonth = todayIso.take(7) == month.key
    return monthEvents
        .firstOrNull { event -> !isCurrentMonth || event.date.iso >= todayIso }
        ?.date
        ?.iso
        ?: monthEvents.firstOrNull()?.date?.iso
}

private fun defaultLibraryCalendarSelection(
    events: List<LibraryCalendarEvent>,
    month: LibraryCalendarMonth,
    todayIso: String,
): LibraryCalendarSelection {
    val monthEvents = events
        .filter { event -> event.date.year == month.year && event.date.month == month.month }
        .sortedWith(compareBy<LibraryCalendarEvent> { it.date.iso }.thenBy { it.sortTitle.lowercase() })
    return LibraryCalendarSelection(
        month = month,
        dateIso = defaultLibraryCalendarSelectedDate(monthEvents, month, todayIso)
            ?: LibraryCalendarDate(month.year, month.month, 1).iso,
    )
}

private fun displayLibraryCalendarEventDate(date: LibraryCalendarDate): String =
    "${localizedMonthName(date.month)} ${date.day}, ${date.year}"

private fun libraryCalendarCells(month: LibraryCalendarMonth): List<LibraryCalendarDate?> {
    val firstDayOffset = firstLibraryCalendarWeekdayOffset(month.year, month.month)
    val days = daysInLibraryCalendarMonth(month.year, month.month)
    val cells = MutableList<LibraryCalendarDate?>(firstDayOffset) { null }
    for (day in 1..days) {
        cells += LibraryCalendarDate(month.year, month.month, day)
    }
    while (cells.size < 42) {
        cells += null
    }
    return cells
}

private fun firstLibraryCalendarWeekdayOffset(year: Int, month: Int): Int {
    val epochDay = isoEpochDay(LibraryCalendarDate(year, month, 1).iso)
    val raw = (epochDay + 4L) % 7L
    return if (raw < 0L) (raw + 7L).toInt() else raw.toInt()
}

private fun daysInLibraryCalendarMonth(year: Int, month: Int): Int =
    when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLibraryCalendarLeapYear(year)) 29 else 28
        else -> 30
    }

private fun isLibraryCalendarLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

private fun isoEpochDay(date: String): Long {
    val year = date.substring(0, 4).toLong()
    val month = date.substring(5, 7).toLong()
    val day = date.substring(8, 10).toLong()

    val adjustedYear = year - if (month <= 2L) 1L else 0L
    val era = if (adjustedYear >= 0L) adjustedYear / 400L else (adjustedYear - 399L) / 400L
    val yearOfEra = adjustedYear - era * 400L
    val adjustedMonth = month + if (month > 2L) -3L else 9L
    val dayOfYear = (153L * adjustedMonth + 2L) / 5L + day - 1L
    val dayOfEra = yearOfEra * 365L + yearOfEra / 4L - yearOfEra / 100L + dayOfYear
    return era * 146_097L + dayOfEra - 719_468L
}

/** An episode from the Library release calendar, exposed for other screens (Profile Insight). */
internal data class LibraryUpcomingEpisode(
    val key: String,
    val item: LibraryItem,
    val dateIso: String,
    val subtitle: String?,
    val imageUrl: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
)

internal suspend fun warmLibraryReleaseSchedule(items: List<LibraryItem>) {
    if (items.isNotEmpty()) LibraryReleaseCalendarCache.warm(items)
}

/**
 * Episodes of saved series airing from today through the next [days] days (today included),
 * read from the same release-calendar cache the Library calendar shows, so both always agree.
 */
internal fun libraryUpcomingEpisodesFlow(days: Int = 7): Flow<List<LibraryUpcomingEpisode>> =
    LibraryReleaseCalendarCache.state.map { state -> state.events.upcomingEpisodes(days) }

private fun List<LibraryCalendarEvent>.upcomingEpisodes(days: Int): List<LibraryUpcomingEpisode> {
    val today = parseLibraryCalendarDate(CurrentDateProvider.todayIsoDate()) ?: return emptyList()
    val windowIsoDates = (0 until days).map { offset -> libraryCalendarDatePlusDays(today, offset).iso }.toSet()
    return asSequence()
        .filter { event -> event.key.startsWith("episode:") && event.date.iso in windowIsoDates }
        .distinctBy { it.key }
        .sortedWith(compareBy<LibraryCalendarEvent> { it.date.iso }.thenBy { it.sortTitle.lowercase() })
        .map { event ->
            LibraryUpcomingEpisode(
                key = event.key,
                item = event.item,
                dateIso = event.date.iso,
                subtitle = event.subtitle,
                imageUrl = event.imageUrl,
                seasonNumber = event.seasonNumber,
                episodeNumber = event.episodeNumber,
            )
        }
        .toList()
}

@Serializable
private data class StoredLibraryCalendarEvent(
    val key: String,
    val dateIso: String,
    val rawReleaseInfo: String,
    val itemType: String,
    val itemId: String,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val sortTitle: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

@Serializable
private data class StoredLibraryReleaseSchedule(
    val cacheKey: String,
    val savedOnIsoDate: String,
    val loadedMonthKeys: Set<String> = emptySet(),
    val events: List<StoredLibraryCalendarEvent> = emptyList(),
)

private class RestoredLibraryReleaseSchedule(
    val cacheKey: String,
    val savedOnIsoDate: String,
    val loadedMonthKeys: Set<String>,
    val events: List<LibraryCalendarEvent>,
)

private object LibraryReleaseSchedulePersistence {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(state: LibraryReleaseCalendarCacheState) {
        val cacheKey = state.cacheKey ?: return
        runCatching {
            val payload = StoredLibraryReleaseSchedule(
                cacheKey = cacheKey,
                savedOnIsoDate = CurrentDateProvider.todayIsoDate(),
                loadedMonthKeys = state.loadedMonthKeys,
                events = state.events.map { event ->
                    StoredLibraryCalendarEvent(
                        key = event.key,
                        dateIso = event.date.iso,
                        rawReleaseInfo = event.rawReleaseInfo,
                        itemType = event.item.type,
                        itemId = event.item.id,
                        title = event.title,
                        subtitle = event.subtitle,
                        imageUrl = event.imageUrl,
                        sortTitle = event.sortTitle,
                        seasonNumber = event.seasonNumber,
                        episodeNumber = event.episodeNumber,
                    )
                },
            )
            LibraryReleaseScheduleStorage.savePayload(json.encodeToString(StoredLibraryReleaseSchedule.serializer(), payload))
        }
    }

    fun load(items: List<LibraryItem>): RestoredLibraryReleaseSchedule? {
        val raw = LibraryReleaseScheduleStorage.loadPayload() ?: return null
        val stored = runCatching {
            json.decodeFromString(StoredLibraryReleaseSchedule.serializer(), raw)
        }.getOrNull() ?: return null
        val itemsByKey = items.associateBy { item -> "${item.type.lowercase()}:${item.id}" }
        val events = stored.events.mapNotNull { event ->
            val item = itemsByKey["${event.itemType.lowercase()}:${event.itemId}"] ?: return@mapNotNull null
            val date = parseLibraryCalendarDate(event.dateIso) ?: return@mapNotNull null
            LibraryCalendarEvent(
                key = event.key,
                date = date,
                rawReleaseInfo = event.rawReleaseInfo,
                item = item,
                title = event.title,
                subtitle = event.subtitle,
                imageUrl = event.imageUrl,
                sortTitle = event.sortTitle,
                seasonNumber = event.seasonNumber,
                episodeNumber = event.episodeNumber,
            )
        }
        return RestoredLibraryReleaseSchedule(
            cacheKey = stored.cacheKey,
            savedOnIsoDate = stored.savedOnIsoDate,
            loadedMonthKeys = stored.loadedMonthKeys,
            events = events,
        )
    }
}

private fun libraryCalendarDatePlusDays(date: LibraryCalendarDate, days: Int): LibraryCalendarDate {
    var year = date.year
    var month = date.month
    var day = date.day + days
    while (day > daysInLibraryCalendarMonth(year, month)) {
        day -= daysInLibraryCalendarMonth(year, month)
        if (month == 12) {
            month = 1
            year += 1
        } else {
            month += 1
        }
    }
    return LibraryCalendarDate(year, month, day)
}
