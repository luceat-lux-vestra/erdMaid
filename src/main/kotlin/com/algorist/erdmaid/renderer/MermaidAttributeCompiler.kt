package com.algorist.erdmaid.renderer

import com.algorist.erdmaid.core.ColumnId
import com.algorist.erdmaid.core.CoreDiagnostic
import com.algorist.erdmaid.core.CoreDiagnostics
import com.algorist.erdmaid.core.Evidence
import com.algorist.erdmaid.core.ExportOutcome
import com.algorist.erdmaid.core.FrozenList
import com.algorist.erdmaid.core.OptionalValue
import com.algorist.erdmaid.core.RawTypeMetadata
import com.algorist.erdmaid.core.TableId
import com.algorist.erdmaid.core.WorkCheckpoint
import com.algorist.erdmaid.semantic.ErdGraph
import com.algorist.erdmaid.semantic.ErdGraphTable

/** Pure renderer token for one canonical database column. */
data class MermaidAttributeToken(
    val columnId: ColumnId,
    val type: String,
    val name: String,
    val primaryKey: Boolean,
    /** Null means authoritative absence; empty means an authoritative present empty comment. */
    val comment: String?,
)

/** Attribute tokens for one canonical entity, preserving authoritative source column order. */
data class MermaidEntityAttributeTokens(
    val tableId: TableId,
    val columns: FrozenList<MermaidAttributeToken>,
)

data class MermaidAttributeTokenSet(
    val entities: FrozenList<MermaidEntityAttributeTokens>,
)

/**
 * Canonical column/type/key/comment facts -> Mermaid attribute tokens.
 *
 * This stage does not emit Mermaid lines or a document. It preserves source ordering and fails
 * closed whenever required or presentation metadata is unavailable under the current product
 * contract.
 */
object MermaidAttributeCompiler {

    fun compile(graph: ErdGraph): ExportOutcome<MermaidAttributeTokenSet> =
        compile(graph, WorkCheckpoint.NONE)

    internal fun compile(
        graph: ErdGraph,
        checkpoint: WorkCheckpoint,
    ): ExportOutcome<MermaidAttributeTokenSet> {
        val entities = ArrayList<MermaidEntityAttributeTokens>(graph.tables.size)
        for (table in graph.tables) {
            checkpoint.check()
            when (val compiled = compileTable(table, checkpoint)) {
                is ExportOutcome.Complete -> entities += compiled.value
                ExportOutcome.NoExport -> return ExportOutcome.NoExport
                is ExportOutcome.Degraded -> return ExportOutcome.Degraded(compiled.diagnostics)
                is ExportOutcome.Unsupported -> return ExportOutcome.Unsupported(compiled.diagnostics)
                is ExportOutcome.Failure -> return ExportOutcome.Failure(compiled.diagnostics)
                ExportOutcome.Cancelled -> return ExportOutcome.Cancelled
            }
        }
        return ExportOutcome.Complete(
            MermaidAttributeTokenSet(FrozenList.copyOf(entities))
        )
    }

    internal fun compileTable(
        table: ErdGraphTable,
    ): ExportOutcome<MermaidEntityAttributeTokens> =
        compileTable(table, WorkCheckpoint.NONE)

    internal fun compileTable(
        table: ErdGraphTable,
        checkpoint: WorkCheckpoint,
    ): ExportOutcome<MermaidEntityAttributeTokens> {
        checkpoint.check()
        val primaryKeyColumns = when (val primaryKey = table.snapshot.primaryKey) {
            is OptionalValue.Present -> primaryKey.value.columns.toSet()
            OptionalValue.Absent -> emptySet()
            is OptionalValue.Unavailable -> return degraded(
                code = "mermaid-primary-key-unavailable",
                detail = primaryKey.diagnostic.code,
            )
        }

        val tokens = ArrayList<MermaidAttributeToken>(table.snapshot.columns.size)
        for (column in table.snapshot.columns) {
            checkpoint.check()
            val rawType = when (val evidence = column.rawType) {
                is Evidence.Known -> evidence.value
                is Evidence.Unavailable -> return degraded(
                    code = "mermaid-column-type-unavailable",
                    detail = "${column.id.name}:${evidence.diagnostic.code}",
                )
            }

            val typeToken = when (val encoded = encodeType(rawType)) {
                is ExportOutcome.Complete -> encoded.value
                ExportOutcome.NoExport -> return ExportOutcome.NoExport
                is ExportOutcome.Degraded -> return ExportOutcome.Degraded(encoded.diagnostics)
                is ExportOutcome.Unsupported -> return ExportOutcome.Unsupported(encoded.diagnostics)
                is ExportOutcome.Failure -> return ExportOutcome.Failure(encoded.diagnostics)
                ExportOutcome.Cancelled -> return ExportOutcome.Cancelled
            }
            val nameToken = encodeAttributeIdentifier(prefix = 'c', value = column.id.name)
            val comment = when (val value = column.comment) {
                is OptionalValue.Present -> encodeComment(value.value)
                OptionalValue.Absent -> null
                is OptionalValue.Unavailable -> return degraded(
                    code = "mermaid-column-comment-unavailable",
                    detail = "${column.id.name}:${value.diagnostic.code}",
                )
            }

            tokens += MermaidAttributeToken(
                columnId = column.id,
                type = typeToken,
                name = nameToken,
                primaryKey = column.id in primaryKeyColumns,
                comment = comment,
            )
        }

        firstColumnNameCollision(tokens)?.let { collision ->
            return degraded(
                code = "mermaid-column-name-collision",
                detail = collision,
            )
        }

        return ExportOutcome.Complete(
            MermaidEntityAttributeTokens(
                tableId = table.snapshot.id,
                columns = FrozenList.copyOf(tokens),
            )
        )
    }

    internal fun firstColumnNameCollision(tokens: List<MermaidAttributeToken>): String? {
        val owners = LinkedHashMap<String, ColumnId>(tokens.size)
        for (token in tokens) {
            val prior = owners.putIfAbsent(token.name, token.columnId)
            if (prior != null && prior != token.columnId) return token.name
        }
        return null
    }

    internal fun encodeType(rawType: RawTypeMetadata): ExportOutcome<String> {
        val length = optionalInt(rawType.length, "length")
        if (length is OptionalIntResult.Unavailable) return length.outcome
        val precision = optionalInt(rawType.precision, "precision")
        if (precision is OptionalIntResult.Unavailable) return precision.outcome
        val scale = optionalInt(rawType.scale, "scale")
        if (scale is OptionalIntResult.Unavailable) return scale.outcome

        val l = (length as? OptionalIntResult.Present)?.value
        val p = (precision as? OptionalIntResult.Present)?.value
        val s = (scale as? OptionalIntResult.Present)?.value
        val dimensions = when {
            // Precision + scale form one coherent SQL-style dimension pair. Some hosts also
            // report length for the same numeric type; do not leak that duplicate transport
            // metadata into the user-visible type as synthetic L/P/S labels.
            p != null && s != null -> "($p,$s)"
            // JetBrains DatabaseTools may expose the same character/binary size through both
            // length and precision. Collapse equal evidence rather than rendering it twice.
            l != null && p != null && s == null && l == p -> "($l)"
            l != null && p == null && s == null -> "($l)"
            l == null && p != null && s == null -> "($p)"
            // Conflicting optional details without a coherent renderer-neutral representation
            // are presentation metadata. Preserve the authoritative raw type name rather than
            // inventing dialect semantics or an invalid Mermaid token.
            else -> ""
        }

        // The authoritative type name may already include the same dimensions.
        // Avoid repeating an identical suffix; ambiguous optional details are intentionally
        // omitted instead of becoming synthetic database facts.
        val display = if (dimensions.isNotEmpty() && rawType.name.endsWith(dimensions)) {
            rawType.name
        } else {
            rawType.name + dimensions
        }
        return ExportOutcome.Complete(encodeAttributeIdentifier(prefix = 't', value = display))
    }

    /**
     * Prefer the source name itself. Mermaid 11.17.2 accepts ordinary identifier words
     * (including underscores and Unicode) and backtick-delimited attribute words.
     * Reserve one namespace for the rare unsafe fallback so mapping stays injective:
     * an actual identifier beginning with that namespace is also hex-encoded.
     * The prefix parameter only distinguishes type versus column fallback tokens.
     */
    internal fun encodeAttributeIdentifier(prefix: Char, value: String): String {
        require(prefix in 'A'..'Z' || prefix in 'a'..'z') {
            "Mermaid attribute token prefix must be alphabetic"
        }
        if (!value.startsWith(OPAQUE_ATTRIBUTE_PREFIX)) {
            if (isReadableAttributeWord(value)) return value
            if (isSafeBacktickAttributeWord(value)) return "`$value`"
        }
        return buildString {
            append(OPAQUE_ATTRIBUTE_PREFIX)
            append(prefix.uppercaseChar())
            append('_')
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val unsigned = byte.toInt() and 0xff
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }

    /**
     * Mermaid 11.17.2's ER block lexer classifies non-whitespace text containing `~...~` as an
     * attribute word before it can be recognized as a quoted COMMENT token. Quoted attribute
     * comments therefore use `_uXXXX_` escapes and never emit `~`; literal `_` is escaped too so
     * the representation remains injective instead of colliding with source text that resembles
     * an escape sequence.
     */
    internal fun encodeComment(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (isUnsafeCommentCodePoint(codePoint)) {
                append("_u")
                appendCodePointHex(codePoint)
                append('_')
            } else {
                appendCodePointValue(codePoint)
            }
            index += Character.charCount(codePoint)
        }
    }

    private fun optionalInt(
        value: OptionalValue<Int>,
        field: String,
    ): OptionalIntResult = when (value) {
        is OptionalValue.Present -> OptionalIntResult.Present(value.value)
        OptionalValue.Absent -> OptionalIntResult.Absent
        is OptionalValue.Unavailable -> OptionalIntResult.Unavailable(
            degraded(
                code = "mermaid-type-detail-unavailable",
                detail = "$field:${value.diagnostic.code}",
            )
        )
    }

    /** Conservative subset of the pinned Mermaid 11.17.2 ATTRIBUTE_WORD lexer. */
    private fun isReadableAttributeWord(value: String): Boolean {
        if (value.isEmpty() || listOf("PK", "FK", "UK").any { value.equals(it, ignoreCase = true) }) return false
        var offset = 0
        while (offset < value.length) {
            val cp = value.codePointAt(offset)
            val letter = cp <= 0xffff && Character.isLetter(cp)
            val valid = if (offset == 0) {
                letter || cp == '_'.code
            } else {
                letter || (Character.isDigit(cp) && cp <= 0xffff) ||
                    cp in READABLE_ATTRIBUTE_WORD_PUNCTUATION
            }
            if (!valid) return false
            offset += Character.charCount(cp)
        }
        return true
    }

    private fun isSafeBacktickAttributeWord(value: String): Boolean =
        value.isNotBlank() && value.codePoints().allMatch { cp ->
            // Quoting is for readable one-line SQL names, not a way to admit hostile
            // punctuation or markup into Mermaid's attribute/rendering context.
            Character.isLetterOrDigit(cp) ||
                cp in BACKTICK_ATTRIBUTE_WORD_PUNCTUATION ||
                cp == ' '.code
        }

    private fun isUnsafeCommentCodePoint(codePoint: Int): Boolean =
        codePoint < 0x20 ||
            codePoint == 0x7f ||
            codePoint == 0x2028 ||
            codePoint == 0x2029 ||
            codePoint in UNSAFE_COMMENT_ASCII

    private fun StringBuilder.appendCodePointValue(codePoint: Int) {
        append(String(Character.toChars(codePoint)))
    }

    private fun StringBuilder.appendCodePointHex(codePoint: Int) {
        val width = if (codePoint <= 0xffff) 4 else 6
        for (shift in (width - 1) * 4 downTo 0 step 4) {
            append(HEX[(codePoint ushr shift) and 0x0f])
        }
    }

    private fun degraded(code: String, detail: String? = null): ExportOutcome.Degraded =
        ExportOutcome.Degraded(CoreDiagnostics.of(CoreDiagnostic(code, detail)))

    private sealed interface OptionalIntResult {
        data class Present(val value: Int) : OptionalIntResult
        data object Absent : OptionalIntResult
        data class Unavailable(val outcome: ExportOutcome.Degraded) : OptionalIntResult
    }

    private const val OPAQUE_ATTRIBUTE_PREFIX = "ERDMAID_"
    private val READABLE_ATTRIBUTE_WORD_PUNCTUATION = setOf(
        '_'.code, '-'.code, '['.code, ']'.code,
        '('.code, ')'.code, '.'.code,
    )
    // Comma support differs across Mermaid ER lexer versions. Keep SQL-style precision/scale
    // readable through backtick quoting instead of relying on an unquoted comma token.
    private val BACKTICK_ATTRIBUTE_WORD_PUNCTUATION =
        READABLE_ATTRIBUTE_WORD_PUNCTUATION + ','.code
    private val HEX = "0123456789ABCDEF".toCharArray()
    private val UNSAFE_COMMENT_ASCII = setOf(
        '_'.code,
        '~'.code,
        '"'.code,
        '\\'.code,
        '%'.code,
        '{'.code,
        '}'.code,
        '['.code,
        ']'.code,
        '|'.code,
        ':'.code,
        '<'.code,
        '>'.code,
    )
}
