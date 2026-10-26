package app.wayneedle.architecture

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Privacy and build-hygiene promises made in README.md, PRIVACY.md and CONTRIBUTING.md, checked on the repository. */
class RepositoryHygieneTest {
    private val catalogText: String by lazy { ProjectLayout.versionCatalog.readText() }
    private val catalogPath: String by lazy { ProjectLayout.relativePath(ProjectLayout.versionCatalog) }

    @Test
    fun `only the main manifest declares INTERNET, for the update check`() {
        val sourceManifests = ProjectLayout.sourceManifests()
        assertTrue(sourceManifests.isNotEmpty(), "No AndroidManifest.xml found under app/src")
        val (main, others) = sourceManifests.partition { it.path.endsWith("src/main/AndroidManifest.xml") }
        assertTrue(
            main.single().text.contains("android.permission.INTERNET"),
            "the update check needs INTERNET in the main manifest (see PRIVACY.md)",
        )
        // Test and build-type manifests must not widen it; what the network may be used for is confined to
        // app.wayneedle.update (see ArchitectureTest).
        assertNoViolations(
            rule = "android.permission.INTERNET only in src/main/AndroidManifest.xml",
            violations = others.flatMap(ArchitectureRules::checkNoInternetPermission),
        )
    }

    @Test
    fun `cloud backup is disabled`() {
        val main = ProjectLayout.sourceManifests().first { it.path.endsWith("src/main/AndroidManifest.xml") }
        assertNoViolations(
            rule = "trip data is never backed up to the cloud",
            violations = ArchitectureRules.checkBackupDisabled(main),
        )
    }

    @Test
    fun `version catalog and build scripts pin exact stable versions`() {
        val scripts = ProjectLayout.gradleScripts()
        assertTrue(scripts.isNotEmpty(), "No *.gradle.kts files found")
        assertTrue("[versions]" in catalogText, "$catalogPath has no [versions] section")
        val violations = VersionPolicy.checkCatalog(catalogPath, catalogText) +
            scripts.flatMap { VersionPolicy.checkBuildScript(it.path, it.codeWithStrings) }
        assertNoViolations(
            rule = "no '+', ranges, latest, snapshot, alpha, beta, rc, dev, eap or milestone versions",
            violations = violations,
        )
    }

    @Test
    fun `the Kotlin Android plugin is not applied`() {
        val masked = ProjectLayout.gradleScripts().map { it.path to it.codeWithStrings } +
            (catalogPath to TextMasker.maskHashComments(catalogText))
        assertNoViolations(
            rule = "AGP 9 has built-in Kotlin; org.jetbrains.kotlin.android must not be applied",
            violations = masked.flatMap { (path, text) -> ArchitectureRules.checkNoKotlinAndroidPlugin(path, text) },
        )
    }

    @Test
    fun `no analytics, crash reporting, networking or Play Services dependencies`() {
        val masked = ProjectLayout.gradleScripts().map { it.path to it.codeWithStrings } +
            (catalogPath to TextMasker.maskHashComments(catalogText))
        assertNoViolations(
            rule = "no network, analytics or third-party SDK dependencies",
            violations = masked.flatMap { (path, text) -> ArchitectureRules.checkNoDeniedDependencies(path, text) },
        )
    }

    @Test
    fun `LICENSE is the unmodified Apache License 2_0`() {
        val license = ProjectLayout.read(ProjectLayout.license)
        assertNoViolations(
            rule = "LICENSE is the Apache License, Version 2.0, verbatim",
            violations = LicenseRule.check(license),
        )
    }
}

/** The Apache License 2.0 as published at https://www.apache.org/licenses/LICENSE-2.0.txt. */
internal object LicenseRule {
    const val APACHE_2_SHA256 = "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"

    private val requiredPhrases = listOf(
        "Apache License",
        "Version 2.0, January 2004",
        "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION",
        "END OF TERMS AND CONDITIONS",
    )

    fun check(license: SourceFile): List<Violation> {
        val missing = requiredPhrases.filterNot { it in license.text }
        if (missing.isNotEmpty()) {
            return listOf(Violation(license.path, 0, "not the Apache License 2.0; missing: ${missing.joinToString()}"))
        }
        val actual = sha256(license.text.replace("\r\n", "\n"))
        return if (actual == APACHE_2_SHA256) {
            emptyList()
        } else {
            listOf(
                Violation(
                    license.path,
                    0,
                    "text differs from the official Apache-2.0 text (sha-256 $actual, expected $APACHE_2_SHA256); " +
                        "keep the file unmodified and put the copyright notice in NOTICE/README",
                ),
            )
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
