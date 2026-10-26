package app.wayneedle.domain

import app.wayneedle.domain.model.AppSettings
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    /** Emits [AppSettings.Default] values for anything unset; never throws on corrupt data. */
    val settings: Flow<AppSettings>

    suspend fun setSpeedUnit(unit: SpeedUnit)

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setDynamicColor(enabled: Boolean)

    suspend fun setKeepScreenOn(enabled: Boolean)

    suspend fun setSpeedAlertEnabled(enabled: Boolean)

    /** Clamped to [AppSettings.MIN_SPEED_ALERT_MPS]..[AppSettings.MAX_SPEED_ALERT_MPS]; non-finite is ignored. */
    suspend fun setSpeedAlertMps(limitMps: Float)

    suspend fun setUpdateCheckEnabled(enabled: Boolean)

    suspend fun setSpeedAlertSound(enabled: Boolean)
}
