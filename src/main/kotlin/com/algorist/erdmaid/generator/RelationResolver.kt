package com.algorist.erdmaid.generator

import com.intellij.database.model.DasTable
import com.intellij.database.model.ModelRelationManager
import com.intellij.database.util.DasUtil
import com.intellij.openapi.project.Project

internal object RelationResolver {

    internal data class TableIdentity(
        val catalog: String?,
        val schema: String?,
        val name: String,
    ) {
        val qualifiedName: String
            get() = listOfNotNull(catalog, schema).plus(name).joinToString(".")
    }

    internal data class TableReference(
        val catalog: String?,
        val schema: String?,
        val name: String,
    )

    internal data class RelationSpec(
        val childTable: TableIdentity,
        val parentTable: TableIdentity,
        val name: String,
        val childColumns: List<String> = emptyList(),
        val parentColumns: List<String> = emptyList(),
    )

    private data class RelationKey(
        val childTable: TableIdentity,
        val parentTable: TableIdentity,
        val childColumns: List<String>,
        val parentColumns: List<String>,
    )

    fun resolve(project: Project?, tables: List<DasTable>): Map<TableIdentity, List<RelationSpec>> {
        if (tables.isEmpty()) return emptyMap()

        val selectedTableIdentities = tables.map(::tableIdentity).toSet()
        val normalized = normalize(
            relations = tables.flatMap { table ->
                collectOutgoingRelations(
                    project = project,
                    table = table,
                    childTable = tableIdentity(table),
                    selectedTables = selectedTableIdentities,
                )
            },
            selectedTables = selectedTableIdentities,
        )

        return normalized.groupBy { it.childTable }
    }

    internal fun tableIdentity(table: DasTable): TableIdentity = TableIdentity(
        catalog = metadataPart(DasUtil.getCatalog(table)),
        schema = metadataPart(DasUtil.getSchema(table)),
        name = table.name,
    )

    internal fun resolveTableReference(
        reference: TableReference,
        selectedTables: Set<TableIdentity>,
    ): TableIdentity? {
        val matches = selectedTables.asSequence()
            .filter { it.name == reference.name }
            .filter { reference.catalog == null || it.catalog == reference.catalog }
            .filter { reference.schema == null || it.schema == reference.schema }
            .toList()

        return matches.singleOrNull()
    }

    internal fun normalize(
        relations: List<RelationSpec>,
        selectedTables: Set<TableIdentity>,
    ): List<RelationSpec> {
        val seen = LinkedHashMap<RelationKey, RelationSpec>()

        for (relation in relations) {
            if (relation.childTable !in selectedTables) continue
            if (relation.parentTable !in selectedTables) continue

            val key = RelationKey(
                childTable = relation.childTable,
                parentTable = relation.parentTable,
                childColumns = relation.childColumns,
                parentColumns = relation.parentColumns,
            )
            val existing = seen[key]
            if (existing == null || (existing.name.isBlank() && relation.name.isNotBlank())) {
                seen[key] = relation
            }
        }

        return seen.values.toList()
    }

    private fun collectOutgoingRelations(
        project: Project?,
        table: DasTable,
        childTable: TableIdentity,
        selectedTables: Set<TableIdentity>,
    ): List<RelationSpec> {
        val foreignKeys = if (project != null) {
            // ModelRelationManager combines the IDE's relation providers, including
            // explicit database FKs and DataGrip/IDE-maintained virtual relations.
            ModelRelationManager.getForeignKeys(project, table)
        } else {
            // Headless fallback used only when no Project is available.
            DasUtil.getForeignKeys(table)
        }

        return foreignKeys.mapNotNull { fk ->
            val parentTable = resolveTableReference(
                reference = TableReference(
                    catalog = metadataPart(fk.refTableCatalog),
                    schema = metadataPart(fk.refTableSchema),
                    name = fk.refTableName,
                ),
                selectedTables = selectedTables,
            ) ?: return@mapNotNull null

            RelationSpec(
                childTable = childTable,
                parentTable = parentTable,
                name = fk.name.orEmpty(),
                childColumns = fk.columnsRef.names().toList(),
                parentColumns = fk.refColumns.names().toList(),
            )
        }.toList()
    }

    private fun metadataPart(value: String?): String? = value?.takeIf { it.isNotEmpty() }
}
