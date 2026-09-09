package com.algorist.erdmaid.semantic

import com.algorist.erdmaid.core.ColumnId
import com.algorist.erdmaid.core.CoreDiagnostic
import com.algorist.erdmaid.core.Evidence
import com.algorist.erdmaid.core.ExportOutcome
import com.algorist.erdmaid.core.FrozenList
import com.algorist.erdmaid.core.OptionalValue
import com.algorist.erdmaid.core.SchemaSnapshot
import com.algorist.erdmaid.core.TableSnapshot

enum class RelationTupleNullability {
    ALL_NON_NULL,
    HAS_NULLABLE_COMPONENT,
}

enum class RelationTupleUniqueness {
    UNIQUE,
    NON_UNIQUE,
}

/**
 * Renderer-neutral structural evidence available for later cardinality/optionality decisions.
 *
 * These values intentionally stop short of relationship-end cardinality. In particular, tuple
 * nullability is not enough to establish composite-FK match semantics, and key facts are not yet
 * proof that every supported host exposes the same semantics. Those authority gates belong to
 * #36 and #37.
 */
data class RelationConstraintEvidence(
    val childNullability: Evidence<RelationTupleNullability>,
    val childUniqueness: Evidence<RelationTupleUniqueness>,
    val parentUniqueness: Evidence<RelationTupleUniqueness>,
)

/**
 * A canonical semantic relation paired with independently derived structural constraint evidence.
 *
 * [relation] remains the sole identity/order/de-duplication authority. Constraint evidence is
 * attached only after [RelationSemanticCompiler] has completed canonicalization.
 */
data class ConstrainedSemanticRelation(
    val relation: SemanticRelation,
    val constraints: RelationConstraintEvidence,
)

/**
 * Enriches canonical relations with the constraint evidence that the immutable snapshot proves.
 * Unknown evidence stays unknown and never degrades otherwise complete relation connectivity.
 */
object RelationConstraintCompiler {

    fun compile(snapshot: SchemaSnapshot): ExportOutcome<FrozenList<ConstrainedSemanticRelation>> {
        val relations = RelationSemanticCompiler.compile(snapshot)
        return when (relations) {
            is ExportOutcome.Complete -> enrich(snapshot, relations.value)
            ExportOutcome.NoExport -> ExportOutcome.NoExport
            is ExportOutcome.Degraded -> ExportOutcome.Degraded(relations.diagnostics)
            is ExportOutcome.Unsupported -> ExportOutcome.Unsupported(relations.diagnostics)
            is ExportOutcome.Failure -> ExportOutcome.Failure(relations.diagnostics)
            ExportOutcome.Cancelled -> ExportOutcome.Cancelled
        }
    }

    private fun enrich(
        snapshot: SchemaSnapshot,
        relations: FrozenList<SemanticRelation>,
    ): ExportOutcome<FrozenList<ConstrainedSemanticRelation>> {
        val tables = snapshot.tables.associateBy { it.id }
        val constrained = ArrayList<ConstrainedSemanticRelation>(relations.size)

        for (relation in relations) {
            val childTable = tables[relation.childTable]
                ?: return failure("relation-constraint-child-table-missing")
            val parentTable = tables[relation.parentTable]
                ?: return failure("relation-constraint-parent-table-missing")

            val childColumns = relation.mappings.map { it.child }
            val parentColumns = relation.mappings.map { it.parent }

            constrained += ConstrainedSemanticRelation(
                relation = relation,
                constraints = RelationConstraintEvidence(
                    childNullability = deriveChildNullability(childTable, childColumns),
                    childUniqueness = deriveTupleUniqueness(
                        table = childTable,
                        tupleColumns = childColumns,
                        context = "child",
                    ),
                    parentUniqueness = deriveTupleUniqueness(
                        table = parentTable,
                        tupleColumns = parentColumns,
                        context = "parent",
                    ),
                ),
            )
        }

        return ExportOutcome.Complete(FrozenList.copyOf(constrained))
    }

    private fun deriveChildNullability(
        table: TableSnapshot,
        tupleColumns: List<ColumnId>,
    ): Evidence<RelationTupleNullability> {
        val columns = table.columns.associateBy { it.id }
        val unavailable = ArrayList<String>()

        for (columnId in tupleColumns) {
            val column = columns[columnId]
                ?: return Evidence.Unavailable(
                    CoreDiagnostic(
                        code = "relation-child-nullability-unavailable",
                        detail = "column-missing:${columnId.name}",
                    )
                )

            when (val nullable = column.nullable) {
                is Evidence.Known -> if (nullable.value) {
                    return Evidence.Known(RelationTupleNullability.HAS_NULLABLE_COMPONENT)
                }
                is Evidence.Unavailable -> unavailable +=
                    "${columnId.name}:${nullable.diagnostic.code}"
            }
        }

        return if (unavailable.isEmpty()) {
            Evidence.Known(RelationTupleNullability.ALL_NON_NULL)
        } else {
            Evidence.Unavailable(
                CoreDiagnostic(
                    code = "relation-child-nullability-unavailable",
                    detail = unavailable.joinToString(","),
                )
            )
        }
    }

    private fun deriveTupleUniqueness(
        table: TableSnapshot,
        tupleColumns: List<ColumnId>,
        context: String,
    ): Evidence<RelationTupleUniqueness> {
        val tuple = tupleColumns.toSet()

        val primaryKeyMatches = when (val primaryKey = table.primaryKey) {
            is OptionalValue.Present -> keyIsContained(primaryKey.value.columns, tuple)
            OptionalValue.Absent -> false
            is OptionalValue.Unavailable -> false
        }
        if (primaryKeyMatches) {
            return Evidence.Known(RelationTupleUniqueness.UNIQUE)
        }

        val uniqueKeyMatches = when (val uniqueKeys = table.uniqueKeys) {
            is Evidence.Known -> uniqueKeys.value.any { key -> keyIsContained(key.columns, tuple) }
            is Evidence.Unavailable -> false
        }
        if (uniqueKeyMatches) {
            return Evidence.Known(RelationTupleUniqueness.UNIQUE)
        }

        val unavailable = ArrayList<String>(2)
        val primaryKey = table.primaryKey
        val uniqueKeys = table.uniqueKeys
        if (primaryKey is OptionalValue.Unavailable) {
            unavailable += "primary-key:${primaryKey.diagnostic.code}"
        }
        if (uniqueKeys is Evidence.Unavailable) {
            unavailable += "unique-keys:${uniqueKeys.diagnostic.code}"
        }

        return if (unavailable.isEmpty()) {
            Evidence.Known(RelationTupleUniqueness.NON_UNIQUE)
        } else {
            Evidence.Unavailable(
                CoreDiagnostic(
                    code = "relation-$context-uniqueness-unavailable",
                    detail = unavailable.joinToString(","),
                )
            )
        }
    }

    private fun keyIsContained(
        keyColumns: List<ColumnId>,
        tupleColumns: Set<ColumnId>,
    ): Boolean = keyColumns.all { it in tupleColumns }

    private fun failure(code: String): ExportOutcome.Failure =
        ExportOutcome.Failure(
            com.algorist.erdmaid.core.CoreDiagnostics.of(CoreDiagnostic(code))
        )
}
