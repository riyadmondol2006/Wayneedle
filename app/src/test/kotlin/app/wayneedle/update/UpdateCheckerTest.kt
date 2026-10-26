package app.wayneedle.update

import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {
    private class Memory : UpdateCheckMemory {
        override var lastCheckEpochMillis: Long = 0L
        override var skippedVersion: String? = null
    }

    private val day = UpdateChecker.DAY_MILLIS
    private val release = ReleaseInfo("1.0.57", "https://github.com/riyadmondol2006/Wayneedle/releases/tag/v1.0.57", "")
    private var now = 100 * day
    private var calls = 0
    private var published: ReleaseInfo? = release
    private var failure: Exception? = null
    private val memory = Memory()

    private fun checker(current: String = "1.0.50") = UpdateChecker(
        source = {
            calls++
            failure?.let { throw it }
            published
        },
        currentVersion = current,
        memory = memory,
        nowEpochMillis = { now },
    )

    @Test
    fun `versions compare number by number and ignore suffixes`() {
        assertTrue(ReleaseVersion.isNewer("v1.0.57", "1.0.50"))
        assertTrue(ReleaseVersion.isNewer("1.0.10", "1.0.9"), "10 is more than 9, not less")
        assertTrue(ReleaseVersion.isNewer("1.1", "1.0.99"))
        assertTrue(ReleaseVersion.isNewer("1.0.57", "0.1.0-debug"))
        assertFalse(ReleaseVersion.isNewer("1.0.57", "1.0.57"))
        assertFalse(ReleaseVersion.isNewer("1.1", "1.1.0"))
        assertFalse(ReleaseVersion.isNewer("1.0.56", "1.0.57"))
        assertFalse(ReleaseVersion.isNewer("nightly", "1.0.0"), "an unreadable tag is never an update")
    }

    @Test
    fun `a newer release is offered, an older or equal one is not`() = runTest {
        assertEquals(UpdateCheckResult.Available(release), checker("1.0.50").checkNow())
        assertEquals(UpdateCheckResult.UpToDate, checker("1.0.57").checkNow())
        assertEquals(UpdateCheckResult.UpToDate, checker("1.0.60").checkNow())
        published = null
        assertEquals(UpdateCheckResult.UpToDate, checker().checkNow(), "nothing published yet")
    }

    @Test
    fun `the automatic check runs at most once a day`() = runTest {
        val checker = checker()
        assertEquals(release, checker.checkIfDue())
        now += day / 2
        assertNull(checker.checkIfDue())
        assertEquals(1, calls)
        now += day / 2
        assertEquals(release, checker.checkIfDue())
        assertEquals(2, calls)
    }

    @Test
    fun `being offline is not an error and is retried next time`() = runTest {
        failure = IOException("no network")
        val checker = checker()
        assertNull(checker.checkIfDue())
        assertEquals(UpdateCheckResult.Failed, checker.checkNow())
        failure = null
        assertEquals(release, checker.checkIfDue(), "a failed check does not count as the daily check")
    }

    @Test
    fun `a skipped version stays quiet until a newer one appears, but a manual check still reports it`() = runTest {
        val checker = checker()
        checker.skip(release)
        assertNull(checker.checkIfDue())
        assertEquals(UpdateCheckResult.Available(release), checker.checkNow())
        now += day
        published = release.copy(version = "1.0.58")
        assertEquals("1.0.58", checker.checkIfDue()?.version)
    }

    @Test
    fun `a clock set back does not block the check forever`() = runTest {
        memory.lastCheckEpochMillis = now + 30 * day
        assertEquals(release, checker().checkIfDue())
    }
}
