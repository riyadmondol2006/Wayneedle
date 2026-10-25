package app.wayneedle.ui.format

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import app.wayneedle.R
import app.wayneedle.domain.model.SpeedUnit
import kotlin.math.abs

/** Short visible label for a speed, e.g. `km/h`. */
val SpeedUnit.speedLabelRes: Int
    @StringRes get() = when (this) {
        SpeedUnit.KMH -> R.string.unit_speed_kmh
        SpeedUnit.MPH -> R.string.unit_speed_mph
    }

/** Short visible label for a distance, e.g. `km`. */
val SpeedUnit.distanceLabelRes: Int
    @StringRes get() = when (this) {
        SpeedUnit.KMH -> R.string.unit_distance_km
        SpeedUnit.MPH -> R.string.unit_distance_mi
    }

/** Spoken speed with a `%1$s` value placeholder, e.g. "54 kilometres per hour". */
val SpeedUnit.speedSpokenRes: Int
    @PluralsRes get() = when (this) {
        SpeedUnit.KMH -> R.plurals.spoken_speed_kmh
        SpeedUnit.MPH -> R.plurals.spoken_speed_mph
    }

/** Spoken distance with a `%1$s` value placeholder, e.g. "12.34 kilometres". */
val SpeedUnit.distanceSpokenRes: Int
    @PluralsRes get() = when (this) {
        SpeedUnit.KMH -> R.plurals.spoken_distance_km
        SpeedUnit.MPH -> R.plurals.spoken_distance_mi
    }

/** Accuracy radius text with a `%1$s` value placeholder, e.g. "±5 m". */
val SpeedUnit.accuracyRes: Int
    @StringRes get() = when (this) {
        SpeedUnit.KMH -> R.string.gps_accuracy_m
        SpeedUnit.MPH -> R.string.gps_accuracy_ft
    }

private const val SINGULAR_TOLERANCE = 0.005

/** Plural quantity for a two-decimal value: "one" only for exactly 1.00, otherwise "other". */
fun pluralQuantityOf(value: Double): Int = if (abs(value - 1.0) < SINGULAR_TOLERANCE) 1 else 2
