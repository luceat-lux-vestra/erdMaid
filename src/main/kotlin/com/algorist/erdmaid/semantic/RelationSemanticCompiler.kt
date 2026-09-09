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
import com.algorist.erdmaid.core.WorkCheckpoint

/** Exact child/parent column pair after the referenced table has been authoritatively resolved. */
data class ResolvedForeignKeyColumnMapping(
    val child: ColumnId,
    val parent: ColumnId,
)

/**
 * Pure relation fact whose endpoints, provenance, and ordered column mappings are authoritative.
 *
 * This remains deliberately smaller than the final ER graph. Cardinality and display
 * qualification belong to later #35/#36 slices.
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
 * Resolves FK reference evidence into a canonical selected semantic relation set without
 * selection-local guessing. Exact endpoint identity is established before selected-subgraph
 * omission; retained relations are then fail-closed de-duplicated and deterministically ordered.
 */
object RelationSemanticCompiler {

    fun compile(snapshot: SchemaSnapshot): ExportOutcome<FrozenList<SemanticRelation>> =
        compile(snapshot, WorkCheckpoint.NONE)

    internal fun compile(
        snapshot: SchemaSnapshot,
        checkpoint: WorkCheckpoint,
    ): ExportOutcome<FrozenList<SemanticRelation>> {
        checkpoint.check()
        val selectedTables = snapshot.tables.associateBy { it.id }
        val resolved = ArrayList<SemanticRelation>()

        for (childTable in snapshot.tables) {
            checkpoint.check()
            val foreignKeys = when (val evidence = childTable.foreignKeys) {
                is Evidence.Known -> evidence.value
                is Evidence.Unavailable -> return degraded(
                    code = "foreign-keys-unavailable",
                    detail = evidence.diagnostic.code,
                )
            }

            for (foreignKey in foreignKeys) {
                checkpoint.check()
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
                    checkpoint.check()
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

        return canonicalizeRelations(resolved, checkpoint)
    }

    private fun canonicalizeRelations(
        relations: List<SemanticRelation>,
        checkpoint: WorkCheckpoint,
    ): ExportOutcome<FrozenList<SemanticRelation>> {
        if (relations.isEmpty()) {
            return ExportOutcome.Complete(FrozenList.copyOf(relations))
        }

        checkpoint.check()
        val ordered = relations.sortedWith(relationComparator)
        val canonical = ArrayList<SemanticRelation>(ordered.size)
        var groupStart = 0

        while (groupStart < ordered.size) {
            checkpoint.check()
            var groupEnd = groupStart + 1
            while (
                groupEnd < ordered.size &&
                sameStructuralRelation(ordered[groupStart], ordered[groupEnd])
            ) {
                groupEnd++
            }

            if (groupEnd - groupStart == 1) {
                canonical += ordered[groupStart]
                groupStart = groupEnd
                continue
            }

            val group = ordered.subList(groupStart, groupEnd)
            if (group.any { it.name !is OptionalValue.Present }) {
                return degraded(
                    code = "relation-identity-ambiguous",
                    detail = relationNameStateDetail(group),
                )
            }

            var previousName: String? = null
            for (relation in group) {
                checkpoint.check()
                val relationName = (relation.name as OptionalValue.Present).value
                if (previousName != relationName) {
                    canonical += relation
                    previousName = relationName
                }
            }

            groupStart = groupEnd
        }

        return ExportOutcome.Complete(FrozenList.copyOf(canonical))
    }

    private fun sameStructuralRelation(
        left: SemanticRelation,
        right: SemanticRelation,
    ): Boolean =
        left.childTable == right.childTable &&
            left.parentTable == right.parentTable &&
            left.provenance == right.provenance &&
            left.mappings == right.mappings

    private fun relationNameStateDetail(relations: List<SemanticRelation>): String {
        val states = relations
            .map { relationNameState(it.name) }
            .distinct()
            .sorted()
        return "name-states=${states.joinToString(",")}" 
    }

    private fun relationNameState(name: OptionalValue<String>): String = when (name) {
        is OptionalValue.Present -> "present"
        OptionalValue.Absent -> "absent"
        is OptionalValue.Unavailable -> "unavailable"
    }

    private val relationComparator = Comparator<SemanticRelation> { left, right ->
        compareRelation(left, right)
    }

    private fun compareRelation(left: SemanticRelation, right: SemanticRelation): Int {
        var comparison = compareTableId(left.childTable, right.childTable)
        if (comparison != 0) return comparison

        comparison = compareTableId(left.parentTable, right.parentTable)
        if (comparison != 0) return comparison

        comparison = left.provenance.name.compareTo(right.provenance.name)
        if (comparison != 0) return comparison

        comparison = compareMappings(left.mappings, right.mappings)
        if (comparison != 0) return comparison

        return compareRelationName(left.name, right.name)
    }

    private fun compareTableId(left: TableId, right: TableId): Int {
        var comparison = left.origin.value.compareTo(right.origin.value)
        if (comparison != 0) return comparison

        comparison = compareNullableString(left.catalog, right.catalog)
        if (comparison != 0) return comparison

        comparison = compareNullableString(left.schema, right.schema)
        if (comparison != 0) return comparison

        return left.name.compareTo(right.name)
    }

    private fun compareColumnId(left: ColumnId, right: ColumnId): Int {
        val tableComparison = compareTableId(left.table, right.table)
        if (tableComparison != 0) return tableComparison
        return left.name.compareTo(right.name)
    }

    private fun compareMappings(
        left: List<ResolvedForeignKeyColumnMapping>,
        right: List<ResolvedForeignKeyColumnMapping>,
    ): Int {
        val commonSize = minOf(left.size, right.size)
        for (index in 0 until commonSize) {
            var comparison = compareColumnId(left[index].child, right[index].child)
            if (comparison != 0) return comparison

            comparison = compareColumnId(left[index].parent, right[index].parent)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    private fun compareRelationName(
        left: OptionalValue<String>,
        right: OptionalValue<String>,
    ): Int {
        val rankComparison = relationNameRank(left).compareTo(relationNameRank(right))
        if (rankComparison != 0) return rankComparison

        return when {
            left is OptionalValue.Present && right is OptionalValue.Present ->
                left.value.compareTo(right.value)
            left is OptionalValue.Unavailable && right is OptionalValue.Unavailable ->
                compareDiagnostic(left.diagnostic, right.diagnostic)
            else -> 0
        }
    }

    private fun relationNameRank(name: OptionalValue<String>): Int = when (name) {
        is OptionalValue.Present -> 0
        OptionalValue.Absent -> 1
        is OptionalValue.Unavailable -> 2
    }

    private fun compareDiagnostic(left: CoreDiagnostic, right: CoreDiagnostic): Int {
        val codeComparison = left.code.compareTo(right.code)
        if (codeComparison != 0) return codeComparison
        return compareNullableString(left.detail, right.detail)
    }

    private fun compareNullableString(left: String?, right: String?): Int = when {
        left == right -> 0
        left == null -> -1
        right == null -> 1
        else -> left.compareTo(right)
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
