package com.algorist.erdmaid

import com.intellij.database.model.DasColumn
import com.intellij.database.model.DasForeignKey
import com.intellij.database.model.DasTable
import com.intellij.database.util.DasUtil

object MermaidGenerator {

    fun generate(tables: List<DasTable>): String = buildString {
        appendLine("erDiagram")

        val tableNames = tables.mapNotNull { it.name }.toSet()
        
        // Generate tables and columns
        for (table in tables) {
            val tableName = table.name ?: continue
            
            // Table comment: %% [comment] if exists
            val tableComment = (table.comment as? String)?.trim() ?: ""
            if (tableComment.isNotEmpty()) {
                appendLine("%% $tableComment")
            }

            // Table block start: TABLE_NAME {
            appendLine("$tableName {")

            val columns = DasUtil.getColumns(table) ?: emptyList()
            val primaryKeys = (DasUtil.getPrimaryKey(table)?.map { it.name } ?: emptyList()).toSet()

            for (col in columns) {
                val type = col.dataType.typeName.replace(' ', '_')
                val name = col.name ?: continue
                
                var pkStr = ""
                if (primaryKeys.contains(name)) {
                    pkStr = " PK"
                }

                var commentStr = ""
                val colComment = (col.comment as? String)?.trim() ?: ""
                if (colComment.isNotEmpty()) {
                    val escaped = colComment.replace("\"", "'")
                    commentStr = " \"$escaped\""
                }

                // Format: [type] [name] [PK?] ["comment"] (4 spaces indent)
                appendLine("    $type $name$pkStr\"$commentStr\"")
            }

            appendLine("}")
        }

        // Generate relationships (Foreign Keys) after all tables are processed
        for (table in tables) {
            val tableName = table.name ?: continue
            val foreignKeys = DasUtil.getForeignKeys(table) ?: emptyList()
            
            for (fk in foreignKeys) {
                // Fallback to refTable.name if direct property isn't available in your IDE version
                val refTable = fk.refTableName ?: fk.refTable?.name ?: continue
                
                // Filter: only if both parent and child tables are in the selected list
                if (tableNames.contains(refTable) && tableNames.contains(tableName)) {
                    val fkName = fk.constraintName?.trim() ?: "FK"
                    appendLine("$refTable ||--o{ $tableName : \"$fkName\"")
                }
            }
        }
    }
}
