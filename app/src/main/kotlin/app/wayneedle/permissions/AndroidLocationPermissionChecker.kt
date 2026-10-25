package app.wayneedle.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.wayneedle.domain.LocationPermissionChecker
import app.wayneedle.domain.model.LocationPermissionState

/**
 * Reads the runtime location grant: precise (FINE) lets the GPS provider be used, approximate-only (COARSE)
 * cannot measure speed, anything else is denied. Cheap enough to call on every resume.
 */
class AndroidLocationPermissionChecker(context: Context) : LocationPermissionChecker {
    private val appContext: Context = context.applicationContext

    override fun current(): LocationPermissionState = mapLocationPermission(
        fineGranted = isGranted(Manifest.permission.ACCESS_FINE_LOCATION),
        coarseGranted = isGranted(Manifest.permission.ACCESS_COARSE_LOCATION),
    )

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Pure mapping from the two grants to the domain state. FINE wins even if COARSE is somehow missing
 * (Android normally grants both together, or only COARSE when the user picks "approximate").
 */
fun mapLocationPermission(fineGranted: Boolean, coarseGranted: Boolean): LocationPermissionState = when {
    fineGranted -> LocationPermissionState.GRANTED_PRECISE
    coarseGranted -> LocationPermissionState.GRANTED_APPROXIMATE_ONLY
    else -> LocationPermissionState.DENIED
}
