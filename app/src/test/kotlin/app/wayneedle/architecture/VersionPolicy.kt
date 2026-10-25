package app.wayneedle.architecture

/**
 * Only exact, stable releases are allowed in the build: no dynamic (`+`), range, `latest`, snapshot or
 * pre-release (`alpha`, `beta`, `rc`, `dev`, `eap`, ...) versions.
 */
internal object VersionPolicy {
    private val unstableQualifier = Regex(
        "^(alpha|beta|rc|snapshot|dev|eap|latest|preview|canary|nightly|milestone|release-candidate)\\d*$|^m\\d+$",
        RegexOption.IGNORE_CASE,
    )
    private val separators = Regex("[.\\-_+\\s]+")

    /** Why [version] is not an exact stable release, or null when it is acceptable. */
    fun unstableReason(version: String): String? {
        val trimmed = version.trim()
        return when {
            trimmed.isEmpty() -> "empty version"

            '+' in trimmed -> "dynamic version (contains '+')"

            trimmed.any { it in "[](),*" } -> "version range or wildcard"

            else -> trimmed.split(separators)
                .firstOrNull { unstableQualifier.matches(it) }
                ?.let { "pre-release or unpinned qualifier '$it'" }
        }
    }

    private val versionLiteral = Regex("""(?<![\w.])version\s*=\s*"([^"]*)"""")
    private val richVersion = Regex("""\b(?:strictly|require|prefer)\s*=\s*"([^"]*)"""")
    private val versionsEntry = Regex("""^\s*([\w.\-]+)\s*=\s*"([^"]*)"""")
    private val sectionHeader = Regex("""^\s*\[([\w.\-]+)]\s*$""")

    /** Every version string declared in a version catalog (`[versions]` entries and inline `version = "..."`). */
    fun checkCatalog(path: String, tomlText: String): List<Violation> {
        var section = ""
        return TextMasker.maskHashComments(tomlText).lines().flatMapIndexed { index, line ->
            sectionHeader.find(line)?.let { section = it.groupValues[1] }
            val declared = buildList {
                if (section == "versions") versionsEntry.find(line)?.let { add(it.groupValues[2]) }
                versionLiteral.findAll(line).forEach { add(it.groupValues[1]) }
                richVersion.findAll(line).forEach { add(it.groupValues[1]) }
            }
            declared.mapNotNull { version ->
                unstableReason(version)?.let {
                    Violation(path, index + 1, "version \"$version\" is not an exact stable release: $it")
                }
            }
        }
    }

    private val inlineCoordinate = Regex(""""[A-Za-z][\w.\-]*:[\w.\-]+:([^"$:]+)"""")

    /** Literal `"group:artifact:version"` strings in a build script (comments already masked). */
    fun checkBuildScript(path: String, maskedScript: String): List<Violation> =
        maskedScript.lines().flatMapIndexed { index, line ->
            inlineCoordinate.findAll(line).mapNotNull { match ->
                val version = match.groupValues[1]
                unstableReason(version)?.let {
                    Violation(path, index + 1, "dependency version \"$version\" is not an exact stable release: $it")
                }
            }.toList()
        }
}
