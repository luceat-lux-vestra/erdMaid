package com.algorist.erdmaid.semantic

import com.algorist.erdmaid.core.ColumnId
import com.algorist.erdmaid.core.ColumnSnapshot
import com.algorist.erdmaid.core.CoreDiagnostic
import com.algorist.erdmaid.core.Evidence
import com.algorist.erdmaid.core.FrozenList
import com.algorist.erdmaid.core.OptionalValue
import com.algorist.erdmaid.core.OriginId
import com.algorist.erdmaid.core.PrimaryKeyFact
import com.algorist.erdmaid.core.RawTypeMetadata
import com.algorist.erdmaid.core.RelationProvenance
import com.algorist.erdmaid.core.TableId
import com.algorist.erdmaid.core.TableSnapshot
import com.algorist.erdmaid.core.frozenListOf
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationIdentificationCompilerTest {
    private val origin = OriginId("ds-a")

    @Test
    fun `child primary key unavailable keeps identification unavailable`() {
        val child = tableId("child")
        val parent = tableId("parent")
        val relation = relation(child, parent, "parent_id" to "id")
        val result = RelationIdentificationCompiler.derive(
            relation,
            table(child, listOf("parent_id"), unavailablePk("child-pk-read-failed")),
            table(parent, listOf("id"), primaryKey(parent, "id")),
        )

        assertEquals(
            Evidence.Unavailable(
                CoreDiagnostic("relation-identification-unavailable", "child-primary-key:child-pk-read-failed")
            ),
            result,
        )
    }

    @Test
    fun `authoritative absence of child primary key does not prove non identifying`() {
        val child = tableId("child")
        val parent = tableId("parent")
        val result = RelationIdentificationCompiler.derive(
            relation(child, parent, "parent_id" to "id"),
            table(child, listOf("parent_id"), OptionalValue.Absent),
            table(parent, listOf("id"), primaryKey(parent, "id")),
        )

        assertEquals(
            Evidence.Unavailable(
                CoreDiagnostic("relation-identification-unavailable", "child-primary-key-absent")
            ),
            result,
        )
    }

    @Test
    fun `foreign key outside child primary key proves non identifying`() {
        val child = tableId("child")
        val parent = tableId("parent")
        val result = RelationIdentificationCompiler.derive(
            relation(child, parent, "parent_id" to "id"),
            table(child, listOf("id", "parent_id"), primaryKey(child, "id")),
            table(parent, listOf("id"), primaryKey(parent, "id")),
        )

        assertEquals(Evidence.Known(RelationIdentification.NON_IDENTIFYING), result)
    }

    @Test
    fun `composite child key containing exact ordered parent primary key proves identifying`() {
        val child = tableId("line")
        val parent = tableId("orders")
        val result = RelationIdentificationCompiler.derive(
            relation(
                child,
                parent,
                "tenant_id" to "tenant_id",
                "order_id" to "id",
            ),
            table(
                child,
                listOf("tenant_id", "order_id", "line_no"),
                primaryKey(child, "tenant_id", "order_id", "line_no"),
            ),
            table(
                parent,
                listOf("tenant_id", "id"),
                primaryKey(parent, "tenant_id", "id"),
            ),
        )

        assertEquals(Evidence.Known(RelationIdentification.IDENTIFYING), result)
    }

    @Test
    fun `child key containment with absent or unavailable parent primary key stays unavailable`() {
        val child = tableId("child")
        val parent = tableId("parent")
        val semantic = relation(child, parent, "parent_id" to "id")
        val childTable = table(child, listOf("parent_id", "seq"), primaryKey(child, "parent_id", "seq"))

        assertEquals(
            Evidence.Unavailable(
                CoreDiagnostic("relation-identification-unavailable", "parent-primary-key-absent")
            ),
            RelationIdentificationCompiler.derive(
                semantic,
                childTable,
                table(parent, listOf("id"), OptionalValue.Absent),
            ),
        )
        assertEquals(
            Evidence.Unavailable(
                CoreDiagnostic("relation-identification-unavailable", "parent-primary-key:parent-pk-read-failed")
            ),
            RelationIdentificationCompiler.derive(
                semantic,
                childTable,
                table(parent, listOf("id"), unavailablePk("parent-pk-read-failed")),
            ),
        )
    }

    @Test
    fun `alternate parent key and reordered parent primary key stay unavailable`() {
        val child = tableId("child")
        val parent = tableId("parent")
        val childTable = table(
            child,
            listOf("parent_a", "parent_b", "seq"),
            primaryKey(child, "parent_a", "parent_b", "seq"),
        )
        val parentTable = table(
            parent,
            listOf("a", "b", "alternate_a", "alternate_b"),
            primaryKey(parent, "a", "b"),
        )

        val alternate = RelationIdentificationCompiler.derive(
            relation(child, parent, "parent_a" to "alternate_a", "parent_b" to "alternate_b"),
            childTable,
            parentTable,
        )
        val reordered = RelationIdentificationCompiler.derive(
            relation(child, parent, "parent_a" to "b", "parent_b" to "a"),
            childTable,
            parentTable,
        )

        val expected = Evidence.Unavailable(
            CoreDiagnostic("relation-identification-unavailable", "parent-target-is-not-primary-key")
        )
        assertEquals(expected, alternate)
        assertEquals(expected, reordered)
    }

    @Test
    fun `multiple relations classify independently and provenance does not decide identification`() {
        val child = tableId("child")
        val parent = tableId("parent")
        val childTable = table(
            child,
            listOf("parent_id", "seq", "audit_parent_id"),
            primaryKey(child, "parent_id", "seq"),
        )
        val parentTable = table(parent, listOf("id"), primaryKey(parent, "id"))

        val identifyingVirtual = RelationIdentificationCompiler.derive(
            relation(
                child,
                parent,
                "parent_id" to "id",
                provenance = RelationProvenance.VIRTUAL,
            ),
            childTable,
            parentTable,
        )
        val nonIdentifyingPhysical = RelationIdentificationCompiler.derive(
            relation(child, parent, "audit_parent_id" to "id"),
            childTable,
            parentTable,
        )

        assertEquals(Evidence.Known(RelationIdentification.IDENTIFYING), identifyingVirtual)
        assertEquals(Evidence.Known(RelationIdentification.NON_IDENTIFYING), nonIdentifyingPhysical)
    }

    @Test
    fun `self relation uses the same exact key truth table`() {
        val node = tableId("node")
        val nodeTable = table(
            node,
            listOf("parent_id", "seq"),
            primaryKey(node, "parent_id", "seq"),
        )

        val result = RelationIdentificationCompiler.derive(
            relation(node, node, "parent_id" to "parent_id"),
            nodeTable,
            nodeTable,
        )

        assertEquals(
            Evidence.Unavailable(
                CoreDiagnostic("relation-identification-unavailable", "parent-target-is-not-primary-key")
            ),
            result,
        )
    }

    @Test
    fun `graph relation wrapper preserves semantic relation and multiplicity exactly`() {
        val child = tableId("child")
        val parent = tableId("parent")
        val semantic = relation(child, parent, "parent_id" to "id")
        val wrapped = ErdGraphRelation(
            semantic = semantic,
            identification = Evidence.Known(RelationIdentification.NON_IDENTIFYING),
        )

        assertEquals(semantic, wrapped.semantic)
        assertEquals(semantic.relation, wrapped.relation)
        assertEquals(semantic.multiplicity, wrapped.multiplicity)
    }

    @Test
    fun `identification derivation is locale independent`() {
        val child = tableId("I_child")
        val parent = tableId("İ_parent")
        val semantic = relation(child, parent, "parent_id" to "id")
        val childTable = table(child, listOf("id", "parent_id"), primaryKey(child, "id"))
        val parentTable = table(parent, listOf("id"), primaryKey(parent, "id"))
        val original = Locale.getDefault()

        try {
            Locale.setDefault(Locale.US)
            val us = RelationIdentificationCompiler.derive(semantic, childTable, parentTable)
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val turkish = RelationIdentificationCompiler.derive(semantic, childTable, parentTable)
            assertEquals(us, turkish)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `identification semantic surface contains no IntelliJ Mermaid or legacy generator types`() {
        val classes = listOf(
            RelationIdentification::class.java,
            ErdGraphRelation::class.java,
            RelationIdentificationCompiler::class.java,
        )
        val referenced = classes.flatMap { type ->
            buildList {
                addAll(type.declaredFields.map { it.type })
                addAll(type.declaredMethods.map { it.returnType })
                addAll(type.declaredMethods.flatMap { it.parameterTypes.toList() })
                addAll(type.declaredConstructors.flatMap { it.parameterTypes.toList() })
            }
        }

        assertTrue(referenced.none { it.name.startsWith("com.intellij.") })
        assertTrue(referenced.none { it.name.contains("mermaid", ignoreCase = true) })
        assertTrue(referenced.none { it.name.startsWith("com.algorist.erdmaid.generator.") })
    }

    private fun relation(
        child: TableId,
        parent: TableId,
        vararg mappings: Pair<String, String>,
        provenance: RelationProvenance = RelationProvenance.PHYSICAL,
    ): MultiplicitySemanticRelation {
        val semantic = SemanticRelation(
            childTable = child,
            parentTable = parent,
            name = OptionalValue.Absent,
            provenance = provenance,
            mappings = FrozenList.copyOf(
                mappings.map { (childColumn, parentColumn) ->
                    ResolvedForeignKeyColumnMapping(
                        child = ColumnId(child, childColumn),
                        parent = ColumnId(parent, parentColumn),
                    )
                }
            ),
        )
        return MultiplicitySemanticRelation(
            relation = ConstrainedSemanticRelation(
                relation = semantic,
                constraints = RelationConstraintEvidence(
                    childNullability = Evidence.Known(RelationTupleNullability.ALL_NON_NULL),
                    childUniqueness = Evidence.Known(RelationTupleUniqueness.NON_UNIQUE),
                    parentUniqueness = Evidence.Known(RelationTupleUniqueness.UNIQUE),
                ),
            ),
            multiplicity = RelationMultiplicity(
                parentsPerChild = RelationMultiplicityBounds(
                    minimum = Evidence.Known(RelationMultiplicityMinimum.ONE),
                    maximum = Evidence.Known(RelationMultiplicityMaximum.ONE),
                ),
                childrenPerParent = RelationMultiplicityBounds(
                    minimum = Evidence.Known(RelationMultiplicityMinimum.ZERO),
                    maximum = Evidence.Known(RelationMultiplicityMaximum.MANY),
                ),
            ),
        )
    }

    private fun table(
        id: TableId,
        columnNames: List<String>,
        primaryKey: OptionalValue<PrimaryKeyFact>,
    ): TableSnapshot = TableSnapshot(
        id = id,
        comment = OptionalValue.Absent,
        columns = FrozenList.copyOf(
            columnNames.mapIndexed { index, name ->
                ColumnSnapshot(
                    id = ColumnId(id, name),
                    sourcePosition = index,
                    rawType = Evidence.Known(RawTypeMetadata("text")),
                    nullable = Evidence.Known(false),
                    comment = OptionalValue.Absent,
                )
            }
        ),
        primaryKey = primaryKey,
        uniqueKeys = Evidence.Known(frozenListOf()),
        foreignKeys = Evidence.Known(frozenListOf()),
    )

    private fun primaryKey(table: TableId, vararg columns: String): OptionalValue<PrimaryKeyFact> =
        OptionalValue.Present(
            PrimaryKeyFact(
                name = OptionalValue.Present("pk_${table.name}"),
                columns = FrozenList.copyOf(columns.map { ColumnId(table, it) }),
            )
        )

    private fun unavailablePk(code: String): OptionalValue<PrimaryKeyFact> =
        OptionalValue.Unavailable(CoreDiagnostic(code))

    private fun tableId(name: String): TableId =
        TableId(origin = origin, catalog = null, schema = "sales", name = name)
}
