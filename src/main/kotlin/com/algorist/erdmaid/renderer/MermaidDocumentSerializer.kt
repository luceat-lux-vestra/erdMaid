package com.algorist.erdmaid.renderer

import com.algorist.erdmaid.core.CoreDiagnostic
import com.algorist.erdmaid.core.CoreDiagnostics
import com.algorist.erdmaid.core.Evidence
import com.algorist.erdmaid.core.ExportOutcome
import com.algorist.erdmaid.core.OptionalValue
import com.algorist.erdmaid.core.RelationProvenance
import com.algorist.erdmaid.semantic.ErdGraph
import com.algorist.erdmaid.semantic.RelationIdentification

/** Pure options for deterministic Mermaid document serialization. */
data class MermaidDocumentOptions(
    val includeColumnReferences: Boolean = true,
)

/**
 * Final pure renderer boundary: established semantic facts -> publishable Mermaid `erDiagram`.
 *
 * This serializer never derives database semantics. Entity/cardinality tokens and attribute facts
 * come only from the existing compilers; any unavailable required fact terminates without a
 * partial document payload.
 */
object MermaidDocumentSerializer {

    fun serialize(
        graph: ErdGraph,
        options: MermaidDocumentOptions = MermaidDocumentOptions(),
    ): ExportOutcome<String> {
        if (graph.tables.isEmpty()) {
            return failure("mermaid-empty-graph")
        }

        val tokens = when (val outcome = MermaidTokenCompiler.compile(graph)) {
            is ExportOutcome.Complete -> outcome.value
            is ExportOutcome.Degraded -> return ExportOutcome.Degraded(outcome.diagnostics)
            is ExportOutcome.Unsupported -> return ExportOutcome.Unsupported(outcome.diagnostics)
            is ExportOutcome.Failure -> return ExportOutcome.Failure(outcome.diagnostics)
            ExportOutcome.Cancelled -> return ExportOutcome.Cancelled
        }
        val attributes = when (val outcome = MermaidAttributeCompiler.compile(graph)) {
            is ExportOutcome.Complete -> outcome.value
            is ExportOutcome.Degraded -> return ExportOutcome.Degraded(outcome.diagnostics)
            is ExportOutcome.Unsupported -> return ExportOutcome.Unsupported(outcome.diagnostics)
            is ExportOutcome.Failure -> return ExportOutcome.Failure(outcome.diagnostics)
            ExportOutcome.Cancelled -> return ExportOutcome.Cancelled
        }

        if (tokens.entities.size != graph.tables.size || attributes.entities.size != graph.tables.size) {
            return failure("mermaid-renderer-entity-token-mismatch")
        }
        if (tokens.relationships.size != graph.relations.size) {
            return failure("mermaid-renderer-relation-token-mismatch")
        }

        val graphTablesById = graph.tables.associateBy { it.snapshot.id }
        val attributesByTable = attributes.entities.associateBy { it.tableId }

        val document = StringBuilder("erDiagram\n")
        for (entity in tokens.entities) {
            val table = graphTablesById[entity.tableId]
                ?: return failure("mermaid-renderer-entity-token-mismatch", entity.id)
            val entityAttributes = attributesByTable[entity.tableId]
                ?: return failure("mermaid-renderer-attribute-token-mismatch", entity.id)

            if (!isSafeQuotedAliasBody(entity.alias)) {
                return failure("mermaid-entity-alias-wrapper-unsafe", entity.id)
            }

            when (val comment = table.snapshot.comment) {
                is OptionalValue.Present -> {
                    document.append("    %% table-comment: ")
                    document.append(encodeContextText(comment.value))
                    document.append('\n')
                }
                OptionalValue.Absent -> Unit
                is OptionalValue.Unavailable -> return degraded(
                    code = "mermaid-table-comment-unavailable",
                    detail = comment.diagnostic.code,
                )
            }

            document.append("    ")
            document.append(entity.id)
            document.append("[\"")
            document.append(entity.alias)
            document.append("\"] {\n")
            for (attribute in entityAttributes.columns) {
                document.append("        ")
                document.append(attribute.type)
                document.append(' ')
                document.append(attribute.name)
                if (attribute.primaryKey) {
                    document.append(" PK")
                }
                if (attribute.comment != null) {
                    document.append(" \"")
                    document.append(attribute.comment)
                    document.append('"')
                }
                document.append('\n')
            }
            document.append("    }\n")
        }

        for (relationship in tokens.relationships) {
            val relation = relationship.relation.relation.relation

            if (options.includeColumnReferences) {
                document.append("    %% FK: ")
                document.append(provenanceLabel(relation.provenance))
                document.append(' ')
                relation.mappings.forEachIndexed { index, mapping ->
                    if (index > 0) document.append(", ")
                    document.append(relationship.childEntityId)
                    document.append('.')
                    document.append(encodeContextText(mapping.child.name))
                    document.append(" -> ")
                    document.append(relationship.parentEntityId)
                    document.append('.')
                    document.append(encodeContextText(mapping.parent.name))
                }
                document.append('\n')
            }

            val connector = when (val identification = relationship.relation.identification) {
                is Evidence.Known -> when (identification.value) {
                    RelationIdentification.IDENTIFYING -> "--"
                    RelationIdentification.NON_IDENTIFYING -> ".."
                }
                is Evidence.Unavailable -> return degraded(
                    code = "mermaid-relation-identification-unavailable",
                    detail = identification.diagnostic.code,
                )
            }
            val label = when (val name = relation.name) {
                is OptionalValue.Present ->
                    provenanceLabel(relation.provenance) + ":" + encodeContextText(name.value)
                OptionalValue.Absent -> provenanceLabel(relation.provenance)
                is OptionalValue.Unavailable -> return degraded(
                    code = "mermaid-relation-name-unavailable",
                    detail = name.diagnostic.code,
                )
            }

            document.append("    ")
            document.append(relationship.parentEntityId)
            document.append(' ')
            document.append(relationship.parentEnd)
            document.append(connector)
            document.append(relationship.childEnd)
            document.append(' ')
            document.append(relationship.childEntityId)
            document.append(" : \"")
            document.append(label)
            document.append("\"\n")
        }

        return ExportOutcome.Complete(document.toString())
    }

    internal fun encodeContextText(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (isUnsafeContextCodePoint(codePoint)) {
                append("~u")
                appendCodePointHex(codePoint)
                append('~')
            } else {
                append(String(Character.toChars(codePoint)))
            }
            index += Character.charCount(codePoint)
        }
    }

    private fun isSafeQuotedAliasBody(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (
                codePoint < 0x20 ||
                codePoint == 0x7f ||
                codePoint == 0x2028 ||
                codePoint == 0x2029 ||
                codePoint == '"'.code ||
                codePoint == '\\'.code
            ) {
                return false
            }
            index += Character.charCount(codePoint)
        }
        return true
    }

    private fun provenanceLabel(provenance: RelationProvenance): String = when (provenance) {
        RelationProvenance.PHYSICAL -> "physical"
        RelationProvenance.VIRTUAL -> "virtual"
    }

    private fun StringBuilder.appendCodePointHex(codePoint: Int) {
        val width = if (codePoint <= 0xffff) 4 else 6
        for (shift in (width - 1) * 4 downTo 0 step 4) {
            append(HEX[(codePoint ushr shift) and 0x0f])
        }
    }

    private fun degraded(code: String, detail: String? = null): ExportOutcome.Degraded =
        ExportOutcome.Degraded(CoreDiagnostics.of(CoreDiagnostic(code, detail)))

    private fun failure(code: String, detail: String? = null): ExportOutcome.Failure =
        ExportOutcome.Failure(CoreDiagnostics.of(CoreDiagnostic(code, detail)))

    private val HEX = "0123456789ABCDEF".toCharArray()
    private val UNSAFE_CONTEXT_ASCII = setOf(
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

    private fun isUnsafeContextCodePoint(codePoint: Int): Boolean =
        codePoint < 0x20 ||
            codePoint == 0x7f ||
            codePoint == 0x2028 ||
            codePoint == 0x2029 ||
            codePoint in UNSAFE_CONTEXT_ASCII
}
