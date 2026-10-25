package app.wayneedle.service

import app.wayneedle.domain.model.LiveSpeed
import app.wayneedle.domain.model.LocationPermissionState
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.TrackingState
import app.wayneedle.domain.model.TripPhase
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** The single button shown on the ongoing notification. */
internal enum class NotificationAction {
    PAUSE,
    RESUME,
}

/**
 * Already-resolved user-facing strings. The Android adapter fills this from `strings_service.xml`;
 * tests pass literals, so all formatting below stays free of `android.*`.
 *
 * Format strings use positional `%1$s` placeholders: speed/status, distance, duration for
 * [recordingFormat]; distance, duration for [pausedFormat]; an already localised number for the unit formats.
 */
internal data class NotificationLabels(
    val titleApp: String,
    val titleRecording: String,
    val titlePaused: String,
    val starting: String,
    val waitingForGps: String,
    val gpsOff: String,
    val permissionNeeded: String,
    val recordingFormat: String,
    val pausedFormat: String,
    val speedKmhFormat: String,
    val speedMphFormat: String,
    val distanceKmFormat: String,
    val distanceMiFormat: String,
)

/**
 * Everything visible in the ongoing notification. Equality is the "did anything visible change?"
 * test used by [NotificationUpdateGate], so keep this free of timestamps and other invisible noise.
 */
internal data class NotificationContent(
    val phase: TripPhase,
    val title: String,
    val text: String,
    val action: NotificationAction?,
) {
    companion object {
        /** Distances below this many units are shown with two decimals, larger ones with one. */
        private const val TWO_DECIMALS_BELOW = 9.995

        private const val MILLIS_PER_SECOND = 1_000L
        private const val SECONDS_PER_MINUTE = 60L
        private const val SECONDS_PER_HOUR = 3_600L

        /**
         * Content for the current [state], or null when no trip is being recorded (IDLE / FINISHED),
         * in which case there is nothing to show.
         *
         * The speed is only ever a real, accepted GPS reading; without one the text says why
         * (no permission, GPS off, still searching) instead of showing a made-up number.
         */
        fun from(
            state: TrackingState,
            unit: SpeedUnit,
            labels: NotificationLabels,
            locale: Locale,
        ): NotificationContent? {
            val phase = state.trip.phase
            val stats = state.trip.stats
            val distance = formatDistance(stats.distanceMeters, unit, labels, locale)
            val duration = formatDuration(stats.activeDurationMillis, locale)
            return when (phase) {
                TripPhase.IDLE, TripPhase.FINISHED -> null

                TripPhase.RUNNING -> NotificationContent(
                    phase = phase,
                    title = labels.titleRecording,
                    text = labels.recordingFormat.formatWith(
                        locale,
                        leadingText(state, unit, labels, locale),
                        distance,
                        duration,
                    ),
                    action = NotificationAction.PAUSE,
                )

                TripPhase.PAUSED -> NotificationContent(
                    phase = phase,
                    title = labels.titlePaused,
                    text = labels.pausedFormat.formatWith(locale, distance, duration),
                    action = NotificationAction.RESUME,
                )
            }
        }

        /**
         * Unit-independent content used for the very first `startForeground` call, which must not wait
         * for settings to load. Replaced by [from] within milliseconds.
         */
        fun placeholder(phase: TripPhase, labels: NotificationLabels): NotificationContent = NotificationContent(
            phase = phase,
            title = when (phase) {
                TripPhase.RUNNING -> labels.titleRecording
                TripPhase.PAUSED -> labels.titlePaused
                TripPhase.IDLE, TripPhase.FINISHED -> labels.titleApp
            },
            text = labels.starting,
            action = when (phase) {
                TripPhase.RUNNING -> NotificationAction.PAUSE
                TripPhase.PAUSED -> NotificationAction.RESUME
                TripPhase.IDLE, TripPhase.FINISHED -> null
            },
        )

        private fun leadingText(
            state: TrackingState,
            unit: SpeedUnit,
            labels: NotificationLabels,
            locale: Locale,
        ): String {
            val speed = state.liveSpeed
            if (speed is LiveSpeed.Available && speed.smoothedMps.isFinite() && speed.smoothedMps >= 0f) {
                return formatSpeed(speed.smoothedMps.toDouble(), unit, labels, locale)
            }
            return when {
                state.permission != LocationPermissionState.GRANTED_PRECISE -> labels.permissionNeeded
                !state.gps.providerEnabled -> labels.gpsOff
                else -> labels.waitingForGps
            }
        }

        internal fun formatSpeed(
            metersPerSecond: Double,
            unit: SpeedUnit,
            labels: NotificationLabels,
            locale: Locale,
        ): String {
            val value = nonNegative(unit.fromMetersPerSecond(metersPerSecond))
            val number = formatNumber(value, fractionDigits = 0, locale = locale)
            val format = if (unit == SpeedUnit.KMH) labels.speedKmhFormat else labels.speedMphFormat
            return format.formatWith(locale, number)
        }

        internal fun formatDistance(
            meters: Double,
            unit: SpeedUnit,
            labels: NotificationLabels,
            locale: Locale,
        ): String {
            val value = nonNegative(unit.fromMeters(meters))
            val digits = if (value < TWO_DECIMALS_BELOW) 2 else 1
            val number = formatNumber(value, fractionDigits = digits, locale = locale)
            val format = if (unit == SpeedUnit.KMH) labels.distanceKmFormat else labels.distanceMiFormat
            return format.formatWith(locale, number)
        }

        /** `m:ss` below one hour, `h:mm:ss` from one hour. Negative input is treated as zero. */
        internal fun formatDuration(millis: Long, locale: Locale): String {
            val totalSeconds = millis.coerceAtLeast(0L) / MILLIS_PER_SECOND
            val hours = totalSeconds / SECONDS_PER_HOUR
            val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
            val seconds = totalSeconds % SECONDS_PER_MINUTE
            val plain = NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = false }
            val twoDigits = NumberFormat.getIntegerInstance(locale).apply {
                isGroupingUsed = false
                minimumIntegerDigits = 2
            }
            return if (hours > 0) {
                "${plain.format(hours)}:${twoDigits.format(minutes)}:${twoDigits.format(seconds)}"
            } else {
                "${plain.format(minutes)}:${twoDigits.format(seconds)}"
            }
        }

        private fun nonNegative(value: Double): Double = if (value > 0.0) value else 0.0

        private fun formatNumber(value: Double, fractionDigits: Int, locale: Locale): String =
            NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = fractionDigits
                maximumFractionDigits = fractionDigits
                roundingMode = RoundingMode.HALF_UP
            }.format(value)

        private fun String.formatWith(locale: Locale, vararg args: Any): String = String.format(locale, this, *args)
    }
}
