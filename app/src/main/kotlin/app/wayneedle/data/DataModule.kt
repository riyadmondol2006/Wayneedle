package app.wayneedle.data

import android.content.Context
import app.wayneedle.data.db.RoomTripCheckpointStore
import app.wayneedle.data.db.RoomTripRepository
import app.wayneedle.data.db.WayneedleDatabase
import app.wayneedle.data.settings.DataStoreSettingsRepository
import app.wayneedle.data.settings.settingsDataStore
import app.wayneedle.domain.SettingsRepository
import app.wayneedle.domain.TripCheckpointStore
import app.wayneedle.domain.TripRepository

/**
 * Builds the persistence layer lazily from a [Context]. Create exactly one per process, as part of the app
 * container; every member is created on first use (thread-safe), so process start stays cheap and the
 * database file is only opened when something actually needs it.
 *
 * Wiring in `DefaultAppContainer`:
 *
 * ```
 * private val data = DataModule(context)
 *
 * override val settingsRepository: SettingsRepository get() = data.settingsRepository
 * override val tripRepository: TripRepository get() = data.tripRepository
 * // pass data.tripCheckpointStore to the TripTracker constructor
 * ```
 *
 * [tripRepository] and [tripCheckpointStore] share one [database]. It is never closed explicitly: the
 * process owns it for its whole life.
 */
class DataModule(context: Context) {
    private val appContext: Context = context.applicationContext

    val database: WayneedleDatabase by lazy { WayneedleDatabase.create(appContext) }

    val tripRepository: TripRepository by lazy { RoomTripRepository(database.tripDao()) }

    val tripCheckpointStore: TripCheckpointStore by lazy { RoomTripCheckpointStore(database.tripCheckpointDao()) }

    val settingsRepository: SettingsRepository by lazy { DataStoreSettingsRepository(settingsDataStore(appContext)) }
}
