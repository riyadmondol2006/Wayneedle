package app.wayneedle.permissions

import android.Manifest
import android.os.Build

/** Single source of truth for the runtime permissions Wayneedle asks for. */
object AppPermissions {
    /** Location permissions, requested together so the user can pick precise or approximate. */
    val location: Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /** POST_NOTIFICATIONS exists (and is runtime) only on Android 13+; null below. */
    val notifications: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
}
