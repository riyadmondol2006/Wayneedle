package app.wayneedle.architecture

import java.io.File

/**
 * Finds the repository on disk without assuming a working directory: Gradle runs unit tests from the
 * module directory (`app/`), an IDE may run them from the repository root.
 */
internal object ProjectLayout {
    val root: File by lazy { findRoot(File("").absoluteFile) }

    val appDir: File get() = File(root, "app")

    val mainSources: File get() = File(appDir, "src/main")

    val versionCatalog: File get() = File(root, "gradle/libs.versions.toml")

    val license: File get() = File(root, "LICENSE")

    /** Directory names never scanned: build output, caches, VCS data and tool state (e.g. other worktrees). */
    private val ignoredDirectories = setOf("build", "node_modules", "out")

    private fun isIgnored(directory: File): Boolean {
        val name = directory.name
        return name in ignoredDirectories || (name.startsWith(".") && name != ".github")
    }

    private fun findRoot(start: File): File {
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "app/build.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("Cannot locate the repository root (settings.gradle.kts + app/build.gradle.kts) above $start")
    }

    fun relativePath(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    /** Every file below [base] (default: the repository root) that is not inside an ignored directory. */
    fun walk(base: File = root): Sequence<File> {
        if (!base.isDirectory) return emptySequence()
        return base.walkTopDown()
            .onEnter { directory -> directory == base || !isIgnored(directory) }
            .filter { it.isFile }
    }

    fun read(file: File): SourceFile = SourceFile(relativePath(file), file.readText())

    /** Kotlin sources of the app's `main` source set. Empty when a directory does not exist. */
    fun mainKotlinSources(): List<SourceFile> =
        walk(mainSources).filter { it.extension == "kt" }.map(::read).toList()

    /** Every Gradle Kotlin-DSL script in the repository. */
    fun gradleScripts(): List<SourceFile> =
        walk().filter { it.name.endsWith(".gradle.kts") }.map(::read).toList()

    /** The `AndroidManifest.xml` of every source set below `app/src`. */
    fun sourceManifests(): List<SourceFile> =
        walk(File(appDir, "src")).filter { it.name == "AndroidManifest.xml" }.map(::read).toList()

    /**
     * Manifests produced by the build (merged from all dependencies). They exist only after the manifest
     * tasks have run, so callers must treat an empty result as "nothing to check".
     */
    fun mergedManifests(): List<SourceFile> {
        val intermediates = File(appDir, "build/intermediates")
        if (!intermediates.isDirectory) return emptyList()
        return intermediates.walkTopDown()
            .filter { it.isFile && it.name == "AndroidManifest.xml" }
            .filter { file ->
                val path = file.invariantSeparatorsPath
                "/merged_manifest/" in path || "/merged_manifests/" in path || "/packaged_manifests/" in path
            }
            .map(::read)
            .toList()
    }
}
