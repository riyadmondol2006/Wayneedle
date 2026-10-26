package app.wayneedle.ui.format

import app.wayneedle.R
import kotlin.test.Test
import kotlin.test.assertEquals

class CompassTest {
    @Test
    fun `courses map to the nearest of eight compass points`() {
        assertEquals(R.string.compass_n, compassPointRes(0f))
        assertEquals(R.string.compass_n, compassPointRes(22f))
        assertEquals(R.string.compass_ne, compassPointRes(23f))
        assertEquals(R.string.compass_ne, compassPointRes(47f))
        assertEquals(R.string.compass_e, compassPointRes(90f))
        assertEquals(R.string.compass_s, compassPointRes(180f))
        assertEquals(R.string.compass_w, compassPointRes(270f))
        assertEquals(R.string.compass_nw, compassPointRes(315f))
        assertEquals(R.string.compass_n, compassPointRes(359f))
    }

    @Test
    fun `degrees are normalised into 0 to 359`() {
        assertEquals(10, normalizedDegrees(370f))
        assertEquals(350, normalizedDegrees(-10f))
        assertEquals(0, normalizedDegrees(359.6f))
    }
}
