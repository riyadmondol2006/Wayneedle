package app.wayneedle.architecture

/**
 * A text file of the repository, addressed by its `/`-separated path relative to the repository root.
 * Kept free of `java.io.File` so rules can be exercised on synthetic sources in [RuleSelfTest].
 */
class SourceFile(val path: String, val text: String) {
    /** Kotlin code with comments removed; string and character literals are kept. */
    val codeWithStrings: String by lazy { KotlinMasker.maskComments(text) }

    /** Kotlin code with comments removed and the content of string and character literals blanked. */
    val code: String by lazy { KotlinMasker.maskCommentsAndStrings(text) }

    /** The declared Kotlin package, or an empty string when the file has none. */
    val packageName: String by lazy {
        PACKAGE_DECLARATION.find(code)?.groupValues?.get(1).orEmpty()
    }

    fun inPackage(packagePrefix: String): Boolean =
        packageName == packagePrefix || packageName.startsWith("$packagePrefix.")

    private companion object {
        val PACKAGE_DECLARATION = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
    }
}

/** One broken rule at a precise location. [line] is 1-based; 0 means "the file as a whole". */
data class Violation(val path: String, val line: Int, val message: String) {
    override fun toString(): String = if (line > 0) "$path:$line: $message" else "$path: $message"
}

/**
 * Removes Kotlin comments (and optionally the content of literals) while keeping every newline, so line
 * numbers computed on the masked text match the original file. Handles nested block comments, raw strings
 * and `${...}` templates that themselves contain strings.
 */
internal object KotlinMasker {
    fun maskComments(source: String): String = Masker(source, blankStrings = false).run()

    fun maskCommentsAndStrings(source: String): String = Masker(source, blankStrings = true).run()

    private class Masker(private val s: String, private val blankStrings: Boolean) {
        private val out = StringBuilder(s.length)
        private var i = 0

        fun run(): String {
            code(inTemplate = false, blank = false)
            return out.toString()
        }

        private fun emit(c: Char, blank: Boolean) {
            out.append(if (blank && c != '\n' && c != '\r') ' ' else c)
        }

        /** Consumes code until the end of input or, inside a `${...}` template, the template's closing brace. */
        private fun code(inTemplate: Boolean, blank: Boolean) {
            var depth = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    s.startsWith("//", i) -> lineComment()

                    s.startsWith("/*", i) -> blockComment()

                    s.startsWith("\"\"\"", i) -> rawString()

                    c == '"' -> string()

                    c == '\'' -> charLiteral()

                    c == '`' -> backticked(blank)

                    c == '{' -> {
                        depth++
                        emit(c, blank)
                        i++
                    }

                    c == '}' -> {
                        if (inTemplate && depth == 0) return
                        depth--
                        emit(c, blank)
                        i++
                    }

                    else -> {
                        emit(c, blank)
                        i++
                    }
                }
            }
        }

        private fun lineComment() {
            while (i < s.length && s[i] != '\n') {
                emit(s[i], blank = true)
                i++
            }
        }

        private fun blockComment() {
            var depth = 0
            while (i < s.length) {
                when {
                    s.startsWith("/*", i) -> {
                        depth++
                        out.append("  ")
                        i += 2
                    }

                    s.startsWith("*/", i) -> {
                        depth--
                        out.append("  ")
                        i += 2
                        if (depth == 0) return
                    }

                    else -> {
                        emit(s[i], blank = true)
                        i++
                    }
                }
            }
        }

        private fun string() {
            out.append('"')
            i++
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\\' && i + 1 < s.length -> {
                        emit(c, blankStrings)
                        emit(s[i + 1], blankStrings)
                        i += 2
                    }

                    c == '"' -> {
                        out.append('"')
                        i++
                        return
                    }

                    c == '\n' -> return

                    s.startsWith("\${", i) -> template()

                    else -> {
                        emit(c, blankStrings)
                        i++
                    }
                }
            }
        }

        private fun rawString() {
            out.append("\"\"\"")
            i += 3
            while (i < s.length) {
                when {
                    s.startsWith("\"\"\"", i) -> {
                        var quotes = 0
                        while (i + quotes < s.length && s[i + quotes] == '"') quotes++
                        repeat(quotes - 3) { emit('"', blankStrings) }
                        out.append("\"\"\"")
                        i += quotes
                        return
                    }

                    s.startsWith("\${", i) -> template()

                    else -> {
                        emit(s[i], blankStrings)
                        i++
                    }
                }
            }
        }

        private fun template() {
            emit('$', blankStrings)
            emit('{', blankStrings)
            i += 2
            code(inTemplate = true, blank = blankStrings)
            if (i < s.length) {
                emit('}', blankStrings)
                i++
            }
        }

        private fun charLiteral() {
            var j = i + 1
            while (j < s.length && s[j] != '\'' && j - i < MAX_CHAR_LITERAL_LENGTH) {
                if (s[j] == '\\') j++
                j++
            }
            if (j < s.length && s[j] == '\'') {
                out.append('\'')
                for (k in i + 1 until j) emit(s[k], blankStrings)
                out.append('\'')
                i = j + 1
            } else {
                out.append('\'')
                i++
            }
        }

        private fun backticked(blank: Boolean) {
            val end = s.indexOf('`', i + 1)
            val newline = s.indexOf('\n', i + 1)
            if (end == -1 || (newline != -1 && newline < end)) {
                emit('`', blank)
                i++
                return
            }
            for (k in i..end) emit(s[k], blank)
            i = end + 1
        }

        private companion object {
            /** Longest char literal is a unicode escape such as `'A'` (8 characters). */
            const val MAX_CHAR_LITERAL_LENGTH = 8
        }
    }
}

/** Comment stripping for the other text formats the rules read. */
internal object TextMasker {
    /** Replaces `<!-- ... -->` with spaces, keeping newlines. */
    fun maskXmlComments(xml: String): String {
        val out = StringBuilder(xml.length)
        var i = 0
        while (i < xml.length) {
            if (xml.startsWith("<!--", i)) {
                val end = xml.indexOf("-->", i + 4).let { if (it == -1) xml.length else it + 3 }
                for (k in i until end) out.append(if (xml[k] == '\n') '\n' else ' ')
                i = end
            } else {
                out.append(xml[i])
                i++
            }
        }
        return out.toString()
    }

    /** Drops `#` comments (TOML) while respecting quoted strings; keeps newlines. */
    fun maskHashComments(text: String): String = text.lines().joinToString("\n") { line ->
        var quoted = false
        var cut = line.length
        for ((index, c) in line.withIndex()) {
            if (c == '"') quoted = !quoted
            if (c == '#' && !quoted) {
                cut = index
                break
            }
        }
        line.substring(0, cut)
    }
}
