package com.algorist.erdmaid.renderer

import com.algorist.erdmaid.core.ColumnId
import com.algorist.erdmaid.core.ColumnSnapshot
import com.algorist.erdmaid.core.Evidence
import com.algorist.erdmaid.core.ExportOutcome
import com.algorist.erdmaid.core.FrozenList
import com.algorist.erdmaid.core.OptionalValue
import com.algorist.erdmaid.core.OriginId
import com.algorist.erdmaid.core.PrimaryKeyFact
import com.algorist.erdmaid.core.RawTypeMetadata
import com.algorist.erdmaid.core.RelationProvenance
import com.algorist.erdmaid.core.TableId
import com.algorist.erdmaid.core.TableSnapshot
import com.algorist.erdmaid.core.frozenListOf
import com.algorist.erdmaid.semantic.ConstrainedSemanticRelation
import com.algorist.erdmaid.semantic.ErdGraph
import com.algorist.erdmaid.semantic.ErdGraphRelation
import com.algorist.erdmaid.semantic.ErdGraphTable
import com.algorist.erdmaid.semantic.MultiplicitySemanticRelation
import com.algorist.erdmaid.semantic.RelationConstraintEvidence
import com.algorist.erdmaid.semantic.RelationIdentification
import com.algorist.erdmaid.semantic.RelationMultiplicity
import com.algorist.erdmaid.semantic.RelationMultiplicityBounds
import com.algorist.erdmaid.semantic.RelationMultiplicityMaximum
import com.algorist.erdmaid.semantic.RelationMultiplicityMinimum
import com.algorist.erdmaid.semantic.RelationTupleNullability
import com.algorist.erdmaid.semantic.RelationTupleUniqueness
import com.algorist.erdmaid.semantic.ResolvedForeignKeyColumnMapping
import com.algorist.erdmaid.semantic.SemanticRelation
import com.algorist.erdmaid.semantic.TableQualificationIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MermaidGrammarFixtureBindingTest {
    private val origin = OriginId("o")
    private val parent = tableId("A")
    private val child = tableId("B")

    @Test
    fun `production grammar surface fixture is exact serializer output`() {
        val graph = ErdGraph(
            origin = origin,
            tables = frozenListOf(
                graphTable(
                    table(
                        id = parent,
                        columns = frozenListOf(
                            column(parent, "id", 0, comment = OptionalValue.Present(""))
                        ),
                        primaryKeyColumns = listOf("id"),
                    )
                ),
                graphTable(
                    table(
                        id = child,
                        columns = frozenListOf(column(child, "parent_id", 0)),
                    )
                ),
            ),
            relations = frozenListOf(
                relation(
                    name = OptionalValue.Present("one-one"),
                    identification = known(RelationIdentification.IDENTIFYING),
                    parents = bounds(RelationMultiplicityMinimum.ONE, RelationMultiplicityMaximum.ONE),
                    children = bounds(RelationMultiplicityMinimum.ONE, RelationMultiplicityMaximum.ONE),
                ),
                relation(
                    name = OptionalValue.Present("zero-one"),
                    identification = known(RelationIdentification.NON_IDENTIFYING),
                    parents = bounds(RelationMultiplicityMinimum.ZERO, RelationMultiplicityMaximum.ONE),
                    children = bounds(RelationMultiplicityMinimum.ZERO, RelationMultiplicityMaximum.ONE),
                ),
                relation(
                    name = OptionalValue.Present("one-many"),
                    identification = known(RelationIdentification.IDENTIFYING),
                    parents = bounds(RelationMultiplicityMinimum.ONE, RelationMultiplicityMaximum.MANY),
                    children = bounds(RelationMultiplicityMinimum.ONE, RelationMultiplicityMaximum.MANY),
                ),
                relation(
                    name = OptionalValue.Present("zero-many"),
                    identification = known(RelationIdentification.NON_IDENTIFYING),
                    parents = bounds(RelationMultiplicityMinimum.ZERO, RelationMultiplicityMaximum.MANY),
                    children = bounds(RelationMultiplicityMinimum.ZERO, RelationMultiplicityMaximum.MANY),
                ),
                relation(name = OptionalValue.Present("")),
            ),
        )

        assertEquals(fixture("production-surface.mmd"), complete(graph, includeColumnReferences = false))
    }

    @Test
    fun `hostile encoded metadata fixture is exact serializer output`() {
        val hostile = "x\rY\nZ\r\n%%\"\\{}[]|:<>\u0001\u2028~"
        val parentTable = table(
            id = parent,
            columns = frozenListOf(column(parent, "id", 0, comment = OptionalValue.Present(hostile))),
            primaryKeyColumns = listOf("id"),
            comment = OptionalValue.Present(hostile),
        )
        val childTable = table(
            id = child,
            columns = frozenListOf(column(child, hostile, 0)),
        )
        val graph = ErdGraph(
            origin = origin,
            tables = frozenListOf(graphTable(parentTable), graphTable(childTable)),
            relations = frozenListOf(
                relation(
                    childColumn = hostile,
                    name = OptionalValue.Present(hostile),
                )
            ),
        )

        assertEquals(fixture("production-hostile.mmd"), complete(graph))
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/mermaid/$name")) { "Missing Mermaid grammar fixture: $name" }
            .readText()

    private fun relation(
        childColumn: String = "parent_id",
        name: OptionalValue<String>,
        identification: Evidence<RelationIdentification> = known(RelationIdentification.NON_IDENTIFYING),
        parents: RelationMultiplicityBounds = bounds(
            RelationMultiplicityMinimum.ONE,
            RelationMultiplicityMaximum.ONE,
        ),
        children: RelationMultiplicityBounds = bounds(
            RelationMultiplicityMinimum.ZERO,
            RelationMultiplicityMaximum.MANY,
        ),
    ): ErdGraphRelation {
        val semantic = SemanticRelation(
            childTable = child,
            parentTable = parent,
            name = name,
            provenance = RelationProvenance.PHYSICAL,
            mappings = frozenListOf(
                ResolvedForeignKeyColumnMapping(
                    child = ColumnId(child, childColumn),
                    parent = ColumnId(parent, "id"),
                )
            ),
        )
        return ErdGraphRelation(
            semantic = MultiplicitySemanticRelation(
                relation = ConstrainedSemanticRelation(
                    relation = semantic,
                    constraints = RelationConstraintEvidence(
                        childNullability = known(RelationTupleNullability.ALL_NON_NULL),
                        childUniqueness = known(RelationTupleUniqueness.NON_UNIQUE),
                        parentUniqueness = known(RelationTupleUniqueness.UNIQUE),
                    ),
                ),
                multiplicity = RelationMultiplicity(
                    parentsPerChild = parents,
                    childrenPerParent = children,
                ),
            ),
            identification = identification,
        )
    }

    private fun table(
        id: TableId,
        columns: FrozenList<ColumnSnapshot>,
        primaryKeyColumns: List<String> = emptyList(),
        comment: OptionalValue<String> = OptionalValue.Absent,
    ): TableSnapshot {
        val primaryKey = if (primaryKeyColumns.isEmpty()) {
            OptionalValue.Absent
        } else {
            OptionalValue.Present(
                PrimaryKeyFact(
                    name = OptionalValue.Absent,
                    columns = FrozenList.copyOf(primaryKeyColumns.map { ColumnId(id, it) }),
                )
            )
        }
        return TableSnapshot(
            id = id,
            comment = comment,
            columns = columns,
            primaryKey = primaryKey,
            uniqueKeys = known(frozenListOf()),
            foreignKeys = known(frozenListOf()),
        )
    }

    private fun column(
        table: TableId,
        name: String,
        position: Int,
        comment: OptionalValue<String> = OptionalValue.Absent,
    ): ColumnSnapshot = ColumnSnapshot(
        id = ColumnId(table, name),
        sourcePosition = position,
        rawType = known(
            RawTypeMetadata(
                name = "INT",
                length = OptionalValue.Absent,
                precision = OptionalValue.Absent,
                scale = OptionalValue.Absent,
            )
        ),
        nullable = known(true),
        comment = comment,
    )

    private fun graphTable(table: TableSnapshot): ErdGraphTable =
        ErdGraphTable(table, TableQualificationIntent.UNQUALIFIED)

    private fun tableId(name: String): TableId = TableId(
        origin = origin,
        catalog = null,
        schema = null,
        name = name,
    )

    private fun bounds(
        minimum: RelationMultiplicityMinimum,
        maximum: RelationMultiplicityMaximum,
    ): RelationMultiplicityBounds = RelationMultiplicityBounds(known(minimum), known(maximum))

    private fun complete(
        graph: ErdGraph,
        includeColumnReferences: Boolean = true,
    ): String {
        val outcome = MermaidDocumentSerializer.serialize(
            graph,
            MermaidDocumentOptions(includeColumnReferences = includeColumnReferences),
        )
        assertTrue(outcome is ExportOutcome.Complete)
        return (outcome as ExportOutcome.Complete).value
    }

    private fun <T> known(value: T): Evidence<T> = Evidence.Known(value)
}
