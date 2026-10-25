package app.wayneedle.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves every rule actually fires on a violation (and stays quiet on look-alikes such as comments), so the
 * architecture tests cannot go green merely because a rule matches nothing.
 */
class RuleSelfTest {
    private fun file(path: String, source: String) = SourceFile(path, source.trimIndent())

    private fun main(pkg: String, name: String, body: String) = file(
        "app/src/main/kotlin/app/wayneedle/${pkg.replace('.', '/')}/$name.kt",
        "package app.wayneedle.$pkg\n\n$body",
    )

    // ---- masking -------------------------------------------------------------------------------------------

    @Test
    fun `masker removes comments but keeps line structure`() {
        val source = "val a = 1 // import android.os.Build\n" +
            "/* android.util.Log\n  /* nested */ still comment */\n" +
            "val b = 2\n"
        val masked = KotlinMasker.maskCommentsAndStrings(source)
        assertEquals(source.count { it == '\n' }, masked.count { it == '\n' })
        assertFalse("android" in masked)
        assertTrue("val b = 2" in masked)
    }

    @Test
    fun `masker blanks string content, raw strings, templates and char literals`() {
        val source = """
            val url = "https://example.org/a//b"
            val raw = ${"\"\"\""}http://raw.example "quoted" //not a comment${"\"\"\""}
            val t = "x ${'$'}{listOf("http://nested.example").size} y"
            val q = '"'
            val after = 1 // trailing
        """.trimIndent()
        val masked = KotlinMasker.maskCommentsAndStrings(source)
        assertFalse("example" in masked, masked)
        assertTrue("val after = 1" in masked, masked)
        assertTrue("http://raw.example" in KotlinMasker.maskComments(source))
    }

    @Test
    fun `package declaration is parsed from masked code`() {
        assertEquals("app.wayneedle.ui", file("A.kt", "// package nope\npackage app.wayneedle.ui\n").packageName)
        assertTrue(file("A.kt", "package app.wayneedle.ui.theme").inPackage("app.wayneedle.ui"))
        assertFalse(file("A.kt", "package app.wayneedle.uikit").inPackage("app.wayneedle.ui"))
    }

    // ---- layering ------------------------------------------------------------------------------------------

    @Test
    fun `domain and tracking engine reject Android imports with file and line`() {
        val domain = main("domain", "Bad", "import android.os.Build\n\nval x = 1")
        val engine = main("tracking", "Bad", "import androidx.annotation.Keep\nimport android.util.Log")
        val violations = ArchitectureRules.checkLayering(listOf(domain, engine))
        assertEquals(
            listOf(
                "app/src/main/kotlin/app/wayneedle/domain/Bad.kt:3",
                "app/src/main/kotlin/app/wayneedle/tracking/Bad.kt:3",
                "app/src/main/kotlin/app/wayneedle/tracking/Bad.kt:4",
            ),
            violations.map { "${it.path}:${it.line}" }.sorted(),
        )
        assertTrue(violations.all { "references" in it.message })
    }

    @Test
    fun `fully qualified Android use without an import is caught, comments and strings are not`() {
        val sneaky = main("domain", "Sneaky", "fun f() = android.util.Log.d(\"t\", \"m\")")
        val harmless = main(
            "domain",
            "Harmless",
            "/** Wraps [android.location.Location] and androidx.room.Room. */\nval s = \"android.os.Build\"",
        )
        assertEquals(1, ArchitectureRules.checkLayering(listOf(sneaky)).size)
        assertTrue(ArchitectureRules.checkLayering(listOf(harmless)).isEmpty())
    }

    @Test
    fun `tracking platform adapters may use Android but not storage or UI`() {
        val adapter = main(
            "tracking.platform",
            "Adapter",
            "import android.os.SystemClock\nimport app.wayneedle.domain.TimeSource",
        )
        val leaky = main("tracking.platform", "Leaky", "import app.wayneedle.data.TripDao")
        val violations = ArchitectureRules.checkLayering(listOf(adapter, leaky))
        assertEquals(listOf("Leaky.kt"), violations.map { it.path.substringAfterLast('/') })
    }

    @Test
    fun `ui and data may not reach into service or tracking, ui may not use Room`() {
        val ui = main(
            "ui.home",
            "Screen",
            "import app.wayneedle.tracking.TripTracker\nimport app.wayneedle.service.TrackingService\n" +
                "import androidx.room.Room\nimport androidx.compose.runtime.Composable\n" +
                "import app.wayneedle.AppContainer",
        )
        val data = main("data.db", "Db", "import app.wayneedle.service.TrackingService\nimport androidx.room.Room")
        val svc = main("service", "Svc", "import app.wayneedle.tracking.platform.AndroidLocationSource")
        val uiHits = ArchitectureRules.checkLayering(listOf(ui)).map { it.line }.sorted()
        assertEquals(listOf(3, 4, 5), uiHits)
        assertEquals(1, ArchitectureRules.checkLayering(listOf(data)).size)
        assertEquals(1, ArchitectureRules.checkLayering(listOf(svc)).size)
    }

    @Test
    fun `domain contracts may not import implementation layers`() {
        val bad = main("domain", "Bad", "import app.wayneedle.ui.MainActivity")
        val ok = main(
            "domain.model",
            "Ok",
            "import app.wayneedle.domain.TimeSource\nimport kotlinx.coroutines.flow.Flow",
        )
        assertEquals(1, ArchitectureRules.checkLayering(listOf(bad)).size)
        assertTrue(ArchitectureRules.checkLayering(listOf(ok)).isEmpty())
    }

    // ---- one GPS owner -------------------------------------------------------------------------------------

    @Test
    fun `location APIs are confined to the AndroidLocationSource adapter`() {
        val adapter = main(
            "tracking.platform",
            "AndroidLocationSource",
            "import android.location.LocationManager\nclass AndroidLocationSource(m: LocationManager) { " +
                "fun go() = m.requestLocationUpdates(\"gps\", 1000L, 0f, l) }",
        )
        val intruder = main(
            "service",
            "Intruder",
            "/** LocationManager is mentioned only in this comment. */\nimport android.location.LocationManager\n" +
                "fun go(m: LocationManager) = m.requestLocationUpdates(\"gps\", 1L, 0f, l)",
        )
        val violations = ArchitectureRules.checkLocationApiConfinement(listOf(adapter, intruder))
        assertEquals(setOf("Intruder.kt"), violations.map { it.path.substringAfterLast('/') }.toSet())
        assertEquals(listOf(4, 5), violations.map { it.line }.sorted())
    }

    @Test
    fun `only the tracker side may reference LocationSource`() {
        val ui = main("ui", "Vm", "import app.wayneedle.domain.LocationSource\nclass Vm(val s: LocationSource)")
        val ui2 = main("ui", "Vm2", "fun f(e: LocationSourceEvent) = e")
        val tracker = main("tracking", "Tracker", "class Tracker(private val source: LocationSource)")
        val domain = main("domain", "LocationSource", "interface LocationSource")
        val violations = ArchitectureRules.checkLocationSourceOwnership(listOf(ui, ui2, tracker, domain))
        assertEquals(3, violations.size)
        assertTrue(violations.none { it.path.contains("/tracking/") || it.path.contains("/domain/") })
    }

    // ---- hygiene -------------------------------------------------------------------------------------------

    @Test
    fun `hard-coded URLs are found in strings but not in comments, and the allowlist works`() {
        val source = main(
            "ui",
            "Links",
            "// see https://example.org/doc\nval a = \"https://example.org/x\"\nval b = \"http://allowed.example/y\"",
        )
        val allowed = listOf("http://allowed.")
        val violations = ArchitectureRules.checkHardCodedUrls(listOf(source), allowedPrefixes = allowed)
        assertEquals(listOf(4), violations.map { it.line })
        assertEquals(2, ArchitectureRules.checkHardCodedUrls(listOf(source), allowedPrefixes = emptyList()).size)
    }

    @Test
    fun `logging a coordinate or a whole fix is reported`() {
        val source = main(
            "tracking",
            "Logs",
            "fun f(latitude: Double, sample: Any) {\n" +
                "    Log.d(\"T\", \"lat=\$latitude\")\n" +
                "    println(\"fix \$sample\")\n" +
                "    Log.d(\"T\", \"accepted a fix\")\n" +
                "    val latitudeDelta = latitude + 1 // not logging\n" +
                "}",
        )
        assertEquals(listOf(4, 5), ArchitectureRules.checkNoCoordinateLogging(listOf(source)).map { it.line })
    }

    @Test
    fun `java and groovy files are reported`() {
        val violations = ArchitectureRules.checkNoJavaOrGroovy(
            listOf("app/src/main/Foo.java", "build.gradle", "app/build.gradle.kts", "a/B.kt", "x.groovy"),
        )
        assertEquals(listOf("app/src/main/Foo.java", "build.gradle", "x.groovy"), violations.map { it.path })
    }

    @Test
    fun `INTERNET permission is reported, a comment mentioning it is not`() {
        val xml = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <!-- <uses-permission android:name="android.permission.INTERNET" /> -->
                <uses-permission android:name="android.permission.INTERNET" />
            </manifest>
        """.trimIndent()
        val violations = ArchitectureRules.checkNoInternetPermission(SourceFile("AndroidManifest.xml", xml))
        assertEquals(listOf(3), violations.map { it.line })
        assertTrue(
            ArchitectureRules.checkNoInternetPermission(
                SourceFile("m.xml", "<!-- android.permission.INTERNET -->\n<manifest/>"),
            ).isEmpty(),
        )
    }

    @Test
    fun `allowBackup must be false`() {
        fun check(xml: String) = ArchitectureRules.checkBackupDisabled(SourceFile("m.xml", xml))
        assertTrue(check("<application android:allowBackup=\"false\"/>").isEmpty())
        assertEquals(1, check("<application android:allowBackup=\"true\"/>").size)
        assertEquals(1, check("<application/>").size)
        assertEquals(1, check("<!-- android:allowBackup=\"false\" -->\n<application/>").size)
    }

    @Test
    fun `unstable versions are recognised`() {
        val unstable = listOf(
            "1.0.0-alpha01", "2.0.0-beta2", "1.2.3-RC1", "1.0-SNAPSHOT", "1.+", "latest.release", "2.4.20-dev-1234",
            "2.4.0-eap", "1.0.0-M2", "[1.0,2.0)", "", "1.0.0.rc.1", "2.0.0-Preview3", "1.0.0-canary",
        )
        unstable.forEach { assertNotNull(VersionPolicy.unstableReason(it), "'$it' should be rejected") }
        val stable = listOf(
            "9.4.1", "2026.09.00", "1.13.0", "2.4.21", "1.3.0", "4.13.2", "2.8.5", "37", "1.0.0-release",
        )
        stable.forEach { assertNull(VersionPolicy.unstableReason(it), "'$it' should be accepted") }
    }

    @Test
    fun `version catalog scan ignores comments and reports file and line`() {
        val toml = """
            [versions]
            agp = "9.4.1"                # not alpha, not beta
            lib = "1.0.0-rc01"
            [libraries]
            a = { module = "x:y", version = "2.0.0-alpha1" }
            b = { module = "x:z", version.ref = "agp" }
            c = { module = "x:w", version = { strictly = "1.+" } }
            [plugins]
            p = { id = "p", version = "3.0.0-SNAPSHOT" }
        """.trimIndent()
        val violations = VersionPolicy.checkCatalog("libs.versions.toml", toml)
        assertEquals(listOf(3, 5, 7, 9), violations.map { it.line })
    }

    @Test
    fun `literal dependency coordinates with dynamic versions are reported`() {
        val script = "dependencies {\n    implementation(\"a.b:c:1.+\")\n    implementation(\"a.b:d:2.0.0\")\n    " +
            "implementation(\"a.b:e:1.0-beta1\")\n    implementation(\"a.b:f:\$v\")\n}"
        assertEquals(listOf(2, 4), VersionPolicy.checkBuildScript("b.gradle.kts", script).map { it.line })
    }

    @Test
    fun `Kotlin Android plugin usage is reported, a comment about it is not`() {
        fun check(text: String) = ArchitectureRules.checkNoKotlinAndroidPlugin(
            "b.gradle.kts",
            KotlinMasker.maskComments(text),
        )
        assertEquals(1, check("plugins { id(\"org.jetbrains.kotlin.android\") }").size)
        assertEquals(1, check("plugins { kotlin(\"android\") }").size)
        assertEquals(1, check("plugins { id(\"kotlin-android\") }").size)
        assertEquals(1, check("plugins { alias(libs.plugins.kotlin.android) }").size)
        assertTrue(
            check("// do not apply org.jetbrains.kotlin.android\nplugins { alias(libs.plugins.kotlin.compose) }")
                .isEmpty(),
        )
    }

    @Test
    fun `analytics, networking and Play Services dependencies are reported`() {
        val toml = """
            a = { module = "com.google.firebase:firebase-analytics", version.ref = "x" }
            b = { module = "com.squareup.okhttp3:okhttp", version.ref = "x" }
            c = { module = "com.google.android.gms:play-services-location", version.ref = "x" }
            d = { module = "androidx.room:room-runtime", version.ref = "x" }
            e = { id = "com.google.gms.google-services", version.ref = "x" }
        """.trimIndent()
        val kts = "dependencies { implementation(\"io.sentry:sentry-android:7.0.0\") }\n" +
            "plugins { id(\"com.google.gms.google-services\") }"
        assertEquals(listOf(1, 2, 3, 5), ArchitectureRules.checkNoDeniedDependencies("t.toml", toml).map { it.line })
        assertEquals(listOf(1, 2), ArchitectureRules.checkNoDeniedDependencies("b.gradle.kts", kts).map { it.line })
    }

    @Test
    fun `license rule accepts only the exact Apache 2_0 text`() {
        val real = ProjectLayout.read(ProjectLayout.license)
        assertTrue(LicenseRule.check(real).isEmpty(), LicenseRule.check(real).joinToString())
        assertTrue(LicenseRule.check(SourceFile("LICENSE", real.text.replace("\n", "\r\n"))).isEmpty())
        assertEquals(1, LicenseRule.check(SourceFile("LICENSE", real.text + "\nextra")).size)
        assertEquals(1, LicenseRule.check(SourceFile("LICENSE", "MIT License\n")).size)
    }
}
