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

class RelationMultiplicityCompilerTest {
    private val origin = OriginId("ds-a")

    @Test
    fun `physical non-null child and unique parent prove exactly one parent per child`() {
        val child = tableId("orders")
        val parent = tableId("users")
        val snapshot = snapshot(
            table(
                child,
                columns = listOf(column("user_id", false)),
                foreignKeys = known(frozenListOf(fk(child, parent, "user_id" to "id"))),
            ),
            table(
                parent,
                columns = listOf(column("id", false)),
                primaryKey = primaryKey(parent, "id"),
            ),
        )

        assertEquals(
            bounds(RelationMultiplicityMinimum.ONE, RelationMultiplicityMaximum.ONE),
            only(snapshot).multiplicity.parentsPerChild,
        )
    }

    @Test
    fun `physical nullable single-column child proves zero-or-one parent per child`() {
        val child = tableId("orders")
        val parent = tableId("users")
        val snapshot = snapshot(
            table(
                child,
                columns = listOf(column("user_id", true)),
                foreignKeys = known(frozenListOf(fk(child, parent, "user_id" to "id"))),
            ),
            table(
                parent,
                columns = listOf(column("id", false)),
                primaryKey = primaryKey(parent, "id"),
            ),
        )

        assertEquals(
            bounds(RelationMultiplicityMinimum.ZERO, RelationMultiplicityMaximum.ONE),
            only(snapshot).multiplicity.parentsPerChild,
        )
    }

    @Test
    fun `physical all-non-null composite child still proves minimum one`() {
        val child = tableId("lines")
        val parent = tableId("orders")
        val snapshot = snapshot(
            table(
                child,
                columns = listOf(column("tenant_id", false), column("order_id", false)),
                foreignKeys = known(
                    frozenListOf(
                        fk(
                            child,
                            parent,
                            "tenant_id" to "tenant_id",
                            "order_id" to "id",
                        )
                    )
                ),
            ),
            table(
                parent,
                columns = listOf(column("tenant_id", false), column("id", false)),
                primaryKey = primaryKey(parent, "tenant_id", "id"),
            ),
        )

        assertEquals(
            known(RelationMultiplicityMinimum.ONE),
            only(snapshot).multiplicity.parentsPerChild.minimum,
        )
    }

    @Test
    fun `nullable composite child keeps parent minimum unavailable without match semantics`() {
        val child = tableId("lines")
        val parent = tableId("orders")
        val snapshot = snapshot(
            table(
                child,
                columns = listOf(column("tenant_id", false), column("order_id", true)),
                foreignKeys = known(
                    frozenListOf(
                        fk(
                            child,
                            parent,
                            "tenant_id" to "tenant_id",
                            "order_id" to "id",
                        )
                    )
                ),
            ),
            table(
                parent,
                columns = listOf(column("tenant_id", false), column("id", false)),
                primaryKey = primaryKey(parent, "tenant_id", "id"),
            ),
        )

        assertEquals(
            unavailableMinimum("composite-nullable-match-semantics-unavailable"),
            only(snapshot).multiplicity.parentsPerChild.minimum,
        )
    }

    @Test
    fun `virtual relation never upgrades non-null tuple to mandatory parent participation`() {
        val child = tableId("orders")
        val parent = tableId("users")
        val snapshot = snapshot(
            table(
                child,
                columns = listOf(column("user_id", false)),
                foreignKeys = known(
                    frozenListOf(
                        fk(
                            child,
                            parent,
                            "user_id" to "id",
                            provenance = RelationProvenance.VIRTUAL,
                        )
                    )
                ),
            ),
            table(
                parent,
                columns = listOf(column("id", false)),
                primaryKey = primaryKey(parent, "id"),
            ),
        )

        assertEquals(
            unavailableMinimum("virtual-relation-does-not-prove-referential-integrity"),
            only(snapshot).multiplicity.parentsPerChild.minimum,
        )
        assertEquals(
            known(RelationMultiplicityMaximum.ONE),
            only(snapshot).multiplicity.parentsPerChild.maximum,
        )
    }

    @Test
    fun `non-unique parent tuple proves many possible parents`() {
        val child = tableId("child")
        val parent = tableId("parent")
        val snapshot = snapshot(
            table(
                child,
                columns = listOf(column("parent_code", false)),
                foreignKeys = known(frozenListOf(fk(child, parent, "parent_code" to "code"))),
            ),
            table(parent, columns = listOf(column("code", false))),
        )

        assertEquals(
            known(RelationMultiplicityMaximum.MANY),
            only(snapshot).multiplicity.parentsPerChild.maximum,
        )
    }

    @Test
    fun `unique child tuple proves at most one child per parent while reverse minimum stays zero`() {
        val child = tableId("profile")
        val parent = tableId("user")
        val snapshot = snapshot(
            table(
                child,
                columns = listOf(column("user_id", false)),
                primaryKey = primaryKey(child, "user_id"),
                foreignKeys = known(frozenListOf(fk(child, parent, "user_id" to "id"))),
            ),
            table(
                parent,
                columns = listOf(column("id", false)),
                primaryKey = primaryKey(parent, "id"),
            ),
        )

        assertEquals(
            bounds(RelationMultiplicityMinimum.ZERO, RelationMultiplicityMaximum.ONE),
            only(snapshot).multiplicity.childrenPerParent,
        )
    }

    @Test
    fun `non-unique child tuple proves many children per parent while reverse minimum stays zero`() {
        val child = tableId("orders")
        val parent = tableId("users")
        val snapshot = snapshot(
            table(
                child,
                columns = listOf(column("user_id", false)),
                foreignKeys = known(frozenListOf(fk(child, parent, "user_id" to "id"))),
            ),
            table(
                parent,
                columns = listOf(column("id", false)),
                primaryKey = primaryKey(parent, "id"),
            ),
        )

        assertEquals(
            bounds(RelationMultiplicityMinimum.ZERO, RelationMultiplicityMaximum.MANY),
            only(snapshot).multiplicity.childrenPerParent,
        )
    }

    @Test
    fun `unavailable evidence affects only the bound that depends on it`() {
        val child = tableId("orders")
        val parent = tableId("users")
        val snapshot = snapshot(
            table(
                child,
                columns = listOf(column("user_id", unavailable("child-nullability-unavailable"))),
                foreignKeys = known(frozenListOf(fk(child, parent, "user_id" to "id"))),
            ),
            table(
                parent,
                columns = listOf(column("id", false)),
                primaryKey = OptionalValue.Unavailable(CoreDiagnostic("parent-pk-unavailable")),
                uniqueKeys = unavailable("parent-uk-unavailable"),
            ),
        )

        val multiplicity = only(snapshot).multiplicity
        assertEquals(
            Evidence.Unavailable(
                CoreDiagnostic(
                    "relation-child-nullability-unavailable",
                    "user_id:child-nullability-unavailable",
                )
            ),
            multiplicity.parentsPerChild.minimum,
        )
        assertEquals(
            Evidence.Unavailable(
                CoreDiagnostic(
                    "relation-parent-uniqueness-unavailable",
                    "primary-key:parent-pk-unavailable,unique-keys:parent-uk-unavailable",
                )
            ),
            multiplicity.parentsPerChild.maximum,
        )
        assertEquals(known(RelationMultiplicityMinimum.ZERO), multiplicity.childrenPerParent.minimum)
        assertEquals(known(RelationMultiplicityMaximum.MANY), multiplicity.childrenPerParent.maximum)
    }

    @Test
    fun `unavailable child uniqueness does not erase known parent bounds`() {
        val child = tableId("orders")
        val parent = tableId("users")
        val snapshot = snapshot(
            table(
                child,
                columns = listOf(column("user_id", false)),
                primaryKey = OptionalValue.Unavailable(CoreDiagnostic("child-pk-unavailable")),
                uniqueKeys = unavailable("child-uk-unavailable"),
                foreignKeys = known(frozenListOf(fk(child, parent, "user_id" to "id"))),
            ),
            table(
                parent,
                columns = listOf(column("id", false)),
                primaryKey = primaryKey(parent, "id"),
            ),
        )

        val multiplicity = only(snapshot).multiplicity
        assertEquals(
            bounds(RelationMultiplicityMinimum.ONE, RelationMultiplicityMaximum.ONE),
            multiplicity.parentsPerChild,
        )
        assertEquals(known(RelationMultiplicityMinimum.ZERO), multiplicity.childrenPerParent.minimum)
        assertEquals(
            Evidence.Unavailable(
                CoreDiagnostic(
                    "relation-child-uniqueness-unavailable",
                    "primary-key:child-pk-unavailable,unique-keys:child-uk-unavailable",
                )
            ),
            multiplicity.childrenPerParent.maximum,
        )
    }

    @Test
    fun `multiplicity enrichment preserves canonical relation authority exactly`() {
        val child = tableId("orders")
        val parent = tableId("users")
        val a = fk(child, parent, "created_by" to "id", name = "a_fk")
        val z = fk(child, parent, "updated_by" to "id", name = "z_fk")
        val snapshot = snapshot(
            table(
                child,
                columns = listOf(column("created_by", false), column("updated_by", true)),
                foreignKeys = known(frozenListOf(z, a, a.copy())),
            ),
            table(
                parent,
                columns = listOf(column("id", false)),
                primaryKey = primaryKey(parent, "id"),
            ),
        )

        val constrained = RelationConstraintCompiler.compile(snapshot)
            as ExportOutcome.Complete<FrozenList<ConstrainedSemanticRelation>>
        val enriched = RelationMultiplicityCompiler.compile(snapshot)
            as ExportOutcome.Complete<FrozenList<MultiplicitySemanticRelation>>

        assertEquals(
            constrained.value,
            FrozenList.copyOf(enriched.value.map { it.relation }),
        )
    }

    @Test
    fun `multiplicity semantic type surface is IntelliJ Mermaid and legacy-generator free`() {
        val semanticClasses = listOf(
            RelationMultiplicityMinimum::class.java,
            RelationMultiplicityMaximum::class.java,
            RelationMultiplicityBounds::class.java,
            RelationMultiplicity::class.java,
            MultiplicitySemanticRelation::class.java,
            RelationMultiplicityCompiler::class.java,
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

    private data class ColumnSpec(
        val name: String,
        val nullable: Evidence<Boolean>,
    )

    private fun tableId(name: String) =
        TableId(origin, catalog = null, schema = "sales", name = name)

    private fun column(name: String, nullable: Boolean) = ColumnSpec(name, known(nullable))

    private fun column(name: String, nullable: Evidence<Boolean>) = ColumnSpec(name, nullable)

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

    private fun fk(
        child: TableId,
        parent: TableId,
        vararg mappings: Pair<String, String>,
        name: String = "fk_${child.name}_${parent.name}",
        provenance: RelationProvenance = RelationProvenance.PHYSICAL,
    ): ForeignKeyFact = ForeignKeyFact(
        childTable = child,
        referencedTable = TableReferenceEvidence(
            origin = known(parent.origin),
            catalog = OptionalValue.Absent,
            schema = OptionalValue.Present(parent.schema!!),
            name = known(parent.name),
        ),
        name = OptionalValue.Present(name),
        provenance = known(provenance),
        mappings = known(
            FrozenList.copyOf(
                mappings.map { (childColumn, parentColumn) ->
                    ForeignKeyColumnMapping(ColumnId(child, childColumn), parentColumn)
                }
            )
        ),
    )

    private fun snapshot(vararg tables: TableSnapshot) =
        SchemaSnapshot(origin, FrozenList.copyOf(tables.asList()))

    private fun only(snapshot: SchemaSnapshot): MultiplicitySemanticRelation {
        val outcome = RelationMultiplicityCompiler.compile(snapshot)
        assertTrue("Expected complete outcome but got $outcome", outcome is ExportOutcome.Complete)
        outcome as ExportOutcome.Complete<FrozenList<MultiplicitySemanticRelation>>
        assertEquals(1, outcome.value.size)
        return outcome.value.single()
    }

    private fun bounds(
        minimum: RelationMultiplicityMinimum,
        maximum: RelationMultiplicityMaximum,
    ) = RelationMultiplicityBounds(known(minimum), known(maximum))

    private fun unavailableMinimum(detail: String) =
        Evidence.Unavailable(
            CoreDiagnostic("relation-parents-per-child-minimum-unavailable", detail)
        )

    private fun <T> known(value: T): Evidence<T> = Evidence.Known(value)

    private fun <T> unavailable(code: String): Evidence<T> =
        Evidence.Unavailable(CoreDiagnostic(code))
}
