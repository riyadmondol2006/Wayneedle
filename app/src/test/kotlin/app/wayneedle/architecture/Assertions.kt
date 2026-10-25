package app.wayneedle.architecture

import kotlin.test.fail

/** Fails with one `file:line: explanation` row per violation so a broken rule can be fixed without hunting. */
internal fun assertNoViolations(rule: String, violations: List<Violation>) {
    if (violations.isEmpty()) return
    fail(
        buildString {
            appendLine("Rule violated: $rule")
            appendLine("${violations.size} violation(s):")
            violations.sortedWith(compareBy({ it.path }, { it.line })).forEach { appendLine("  $it") }
        },
    )
}
