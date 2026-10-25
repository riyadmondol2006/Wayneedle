package app.wayneedle.architecture

/**
 * The enforceable subset of `docs/ARCHITECTURE.md` and `CONTRIBUTING.md`, as pure functions over file contents.
 * [ArchitectureTest] and [RepositoryHygieneTest] apply them to the real tree; [RuleSelfTest] proves each rule
 * really fires on a violation, so a green run is not just a rule that matches nothing.
 */
internal object ArchitectureRules {
    private const val ROOT = "app.wayneedle"

    /** Packages (below [ROOT]) that hold implementations; contracts and the pure engine must not depend on them. */
    private fun appPackages(vararg names: String): List<String> = names.map { "$ROOT.$it" }

    private val androidPackages = listOf("android", "androidx")

    /** One "package P may not reference these package prefixes" rule. */
    class ImportRule(
        val description: String,
        val appliesTo: (SourceFile) -> Boolean,
        val forbidden: List<String>,
        val reason: String,
    )

    val layerRules: List<ImportRule> = listOf(
        ImportRule(
            description = "domain/** is pure Kotlin and depends on no implementation layer",
            appliesTo = { it.inPackage("$ROOT.domain") },
            forbidden = androidPackages + appPackages("tracking", "data", "ui", "service", "permissions"),
            reason = "domain holds contracts only (docs/ARCHITECTURE.md)",
        ),
        ImportRule(
            description = "tracking/** (except tracking/platform/**) is pure Kotlin",
            appliesTo = { it.inPackage("$ROOT.tracking") && !it.inPackage("$ROOT.tracking.platform") },
            forbidden = androidPackages + appPackages("data", "ui", "service", "permissions"),
            reason = "the engine must run in JVM unit tests; Android access goes through tracking/platform",
        ),
        ImportRule(
            description = "tracking/platform/** is the Android adapter layer and depends on no UI/storage/service code",
            appliesTo = { it.inPackage("$ROOT.tracking.platform") },
            forbidden = appPackages("data", "ui", "service", "permissions"),
            reason = "adapters implement domain contracts only",
        ),
        ImportRule(
            description = "data/** does not use ui/, service/ or tracking/",
            appliesTo = { it.inPackage("$ROOT.data") },
            forbidden = appPackages("service", "tracking", "ui"),
            reason = "storage implements domain repositories and is unaware of its callers",
        ),
        ImportRule(
            description = "ui/** does not use service/, tracking/ internals or Room",
            appliesTo = { it.inPackage("$ROOT.ui") },
            forbidden = appPackages("service", "tracking") + "androidx.room",
            reason = "the UI talks to TrackingController and the domain repositories only",
        ),
        ImportRule(
            description = "service/** does not use tracking/ internals",
            appliesTo = { it.inPackage("$ROOT.service") },
            forbidden = appPackages("tracking"),
            reason = "the service only observes and commands TrackingController (one-GPS-owner rule)",
        ),
    )

    fun checkLayering(files: List<SourceFile>): List<Violation> = layerRules.flatMap { rule ->
        files.filter(rule.appliesTo).flatMap { file ->
            rule.forbidden.flatMap { prefix ->
                findReferences(file, prefix) { name ->
                    "references `$name`, which breaks \"${rule.description}\" (${rule.reason})"
                }
            }
        }
    }

    // ---- one GPS owner -------------------------------------------------------------------------------------

    private val platformLocationPackages = listOf("android.location", "com.google.android.gms.location")

    private val locationApiIdentifiers = listOf(
        "LocationManager",
        "LocationListener",
        "LocationCallback",
        "FusedLocationProviderClient",
        "LocationServices",
        "GnssStatus",
        "LOCATION_SERVICE",
        "requestLocationUpdates",
        "requestSingleUpdate",
        "getCurrentLocation",
        "registerGnssStatusCallback",
        "addNmeaListener",
    )

    /** `AndroidLocationSource` (and, if it is ever split up, `AndroidLocationSource*` helpers) is the only adapter. */
    fun isLocationAdapter(file: SourceFile): Boolean =
        file.inPackage("$ROOT.tracking.platform") &&
            file.path.substringAfterLast('/').startsWith("AndroidLocationSource")

    fun checkLocationApiConfinement(files: List<SourceFile>): List<Violation> =
        files.filterNot(::isLocationAdapter).flatMap { file ->
            val reason = "only tracking/platform/AndroidLocationSource may talk to the platform location APIs"
            val packageUses = platformLocationPackages.flatMap { prefix ->
                findReferences(file, prefix) { name -> "references `$name`; $reason" }
            }
            val identifierUses = locationApiIdentifiers.flatMap { identifier ->
                findIdentifier(file, identifier) { "uses `$identifier`; $reason" }
            }
            (packageUses + identifierUses).distinctBy { it.line }
        }

    private val gpsObserverPackages = listOf("ui", "service", "data", "permissions")

    /** UI, service, data and permissions code may never see the `LocationSource` that only the tracker collects. */
    fun checkLocationSourceOwnership(files: List<SourceFile>): List<Violation> =
        files.filter { file -> gpsObserverPackages.any { file.inPackage("$ROOT.$it") } }.flatMap { file ->
            listOf("LocationSource", "LocationSourceEvent").flatMap { identifier ->
                findIdentifier(file, identifier) {
                    "uses `$identifier`; only the TrackingController implementation may subscribe to location " +
                        "(one-GPS-owner rule), everything else observes TrackingController.state"
                }
            }
        }

    // ---- hard-coded URLs -----------------------------------------------------------------------------------

    private val urlPattern = Regex("""https?://[^\s"'<>)\]]+""", RegexOption.IGNORE_CASE)

    /**
     * URLs that may appear in main Kotlin sources. Empty on purpose: the app is offline and has no reason to
     * embed an address. Add an entry (with a justification) only for a reviewed exception.
     */
    val allowedUrlPrefixes: List<String> = emptyList()

    fun checkHardCodedUrls(
        files: List<SourceFile>,
        allowedPrefixes: List<String> = allowedUrlPrefixes,
    ): List<Violation> =
        files.flatMap { file ->
            file.codeWithStrings.lines().flatMapIndexed { index, line ->
                urlPattern.findAll(line)
                    .map { it.value }
                    .filter { url -> allowedPrefixes.none { url.startsWith(it) } }
                    .map { url ->
                        Violation(
                            file.path,
                            index + 1,
                            "hard-coded URL `$url` in main sources; Wayneedle is offline. " +
                                "If it is a reviewed exception, add it to ArchitectureRules.allowedUrlPrefixes.",
                        )
                    }
                    .toList()
            }
        }

    // ---- no coordinate logging -----------------------------------------------------------------------------

    private val loggingCall = Regex("""\b(?:Log\.[a-z]\s*\(|Timber\.|println\s*\(|print\s*\(|Logger\b)""")
    private val coordinateMention = Regex(
        """(?i)\b(?:latitude|longitude|lat|lon|lng|coordinates?)\b|\$\{?(?:sample|fix|location)\b""",
    )

    /**
     * Best-effort tripwire for PRIVACY.md ("coordinates are never written to the system log"): a single-line
     * logging or print call that mentions a coordinate or interpolates a whole fix. It cannot see calls split over
     * several lines, so code review remains the real control.
     */
    fun checkNoCoordinateLogging(files: List<SourceFile>): List<Violation> = files.flatMap { file ->
        file.codeWithStrings.lines().mapIndexedNotNull { index, line ->
            if (loggingCall.containsMatchIn(line) && coordinateMention.containsMatchIn(line)) {
                Violation(file.path, index + 1, "logs or prints a coordinate or a whole GPS fix; PRIVACY.md forbids it")
            } else {
                null
            }
        }
    }

    // ---- repository hygiene --------------------------------------------------------------------------------

    /** Paths of Java sources (the project is 100 % Kotlin) and Groovy build scripts (Kotlin DSL only). */
    fun checkNoJavaOrGroovy(paths: List<String>): List<Violation> = paths.mapNotNull { path ->
        when {
            path.endsWith(".java") -> Violation(path, 0, "Java source file; the project is 100 % Kotlin")

            path.endsWith(".groovy") || (path.endsWith(".gradle") && !path.endsWith(".gradle.kts")) ->
                Violation(path, 0, "Groovy build script; Gradle Kotlin DSL (*.gradle.kts) only")

            else -> null
        }
    }

    private val internetPermission = Regex("""android\.permission\.INTERNET\b""")

    fun checkNoInternetPermission(manifest: SourceFile): List<Violation> {
        val xml = TextMasker.maskXmlComments(manifest.text)
        return xml.lines().mapIndexedNotNull { index, line ->
            if (internetPermission.containsMatchIn(line)) {
                Violation(
                    manifest.path,
                    index + 1,
                    "declares android.permission.INTERNET; Wayneedle must have no network access. " +
                        "In a build/intermediates manifest it comes from a dependency: remove that dependency " +
                        "(if it is already gone, refresh with ./gradlew :app:processDebugManifest).",
                )
            } else {
                null
            }
        }
    }

    private val allowBackupAttribute = Regex("""android:allowBackup\s*=\s*"([^"]*)"""")

    fun checkBackupDisabled(manifest: SourceFile): List<Violation> {
        val xml = TextMasker.maskXmlComments(manifest.text)
        val value = allowBackupAttribute.find(xml)?.groupValues?.get(1)
        return if (value == "false") {
            emptyList()
        } else {
            listOf(
                Violation(
                    manifest.path,
                    0,
                    "android:allowBackup must be \"false\" (found ${value?.let { "\"$it\"" } ?: "no attribute"}); " +
                        "PRIVACY.md promises trips are never backed up to the cloud",
                ),
            )
        }
    }

    private val kotlinAndroidPlugin = listOf(
        Regex("""org\.jetbrains\.kotlin\.android"""),
        Regex("""kotlin\(\s*"android"\s*\)"""),
        Regex("""["']kotlin-android["']"""),
        Regex("""libs\.plugins\.kotlin\.android\b"""),
    )

    /** AGP 9 compiles Kotlin itself; applying the separate Kotlin Android plugin breaks the build. */
    fun checkNoKotlinAndroidPlugin(path: String, maskedText: String): List<Violation> =
        maskedText.lines().flatMapIndexed { index, line ->
            kotlinAndroidPlugin.filter { it.containsMatchIn(line) }.map {
                Violation(
                    path,
                    index + 1,
                    "applies the org.jetbrains.kotlin.android plugin; AGP 9 has built-in Kotlin support",
                )
            }
        }

    // ---- third-party / network dependencies ------------------------------------------------------------------

    /** Group-id prefixes of SDKs that would break the "offline, no analytics, no third-party SDK" promise. */
    val deniedGroups: List<String> = listOf(
        "com.google.firebase",
        "com.google.android.gms",
        "com.google.android.play",
        "com.google.mlkit",
        "com.google.gms",
        "com.crashlytics",
        "io.sentry",
        "com.bugsnag",
        "com.squareup.okhttp",
        "com.squareup.retrofit",
        "io.ktor",
        "com.mixpanel",
        "com.amplitude",
        "com.segment",
        "com.facebook",
        "com.appsflyer",
        "com.adjust",
    )

    private val declaredId = Regex("""(?:module|id)\s*=\s*"([^"]+)"""")
    private val pluginCall = Regex("""\bid\(\s*"([^"]+)"\s*\)""")
    private val coordinateLiteral = Regex(""""([A-Za-z][\w.\-]*:[\w.\-]+)(?::[^":]+)?"""")

    /** Checks module coordinates and plugin ids in a version catalog or build script (comments already masked). */
    fun checkNoDeniedDependencies(path: String, maskedText: String): List<Violation> =
        maskedText.lines().flatMapIndexed { index, line ->
            val declared = (declaredId.findAll(line) + pluginCall.findAll(line) + coordinateLiteral.findAll(line))
                .map { it.groupValues[1] }
                .toSet()
            declared.mapNotNull { id ->
                val group = id.substringBefore(':')
                val denied = deniedGroups.firstOrNull { group.startsWith(it) }
                    ?: "play-services".takeIf { it in id }
                denied?.let {
                    Violation(path, index + 1, "depends on `$id` ($it): no network, analytics or third-party SDKs")
                }
            }
        }

    // ---- reference helpers -----------------------------------------------------------------------------------

    private fun findReferences(file: SourceFile, prefix: String, message: (String) -> String): List<Violation> {
        val pattern = Regex("""(?<![\w.])${Regex.escape(prefix)}(?:\.[\w*]+)+""")
        return file.code.lines().flatMapIndexed { index, line ->
            if (line.trimStart().startsWith("package ")) {
                emptyList()
            } else {
                pattern.findAll(line).map { Violation(file.path, index + 1, message(it.value)) }.toList()
            }
        }
    }

    private fun findIdentifier(file: SourceFile, identifier: String, message: () -> String): List<Violation> {
        val pattern = Regex("""\b${Regex.escape(identifier)}\b""")
        return file.code.lines().flatMapIndexed { index, line ->
            if (pattern.containsMatchIn(line)) listOf(Violation(file.path, index + 1, message())) else emptyList()
        }
    }
}
