package app.wayneedle.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.wayneedle.BuildConfig
import app.wayneedle.R
import app.wayneedle.domain.model.AppSettings
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.ThemeMode
import app.wayneedle.ui.components.ConfirmDialog
import app.wayneedle.ui.components.DeleteFailedDialog
import app.wayneedle.ui.components.ExpressiveButton
import app.wayneedle.ui.components.ExpressiveButtonStyle
import app.wayneedle.ui.components.TitleScaffold
import app.wayneedle.ui.components.UpdateDialog
import app.wayneedle.ui.format.rememberUiFormatter
import app.wayneedle.ui.format.speedLabelRes
import app.wayneedle.ui.icons.WayneedleIcons
import app.wayneedle.ui.theme.DynamicColorSupported
import app.wayneedle.ui.theme.tabular
import app.wayneedle.update.UpdateCheckResult

@Composable
fun SettingsRoute(modifier: Modifier = Modifier) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        callbacks = SettingsCallbacks(
            onSpeedUnit = viewModel::setSpeedUnit,
            onThemeMode = viewModel::setThemeMode,
            onDynamicColor = viewModel::setDynamicColor,
            onKeepScreenOn = viewModel::setKeepScreenOn,
            onSpeedAlertEnabled = viewModel::setSpeedAlertEnabled,
            onSpeedAlertSound = viewModel::setSpeedAlertSound,
            onStepSpeedAlertLimit = viewModel::stepSpeedAlertLimit,
            onRequestDeleteAll = viewModel::requestDeleteAll,
            onConfirmDeleteAll = viewModel::confirmDeleteAll,
            onCancelDeleteAll = viewModel::cancelDeleteAll,
            onDismissDeleteError = viewModel::dismissDeleteError,
            onUpdateCheckEnabled = viewModel::setUpdateCheckEnabled,
            onCheckForUpdatesNow = viewModel::checkForUpdatesNow,
            onDismissUpdateCheck = viewModel::dismissUpdateCheck,
        ),
        dynamicColorSupported = DynamicColorSupported,
        versionName = BuildConfig.VERSION_NAME,
        modifier = modifier,
    )
}

class SettingsCallbacks(
    val onSpeedUnit: (SpeedUnit) -> Unit = {},
    val onThemeMode: (ThemeMode) -> Unit = {},
    val onDynamicColor: (Boolean) -> Unit = {},
    val onKeepScreenOn: (Boolean) -> Unit = {},
    val onSpeedAlertEnabled: (Boolean) -> Unit = {},
    val onSpeedAlertSound: (Boolean) -> Unit = {},
    val onStepSpeedAlertLimit: (Int) -> Unit = {},
    val onRequestDeleteAll: () -> Unit = {},
    val onConfirmDeleteAll: () -> Unit = {},
    val onCancelDeleteAll: () -> Unit = {},
    val onDismissDeleteError: () -> Unit = {},
    val onUpdateCheckEnabled: (Boolean) -> Unit = {},
    val onCheckForUpdatesNow: () -> Unit = {},
    val onDismissUpdateCheck: () -> Unit = {},
)

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    callbacks: SettingsCallbacks,
    dynamicColorSupported: Boolean,
    versionName: String,
    modifier: Modifier = Modifier,
) {
    TitleScaffold(title = stringResource(R.string.nav_settings), modifier = modifier) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                UnitsSection(state.settings.speedUnit, callbacks.onSpeedUnit)
                SpeedAlertSection(state.settings, callbacks)
                AppearanceSection(state.settings, dynamicColorSupported, callbacks)
                DisplaySection(state.settings.keepScreenOn, callbacks.onKeepScreenOn)
                DataSection(state.tripCount, callbacks.onRequestDeleteAll)
                UpdatesSection(state, versionName, callbacks)
                AboutSection(versionName)
            }
        }
    }
    if (state.confirmingDeleteAll) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_delete_all_title),
            text = pluralStringResource(R.plurals.dialog_delete_all_body, state.tripCount, state.tripCount),
            confirmLabel = stringResource(R.string.action_delete_all),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = callbacks.onConfirmDeleteAll,
            onDismiss = callbacks.onCancelDeleteAll,
            destructive = true,
            icon = WayneedleIcons.Delete,
        )
    }
    if (state.deleteFailed) DeleteFailedDialog(callbacks.onDismissDeleteError)
    val updateResult = (state.manualUpdateCheck as? ManualUpdateCheck.Done)?.result
    if (updateResult is UpdateCheckResult.Available) {
        UpdateDialog(updateResult.release, onDismiss = callbacks.onDismissUpdateCheck)
    }
}

@Composable
private fun UpdatesSection(state: SettingsUiState, versionName: String, callbacks: SettingsCallbacks) {
    SettingsSection(stringResource(R.string.settings_updates)) {
        SwitchRow(
            title = stringResource(R.string.settings_update_check),
            subtitle = stringResource(R.string.settings_update_check_summary),
            checked = state.settings.updateCheckEnabled,
            enabled = true,
            onCheckedChange = callbacks.onUpdateCheckEnabled,
        )
        val check = state.manualUpdateCheck
        val status = when (check) {
            ManualUpdateCheck.Idle -> null

            ManualUpdateCheck.Checking -> stringResource(R.string.update_checking)

            is ManualUpdateCheck.Done -> when (check.result) {
                UpdateCheckResult.UpToDate -> stringResource(R.string.update_up_to_date, versionName)
                UpdateCheckResult.Failed -> stringResource(R.string.update_failed)
                is UpdateCheckResult.Available -> null
            }
        }
        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        ExpressiveButton(
            label = stringResource(R.string.action_check_now),
            style = ExpressiveButtonStyle.TONAL,
            onClick = callbacks.onCheckForUpdatesNow,
            enabled = check != ManualUpdateCheck.Checking,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp).semantics { heading() },
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

@Composable
private fun UnitsSection(selected: SpeedUnit, onSelect: (SpeedUnit) -> Unit) {
    SettingsSection(stringResource(R.string.settings_units)) {
        Text(
            text = stringResource(R.string.settings_units_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Choice(
            options = listOf(
                SpeedUnit.KMH to stringResource(R.string.settings_unit_kmh),
                SpeedUnit.MPH to stringResource(R.string.settings_unit_mph),
            ),
            selected = selected,
            onSelect = onSelect,
        )
    }
}

@Composable
private fun SpeedAlertSection(settings: AppSettings, callbacks: SettingsCallbacks) {
    SettingsSection(stringResource(R.string.settings_speed_alert)) {
        SwitchRow(
            title = stringResource(R.string.settings_speed_alert_switch),
            subtitle = stringResource(R.string.settings_speed_alert_summary),
            checked = settings.speedAlertEnabled,
            enabled = true,
            onCheckedChange = callbacks.onSpeedAlertEnabled,
        )
        val enabled = settings.speedAlertEnabled
        val textColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        val value = speedAlertLimitInUnit(settings.speedAlertMps, settings.speedUnit)
        val formatter = rememberUiFormatter()
        val label = stringResource(
            R.string.settings_speed_alert_value,
            formatter.count(value),
            stringResource(settings.speedUnit.speedLabelRes),
        )
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_speed_alert_limit),
                style = MaterialTheme.typography.titleMedium,
                color = textColor,
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(onClick = { callbacks.onStepSpeedAlertLimit(-1) }, enabled = enabled) {
                Icon(WayneedleIcons.Remove, contentDescription = stringResource(R.string.action_lower_limit))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge.tabular(),
                color = textColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 96.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            FilledTonalIconButton(onClick = { callbacks.onStepSpeedAlertLimit(1) }, enabled = enabled) {
                Icon(WayneedleIcons.Add, contentDescription = stringResource(R.string.action_raise_limit))
            }
        }
        SwitchRow(
            title = stringResource(R.string.settings_speed_alert_sound),
            subtitle = stringResource(R.string.settings_speed_alert_sound_summary),
            checked = settings.speedAlertSound && enabled,
            enabled = enabled,
            onCheckedChange = callbacks.onSpeedAlertSound,
        )
    }
}

@Composable
private fun AppearanceSection(settings: AppSettings, dynamicColorSupported: Boolean, callbacks: SettingsCallbacks) {
    SettingsSection(stringResource(R.string.settings_appearance)) {
        Text(
            text = stringResource(R.string.settings_theme),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Choice(
            options = listOf(
                ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
            ),
            selected = settings.themeMode,
            onSelect = callbacks.onThemeMode,
        )
        SwitchRow(
            title = stringResource(R.string.settings_dynamic_color),
            subtitle = stringResource(
                if (dynamicColorSupported) {
                    R.string.settings_dynamic_color_summary
                } else {
                    R.string.settings_dynamic_color_unsupported
                },
            ),
            checked = settings.dynamicColor && dynamicColorSupported,
            enabled = dynamicColorSupported,
            onCheckedChange = callbacks.onDynamicColor,
        )
    }
}

@Composable
private fun DisplaySection(keepScreenOn: Boolean, onKeepScreenOn: (Boolean) -> Unit) {
    SettingsSection(stringResource(R.string.settings_display)) {
        SwitchRow(
            title = stringResource(R.string.settings_keep_screen_on),
            subtitle = stringResource(R.string.settings_keep_screen_on_summary),
            checked = keepScreenOn,
            enabled = true,
            onCheckedChange = onKeepScreenOn,
        )
    }
}

@Composable
private fun DataSection(tripCount: Int, onRequestDeleteAll: () -> Unit) {
    SettingsSection(stringResource(R.string.settings_data)) {
        Text(
            text = pluralStringResource(R.plurals.settings_trip_count, tripCount, tripCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExpressiveButton(
            label = stringResource(R.string.action_delete_all_trips),
            icon = WayneedleIcons.Delete,
            style = ExpressiveButtonStyle.DESTRUCTIVE_TEXT,
            onClick = onRequestDeleteAll,
            enabled = tripCount > 0,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AboutSection(versionName: String) {
    SettingsSection(stringResource(R.string.settings_about)) {
        Text(
            text = stringResource(R.string.settings_about_name_version, stringResource(R.string.app_name), versionName),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.settings_about_license),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.settings_about_offline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun <T> Choice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
