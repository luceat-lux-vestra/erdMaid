package com.algorist.erdmaid.semantic

import com.algorist.erdmaid.core.CoreDiagnostic
import com.algorist.erdmaid.core.Evidence
import com.algorist.erdmaid.core.ExportOutcome
import com.algorist.erdmaid.core.FrozenList
import com.algorist.erdmaid.core.OptionalValue
import com.algorist.erdmaid.core.SchemaSnapshot
import com.algorist.erdmaid.core.TableSnapshot
import com.algorist.erdmaid.core.WorkCheckpoint

enum class RelationIdentification {
    IDENTIFYING,
    NON_IDENTIFYING,
}

/**
 * Final renderer-neutral relation carried by [ErdGraph].
 *
 * [semantic] remains the authority for relation identity/order/de-duplication, constraints and
 * multiplicity. [identification] is an evidence-bounded annotation only; it never participates in
 * canonical relation identity.
 */
data class ErdGraphRelation(
    val semantic: MultiplicitySemanticRelation,
    val identification: Evidence<RelationIdentification>,
) {
    /** Convenience projection preserving the existing constrained-relation ownership boundary. */
    val relation: ConstrainedSemanticRelation
        get() = semantic.relation

    /** Convenience projection preserving the existing multiplicity ownership boundary. */
    val multiplicity: RelationMultiplicity
        get() = semantic.multiplicity
}

/**
 * Derives identifying/non-identifying semantics only from canonical PK/FK evidence.
 *
 * This compiler intentionally stays conservative: lack of a declared PK, an unavailable PK, or a
 * mapping to a parent key that is not exactly the authoritative ordered parent PK remains unknown.
 */
object RelationIdentificationCompiler {

    fun compile(snapshot: SchemaSnapshot): ExportOutcome<FrozenList<ErdGraphRelation>> =
        compile(snapshot, WorkCheckpoint.NONE)

    internal fun compile(
        snapshot: SchemaSnapshot,
        checkpoint: WorkCheckpoint,
    ): ExportOutcome<FrozenList<ErdGraphRelation>> {
        val multiplicity = RelationMultiplicityCompiler.compile(snapshot, checkpoint)
        return when (multiplicity) {
            is ExportOutcome.Complete -> {
                checkpoint.check()
                val tablesById = snapshot.tables.associateBy { it.id }
                val relations = ArrayList<ErdGraphRelation>(multiplicity.value.size)
                for (semantic in multiplicity.value) {
                    checkpoint.check()
                    val relation = semantic.relation.relation
                    val child = tablesById.getValue(relation.childTable)
                    val parent = tablesById.getValue(relation.parentTable)
                    relations += ErdGraphRelation(
                        semantic = semantic,
                        identification = derive(semantic, child, parent),
                    )
                }
                ExportOutcome.Complete(FrozenList.copyOf(relations))
            }
            ExportOutcome.NoExport -> ExportOutcome.NoExport
            is ExportOutcome.Degraded -> ExportOutcome.Degraded(multiplicity.diagnostics)
            is ExportOutcome.Unsupported -> ExportOutcome.Unsupported(multiplicity.diagnostics)
            is ExportOutcome.Failure -> ExportOutcome.Failure(multiplicity.diagnostics)
            ExportOutcome.Cancelled -> ExportOutcome.Cancelled
        }
    }

    internal fun derive(
        semantic: MultiplicitySemanticRelation,
        child: TableSnapshot,
        parent: TableSnapshot,
    ): Evidence<RelationIdentification> {
        val childPrimaryKey = when (val primaryKey = child.primaryKey) {
            is OptionalValue.Present -> primaryKey.value
            OptionalValue.Absent -> return unavailable("child-primary-key-absent")
            is OptionalValue.Unavailable ->
                return unavailable("child-primary-key:${primaryKey.diagnostic.code}")
        }

        val relation = semantic.relation.relation
        val childForeignKeyColumns = relation.mappings.map { it.child }
        if (childForeignKeyColumns.any { it !in childPrimaryKey.columns }) {
            return Evidence.Known(RelationIdentification.NON_IDENTIFYING)
        }

        val parentPrimaryKey = when (val primaryKey = parent.primaryKey) {
            is OptionalValue.Present -> primaryKey.value
            OptionalValue.Absent -> return unavailable("parent-primary-key-absent")
            is OptionalValue.Unavailable ->
                return unavailable("parent-primary-key:${primaryKey.diagnostic.code}")
        }

        val parentTargetColumns = relation.mappings.map { it.parent }
        return if (parentTargetColumns == parentPrimaryKey.columns.toList()) {
            Evidence.Known(RelationIdentification.IDENTIFYING)
        } else {
            unavailable("parent-target-is-not-primary-key")
        }
    }

    private fun unavailable(detail: String): Evidence<RelationIdentification> =
        Evidence.Unavailable(
            CoreDiagnostic(
                code = "relation-identification-unavailable",
                detail = detail,
            )
        )
}
