package app.wayneedle.ui.format

import androidx.annotation.StringRes
import app.wayneedle.R
import kotlin.math.roundToInt

private val compassPoints = intArrayOf(
    R.string.compass_n,
    R.string.compass_ne,
    R.string.compass_e,
    R.string.compass_se,
    R.string.compass_s,
    R.string.compass_sw,
    R.string.compass_w,
    R.string.compass_nw,
)

/** Whole degrees in 0..359 for a course in any range (370 becomes 10, -10 becomes 350). */
fun normalizedDegrees(bearingDeg: Float): Int = ((bearingDeg.roundToInt() % 360) + 360) % 360

/** One of eight compass points (N, NE, ... NW), each covering 45 degrees centred on its direction. */
@StringRes
fun compassPointRes(bearingDeg: Float): Int = compassPoints[((normalizedDegrees(bearingDeg) + 22) / 45) % 8]
