package com.algorist.erdmaid.generator

import com.algorist.erdmaid.generator.RelationResolver.TableIdentity
import com.intellij.database.model.DasTable
import com.intellij.database.util.DasUtil
import com.intellij.openapi.project.Project

object MermaidGenerator {

    private const val INDENT = "    "
    private val ENTITY_NAME_SAFE = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val ENTITY_NAME_RESERVED = setOf(
        "erDiagram",
        "style",
        "classDef",
        "class",
        "subgraph",
        "end",
        "many",
        "one",
        "to",
    )

    // Internal data classes to decouple string-building logic from the IntelliJ DB API,
    // enabling straightforward unit testing without requiring live DB objects.
    internal data class TableSpec(
        val identity: TableIdentity,
        val comment: String?,
        val columns: List<ColumnSpec>,
        val relations: List<RelationResolver.RelationSpec>
    )

    internal data class ColumnSpec(
        val name: String,
        val typeName: String,
        val isPrimaryKey: Boolean,
        val comment: String?
    )

    data class MermaidRenderOptions(
        val includeColumnReferences: Boolean = false,
    )

    fun generate(project: Project?, tables: List<DasTable>): String {
        return generate(project, tables, MermaidRenderOptions())
    }

    fun generate(
        project: Project?,
        tables: List<DasTable>,
        options: MermaidRenderOptions,
    ): String {
        val relationMap = RelationResolver.resolve(project, tables)
        return buildDiagram(
            tables.map { table ->
                val identity = RelationResolver.tableIdentity(table)
                toTableSpec(table, identity, relationMap[identity].orEmpty())
            },
            options,
        )
    }

    fun generate(tables: List<DasTable>): String {
        return generate(null, tables)
    }

    @Suppress("DEPRECATION") // DasType.toDataType() is deprecated but remains the stable
    // way to access the column's data type name in the current IntelliJ Database API.
    private fun toTableSpec(
        table: DasTable,
        identity: TableIdentity,
        relations: List<RelationResolver.RelationSpec>,
    ): TableSpec {
        // Collect PK column names via columnsRef.iterate() to support composite PKs.
        // MultiRef.It is IntelliJ's own cursor (not java.util.Iterator), so a while loop
        // is required; iterate().next() returns the column name directly as a String.
        val pkNames = mutableSetOf<String>()
        DasUtil.getPrimaryKey(table)?.let { pk ->
            val iter = pk.columnsRef.iterate()
            while (iter.hasNext()) {
                pkNames.add(iter.next())
            }
        }

        val columns = DasUtil.getColumns(table).map { col ->
            val dataType = col.dasType.toDataType()
            ColumnSpec(
                name = col.name,
                typeName = renderColumnType(dataType.typeName, dataType),
                isPrimaryKey = col.name in pkNames,
                comment = col.comment
            )
        }.toList()

        return TableSpec(
            identity = identity,
            comment = table.comment?.takeIf { it.isNotBlank() },
            columns = columns,
            relations = relations
        )
    }

    internal fun buildDiagram(
        tables: List<TableSpec>,
        options: MermaidRenderOptions = MermaidRenderOptions(),
    ): String {
        val entityNames = entityNamesFor(tables)
        tables.forEach(::requireUniqueRenderedColumnNames)

        return buildString {
            appendLine("erDiagram")

            for (table in tables) {
                appendTableBlock(table, entityNames)
                appendLine()
            }

            for (table in tables) {
                appendForeignKeys(table, options, entityNames)
            }
        }
    }

    internal fun entityNamesFor(tables: List<TableSpec>): Map<TableIdentity, String> {
        val duplicateIdentity = tables.groupBy { it.identity }.entries.firstOrNull { it.value.size > 1 }
        if (duplicateIdentity != null) {
            throw IllegalArgumentException(
                "Ambiguous selected table identity: ${duplicateIdentity.key.qualifiedName}"
            )
        }

        val byUnqualifiedName = tables.groupBy { it.identity.name }
        val rendered = tables.map { table ->
            val identity = table.identity
            val sameNamed = byUnqualifiedName.getValue(identity.name).map { it.identity }
            val rawName = when {
                sameNamed.size == 1 -> identity.name
                schemasDisambiguate(sameNamed) -> schemaQualifiedName(identity)
                else -> identity.qualifiedName
            }
            val sanitizedName = MermaidSanitizer.sanitize(
                rawName,
                MermaidSanitizer.Context.QUOTED_TEXT,
            )
            if (sanitizedName.isEmpty()) {
                throw IllegalArgumentException(
                    "Cannot render empty table identity: ${identity.qualifiedName}"
                )
            }
            identity to sanitizedName
        }

        val duplicateRenderedName = rendered.groupBy { it.second }
            .entries
            .firstOrNull { it.value.size > 1 }
        if (duplicateRenderedName != null) {
            throw IllegalArgumentException(
                "Ambiguous rendered table identity: ${duplicateRenderedName.key}"
            )
        }

        return LinkedHashMap<TableIdentity, String>().apply {
            for ((identity, renderedName) in rendered) {
                put(identity, renderedName)
            }
        }
    }

    private fun schemasDisambiguate(identities: List<TableIdentity>): Boolean {
        if (identities.any { it.schema == null }) return false
        return identities.map(::schemaQualifiedName).toSet().size == identities.size
    }

    private fun schemaQualifiedName(identity: TableIdentity): String =
        listOfNotNull(identity.schema).plus(identity.name).joinToString(".")

    private fun requireUniqueRenderedColumnNames(table: TableSpec) {
        val duplicate = table.columns
            .groupBy { normalizeIdentifier(it.name) }
            .entries
            .firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            throw IllegalArgumentException(
                "Ambiguous rendered column identity in ${table.identity.qualifiedName}: ${duplicate.key}"
            )
        }
    }

    private fun StringBuilder.appendTableBlock(
        table: TableSpec,
        entityNames: Map<TableIdentity, String>,
    ) {
        if (table.comment != null) {
            appendLine(
                "%% ${MermaidSanitizer.sanitize(table.comment, MermaidSanitizer.Context.LINE_COMMENT)}"
            )
        }
        appendLine("$INDENT${renderEntityName(entityNames.getValue(table.identity))} {")
        for (col in table.columns) {
            append(formatColumn(col))
        }
        appendLine("$INDENT}")
    }

    private fun StringBuilder.appendForeignKeys(
        table: TableSpec,
        options: MermaidRenderOptions,
        entityNames: Map<TableIdentity, String>,
    ) {
        for (relation in table.relations) {
            if (options.includeColumnReferences) {
                appendLine("%% FK: ${formatColumnReference(relation, entityNames)}")
            }
            appendLine(
                "$INDENT${renderEntityName(entityNames.getValue(relation.parentTable))} ||--o{ " +
                    "${renderEntityName(entityNames.getValue(relation.childTable))} : ${renderRelationLabel(relation.name)}"
            )
        }
    }

    private fun normalizeIdentifier(text: String): String =
        MermaidSanitizer.sanitize(text, MermaidSanitizer.Context.ATTRIBUTE_TOKEN)

    private fun formatColumn(col: ColumnSpec): String {
        val type = normalizeIdentifier(col.typeName)
        val safeName = normalizeIdentifier(col.name)
        val parts = mutableListOf(type, safeName)
        if (col.isPrimaryKey) parts.add("PK")
        if (!col.comment.isNullOrEmpty()) {
            parts.add(
                "\"${MermaidSanitizer.sanitize(col.comment, MermaidSanitizer.Context.ATTRIBUTE_COMMENT)}\""
            )
        }
        return "$INDENT$INDENT${parts.joinToString(" ")}\n"
    }

    private fun renderEntityName(name: String): String {
        val sanitized = MermaidSanitizer.sanitize(name, MermaidSanitizer.Context.QUOTED_TEXT)
        require(sanitized.isNotEmpty()) { "Cannot render an empty Mermaid entity name" }
        val canRenderBare = ENTITY_NAME_SAFE.matches(sanitized) &&
            ENTITY_NAME_RESERVED.none { sanitized.equals(it, ignoreCase = true) }
        return if (canRenderBare) sanitized else "\"$sanitized\""
    }

    private fun formatColumnReference(
        relation: RelationResolver.RelationSpec,
        entityNames: Map<TableIdentity, String>,
    ): String {
        val child = relation.childColumns.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "*"
        val parent = relation.parentColumns.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "*"
        val reference = "${entityNames.getValue(relation.childTable)}.$child -> " +
            "${entityNames.getValue(relation.parentTable)}.$parent"
        return MermaidSanitizer.sanitize(reference, MermaidSanitizer.Context.LINE_COMMENT)
    }

    private fun renderRelationLabel(label: String): String {
        val sanitized = MermaidSanitizer.sanitize(
            label.trim(),
            MermaidSanitizer.Context.QUOTED_TEXT,
        )
        return if (sanitized.isBlank()) "\"\"" else "\"$sanitized\""
    }

    internal fun renderColumnType(typeName: String, dataType: Any? = null): String {
        val baseType = typeName.trim()
        if (baseType.contains('(')) return baseType

        val suffix = renderTypeSuffix(typeName, dataType)
        return baseType + suffix
    }

    private fun renderTypeSuffix(typeName: String, dataType: Any?): String {
        val normalized = typeName.lowercase().replace(Regex("\\s+"), "")
        return when {
            isPrecisionScaleType(normalized) -> renderPrecisionScaleSuffix(dataType)
            isLengthType(normalized) -> renderLengthSuffix(dataType)
            isPrecisionOnlyType(normalized) -> renderPrecisionOnlySuffix(dataType)
            else -> null
        }.orEmpty()
    }

    private fun isLengthType(typeName: String): Boolean =
        listOf("char", "binary", "varbinary", "nvarchar", "varchar", "nchar", "bpchar", "bit")
            .any { typeName.contains(it) }

    private fun isPrecisionScaleType(typeName: String): Boolean =
        listOf("decimal", "numeric", "number")
            .any { typeName.contains(it) }

    private fun isPrecisionOnlyType(typeName: String): Boolean =
        listOf("timestamp", "datetime", "time", "interval")
            .any { typeName.contains(it) }

    private fun renderLengthSuffix(dataType: Any?): String? {
        val length = dataType.readIntProperty("length", "size")
        return length?.takeIf { it > 0 }?.let { "($it)" }
    }

    private fun renderPrecisionScaleSuffix(dataType: Any?): String? {
        val precision = dataType.readIntProperty("precision", "size")
        if (precision == null || precision <= 0) return null

        val scale = dataType.readIntProperty("scale")
        return if (scale != null && scale >= 0) {
            "(${precision}_${scale})"
        } else {
            "($precision)"
        }
    }

    private fun renderPrecisionOnlySuffix(dataType: Any?): String? {
        val precision = dataType.readIntProperty("precision", "scale", "length", "size")
        return precision?.takeIf { it > 0 }?.let { "($it)" }
    }

    private fun Any?.readIntProperty(vararg names: String): Int? {
        val receiver = this ?: return null
        for (name in names) {
            val candidates = listOf(
                name,
                "get" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            )
            val method = receiver.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.name in candidates
            } ?: continue

            val value = runCatching { method.invoke(receiver) }.getOrNull() ?: continue
            when (value) {
                is Int -> return value
                is Number -> return value.toInt()
            }
        }
        return null
    }
}
