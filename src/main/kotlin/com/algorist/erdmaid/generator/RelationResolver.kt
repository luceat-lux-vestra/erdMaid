package com.algorist.erdmaid.generator

import com.intellij.database.model.DasTable
import com.intellij.database.model.ModelRelationManager
import com.intellij.database.util.DasUtil
import com.intellij.openapi.project.Project

internal object RelationResolver {

    internal data class RelationSpec(
        val childTableName: String,
        val parentTableName: String,
        val name: String,
        val childColumns: List<String> = emptyList(),
        val parentColumns: List<String> = emptyList(),
    )

    fun resolve(project: Project?, tables: List<DasTable>): Map<String, List<RelationSpec>> {
        if (tables.isEmpty()) return emptyMap()

        val selectedTableNames = tables.map { it.name }.toSet()
        val normalized = normalize(
            relations = tables.flatMap { table -> collectOutgoingRelations(project, table) },
            selectedTableNames = selectedTableNames,
        )

        return normalized.groupBy { it.childTableName }
    }

    internal fun normalize(
        relations: List<RelationSpec>,
        selectedTableNames: Set<String>,
    ): List<RelationSpec> {
        val seen = LinkedHashMap<String, RelationSpec>()

        for (relation in relations) {
            if (relation.childTableName !in selectedTableNames) continue
            if (relation.parentTableName !in selectedTableNames) continue

            val key = buildString {
                append(relation.childTableName)
                append("->")
                append(relation.parentTableName)
                append(":")
                append(relation.childColumns.joinToString(","))
                append("|")
                append(relation.parentColumns.joinToString(","))
            }
            val existing = seen[key]
            if (existing == null || (existing.name.isBlank() && relation.name.isNotBlank())) {
                seen[key] = relation
            }
        }

        return seen.values.toList()
    }

    private fun collectOutgoingRelations(project: Project?, table: DasTable): List<RelationSpec> {
        val foreignKeys = if (project != null) {
            // ModelRelationManager combines the IDE's relation providers, including
            // explicit database FKs and DataGrip/IDE-maintained virtual relations.
            ModelRelationManager.getForeignKeys(project, table)
        } else {
            // Headless fallback used only when no Project is available.
            DasUtil.getForeignKeys(table)
        }

        return foreignKeys.map { fk ->
            RelationSpec(
                childTableName = table.name,
                parentTableName = fk.refTableName,
                name = fk.name.orEmpty(),
                childColumns = fk.columnsRef.names().toList(),
                parentColumns = fk.refColumns.names().toList(),
            )
        }.toList()
    }
}
