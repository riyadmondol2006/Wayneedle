package app.wayneedle.ui.history

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
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
import app.wayneedle.ui.components.InfoDialog
import app.wayneedle.ui.components.NeedleLoadingIndicator
import app.wayneedle.ui.components.StatTile
import app.wayneedle.ui.components.TitleScaffold
import app.wayneedle.ui.dashboard.DistanceStatTile
import app.wayneedle.ui.dashboard.DurationStatTile
import app.wayneedle.ui.dashboard.SpeedStatTile
import app.wayneedle.ui.format.UiFormatter
import app.wayneedle.ui.format.distanceLabelRes
import app.wayneedle.ui.format.rememberUiFormatter
import app.wayneedle.ui.format.speedLabelRes
import app.wayneedle.ui.format.spokenDistance
import app.wayneedle.ui.format.spokenDuration
import app.wayneedle.ui.format.spokenSpeed
import app.wayneedle.ui.icons.WayneedleIcons
import app.wayneedle.ui.theme.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
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
    val export = rememberTripExport(state)
    HistoryScreen(
        state = state,
        onExport = export.start,
        onOpenTrip = onOpenTrip,
        onRequestDelete = viewModel::requestDelete,
        onConfirmDelete = viewModel::confirmDelete,
        onCancelDelete = viewModel::cancelDelete,
        onDismissDeleteError = viewModel::dismissDeleteError,
        modifier = modifier,
    )
    if (export.failed) {
        InfoDialog(
            title = stringResource(R.string.export_failed_title),
            text = stringResource(R.string.export_failed_body),
            buttonLabel = stringResource(R.string.action_ok),
            onDismiss = export.dismissFailure,
            icon = WayneedleIcons.Warning,
        )
    }
}

/** The "export as CSV" flow: the system file picker, then writing off the main thread. */
private class TripExport(val start: () -> Unit, val failed: Boolean, val dismissFailure: () -> Unit)

@Composable
private fun rememberTripExport(state: HistoryUiState): TripExport {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val currentState by rememberUpdatedState(state)
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(CSV_MIME_TYPE)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val trips = currentState.allTrips
        val csv = tripsToCsv(trips, currentState.unit, ZoneId.systemDefault())
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray()) } != null
                } catch (_: IOException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            }
            if (written) {
                val message = resources.getQuantityString(R.plurals.export_done, trips.size, trips.size)
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } else {
                failed = true
            }
        }
    }
    return TripExport(
        start = {
            try {
                picker.launch(csvFileName(System.currentTimeMillis(), ZoneId.systemDefault()))
            } catch (_: ActivityNotFoundException) {
                failed = true
            }
        },
        failed = failed,
        dismissFailure = { failed = false },
    )
}

private const val CSV_MIME_TYPE = "text/csv"

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onOpenTrip: (String) -> Unit,
    onRequestDelete: (String) -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onDismissDeleteError: () -> Unit = {},
    onExport: () -> Unit = {},
) {
    val formatter = rememberUiFormatter()
    TitleScaffold(
        title = stringResource(R.string.nav_history),
        modifier = modifier,
        actions = {
            if (state.totals != null) {
                IconButton(onClick = onExport) {
                    Icon(WayneedleIcons.Download, contentDescription = stringResource(R.string.action_export_trips))
                }
            }
        },
    ) { padding ->
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
        state.totals?.let { totals ->
            item(key = "totals", contentType = "totals") { TotalsCard(totals, state.unit, formatter) }
        }
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
private fun TotalsCard(totals: HistoryTotals, unit: SpeedUnit, formatter: UiFormatter) {
    val columns = if (LocalDensity.current.fontScale >= ONE_COLUMN_FONT_SCALE) 1 else 2
    val tiles = listOf<@Composable (Modifier) -> Unit>(
        { m -> DistanceStatTile(totals.distanceMeters, unit, formatter, m, large = false) },
        { m -> DurationStatTile(totals.activeDurationMillis, formatter, m, large = false) },
        { m -> SpeedStatTile(stringResource(R.string.stat_top), totals.topSpeedMps, unit, formatter, m) },
        { m ->
            StatTile(
                label = stringResource(R.string.stat_trips),
                value = formatter.count(totals.tripCount),
                modifier = m,
            )
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.history_totals_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp).semantics { heading() },
        )
        tiles.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val ONE_COLUMN_FONT_SCALE = 1.5f

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
