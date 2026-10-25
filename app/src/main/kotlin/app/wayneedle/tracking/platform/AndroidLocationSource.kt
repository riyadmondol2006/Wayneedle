package app.wayneedle.tracking.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import app.wayneedle.domain.LocationSource
import app.wayneedle.domain.LocationSourceEvent
import app.wayneedle.tracking.locationSampleOf
import app.wayneedle.tracking.satellitesOf
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.Executor

/**
 * [LocationSource] backed by the platform `LocationManager`, GPS provider only (no Play Services, no
 * fused/network location: Wayneedle is fully offline and needs Doppler speed from GNSS).
 *
 * Each collection registers one location listener plus one GNSS status callback and unregisters both
 * when the collection is cancelled. Emits the provider state first, then fixes, provider changes and
 * satellite counts. Without ACCESS_FINE_LOCATION (or on a SecurityException) it emits
 * [LocationSourceEvent.PermissionDenied] and completes. On a device without a GPS provider it reports
 * the provider as disabled and stays silent. Callbacks arrive on the main thread and are handed to the
 * collector with `trySend` (never blocking the platform).
 */
class AndroidLocationSource(context: Context) : LocationSource {
    private val appContext: Context = context.applicationContext
    private val locationManager: LocationManager? =
        ContextCompat.getSystemService(appContext, LocationManager::class.java)
    private val callbackExecutor: Executor = ContextCompat.getMainExecutor(appContext)

    override fun events(minIntervalMillis: Long): Flow<LocationSourceEvent> = callbackFlow {
        val manager = locationManager
        if (manager == null || !LocationManagerCompat.hasProvider(manager, GPS)) {
            send(LocationSourceEvent.ProviderEnabledChanged(false))
            awaitClose()
            return@callbackFlow
        }
        if (!hasFineLocationPermission()) {
            send(LocationSourceEvent.PermissionDenied)
            close()
            return@callbackFlow
        }
        send(LocationSourceEvent.ProviderEnabledChanged(manager.isProviderEnabled(GPS)))

        val locationListener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) {
                trySend(LocationSourceEvent.Fix(location.toSample()))
            }

            override fun onProviderEnabled(provider: String) {
                if (provider == GPS) trySend(LocationSourceEvent.ProviderEnabledChanged(true))
            }

            override fun onProviderDisabled(provider: String) {
                if (provider == GPS) trySend(LocationSourceEvent.ProviderEnabledChanged(false))
            }
        }
        val gnssCallback = object : GnssStatusCompat.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatusCompat) {
                trySend(satellitesOf(status.satelliteCount, status::usedInFix))
            }
        }
        val request = LocationRequestCompat.Builder(minIntervalMillis)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(minIntervalMillis)
            .setMinUpdateDistanceMeters(0f)
            .build()

        val failure: LocationSourceEvent? = try {
            register(manager, request, locationListener, gnssCallback)
            null
        } catch (_: SecurityException) {
            LocationSourceEvent.PermissionDenied
        } catch (_: IllegalArgumentException) {
            // The provider vanished between the check and the request.
            LocationSourceEvent.ProviderEnabledChanged(false)
        }
        when (failure) {
            null -> awaitClose { unregister(manager, locationListener, gnssCallback) }

            LocationSourceEvent.PermissionDenied -> {
                unregister(manager, locationListener, gnssCallback)
                send(failure)
                close()
            }

            else -> {
                unregister(manager, locationListener, gnssCallback)
                send(failure)
                awaitClose()
            }
        }
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun register(
        manager: LocationManager,
        request: LocationRequestCompat,
        locationListener: LocationListenerCompat,
        gnssCallback: GnssStatusCompat.Callback,
    ) {
        LocationManagerCompat.requestLocationUpdates(manager, GPS, request, callbackExecutor, locationListener)
        // Satellite counts are optional extras; a device without GNSS status support still works.
        LocationManagerCompat.registerGnssStatusCallback(manager, callbackExecutor, gnssCallback)
    }

    private fun unregister(
        manager: LocationManager,
        locationListener: LocationListenerCompat,
        gnssCallback: GnssStatusCompat.Callback,
    ) {
        try {
            LocationManagerCompat.removeUpdates(manager, locationListener)
        } catch (_: SecurityException) {
            // Permission already revoked: the platform has dropped the listener itself.
        }
        LocationManagerCompat.unregisterGnssStatusCallback(manager, gnssCallback)
    }

    private fun Location.toSample() = locationSampleOf(
        latitude = latitude,
        longitude = longitude,
        hasAccuracy = hasAccuracy(),
        accuracyM = accuracy,
        hasSpeed = hasSpeed(),
        speedMps = speed,
        hasSpeedAccuracy = hasSpeedAccuracy(),
        speedAccuracyMps = speedAccuracyMetersPerSecond,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        epochMillis = time,
    )

    private companion object {
        const val GPS = LocationManager.GPS_PROVIDER
    }
}
