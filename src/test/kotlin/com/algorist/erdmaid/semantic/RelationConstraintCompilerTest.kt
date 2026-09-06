package com.algorist.erdmaid.semantic

import com.algorist.erdmaid.core.ColumnId
import com.algorist.erdmaid.core.ColumnSnapshot
import com.algorist.erdmaid.core.CoreDiagnostic
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
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationConstraintCompilerTest {
    private val origin = OriginId("ds-a")

    @Test
    fun `all mapped child columns non-null prove all-non-null tuple`() {
        val child = tableId("orders")
        val parent = tableId("users")
        val relation = fk(child, parent, "fk_orders_users", "user_id" to "id")
        val snapshot = snapshot(
            table(
                child,
                columns = listOf(column("user_id", known(false))),
                foreignKeys = known(frozenListOf(relation)),
            ),
            table(parent, columns = listOf(column("id", known(false)))),
        )

        assertEquals(
            complete(
                constrained(
                    semantic(child, parent, "fk_orders_users", "user_id" to "id"),
                    childNullability = known(RelationTupleNullability.ALL_NON_NULL),
                    childUniqueness = known(RelationTupleUniqueness.NON_UNIQUE),
                    parentUniqueness = known(RelationTupleUniqueness.NON_UNIQUE),
                )
            ),
            RelationConstraintCompiler.compile(snapshot),
        )
    }

    @Test
    fun `known nullable component proves nullable tuple even when another component is unavailable`() {
        val child = tableId("lines")
        val parent = tableId("orders")
        val relation = fk(
            child,
            parent,
            "fk_lines_orders",
            "tenant_id" to "tenant_id",
            "order_id" to "id",
        )
        val snapshot = snapshot(
            table(
                child,
                columns = listOf(
                    column("tenant_id", unavailable("tenant-nullability-unavailable")),
                    column("order_id", known(true)),
                ),
                foreignKeys = known(frozenListOf(relation)),
            ),
            table(
                parent,
                columns = listOf(column("tenant_id", known(false)), column("id", known(false))),
            ),
        )

        assertEquals(
            known(RelationTupleNullability.HAS_NULLABLE_COMPONENT),
            only(RelationConstraintCompiler.compile(snapshot)).constraints.childNullability,
        )
    }

    @Test
    fun `unavailable child nullability is not converted to non-null`() {
        val child = tableId("lines")
        val parent = tableId("orders")
        val relation = fk(
            child,
            parent,
            "fk_lines_orders",
            "tenant_id" to "tenant_id",
            "order_id" to "id",
        )
        val snapshot = snapshot(
            table(
                child,
                columns = listOf(
                    column("tenant_id", known(false)),
                    column("order_id", unavailable("order-nullability-unavailable")),
                ),
                foreignKeys = known(frozenListOf(relation)),
            ),
            table(
                parent,
                columns = listOf(column("tenant_id", known(false)), column("id", known(false))),
            ),
        )

        assertEquals(
            unavailableTupleNullability("order_id:order-nullability-unavailable"),
            only(RelationConstraintCompiler.compile(snapshot)).constraints.childNullability,
        )
    }

    @Test
    fun `child tuple containing primary key is unique even when unique-key evidence is unavailable`() {
        val child = tableId("child")
        val parent = tableId("parent")
        val relation = fk(
            child,
            parent,
            "fk_child_parent",
            "tenant_id" to "tenant_id",
            "id" to "id",
            "extra" to "extra",
        )
        val snapshot = snapshot(
            table(
                child,
                columns = nonNullColumns("tenant_id", "id", "extra"),
                primaryKey = primaryKey(child, "tenant_id", "id"),
                uniqueKeys = unavailable("unique-keys-unavailable"),
                foreignKeys = known(frozenListOf(relation)),
            ),
            table(parent, columns = nonNullColumns("tenant_id", "id", "extra")),
        )

        assertEquals(
            known(RelationTupleUniqueness.UNIQUE),
            only(RelationConstraintCompiler.compile(snapshot)).constraints.childUniqueness,
        )
    }

    @Test
    fun `child tuple containing reordered unique key is unique even when primary-key evidence is unavailable`() {
        val child = tableId("child")
        val parent = tableId("parent")
        val relation = fk(
            child,
            parent,
            "fk_child_parent",
            "id" to "id",
            "extra" to "extra",
            "tenant_id" to "tenant_id",
        )
        val snapshot = snapshot(
            table(
                child,
                columns = nonNullColumns("id", "extra", "tenant_id"),
                primaryKey = OptionalValue.Unavailable(CoreDiagnostic("primary-key-unavailable")),
                uniqueKeys = known(
                    frozenListOf(uniqueKey(child, "tenant_id", "id"))
                ),
                foreignKeys = known(frozenListOf(relation)),
            ),
            table(parent, columns = nonNullColumns("id", "extra", "tenant_id")),
        )

        assertEquals(
            known(RelationTupleUniqueness.UNIQUE),
            only(RelationConstraintCompiler.compile(snapshot)).constraints.childUniqueness,
        )
    }

    @Test
    fun `fully authoritative key evidence with no contained key proves non-unique`() {
        val child = tableId("child")
        val parent = tableId("parent")
        val relation = fk(child, parent, "fk_child_parent", "parent_id" to "id")
        val snapshot = snapshot(
            table(
                child,
                columns = nonNullColumns("id", "parent_id", "email"),
                primaryKey = primaryKey(child, "id"),
                uniqueKeys = known(frozenListOf(uniqueKey(child, "email"))),
                foreignKeys = known(frozenListOf(relation)),
            ),
            table(parent, columns = nonNullColumns("id")),
        )

        assertEquals(
            known(RelationTupleUniqueness.NON_UNIQUE),
            only(RelationConstraintCompiler.compile(snapshot)).constraints.childUniqueness,
        )
    }

    @Test
    fun `unavailable primary-key evidence blocks negative uniqueness proof`() {
        val child = tableId("child")
        val parent = tableId("parent")
        val relation = fk(child, parent, "fk_child_parent", "parent_id" to "id")
        val snapshot = snapshot(
            table(
                child,
                columns = nonNullColumns("parent_id", "email"),
                primaryKey = OptionalValue.Unavailable(CoreDiagnostic("primary-key-unavailable")),
                uniqueKeys = known(frozenListOf(uniqueKey(child, "email"))),
                foreignKeys = known(frozenListOf(relation)),
            ),
            table(parent, columns = nonNullColumns("id")),
        )

        assertEquals(
            unavailableTupleUniqueness(
                context = "child",
                detail = "primary-key:primary-key-unavailable",
            ),
            only(RelationConstraintCompiler.compile(snapshot)).constraints.childUniqueness,
        )
    }

    @Test
    fun `unavailable unique-key evidence blocks negative uniqueness proof`() {
        val child = tableId("child")
        val parent = tableId("parent")
        val relation = fk(child, parent, "fk_child_parent", "parent_id" to "id")
        val snapshot = snapshot(
            table(
                child,
                columns = nonNullColumns("id", "parent_id"),
                primaryKey = primaryKey(child, "id"),
                uniqueKeys = unavailable("unique-keys-unavailable"),
                foreignKeys = known(frozenListOf(relation)),
            ),
            table(parent, columns = nonNullColumns("id")),
        )

        assertEquals(
            unavailableTupleUniqueness(
                context = "child",
                detail = "unique-keys:unique-keys-unavailable",
            ),
            only(RelationConstraintCompiler.compile(snapshot)).constraints.childUniqueness,
        )
    }

    @Test
    fun `parent tuple uses the same positive uniqueness proof`() {
        val child = tableId("child")
        val parent = tableId("parent")
        val relation = fk(
            child,
            parent,
            "fk_child_parent",
            "parent_id" to "id",
            "parent_region" to "region",
        )
        val snapshot = snapshot(
            table(
                child,
                columns = nonNullColumns("parent_id", "parent_region"),
                foreignKeys = known(frozenListOf(relation)),
            ),
            table(
                parent,
                columns = nonNullColumns("id", "region"),
                primaryKey = OptionalValue.Unavailable(CoreDiagnostic("primary-key-unavailable")),
                uniqueKeys = known(frozenListOf(uniqueKey(parent, "id"))),
            ),
        )

        assertEquals(
            known(RelationTupleUniqueness.UNIQUE),
            only(RelationConstraintCompiler.compile(snapshot)).constraints.parentUniqueness,
        )
    }

    @Test
    fun `unknown constraint evidence does not degrade complete connectivity`() {
        val child = tableId("child")
        val parent = tableId("parent")
        val relation = fk(child, parent, "fk_child_parent", "parent_id" to "id")
        val snapshot = snapshot(
            table(
                child,
                columns = listOf(column("parent_id", unavailable("nullable-unavailable"))),
                primaryKey = OptionalValue.Unavailable(CoreDiagnostic("child-pk-unavailable")),
                uniqueKeys = unavailable("child-uk-unavailable"),
                foreignKeys = known(frozenListOf(relation)),
            ),
            table(
                parent,
                columns = nonNullColumns("id"),
                primaryKey = OptionalValue.Unavailable(CoreDiagnostic("parent-pk-unavailable")),
                uniqueKeys = unavailable("parent-uk-unavailable"),
            ),
        )

        assertEquals(
            complete(
                constrained(
                    semantic(child, parent, "fk_child_parent", "parent_id" to "id"),
                    childNullability = unavailableTupleNullability(
                        "parent_id:nullable-unavailable"
                    ),
                    childUniqueness = unavailableTupleUniqueness(
                        context = "child",
                        detail = "primary-key:child-pk-unavailable,unique-keys:child-uk-unavailable",
                    ),
                    parentUniqueness = unavailableTupleUniqueness(
                        context = "parent",
                        detail = "primary-key:parent-pk-unavailable,unique-keys:parent-uk-unavailable",
                    ),
                )
            ),
            RelationConstraintCompiler.compile(snapshot),
        )
    }

    @Test
    fun `constraint enrichment preserves canonical relation order and duplicate collapse`() {
        val child = tableId("orders")
        val parent = tableId("users")
        val a = fk(child, parent, "a_fk", "a_user" to "id")
        val z = fk(child, parent, "z_fk", "z_user" to "id")
        val snapshot = snapshot(
            table(
                child,
                columns = nonNullColumns("a_user", "z_user"),
                foreignKeys = known(frozenListOf(z, a, a.copy())),
            ),
            table(parent, columns = nonNullColumns("id")),
        )

        val semantic = RelationSemanticCompiler.compile(snapshot)
            as ExportOutcome.Complete<FrozenList<SemanticRelation>>
        val constrained = RelationConstraintCompiler.compile(snapshot)
            as ExportOutcome.Complete<FrozenList<ConstrainedSemanticRelation>>

        assertEquals(
            semantic.value,
            FrozenList.copyOf(constrained.value.map { it.relation }),
        )
    }

    @Test
    fun `constraint semantic type surface is IntelliJ Mermaid and legacy-generator free`() {
        val semanticClasses = listOf(
            RelationTupleNullability::class.java,
            RelationTupleUniqueness::class.java,
            RelationConstraintEvidence::class.java,
            ConstrainedSemanticRelation::class.java,
            RelationConstraintCompiler::class.java,
        )

        val referencedTypes = semanticClasses.flatMap { type ->
            buildList {
                addAll(type.declaredFields.map { it.type })
                addAll(type.declaredMethods.map { it.returnType })
                addAll(type.declaredMethods.flatMap { it.parameterTypes.asList() })
                addAll(type.declaredConstructors.flatMap { it.parameterTypes.asList() })
            }
        }

        assertTrue(referencedTypes.none { it.name.startsWith("com.intellij.") })
        assertTrue(referencedTypes.none { it.name.contains("mermaid", ignoreCase = true) })
        assertTrue(referencedTypes.none { it.name.startsWith("com.algorist.erdmaid.generator.") })
    }

    private fun tableId(name: String): TableId =
        TableId(origin, catalog = null, schema = "sales", name = name)

    private data class ColumnSpec(
        val name: String,
        val nullable: Evidence<Boolean>,
    )

    private fun column(name: String, nullable: Evidence<Boolean>) = ColumnSpec(name, nullable)

    private fun nonNullColumns(vararg names: String): List<ColumnSpec> =
        names.map { column(it, known(false)) }

    private fun table(
        id: TableId,
        columns: List<ColumnSpec>,
        primaryKey: OptionalValue<PrimaryKeyFact> = OptionalValue.Absent,
        uniqueKeys: Evidence<FrozenList<UniqueKeyFact>> = known(frozenListOf()),
        foreignKeys: Evidence<FrozenList<ForeignKeyFact>> = known(frozenListOf()),
    ): TableSnapshot = TableSnapshot(
        id = id,
        comment = OptionalValue.Absent,
        columns = FrozenList.copyOf(
            columns.mapIndexed { index, spec ->
                ColumnSnapshot(
                    id = ColumnId(id, spec.name),
                    sourcePosition = index,
                    rawType = known(RawTypeMetadata("text")),
                    nullable = spec.nullable,
                    comment = OptionalValue.Absent,
                )
            }
        ),
        primaryKey = primaryKey,
        uniqueKeys = uniqueKeys,
        foreignKeys = foreignKeys,
    )

    private fun primaryKey(table: TableId, vararg columns: String): OptionalValue<PrimaryKeyFact> =
        OptionalValue.Present(
            PrimaryKeyFact(
                name = OptionalValue.Present("pk_${table.name}"),
                columns = FrozenList.copyOf(columns.map { ColumnId(table, it) }),
            )
        )

    private fun uniqueKey(table: TableId, vararg columns: String): UniqueKeyFact =
        UniqueKeyFact(
            name = OptionalValue.Present("uk_${table.name}_${columns.joinToString("_")}"),
            columns = FrozenList.copyOf(columns.map { ColumnId(table, it) }),
        )

    private fun fk(
        child: TableId,
        parent: TableId,
        name: String,
        vararg mappings: Pair<String, String>,
    ): ForeignKeyFact = ForeignKeyFact(
        childTable = child,
        referencedTable = exactReference(parent),
        name = OptionalValue.Present(name),
        provenance = known(RelationProvenance.PHYSICAL),
        mappings = known(
            FrozenList.copyOf(
                mappings.map { (childColumn, parentColumn) ->
                    ForeignKeyColumnMapping(ColumnId(child, childColumn), parentColumn)
                }
            )
        ),
    )

    private fun exactReference(parent: TableId): TableReferenceEvidence = TableReferenceEvidence(
        origin = known(parent.origin),
        catalog = OptionalValue.Absent,
        schema = OptionalValue.Present(parent.schema!!),
        name = known(parent.name),
    )

    private fun semantic(
        child: TableId,
        parent: TableId,
        name: String,
        vararg mappings: Pair<String, String>,
    ): SemanticRelation = SemanticRelation(
        childTable = child,
        parentTable = parent,
        name = OptionalValue.Present(name),
        provenance = RelationProvenance.PHYSICAL,
        mappings = FrozenList.copyOf(
            mappings.map { (childColumn, parentColumn) ->
                ResolvedForeignKeyColumnMapping(
                    child = ColumnId(child, childColumn),
                    parent = ColumnId(parent, parentColumn),
                )
            }
        ),
    )

    private fun constrained(
        relation: SemanticRelation,
        childNullability: Evidence<RelationTupleNullability>,
        childUniqueness: Evidence<RelationTupleUniqueness>,
        parentUniqueness: Evidence<RelationTupleUniqueness>,
    ): ConstrainedSemanticRelation = ConstrainedSemanticRelation(
        relation = relation,
        constraints = RelationConstraintEvidence(
            childNullability = childNullability,
            childUniqueness = childUniqueness,
            parentUniqueness = parentUniqueness,
        ),
    )

    private fun complete(vararg relations: ConstrainedSemanticRelation) =
        ExportOutcome.Complete(FrozenList.copyOf(relations.asList()))

    private fun only(
        outcome: ExportOutcome<FrozenList<ConstrainedSemanticRelation>>,
    ): ConstrainedSemanticRelation {
        assertTrue("Expected complete outcome but got $outcome", outcome is ExportOutcome.Complete)
        outcome as ExportOutcome.Complete<FrozenList<ConstrainedSemanticRelation>>
        assertEquals(1, outcome.value.size)
        return outcome.value.single()
    }

    private fun unavailableTupleNullability(detail: String) =
        Evidence.Unavailable(
            CoreDiagnostic("relation-child-nullability-unavailable", detail)
        )

    private fun unavailableTupleUniqueness(context: String, detail: String) =
        Evidence.Unavailable(
            CoreDiagnostic("relation-$context-uniqueness-unavailable", detail)
        )

    private fun snapshot(vararg tables: TableSnapshot) =
        SchemaSnapshot(origin, FrozenList.copyOf(tables.asList()))

    private fun <T> known(value: T): Evidence<T> = Evidence.Known(value)

    private fun <T> unavailable(code: String): Evidence<T> =
        Evidence.Unavailable(CoreDiagnostic(code))
}
