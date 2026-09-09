package com.algorist.erdmaid.semantic

import com.algorist.erdmaid.core.CoreDiagnostic
import com.algorist.erdmaid.core.Evidence
import com.algorist.erdmaid.core.ExportOutcome
import com.algorist.erdmaid.core.FrozenList
import com.algorist.erdmaid.core.RelationProvenance
import com.algorist.erdmaid.core.SchemaSnapshot
import com.algorist.erdmaid.core.WorkCheckpoint

enum class RelationMultiplicityMinimum {
    ZERO,
    ONE,
}

enum class RelationMultiplicityMaximum {
    ONE,
    MANY,
}

/**
 * Independent lower/upper bounds for one direction of a relation.
 *
 * Partial knowledge remains partial: an unavailable minimum does not erase a known maximum and
 * vice versa.
 */
data class RelationMultiplicityBounds(
    val minimum: Evidence<RelationMultiplicityMinimum>,
    val maximum: Evidence<RelationMultiplicityMaximum>,
)

/** Renderer-neutral multiplicity evidence for both traversal directions of one relation. */
data class RelationMultiplicity(
    val parentsPerChild: RelationMultiplicityBounds,
    val childrenPerParent: RelationMultiplicityBounds,
)

/**
 * Canonical constrained relation enriched with independently derived multiplicity evidence.
 *
 * [relation] remains the sole identity/order/de-duplication authority. Multiplicity is semantic
 * annotation only and never participates in relation identity.
 */
data class MultiplicitySemanticRelation(
    val relation: ConstrainedSemanticRelation,
    val multiplicity: RelationMultiplicity,
)

/**
 * Converts #64 constraint evidence into evidence-bounded relation multiplicity without renderer
 * syntax or host-API assumptions.
 */
object RelationMultiplicityCompiler {

    fun compile(snapshot: SchemaSnapshot): ExportOutcome<FrozenList<MultiplicitySemanticRelation>> =
        compile(snapshot, WorkCheckpoint.NONE)

    internal fun compile(
        snapshot: SchemaSnapshot,
        checkpoint: WorkCheckpoint,
    ): ExportOutcome<FrozenList<MultiplicitySemanticRelation>> {
        val constrained = RelationConstraintCompiler.compile(snapshot, checkpoint)
        return when (constrained) {
            is ExportOutcome.Complete -> {
                val result = ArrayList<MultiplicitySemanticRelation>(constrained.value.size)
                for (relation in constrained.value) {
                    checkpoint.check()
                    result += enrich(relation)
                }
                ExportOutcome.Complete(FrozenList.copyOf(result))
            }
            ExportOutcome.NoExport -> ExportOutcome.NoExport
            is ExportOutcome.Degraded -> ExportOutcome.Degraded(constrained.diagnostics)
            is ExportOutcome.Unsupported -> ExportOutcome.Unsupported(constrained.diagnostics)
            is ExportOutcome.Failure -> ExportOutcome.Failure(constrained.diagnostics)
            ExportOutcome.Cancelled -> ExportOutcome.Cancelled
        }
    }

    private fun enrich(relation: ConstrainedSemanticRelation): MultiplicitySemanticRelation =
        MultiplicitySemanticRelation(
            relation = relation,
            multiplicity = RelationMultiplicity(
                parentsPerChild = RelationMultiplicityBounds(
                    minimum = deriveParentsPerChildMinimum(relation),
                    maximum = deriveMaximum(relation.constraints.parentUniqueness),
                ),
                childrenPerParent = RelationMultiplicityBounds(
                    minimum = Evidence.Known(RelationMultiplicityMinimum.ZERO),
                    maximum = deriveMaximum(relation.constraints.childUniqueness),
                ),
            ),
        )

    private fun deriveParentsPerChildMinimum(
        relation: ConstrainedSemanticRelation,
    ): Evidence<RelationMultiplicityMinimum> = when (relation.relation.provenance) {
        RelationProvenance.PHYSICAL -> when (val nullability = relation.constraints.childNullability) {
            is Evidence.Known -> when (nullability.value) {
                RelationTupleNullability.ALL_NON_NULL ->
                    Evidence.Known(RelationMultiplicityMinimum.ONE)
                RelationTupleNullability.HAS_NULLABLE_COMPONENT ->
                    if (relation.relation.mappings.size == 1) {
                        Evidence.Known(RelationMultiplicityMinimum.ZERO)
                    } else {
                        unavailableMinimum("composite-nullable-match-semantics-unavailable")
                    }
            }
            is Evidence.Unavailable -> Evidence.Unavailable(nullability.diagnostic)
        }
        RelationProvenance.VIRTUAL ->
            unavailableMinimum("virtual-relation-does-not-prove-referential-integrity")
    }

    private fun deriveMaximum(
        uniqueness: Evidence<RelationTupleUniqueness>,
    ): Evidence<RelationMultiplicityMaximum> = when (uniqueness) {
        is Evidence.Known -> Evidence.Known(
            when (uniqueness.value) {
                RelationTupleUniqueness.UNIQUE -> RelationMultiplicityMaximum.ONE
                RelationTupleUniqueness.NON_UNIQUE -> RelationMultiplicityMaximum.MANY
            }
        )
        is Evidence.Unavailable -> Evidence.Unavailable(uniqueness.diagnostic)
    }

    private fun unavailableMinimum(detail: String): Evidence<RelationMultiplicityMinimum> =
        Evidence.Unavailable(
            CoreDiagnostic(
                code = "relation-parents-per-child-minimum-unavailable",
                detail = detail,
            )
        )
}
