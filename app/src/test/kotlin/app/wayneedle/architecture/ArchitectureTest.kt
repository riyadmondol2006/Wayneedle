package app.wayneedle.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Enforces the layering and "one GPS owner" rules of `docs/ARCHITECTURE.md` on the real source tree.
 * Directories that do not exist (yet) are vacuously fine, so the tests hold while workstreams land.
 */
class ArchitectureTest {
    private val mainSources: List<SourceFile> by lazy { ProjectLayout.mainKotlinSources() }

    @Test
    fun `the scanner finds the repository and the domain contracts`() {
        assertTrue(ProjectLayout.root.resolve("docs/ARCHITECTURE.md").isFile, "root = ${ProjectLayout.root}")
        val domain = mainSources.filter { it.inPackage("app.wayneedle.domain") }
        assertTrue(domain.isNotEmpty(), "No domain sources found under ${ProjectLayout.mainSources}")
        val controller = domain.firstOrNull { it.path.endsWith("domain/TrackingController.kt") }
        assertTrue(controller != null, "domain/TrackingController.kt not found")
        assertEquals("app.wayneedle.domain", controller.packageName)
    }

    @Test
    fun `layers only depend on what the architecture allows`() {
        assertNoViolations(
            rule = "package layering from docs/ARCHITECTURE.md (${ArchitectureRules.layerRules.size} rules)",
            violations = ArchitectureRules.checkLayering(mainSources),
        )
    }

    @Test
    fun `only AndroidLocationSource uses platform location APIs`() {
        assertNoViolations(
            rule = "no class except tracking/platform/AndroidLocationSource references LocationManager or " +
                "requests location updates",
            violations = ArchitectureRules.checkLocationApiConfinement(mainSources),
        )
    }

    @Test
    fun `UI, service, data and permissions never see LocationSource`() {
        assertNoViolations(
            rule = "only the TrackingController implementation collects LocationSource (one GPS owner)",
            violations = ArchitectureRules.checkLocationSourceOwnership(mainSources),
        )
    }

    @Test
    fun `main sources contain no hard-coded URLs`() {
        assertNoViolations(
            rule = "no http(s):// string literals in main Kotlin sources",
            violations = ArchitectureRules.checkHardCodedUrls(mainSources),
        )
    }

    @Test
    fun `only the update check uses the network`() {
        assertNoViolations(
            rule = "networking APIs only in app.wayneedle.update",
            violations = ArchitectureRules.checkNetworkConfinement(mainSources),
        )
    }

    @Test
    fun `main sources do not log coordinates`() {
        assertNoViolations(
            rule = "coordinates are never written to the system log (PRIVACY.md)",
            violations = ArchitectureRules.checkNoCoordinateLogging(mainSources),
        )
    }

    @Test
    fun `the repository is Kotlin only`() {
        val paths = ProjectLayout.walk().map { ProjectLayout.relativePath(it) }.toList()
        assertTrue(paths.any { it.endsWith(".kt") }, "No Kotlin files found; is the root ${ProjectLayout.root} right?")
        assertNoViolations(
            rule = "100 % Kotlin: no Java sources and no Groovy build scripts",
            violations = ArchitectureRules.checkNoJavaOrGroovy(paths),
        )
    }
}
