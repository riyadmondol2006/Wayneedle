package app.wayneedle.service

import kotlin.test.Test
import kotlin.test.assertEquals

class ForegroundFailureTest {
    private class NotAllowed : IllegalStateException("ForegroundServiceStartNotAllowedException stand-in")

    @Test
    fun `security exceptions are permission failures`() {
        assertEquals(ForegroundFailure.PERMISSION, ForegroundFailure.classify(SecurityException()))
    }

    @Test
    fun `illegal state including start-not-allowed subclasses is a background start restriction`() {
        assertEquals(ForegroundFailure.START_NOT_ALLOWED, ForegroundFailure.classify(IllegalStateException()))
        assertEquals(ForegroundFailure.START_NOT_ALLOWED, ForegroundFailure.classify(NotAllowed()))
    }

    @Test
    fun `anything else is other`() {
        assertEquals(ForegroundFailure.OTHER, ForegroundFailure.classify(RuntimeException()))
        assertEquals(ForegroundFailure.OTHER, ForegroundFailure.classify(UnsupportedOperationException()))
    }
}
