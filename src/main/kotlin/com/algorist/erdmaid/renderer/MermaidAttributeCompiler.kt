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

        val token = buildString {
            append(encodeAttributeIdentifier(prefix = 't', value = rawType.name))
            appendOptionalTypeDetail("L", length)
            appendOptionalTypeDetail("P", precision)
            appendOptionalTypeDetail("S", scale)
        }
        return ExportOutcome.Complete(token)
    }

    internal fun encodeAttributeIdentifier(prefix: Char, value: String): String {
        require(prefix in 'A'..'Z' || prefix in 'a'..'z') {
            "Mermaid attribute token prefix must be alphabetic"
        }
        return buildString {
            append(prefix)
            append('_')
            var index = 0
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                if (isDirectAttributeCodePoint(codePoint)) {
                    appendCodePointValue(codePoint)
                } else {
                    append("_u")
                    appendCodePointHex(codePoint)
                    append('_')
                }
                index += Character.charCount(codePoint)
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

    private fun StringBuilder.appendOptionalTypeDetail(
        label: String,
        result: OptionalIntResult,
    ) {
        if (result is OptionalIntResult.Present) {
            append("__")
            append(label)
            append(result.value)
        }
    }

    /**
     * Mermaid ER attribute type/name grammar allows digits, hyphens, underscores, parentheses and
     * square brackets after an alphabetic start. The generated prefix supplies that start.
     * Underscore is intentionally encoded rather than passed through so `_uXXXX_` remains an
     * injective escape form instead of colliding with literal source text.
     */
    private fun isDirectAttributeCodePoint(codePoint: Int): Boolean =
        Character.isLetter(codePoint) ||
            Character.isDigit(codePoint) ||
            codePoint == '-'.code ||
            codePoint == '('.code ||
            codePoint == ')'.code ||
            codePoint == '['.code ||
            codePoint == ']'.code

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
