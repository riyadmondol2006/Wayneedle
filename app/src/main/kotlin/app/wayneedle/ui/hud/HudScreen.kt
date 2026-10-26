package app.wayneedle.ui.hud

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.wayneedle.R
import app.wayneedle.alert.signalSpeedAlert
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.ui.dashboard.DashboardViewModel
import app.wayneedle.ui.dashboard.GaugeUiState
import app.wayneedle.ui.format.PLACEHOLDER
import app.wayneedle.ui.format.rememberUiFormatter
import app.wayneedle.ui.format.speedLabelRes
import app.wayneedle.ui.icons.WayneedleIcons
import app.wayneedle.ui.speedometer.SpeedReading
import app.wayneedle.ui.theme.tabular

/** HUD colours are fixed: white on black is what reflects legibly in a windscreen at night. */
private val HudForeground = Color.White
private val HudAlert = Color(0xFFFF5252)
private val HudDim = Color(0xFF9E9E9E)

/**
 * Head-up display: the speed alone, as large as the screen allows, white on black. Mirror flips it left to
 * right, so it reads correctly as a reflection in the windscreen when the phone lies on the dashboard with its
 * top towards you. A tap shows or hides the controls; the screen stays on and the system bars are hidden while
 * it is open. The speed alert still works here.
 */
@Composable
fun HudRoute(onExit: () -> Unit) {
    val viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel, context) {
        viewModel.speedAlerts.collect { sound -> context.signalSpeedAlert(sound) }
    }
    ImmersiveAndAwakeEffect()
    var mirrored by rememberSaveable { mutableStateOf(false) }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    HudScreen(
        gauge = state.gauge,
        unit = state.unit,
        mirrored = mirrored,
        controlsVisible = controlsVisible,
        onToggleControls = { controlsVisible = !controlsVisible },
        onMirroredChange = { mirrored = it },
        onExit = onExit,
    )
}

@Composable
fun HudScreen(
    gauge: GaugeUiState,
    unit: SpeedUnit,
    mirrored: Boolean,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onMirroredChange: (Boolean) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = rememberUiFormatter()
    val reading = gauge.reading
    val speedText = when (reading) {
        is SpeedReading.Available -> formatter.speedInUnit(reading.valueInUnit.toDouble())
        is SpeedReading.Unavailable -> PLACEHOLDER
    }
    val unitLabel = stringResource(unit.speedLabelRes)
    val spoken = "$speedText $unitLabel"
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = stringResource(R.string.hud_toggle_controls),
                onClick = onToggleControls,
            ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = if (mirrored) -1f else 1f }
                .semantics { contentDescription = spoken },
            contentAlignment = Alignment.Center,
        ) {
            val digitsSize = with(LocalDensity.current) { (minOf(maxWidth * 0.42f, maxHeight * 0.62f)).toSp() }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = speedText,
                    style = MaterialTheme.typography.displayLarge.tabular().copy(
                        fontSize = digitsSize,
                        lineHeight = digitsSize,
                    ),
                    color = if (gauge.overLimit) HudAlert else HudForeground,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(text = unitLabel, style = MaterialTheme.typography.headlineMedium, color = HudDim)
            }
        }
        if (controlsVisible) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                contentColor = HudForeground,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalIconButton(onClick = onExit, shape = CircleShape) {
                        Icon(WayneedleIcons.Close, contentDescription = stringResource(R.string.hud_exit))
                    }
                    Text(
                        text = stringResource(R.string.hud_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = HudDim,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalIconToggleButton(checked = mirrored, onCheckedChange = onMirroredChange) {
                        Icon(WayneedleIcons.Flip, contentDescription = stringResource(R.string.hud_mirror))
                    }
                }
            }
        }
    }
}

/** Hides the system bars and keeps the screen on while the HUD is shown; restores both when it closes. */
@Composable
private fun ImmersiveAndAwakeEffect() {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    DisposableEffect(activity, view) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }
}
