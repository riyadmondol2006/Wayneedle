package app.wayneedle.ui.tripdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
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
import app.wayneedle.ui.components.ExpressiveButton
import app.wayneedle.ui.components.ExpressiveButtonStyle
import app.wayneedle.ui.components.NeedleLoadingIndicator
import app.wayneedle.ui.components.TitleScaffold
import app.wayneedle.ui.dashboard.DistanceStatTile
import app.wayneedle.ui.dashboard.DurationStatTile
import app.wayneedle.ui.dashboard.SpeedStatTile
import app.wayneedle.ui.dashboard.TotalTimeStatTile
import app.wayneedle.ui.format.UiFormatter
import app.wayneedle.ui.format.rememberUiFormatter
import app.wayneedle.ui.icons.WayneedleIcons

private const val ONE_COLUMN_FONT_SCALE = 1.5f

@Composable
fun TripDetailRoute(
    tripId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: TripDetailViewModel = viewModel(factory = TripDetailViewModel.factory(tripId))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state is TripDetailUiState.Deleted) onBack()
    }
    TripDetailScreen(
        state = state,
        onBack = onBack,
        onRequestDelete = viewModel::requestDelete,
        onConfirmDelete = viewModel::confirmDelete,
        onCancelDelete = viewModel::cancelDelete,
        onDismissDeleteError = viewModel::dismissDeleteError,
        modifier = modifier,
    )
}

@Composable
fun TripDetailScreen(
    state: TripDetailUiState,
    onBack: () -> Unit,
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onDismissDeleteError: () -> Unit = {},
) {
    val formatter = rememberUiFormatter()
    val loaded = state as? TripDetailUiState.Loaded
    TitleScaffold(
        title = stringResource(R.string.trip_detail_title),
        modifier = modifier,
        large = false,
        onBack = onBack,
        actions = {
            if (loaded != null) {
                IconButton(onClick = onRequestDelete) {
                    Icon(WayneedleIcons.Delete, contentDescription = stringResource(R.string.action_delete))
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when (state) {
                TripDetailUiState.Loading, TripDetailUiState.Deleted -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state == TripDetailUiState.Loading) NeedleLoadingIndicator(size = 48.dp)
                }

                TripDetailUiState.NotFound -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = WayneedleIcons.Warning,
                        title = stringResource(R.string.trip_not_found_title),
                        body = stringResource(R.string.trip_not_found_body),
                    )
                }

                is TripDetailUiState.Loaded -> TripDetailContent(state.trip, state.unit, formatter, onRequestDelete)
            }
        }
    }
    if (loaded != null && loaded.confirmingDelete) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_delete_trip_title),
            text = stringResource(
                R.string.dialog_delete_trip_body,
                formatter.dateTime(loaded.trip.startedAtEpochMillis),
            ),
            confirmLabel = stringResource(R.string.action_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = onConfirmDelete,
            onDismiss = onCancelDelete,
            destructive = true,
            icon = WayneedleIcons.Delete,
        )
    }
    if (loaded != null && loaded.deleteFailed) DeleteFailedDialog(onDismissDeleteError)
}

@Composable
private fun TripDetailContent(
    trip: Trip,
    unit: SpeedUnit,
    formatter: UiFormatter,
    onRequestDelete: () -> Unit,
) {
    val stats = trip.stats
    val columns = if (LocalDensity.current.fontScale >= ONE_COLUMN_FONT_SCALE) 1 else 2
    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = formatter.longDate(trip.startedAtEpochMillis),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val topLabel = stringResource(R.string.stat_top)
        val averageLabel = stringResource(R.string.stat_average)
        val tiles = listOf<@Composable (Modifier) -> Unit>(
            { m -> DistanceStatTile(stats.distanceMeters, unit, formatter, m) },
            { m -> DurationStatTile(stats.activeDurationMillis, formatter, m) },
            { m -> SpeedStatTile(topLabel, stats.maxSpeedMps, unit, formatter, m) },
            { m -> SpeedStatTile(averageLabel, stats.averageSpeedMps, unit, formatter, m) },
        )
        tiles.chunked(columns).forEach { rowTiles ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowTiles.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - rowTiles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        TotalTimeStatTile(trip.totalDurationMillis, formatter, Modifier.fillMaxWidth())
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailRow(stringResource(R.string.detail_started), formatter.dateTime(trip.startedAtEpochMillis))
                DetailRow(stringResource(R.string.detail_finished), formatter.dateTime(trip.endedAtEpochMillis))
            }
        }
        ExpressiveButton(
            label = stringResource(R.string.action_delete_trip),
            icon = WayneedleIcons.Delete,
            style = ExpressiveButtonStyle.DESTRUCTIVE_TEXT,
            onClick = onRequestDelete,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$label, $value" },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
