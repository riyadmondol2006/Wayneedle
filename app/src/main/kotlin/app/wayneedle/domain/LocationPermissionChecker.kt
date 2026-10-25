package app.wayneedle.domain

import app.wayneedle.domain.model.LocationPermissionState

/** Reads the current runtime location permission. Pure query, no UI. */
fun interface LocationPermissionChecker {
    fun current(): LocationPermissionState
}
