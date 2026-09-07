package com.algorist.erdmaid.renderer

import com.algorist.erdmaid.core.CoreDiagnostic
import com.algorist.erdmaid.core.CoreDiagnostics
import com.algorist.erdmaid.core.Evidence
import com.algorist.erdmaid.core.ExportOutcome
import com.algorist.erdmaid.core.FrozenList
import com.algorist.erdmaid.core.TableId
import com.algorist.erdmaid.semantic.ErdGraph
import com.algorist.erdmaid.semantic.ErdGraphRelation
import com.algorist.erdmaid.semantic.ErdGraphTable
import com.algorist.erdmaid.semantic.RelationMultiplicityBounds
import com.algorist.erdmaid.semantic.RelationMultiplicityMaximum
import com.algorist.erdmaid.semantic.RelationMultiplicityMinimum
import com.algorist.erdmaid.semantic.TableQualificationIntent

/** Deterministic Mermaid-safe identity token plus readable encoded display alias. */
data class MermaidEntityToken(
    val tableId: TableId,
    val id: String,
    val alias: String,
)

/**
 * Relationship-end tokens prepared from a fully known semantic multiplicity.
 *
 * [parentEnd] is the left-side marker adjacent to the parent entity and [childEnd] is the
 * right-side marker adjacent to the child entity for a future Mermaid ER edge. The relationship
 * identification connector is intentionally not serialized in this token stage.
 */
data class MermaidRelationshipToken(
    val relation: ErdGraphRelation,
    val parentEntityId: String,
    val childEntityId: String,
    val parentEnd: String,
    val childEnd: String,
)

data class MermaidTokenSet(
    val entities: FrozenList<MermaidEntityToken>,
    val relationships: FrozenList<MermaidRelationshipToken>,
)

/**
 * First renderer boundary: canonical semantic identity/multiplicity -> Mermaid-safe textual tokens.
 *
 * This does not serialize a complete erDiagram document. It deliberately stops before attributes,
 * comments, labels, relationship identification, and final line formatting so incomplete renderer
 * output cannot be published.
 */
object MermaidTokenCompiler {

    fun compile(graph: ErdGraph): ExportOutcome<MermaidTokenSet> {
        val entities = ArrayList<MermaidEntityToken>(graph.tables.size)
        val byTable = LinkedHashMap<TableId, MermaidEntityToken>(graph.tables.size)
        val idOwners = LinkedHashMap<String, TableId>(graph.tables.size)
        val aliasOwners = LinkedHashMap<String, TableId>(graph.tables.size)

        for (table in graph.tables) {
            val token = entityToken(table)
            val priorIdOwner = idOwners.putIfAbsent(token.id, token.tableId)
            if (priorIdOwner != null && priorIdOwner != token.tableId) {
                return degraded(
                    code = "mermaid-entity-id-collision",
                    detail = token.id,
                )
            }
            val priorAliasOwner = aliasOwners.putIfAbsent(token.alias, token.tableId)
            if (priorAliasOwner != null && priorAliasOwner != token.tableId) {
                return degraded(
                    code = "mermaid-entity-alias-collision",
                    detail = token.alias,
                )
            }
            entities += token
            byTable[token.tableId] = token
        }

        val relationships = ArrayList<MermaidRelationshipToken>(graph.relations.size)
        for (relation in graph.relations) {
            val semantic = relation.relation.relation
            val parent = byTable.getValue(semantic.parentTable)
            val child = byTable.getValue(semantic.childTable)

            val parentEnd = when (
                val evidence = cardinalityToken(
                    bounds = relation.multiplicity.parentsPerChild,
                    side = MermaidCardinalitySide.LEFT,
                )
            ) {
                is Evidence.Known -> evidence.value
                is Evidence.Unavailable ->
                    return ExportOutcome.Degraded(CoreDiagnostics.of(evidence.diagnostic))
            }
            val childEnd = when (
                val evidence = cardinalityToken(
                    bounds = relation.multiplicity.childrenPerParent,
                    side = MermaidCardinalitySide.RIGHT,
                )
            ) {
                is Evidence.Known -> evidence.value
                is Evidence.Unavailable ->
                    return ExportOutcome.Degraded(CoreDiagnostics.of(evidence.diagnostic))
            }

            relationships += MermaidRelationshipToken(
                relation = relation,
                parentEntityId = parent.id,
                childEntityId = child.id,
                parentEnd = parentEnd,
                childEnd = childEnd,
            )
        }

        return ExportOutcome.Complete(
            MermaidTokenSet(
                entities = FrozenList.copyOf(entities),
                relationships = FrozenList.copyOf(relationships),
            )
        )
    }

    internal fun entityToken(table: ErdGraphTable): MermaidEntityToken = MermaidEntityToken(
        tableId = table.snapshot.id,
        id = canonicalEntityId(table.snapshot.id),
        alias = displayAlias(table),
    )

    internal fun cardinalityToken(
        bounds: RelationMultiplicityBounds,
        side: MermaidCardinalitySide,
    ): Evidence<String> {
        val minimum = when (val evidence = bounds.minimum) {
            is Evidence.Known -> evidence.value
            is Evidence.Unavailable -> return Evidence.Unavailable(
                CoreDiagnostic(
                    code = "mermaid-cardinality-unavailable",
                    detail = "minimum:${evidence.diagnostic.code}",
                )
            )
        }
        val maximum = when (val evidence = bounds.maximum) {
            is Evidence.Known -> evidence.value
            is Evidence.Unavailable -> return Evidence.Unavailable(
                CoreDiagnostic(
                    code = "mermaid-cardinality-unavailable",
                    detail = "maximum:${evidence.diagnostic.code}",
                )
            )
        }

        return Evidence.Known(cardinalityToken(minimum, maximum, side))
    }

    private fun cardinalityToken(
        minimum: RelationMultiplicityMinimum,
        maximum: RelationMultiplicityMaximum,
        side: MermaidCardinalitySide,
    ): String = when (side) {
        MermaidCardinalitySide.LEFT -> when (minimum) {
            RelationMultiplicityMinimum.ZERO -> when (maximum) {
                RelationMultiplicityMaximum.ONE -> "|o"
                RelationMultiplicityMaximum.MANY -> "}o"
            }
            RelationMultiplicityMinimum.ONE -> when (maximum) {
                RelationMultiplicityMaximum.ONE -> "||"
                RelationMultiplicityMaximum.MANY -> "}|"
            }
        }
        MermaidCardinalitySide.RIGHT -> when (minimum) {
            RelationMultiplicityMinimum.ZERO -> when (maximum) {
                RelationMultiplicityMaximum.ONE -> "o|"
                RelationMultiplicityMaximum.MANY -> "o{"
            }
            RelationMultiplicityMinimum.ONE -> when (maximum) {
                RelationMultiplicityMaximum.ONE -> "||"
                RelationMultiplicityMaximum.MANY -> "|{"
            }
        }
    }

    private fun canonicalEntityId(id: TableId): String = buildString {
        append("e_")
        appendIdComponent(id.origin.value)
        append('_')
        appendNullableIdComponent(id.catalog)
        append('_')
        appendNullableIdComponent(id.schema)
        append('_')
        appendIdComponent(id.name)
    }

    private fun StringBuilder.appendNullableIdComponent(value: String?) {
        if (value == null) {
            append('n')
        } else {
            appendIdComponent(value)
        }
    }

    private fun StringBuilder.appendIdComponent(value: String) {
        append('v')
        val bytes = value.toByteArray(Charsets.UTF_8)
        for (byte in bytes) {
            val unsigned = byte.toInt() and 0xff
            append(HEX[unsigned ushr 4])
            append(HEX[unsigned and 0x0f])
        }
    }

    private fun displayAlias(table: ErdGraphTable): String {
        val id = table.snapshot.id
        return when (table.qualification) {
            TableQualificationIntent.UNQUALIFIED -> encodeAliasComponent(id.name)
            TableQualificationIntent.SCHEMA ->
                listOf(encodeNullableAliasComponent(id.schema), encodeAliasComponent(id.name))
                    .joinToString(".")
            TableQualificationIntent.CATALOG_SCHEMA ->
                listOf(
                    encodeNullableAliasComponent(id.catalog),
                    encodeNullableAliasComponent(id.schema),
                    encodeAliasComponent(id.name),
                ).joinToString(".")
        }
    }

    private fun encodeNullableAliasComponent(value: String?): String =
        if (value == null) NULL_SLOT else encodeAliasComponent(value)

    private fun encodeAliasComponent(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (isUnsafeAliasCodePoint(codePoint)) {
                append("~u")
                appendCodePointHex(codePoint)
                append('~')
            } else {
                append(String(Character.toChars(codePoint)))
            }
            index += Character.charCount(codePoint)
        }
    }

    private fun isUnsafeAliasCodePoint(codePoint: Int): Boolean =
        codePoint < 0x20 ||
            codePoint == 0x7f ||
            codePoint == 0x2028 ||
            codePoint == 0x2029 ||
            codePoint in UNSAFE_ALIAS_ASCII

    private fun StringBuilder.appendCodePointHex(codePoint: Int) {
        val width = if (codePoint <= 0xffff) 4 else 6
        for (shift in (width - 1) * 4 downTo 0 step 4) {
            append(HEX[(codePoint ushr shift) and 0x0f])
        }
    }

    private fun degraded(code: String, detail: String? = null): ExportOutcome.Degraded =
        ExportOutcome.Degraded(CoreDiagnostics.of(CoreDiagnostic(code, detail)))

    private val HEX = "0123456789ABCDEF".toCharArray()
    private const val NULL_SLOT = "~null~"
    private val UNSAFE_ALIAS_ASCII = setOf(
        '.'.code,
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

internal enum class MermaidCardinalitySide {
    LEFT,
    RIGHT,
}
