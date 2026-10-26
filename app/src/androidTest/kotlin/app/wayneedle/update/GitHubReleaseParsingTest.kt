package app.wayneedle.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The JSON of GitHub's "latest release" endpoint, parsed with the platform's org.json (device only). */
@RunWith(AndroidJUnit4::class)
class GitHubReleaseParsingTest {
    private fun json(
        tag: String = "v1.0.57",
        draft: Boolean = false,
        prerelease: Boolean = false,
        page: String = "https://github.com/riyadmondol2006/Wayneedle/releases/tag/v1.0.57",
    ) = """
        {"tag_name":"$tag","name":"Wayneedle 1.0.57","draft":$draft,"prerelease":$prerelease,
         "html_url":"$page","body":"## What's changed\n* Speed alert","assets":[]}
    """.trimIndent()

    @Test
    fun aPublishedReleaseIsRead() {
        val release = parseRelease(json())!!
        assertEquals("1.0.57", release.version)
        assertEquals("https://github.com/riyadmondol2006/Wayneedle/releases/tag/v1.0.57", release.pageUrl)
        assertEquals("## What's changed\n* Speed alert", release.notes)
    }

    @Test
    fun draftsPreReleasesAndUnversionedTagsAreIgnored() {
        assertNull(parseRelease(json(draft = true)))
        assertNull(parseRelease(json(prerelease = true)))
        assertNull(parseRelease(json(tag = "nightly")))
    }

    @Test
    fun aPageOutsideThisProjectIsNeverOpened() {
        val release = parseRelease(json(page = "https://example.com/phishing"))!!
        assertEquals(GitHubReleaseSource.RELEASES_PAGE_URL, release.pageUrl)
    }
}
