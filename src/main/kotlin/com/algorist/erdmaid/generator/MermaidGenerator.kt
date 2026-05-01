package com.algorist.erdmaid.generator

import com.intellij.database.model.DasColumn
import com.intellij.database.model.DasForeignKey
import com.intellij.database.model.DasTable
import com.intellij.database.util.DasUtil

object MermaidGenerator {

    private const val INDENT = "    "

    fun generate(tables: List<DasTable>): String {
        val tableSet = tables.map { it.name }.toSet()
        return buildString {
            appendLine("erDiagram")

            for (table in tables) {
                appendTableBlock(table)
                appendLine()
            }

            for (table in tables) {
                appendForeignKeys(table, tableSet)
            }
        }
    }

    private fun StringBuilder.appendTableBlock(table: DasTable) {
        val tableComment = table.comment?.takeIf { it.isNotBlank() }
        if (tableComment != null) {
            appendLine("%% $tableComment")
        }

        appendLine("$INDENT${table.name} {")

        val columns = DasUtil.getColumns(table)
        val primaryKeys = DasUtil.getPrimaryKey(table)?.let { primaryKey ->
            setOf(primaryKey.name)
        } ?: emptySet()

        for (column in columns) {
            append(formatColumn(column, primaryKeys))
        }

        appendLine("$INDENT}")
    }

    private fun StringBuilder.appendForeignKeys(table: DasTable, tableSet: Set<String>) {
        for (fk in DasUtil.getForeignKeys(table)) {
            val refTableName = fk.refTableName
            if (refTableName !in tableSet) continue

            val relationName = if (fk.name.isBlank()) "\"\"" else "\"${fk.name}\""
            appendLine("$INDENT$refTableName ||--o{ ${table.name} : $relationName")
        }
    }

    @Suppress("DEPRECATION")
    private fun formatColumn(column: DasColumn, primaryKeys: Set<String>): String {
        val type = column.dasType.toDataType().typeName
            .replace(' ', '_') // 공백을 언더스코어로 치환
        val name = column.name
        val pk = if (primaryKeys.contains(name)) "PK" else ""
        val comment = column.comment?.replace('"', '\'') // 큰따옴표를 홑따옴표로 치환

        val parts = mutableListOf<String>()
        parts.add(type)
        parts.add(name)
        if (pk.isNotEmpty()) {
            parts.add(pk)
        }
        if (!comment.isNullOrEmpty()) {
            parts.add("\"$comment\"")
        }

        return "$INDENT$INDENT${parts.joinToString(" ")}\n"
    }
}
