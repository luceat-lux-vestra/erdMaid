package com.algorist.erdmaid.generator

import com.intellij.database.model.DasTable
import com.intellij.database.util.DasUtil
import com.intellij.openapi.project.Project

object MermaidGenerator {

    private const val INDENT = "    "
    private val ENTITY_NAME_SAFE = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    // Internal data classes to decouple string-building logic from the IntelliJ DB API,
    // enabling straightforward unit testing without requiring live DB objects.
    internal data class TableSpec(
        val name: String,
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
            tables.map { toTableSpec(it, relationMap[it.name].orEmpty()) },
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
            ColumnSpec(
                name = col.name,
                typeName = col.dasType.toDataType().typeName,
                isPrimaryKey = col.name in pkNames,
                comment = col.comment
            )
        }.toList()

        return TableSpec(
            name = table.name,
            comment = table.comment?.takeIf { it.isNotBlank() },
            columns = columns,
            relations = relations
        )
    }

    internal fun buildDiagram(
        tables: List<TableSpec>,
        options: MermaidRenderOptions = MermaidRenderOptions(),
    ): String = buildString {
        appendLine("erDiagram")

        for (table in tables) {
            appendTableBlock(table)
            appendLine()
        }

        for (table in tables) {
            appendForeignKeys(table, options)
        }
    }

    private fun StringBuilder.appendTableBlock(table: TableSpec) {
        if (table.comment != null) {
            appendLine("%% ${table.comment}")
        }
        appendLine("$INDENT${renderEntityName(table.name)} {")
        for (col in table.columns) {
            append(formatColumn(col))
        }
        appendLine("$INDENT}")
    }

    private fun StringBuilder.appendForeignKeys(table: TableSpec, options: MermaidRenderOptions) {
        for (relation in table.relations) {
            if (options.includeColumnReferences) {
                appendLine("%% FK: ${formatColumnReference(relation)}")
            }
            appendLine(
                "$INDENT${renderEntityName(relation.parentTableName)} ||--o{ ${renderEntityName(relation.childTableName)} : ${renderRelationLabel(relation.name)}"
            )
        }
    }

    private fun formatColumn(col: ColumnSpec): String {
        val type = col.typeName.replace(' ', '_')
        val parts = mutableListOf(type, col.name)
        if (col.isPrimaryKey) parts.add("PK")
        if (!col.comment.isNullOrEmpty()) {
            parts.add("\"${sanitizeMermaidComment(col.comment)}\"")
        }
        return "$INDENT$INDENT${parts.joinToString(" ")}\n"
    }

    private fun renderEntityName(name: String): String {
        val sanitized = sanitizeMermaidText(name)
        return if (ENTITY_NAME_SAFE.matches(sanitized)) sanitized else "\"$sanitized\""
    }

    private fun formatColumnReference(relation: RelationResolver.RelationSpec): String {
        val child = relation.childColumns.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "*"
        val parent = relation.parentColumns.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "*"
        return "${relation.childTableName}.$child -> ${relation.parentTableName}.$parent"
    }

    private fun sanitizeMermaidText(text: String): String = text.replace('"', '\'')

    private fun sanitizeMermaidComment(text: String): String =
        sanitizeMermaidText(text)
            .replace('(', '（')
            .replace(')', '）')

    private fun renderRelationLabel(label: String): String = "\"\""
}
