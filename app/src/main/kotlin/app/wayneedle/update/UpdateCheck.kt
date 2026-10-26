package app.wayneedle.update

/** A published release, as far as the update check needs it. */
data class ReleaseInfo(
    /** The release's version, e.g. `1.0.57` (taken from the tag, a leading `v` removed). */
    val version: String,
    /** The release page, where the APK and its checksum can be downloaded. */
    val pageUrl: String,
    /** Release notes as published, possibly empty. */
    val notes: String,
)

/** Where releases come from; [latest] is null when nothing has been published yet. */
fun interface ReleaseSource {
    suspend fun latest(): ReleaseInfo?
}

sealed interface UpdateCheckResult {
    data class Available(val release: ReleaseInfo) : UpdateCheckResult

    data object UpToDate : UpdateCheckResult

    /** No answer (offline, GitHub unreachable or changed); the app works on regardless. */
    data object Failed : UpdateCheckResult
}

/**
 * Versions such as `1.0.57`, tags such as `v1.0.57`; anything after the numbers (`-debug`) is ignored.
 * Compared number by number, a missing part counting as 0 (`1.1` equals `1.1.0`).
 */
object ReleaseVersion {
    private val numbers = Regex("""^v?(\d+(?:\.\d+)*)""")

    fun parse(text: String): List<Long>? =
        numbers.find(text.trim())?.groupValues?.get(1)?.split('.')?.map { it.toLongOrNull() ?: return null }

    /** True only when both parse and [candidate] is strictly newer than [current]. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0L }
            val right = b.getOrElse(index) { 0L }
            if (left != right) return left > right
        }
        return false
    }
}

/** What the update check remembers between runs. */
interface UpdateCheckMemory {
    var lastCheckEpochMillis: Long
    var skippedVersion: String?
}

/**
 * Asks [source] whether a newer release than [currentVersion] exists.
 *
 * The automatic check ([checkIfDue]) runs at most once per [intervalMillis] and stays quiet about a version the
 * user chose to skip; a manual check ([checkNow]) always asks and always reports. Failures never throw: an
 * unreachable server is [UpdateCheckResult.Failed] and is retried at the next due time.
 */
class UpdateChecker(
    private val source: ReleaseSource,
    private val currentVersion: String,
    private val memory: UpdateCheckMemory,
    private val nowEpochMillis: () -> Long,
    private val intervalMillis: Long = DAY_MILLIS,
) {
    /** The automatic check: null when it was not due or found nothing to show. */
    suspend fun checkIfDue(): ReleaseInfo? {
        val now = nowEpochMillis()
        val last = memory.lastCheckEpochMillis
        // 0 means never; a time in the future (clock set back) does not block the check either.
        if (last > 0L && last in (now - intervalMillis + 1)..now) return null
        val result = checkNow()
        if (result != UpdateCheckResult.Failed) memory.lastCheckEpochMillis = now
        val release = (result as? UpdateCheckResult.Available)?.release ?: return null
        return release.takeIf { it.version != memory.skippedVersion }
    }

    suspend fun checkNow(): UpdateCheckResult {
        val release = try {
            source.latest()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            return UpdateCheckResult.Failed
        } ?: return UpdateCheckResult.UpToDate
        return if (ReleaseVersion.isNewer(release.version, currentVersion)) {
            UpdateCheckResult.Available(release)
        } else {
            UpdateCheckResult.UpToDate
        }
    }

    /** "Skip this version": the automatic check stays quiet until an even newer release appears. */
    fun skip(release: ReleaseInfo) {
        memory.skippedVersion = release.version
    }

    companion object {
        const val DAY_MILLIS: Long = 24 * 60 * 60 * 1_000L
    }
}
