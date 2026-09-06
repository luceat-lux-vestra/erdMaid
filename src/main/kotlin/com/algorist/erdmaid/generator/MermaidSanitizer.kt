package com.algorist.erdmaid.generator

/**
 * Context-aware sanitization for database-controlled strings before they reach Mermaid.
 *
 * Internal database identity must never use these values. Sanitization is a rendering-only
 * boundary and deliberately preserves readable content by neutralising syntax characters
 * instead of dropping them.
 */
internal object MermaidSanitizer {

    private val ATTRIBUTE_KEYWORDS = setOf("PK", "FK", "UK")

    internal enum class Context {
        QUOTED_TEXT,
        ATTRIBUTE_TOKEN,
        ATTRIBUTE_COMMENT,
        LINE_COMMENT,
    }

    internal fun sanitize(text: String, context: Context): String {
        val neutral = neutralizeCommon(text)
        return when (context) {
            Context.QUOTED_TEXT,
            Context.LINE_COMMENT -> neutral
            Context.ATTRIBUTE_COMMENT -> neutral
                .replace('(', '（')
                .replace(')', '）')
            Context.ATTRIBUTE_TOKEN -> sanitizeAttributeToken(neutral)
        }
    }

    private fun neutralizeCommon(text: String): String = buildString(text.length) {
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                ch == '\r' && index + 1 < text.length && text[index + 1] == '\n' -> {
                    append('␤')
                    index += 2
                    continue
                }
                ch == '\r' || ch == '\n' || ch == '\u0085' || ch == '\u2028' || ch == '\u2029' ->
                    append('␤')
                ch == '"' -> append('\'')
                ch == '%' -> append('％')
                ch == '\\' -> append('＼')
                ch.code in 0x00..0x1f -> append((0x2400 + ch.code).toChar())
                ch == '\u007f' -> append('␡')
                else -> append(ch)
            }
            index++
        }
    }

    private fun sanitizeAttributeToken(text: String): String {
        require(text.isNotEmpty()) { "Cannot render an empty Mermaid attribute token" }

        val neutral = buildString(text.length) {
            for (ch in text) {
                when {
                    ch == ' ' -> append('_')
                    isAttributeBodyCharacter(ch) -> append(ch)
                    ch.code in 0x21..0x7e -> append((ch.code + 0xfee0).toChar())
                    else -> appendCodeUnit(ch)
                }
            }
        }

        var candidate = if (isAttributeFirstCharacter(neutral.first())) neutral else "_$neutral"
        if (candidate.uppercase() in ATTRIBUTE_KEYWORDS) {
            candidate = "_$candidate"
        }
        check(
            isAttributeFirstCharacter(candidate.first()) &&
                candidate.drop(1).all(::isAttributeBodyCharacter)
        ) {
            "Sanitizer produced an invalid Mermaid attribute token"
        }
        return candidate
    }

    private fun StringBuilder.appendCodeUnit(ch: Char) {
        append("_u")
        append(ch.code.toString(16).uppercase().padStart(4, '0'))
        append('_')
    }

    private fun isAttributeFirstCharacter(ch: Char): Boolean =
        ch == '*' || ch == '_' || ch in 'A'..'Z' || ch in 'a'..'z' || ch.code in 0x00c0..0xffff

    private fun isAttributeBodyCharacter(ch: Char): Boolean =
        isAttributeFirstCharacter(ch) ||
            ch in '0'..'9' ||
            ch == '-' || ch == '[' || ch == ']' || ch == '(' || ch == ')' ||
            ch == '.' || ch == ','
}
