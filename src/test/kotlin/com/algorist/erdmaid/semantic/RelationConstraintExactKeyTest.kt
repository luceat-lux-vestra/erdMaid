package com.algorist.erdmaid.semantic

import com.algorist.erdmaid.core.ColumnId
import com.algorist.erdmaid.core.ColumnSnapshot
import com.algorist.erdmaid.core.Evidence
import com.algorist.erdmaid.core.ExportOutcome
import com.algorist.erdmaid.core.ForeignKeyColumnMapping
import com.algorist.erdmaid.core.ForeignKeyFact
import com.algorist.erdmaid.core.FrozenList
import com.algorist.erdmaid.core.OptionalValue
import com.algorist.erdmaid.core.OriginId
import com.algorist.erdmaid.core.PrimaryKeyFact
import com.algorist.erdmaid.core.RawTypeMetadata
import com.algorist.erdmaid.core.RelationProvenance
import com.algorist.erdmaid.core.SchemaSnapshot
import com.algorist.erdmaid.core.TableId
import com.algorist.erdmaid.core.TableReferenceEvidence
import com.algorist.erdmaid.core.TableSnapshot
import com.algorist.erdmaid.core.UniqueKeyFact
import com.algorist.erdmaid.core.frozenListOf
import org.junit.Assert.assertEquals
import org.junit.Test

class RelationConstraintExactKeyTest {
    private val origin = OriginId("ds-a")

    @Test
    fun `exact child primary and unique key tuples both prove uniqueness`() {
        val child = tableId("child")
        val parent = tableId("parent")
        val pkRelation = fk(child, parent, "pk_relation", "pk_fk" to "id")
        val ukRelation = fk(child, parent, "uk_relation", "uk_fk" to "id")
        val snapshot = SchemaSnapshot(
            origin,
            frozenListOf(
                table(
                    id = child,
                    columns = listOf("pk_fk", "uk_fk"),
                    primaryKey = OptionalValue.Present(
                        PrimaryKeyFact(
                            OptionalValue.Present("pk_child"),
                            frozenListOf(ColumnId(child, "pk_fk")),
                        )
                    ),
                    uniqueKeys = known(
                        frozenListOf(
                            UniqueKeyFact(
                                OptionalValue.Present("uk_child"),
                                frozenListOf(ColumnId(child, "uk_fk")),
                            )
                        )
                    ),
                    foreignKeys = known(frozenListOf(pkRelation, ukRelation)),
                ),
                table(
                    id = parent,
                    columns = listOf("id"),
                    primaryKey = OptionalValue.Present(
                        PrimaryKeyFact(
                            OptionalValue.Present("pk_parent"),
                            frozenListOf(ColumnId(parent, "id")),
                        )
                    ),
                ),
            ),
        )

        val expectedConstraints = RelationConstraintEvidence(
            childNullability = known(RelationTupleNullability.ALL_NON_NULL),
            childUniqueness = known(RelationTupleUniqueness.UNIQUE),
            parentUniqueness = known(RelationTupleUniqueness.UNIQUE),
        )
        val expected = ExportOutcome.Complete(
            frozenListOf(
                constrained(child, parent, "pk_relation", "pk_fk", expectedConstraints),
                constrained(child, parent, "uk_relation", "uk_fk", expectedConstraints),
            )
        )

        assertEquals(expected, RelationConstraintCompiler.compile(snapshot))
    }

    private fun tableId(name: String) = TableId(origin, null, "sales", name)

    private fun table(
        id: TableId,
        columns: List<String>,
        primaryKey: OptionalValue<PrimaryKeyFact> = OptionalValue.Absent,
        uniqueKeys: Evidence<FrozenList<UniqueKeyFact>> = known(frozenListOf()),
        foreignKeys: Evidence<FrozenList<ForeignKeyFact>> = known(frozenListOf()),
    ) = TableSnapshot(
        id = id,
        comment = OptionalValue.Absent,
        columns = FrozenList.copyOf(
            columns.mapIndexed { index, name ->
                ColumnSnapshot(
                    id = ColumnId(id, name),
                    sourcePosition = index,
                    rawType = known(RawTypeMetadata("text")),
                    nullable = known(false),
                    comment = OptionalValue.Absent,
                )
            }
        ),
        primaryKey = primaryKey,
        uniqueKeys = uniqueKeys,
        foreignKeys = foreignKeys,
    )

    private fun fk(
        child: TableId,
        parent: TableId,
        name: String,
        mapping: Pair<String, String>,
    ) = ForeignKeyFact(
        childTable = child,
        referencedTable = TableReferenceEvidence(
            origin = known(parent.origin),
            catalog = OptionalValue.Absent,
            schema = OptionalValue.Present(parent.schema!!),
            name = known(parent.name),
        ),
        name = OptionalValue.Present(name),
        provenance = known(RelationProvenance.PHYSICAL),
        mappings = known(
            frozenListOf(
                ForeignKeyColumnMapping(ColumnId(child, mapping.first), mapping.second)
            )
        ),
    )

    private fun constrained(
        child: TableId,
        parent: TableId,
        name: String,
        childColumn: String,
        constraints: RelationConstraintEvidence,
    ) = ConstrainedSemanticRelation(
        relation = SemanticRelation(
            childTable = child,
            parentTable = parent,
            name = OptionalValue.Present(name),
            provenance = RelationProvenance.PHYSICAL,
            mappings = frozenListOf(
                ResolvedForeignKeyColumnMapping(
                    child = ColumnId(child, childColumn),
                    parent = ColumnId(parent, "id"),
                )
            ),
        ),
        constraints = constraints,
    )

    private fun <T> known(value: T): Evidence<T> = Evidence.Known(value)
}
