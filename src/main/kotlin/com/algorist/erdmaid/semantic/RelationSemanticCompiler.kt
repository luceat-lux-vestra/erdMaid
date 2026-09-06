package com.algorist.erdmaid.semantic

import com.algorist.erdmaid.core.ColumnId
import com.algorist.erdmaid.core.CoreDiagnostic
import com.algorist.erdmaid.core.CoreDiagnostics
import com.algorist.erdmaid.core.Evidence
import com.algorist.erdmaid.core.ExportOutcome
import com.algorist.erdmaid.core.FrozenList
import com.algorist.erdmaid.core.OptionalValue
import com.algorist.erdmaid.core.RelationProvenance
import com.algorist.erdmaid.core.SchemaSnapshot
import com.algorist.erdmaid.core.TableId

/** Exact child/parent column pair after the referenced table has been authoritatively resolved. */
data class ResolvedForeignKeyColumnMapping(
    val child: ColumnId,
    val parent: ColumnId,
)

/**
 * Pure relation fact whose endpoints, provenance, and ordered column mappings are authoritative.
 *
 * This is deliberately smaller than the final ER graph. Cardinality, de-duplication, canonical
 * relation ordering, and display qualification belong to later #35/#36 slices.
 */
data class SemanticRelation(
    val childTable: TableId,
    val parentTable: TableId,
    val name: OptionalValue<String>,
    val provenance: RelationProvenance,
    val mappings: FrozenList<ResolvedForeignKeyColumnMapping>,
) {
    init {
        require(mappings.isNotEmpty()) { "A semantic relation must contain at least one mapping" }
        require(mappings.all { it.child.table == childTable }) {
            "Every semantic relation child mapping must belong to the child table"
        }
        require(mappings.all { it.parent.table == parentTable }) {
            "Every semantic relation parent mapping must belong to the parent table"
        }
    }
}

/**
 * Resolves FK reference evidence into the selected semantic subgraph without selection-local
 * guessing. Exact endpoint identity is established before selected-subgraph omission is applied.
 */
object RelationSemanticCompiler {

    fun compile(snapshot: SchemaSnapshot): ExportOutcome<FrozenList<SemanticRelation>> {
        val selectedTables = snapshot.tables.associateBy { it.id }
        val resolved = ArrayList<SemanticRelation>()

        for (childTable in snapshot.tables) {
            val foreignKeys = when (val evidence = childTable.foreignKeys) {
                is Evidence.Known -> evidence.value
                is Evidence.Unavailable -> return degraded(
                    code = "foreign-keys-unavailable",
                    detail = evidence.diagnostic.code,
                )
            }

            for (foreignKey in foreignKeys) {
                val parentOrigin = when (val evidence = foreignKey.referencedTable.origin) {
                    is Evidence.Known -> evidence.value
                    is Evidence.Unavailable -> return endpointUnavailable(
                        component = "origin",
                        diagnostic = evidence.diagnostic,
                    )
                }
                val parentCatalog = when (val value = foreignKey.referencedTable.catalog) {
                    is OptionalValue.Present -> value.value
                    OptionalValue.Absent -> null
                    is OptionalValue.Unavailable -> return endpointUnavailable(
                        component = "catalog",
                        diagnostic = value.diagnostic,
                    )
                }
                val parentSchema = when (val value = foreignKey.referencedTable.schema) {
                    is OptionalValue.Present -> value.value
                    OptionalValue.Absent -> null
                    is OptionalValue.Unavailable -> return endpointUnavailable(
                        component = "schema",
                        diagnostic = value.diagnostic,
                    )
                }
                val parentName = when (val evidence = foreignKey.referencedTable.name) {
                    is Evidence.Known -> evidence.value
                    is Evidence.Unavailable -> return endpointUnavailable(
                        component = "name",
                        diagnostic = evidence.diagnostic,
                    )
                }

                if (parentOrigin != snapshot.origin) {
                    return degraded("relation-cross-origin-target")
                }

                val parentId = TableId(
                    origin = parentOrigin,
                    catalog = parentCatalog,
                    schema = parentSchema,
                    name = parentName,
                )

                // Selected-subgraph omission is valid only after exact parent identity exists.
                val parentTable = selectedTables[parentId] ?: continue

                val sourceMappings = when (val evidence = foreignKey.mappings) {
                    is Evidence.Known -> evidence.value
                    is Evidence.Unavailable -> return degraded(
                        code = "relation-mappings-unavailable",
                        detail = evidence.diagnostic.code,
                    )
                }
                val provenance = when (val evidence = foreignKey.provenance) {
                    is Evidence.Known -> evidence.value
                    is Evidence.Unavailable -> return degraded(
                        code = "relation-provenance-unavailable",
                        detail = evidence.diagnostic.code,
                    )
                }

                val resolvedMappings = ArrayList<ResolvedForeignKeyColumnMapping>(sourceMappings.size)
                for (mapping in sourceMappings) {
                    val parentColumn = parentTable.columns.singleOrNull {
                        it.id.name == mapping.referencedColumnName
                    } ?: return degraded("relation-parent-column-missing")

                    resolvedMappings += ResolvedForeignKeyColumnMapping(
                        child = mapping.child,
                        parent = parentColumn.id,
                    )
                }

                resolved += SemanticRelation(
                    childTable = childTable.id,
                    parentTable = parentTable.id,
                    name = foreignKey.name,
                    provenance = provenance,
                    mappings = FrozenList.copyOf(resolvedMappings),
                )
            }
        }

        return ExportOutcome.Complete(FrozenList.copyOf(resolved))
    }

    private fun endpointUnavailable(
        component: String,
        diagnostic: CoreDiagnostic,
    ): ExportOutcome.Degraded = degraded(
        code = "relation-endpoint-identity-unavailable",
        detail = "$component:${diagnostic.code}",
    )

    private fun degraded(
        code: String,
        detail: String? = null,
    ): ExportOutcome.Degraded = ExportOutcome.Degraded(
        CoreDiagnostics.of(CoreDiagnostic(code, detail))
    )
}
