package app.wayneedle.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.wayneedle.R
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.Trip
import app.wayneedle.ui.components.ConfirmDialog
import app.wayneedle.ui.components.DeleteFailedDialog
import app.wayneedle.ui.components.EmptyState
import app.wayneedle.ui.components.NeedleLoadingIndicator
import app.wayneedle.ui.components.TitleScaffold
import app.wayneedle.ui.format.UiFormatter
import app.wayneedle.ui.format.distanceLabelRes
import app.wayneedle.ui.format.rememberUiFormatter
import app.wayneedle.ui.format.speedLabelRes
import app.wayneedle.ui.format.spokenDistance
import app.wayneedle.ui.format.spokenDuration
import app.wayneedle.ui.format.spokenSpeed
import app.wayneedle.ui.icons.WayneedleIcons
import app.wayneedle.ui.theme.tabular
import java.time.LocalDate
import java.time.ZoneId

/** Test tag of the trip list. */
const val HISTORY_LIST_TAG = "history_list"

@Composable
fun HistoryRoute(
    onOpenTrip: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HistoryScreen(
        state = state,
        onOpenTrip = onOpenTrip,
        onRequestDelete = viewModel::requestDelete,
        onConfirmDelete = viewModel::confirmDelete,
        onCancelDelete = viewModel::cancelDelete,
        onDismissDeleteError = viewModel::dismissDeleteError,
        modifier = modifier,
    )
}

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onOpenTrip: (String) -> Unit,
    onRequestDelete: (String) -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onDismissDeleteError: () -> Unit = {},
) {
    val formatter = rememberUiFormatter()
    TitleScaffold(title = stringResource(R.string.nav_history), modifier = modifier) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                NeedleLoadingIndicator(size = 48.dp)
            }

            state.loadFailed -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = WayneedleIcons.Warning,
                    title = stringResource(R.string.history_load_failed_title),
                    body = stringResource(R.string.history_load_failed_body),
                )
            }

            state.isEmpty -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = WayneedleIcons.History,
                    title = stringResource(R.string.history_empty_title),
                    body = stringResource(R.string.history_empty_body),
                )
            }

            else -> TripList(state, formatter, padding, onOpenTrip, onRequestDelete)
        }
    }
    state.pendingDelete?.let { trip ->
        ConfirmDialog(
            title = stringResource(R.string.dialog_delete_trip_title),
            text = stringResource(
                R.string.dialog_delete_trip_body,
                formatter.dateTime(trip.startedAtEpochMillis),
            ),
            confirmLabel = stringResource(R.string.action_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = onConfirmDelete,
            onDismiss = onCancelDelete,
            destructive = true,
            icon = WayneedleIcons.Delete,
        )
    }
    if (state.deleteFailed) DeleteFailedDialog(onDismissDeleteError)
}

@Composable
private fun TripList(
    state: HistoryUiState,
    formatter: UiFormatter,
    padding: PaddingValues,
    onOpenTrip: (String) -> Unit,
    onRequestDelete: (String) -> Unit,
) {
    val today = LocalDate.now(ZoneId.systemDefault())
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(HISTORY_LIST_TAG),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.days.forEach { day ->
            item(key = "day-${day.date}", contentType = "day") {
                Text(
                    text = dayLabel(day.date, today, formatter),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
                        .semantics { heading() },
                )
            }
            items(day.trips, key = { it.id }, contentType = { "trip" }) { trip ->
                TripCard(
                    trip = trip,
                    unit = state.unit,
                    formatter = formatter,
                    onOpen = { onOpenTrip(trip.id) },
                    onDelete = { onRequestDelete(trip.id) },
                )
            }
        }
    }
}

@Composable
private fun dayLabel(date: LocalDate, today: LocalDate, formatter: UiFormatter): String = when (date) {
    today -> stringResource(R.string.day_today)
    today.minusDays(1) -> stringResource(R.string.day_yesterday)
    else -> formatter.fullDate(date)
}

@Composable
private fun TripCard(
    trip: Trip,
    unit: SpeedUnit,
    formatter: UiFormatter,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stats = trip.stats
    val time = formatter.time(trip.startedAtEpochMillis)
    val summary = stringResource(
        R.string.spoken_trip_summary,
        formatter.dateTime(trip.startedAtEpochMillis),
        spokenDistance(stats.distanceMeters, unit, formatter),
        spokenDuration(stats.activeDurationMillis, formatter),
        spokenSpeed(stats.maxSpeedMps, unit, formatter),
        spokenSpeed(stats.averageSpeedMps, unit, formatter),
    )
    val openLabel = stringResource(R.string.action_open_trip)
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 72.dp)
                    .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
                    .semantics(mergeDescendants = true) { contentDescription = summary }
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${formatter.distance(stats.distanceMeters, unit)} ${stringResource(unit.distanceLabelRes)}",
                    style = MaterialTheme.typography.headlineSmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        R.string.history_trip_meta,
                        formatter.duration(stats.activeDurationMillis),
                        formatter.speed(stats.maxSpeedMps, unit),
                        formatter.speed(stats.averageSpeedMps, unit),
                        stringResource(unit.speedLabelRes),
                    ),
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = WayneedleIcons.Delete,
                    contentDescription = stringResource(
                        R.string.action_delete_trip_from,
                        formatter.dateTime(trip.startedAtEpochMillis),
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
