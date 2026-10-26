package app.wayneedle.update

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * The only network access in Wayneedle: one HTTPS GET of the latest published release of the project's GitHub
 * repository. It sends nothing but the request itself (the app's version is in the User-Agent, as GitHub's
 * API requires one); no identifier, no location, no trip data. Draft and pre-releases are ignored.
 */
class GitHubReleaseSource(private val userAgent: String) : ReleaseSource {
    override suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val connection = URI(LATEST_RELEASE_URL).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", userAgent)
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> parseRelease(connection.inputStream.use { it.readCapped() })

                // No release published yet.
                HttpURLConnection.HTTP_NOT_FOUND -> null

                else -> throw IOException("GitHub answered HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        // Written out in full so the architecture test can check every address the app may contact.
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/riyadmondol2006/Wayneedle/releases/latest"
        const val RELEASES_PAGE_URL = "https://github.com/riyadmondol2006/Wayneedle/releases"
        private const val TIMEOUT_MILLIS = 10_000
    }
}

/** Parses GitHub's "latest release" JSON; null for a draft, a pre-release or a tag without a version. */
internal fun parseRelease(json: String): ReleaseInfo? {
    val release = JSONObject(json)
    if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
    val tag = release.optString("tag_name")
    ReleaseVersion.parse(tag) ?: return null
    // Only ever open this project's own release pages.
    val page = release.optString("html_url").takeIf { it.startsWith(GitHubReleaseSource.RELEASES_PAGE_URL) }
        ?: GitHubReleaseSource.RELEASES_PAGE_URL
    return ReleaseInfo(version = tag.removePrefix("v"), pageUrl = page, notes = release.optString("body"))
}

/** Reads the whole response, refusing anything over [MAX_BYTES]. (readNBytes would need Android 13.) */
private fun InputStream.readCapped(): String {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
        if (out.size() > MAX_BYTES) throw IOException("Release description too large")
    }
    return out.toString(Charsets.UTF_8.name())
}

private const val MAX_BYTES = 1_000_000
private const val BUFFER_BYTES = 16 * 1024

/** Remembers the last check and a skipped version in the app's private preferences. */
class PreferencesUpdateCheckMemory(context: Context) : UpdateCheckMemory {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var lastCheckEpochMillis: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_CHECK, value) }

    override var skippedVersion: String?
        get() = prefs.getString(KEY_SKIPPED, null)
        set(value) = prefs.edit { putString(KEY_SKIPPED, value) }

    private companion object {
        const val FILE = "wayneedle_updates"
        const val KEY_LAST_CHECK = "last_check_epoch_millis"
        const val KEY_SKIPPED = "skipped_version"
    }
}
