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
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErdGraphCompilerTest {
    private val origin = OriginId("ds-a")

    @Test
    fun `permuted snapshot table order produces exactly equal graph`() {
        val first = table(tableId(name = "zeta", schema = "z"), "id")
        val second = table(tableId(name = "alpha", schema = "a"), "id")

        val left = completeGraph(snapshot(first, second))
        val right = completeGraph(snapshot(second, first))

        assertEquals(left, right)
        assertEquals(listOf("alpha", "zeta"), left.tables.map { it.snapshot.id.name })
    }

    @Test
    fun `canonical graph order and qualification are locale independent`() {
        val tables = listOf(
            table(tableId(name = "I"), "id"),
            table(tableId(name = "İ"), "id"),
            table(tableId(name = "i"), "id"),
            table(tableId(name = "ı"), "id"),
        )
        val original = Locale.getDefault()

        try {
            Locale.setDefault(Locale.US)
            val us = completeGraph(snapshot(*tables.reversed().toTypedArray()))

            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val turkish = completeGraph(snapshot(*tables.toTypedArray()))

            assertEquals(us, turkish)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `exact-case table names remain distinct and unqualified`() {
        val upper = table(tableId(name = "Users"), "id")
        val lower = table(tableId(name = "users"), "id")

        val graph = completeGraph(snapshot(lower, upper))

        assertEquals(listOf("Users", "users"), graph.tables.map { it.snapshot.id.name })
        assertEquals(
            listOf(TableQualificationIntent.UNQUALIFIED, TableQualificationIntent.UNQUALIFIED),
            graph.tables.map { it.qualification },
        )
    }

    @Test
    fun `same name across distinct schemas requires schema qualification intent`() {
        val sales = table(tableId(name = "orders", schema = "sales"), "id")
        val archive = table(tableId(name = "orders", schema = "archive"), "id")

        val graph = completeGraph(snapshot(sales, archive))

        assertEquals(
            setOf(TableQualificationIntent.SCHEMA),
            graph.tables.map { it.qualification }.toSet(),
        )
    }

    @Test
    fun `absent and present schema slots remain distinct semantic qualification states`() {
        val unscoped = table(tableId(name = "orders", catalog = "catalog-a", schema = null), "id")
        val scoped = table(tableId(name = "orders", catalog = null, schema = "catalog-a"), "id")

        val graph = completeGraph(snapshot(scoped, unscoped))

        assertEquals(
            setOf(TableQualificationIntent.SCHEMA),
            graph.tables.map { it.qualification }.toSet(),
        )
        assertEquals(2, graph.tables.map { it.snapshot.id }.distinct().size)
    }

    @Test
    fun `same schema and name across catalogs requires catalog schema qualification intent`() {
        val first = table(tableId(name = "orders", catalog = "one", schema = "sales"), "id")
        val second = table(tableId(name = "orders", catalog = "two", schema = "sales"), "id")

        val graph = completeGraph(snapshot(second, first))

        assertEquals(
            setOf(TableQualificationIntent.CATALOG_SCHEMA),
            graph.tables.map { it.qualification }.toSet(),
        )
    }

    @Test
    fun `unique table names remain unqualified`() {
        val users = table(tableId(name = "users"), "id")
        val orders = table(tableId(name = "orders"), "id")

        val graph = completeGraph(snapshot(users, orders))

        assertTrue(graph.tables.all { it.qualification == TableQualificationIntent.UNQUALIFIED })
    }

    @Test
    fun `graph composition preserves source column order exactly`() {
        val id = tableId(name = "source_order")
        val source = table(
            id,
            columns = listOf(
                column("z_first", false),
                column("a_second", true),
                column("m_third", false),
            ),
        )

        val graphTable = completeGraph(snapshot(source)).tables.single()

        assertEquals(source, graphTable.snapshot)
        assertEquals(
            listOf("z_first", "a_second", "m_third"),
            graphTable.snapshot.columns.map { it.id.name },
        )
        assertEquals(listOf(0, 1, 2), graphTable.snapshot.columns.map { it.sourcePosition })
    }

    @Test
    fun `graph preserves exact upstream relation collection including identification composite multiple self and provenance`() {
        val users = tableId(name = "users")
        val orders = tableId(name = "orders")
        val userTable = table(
            users,
            columns = listOf(column("tenant_id", false), column("id", false)),
            primaryKey = primaryKey(users, "tenant_id", "id"),
        )
        val orderTable = table(
            orders,
            columns = listOf(
                column("id", false),
                column("tenant_id", false),
                column("parent_id", false),
                column("created_by", false),
                column("updated_by", true),
                column("manager_id", true),
            ),
            primaryKey = primaryKey(orders, "id"),
            foreignKeys = known(
                frozenListOf(
                    fk(
                        orders,
                        users,
                        "tenant_id" to "tenant_id",
                        "parent_id" to "id",
                        name = presentName("composite_fk"),
                    ),
                    fk(
                        orders,
                        users,
                        "created_by" to "id",
                        name = presentName("created_by_fk"),
                    ),
                    fk(
                        orders,
                        users,
                        "updated_by" to "id",
                        name = presentName("updated_by_virtual_fk"),
                        provenance = RelationProvenance.VIRTUAL,
                    ),
                    fk(
                        orders,
                        orders,
                        "manager_id" to "id",
                        name = presentName("self_fk"),
                    ),
                )
            ),
        )
        val source = snapshot(userTable, orderTable)
        val upstream = RelationIdentificationCompiler.compile(source)
            as ExportOutcome.Complete<FrozenList<ErdGraphRelation>>
        val graph = completeGraph(source)

        assertEquals(upstream.value, graph.relations)

        val composite = graph.relations.single {
            (it.relation.relation.name as? OptionalValue.Present)?.value == "composite_fk"
        }
        assertEquals(
            listOf("tenant_id", "parent_id"),
            composite.relation.relation.mappings.map { it.child.name },
        )

        val virtual = graph.relations.single {
            (it.relation.relation.name as? OptionalValue.Present)?.value ==
                "updated_by_virtual_fk"
        }
        assertEquals(RelationProvenance.VIRTUAL, virtual.relation.relation.provenance)

        val self = graph.relations.single {
            (it.relation.relation.name as? OptionalValue.Present)?.value == "self_fk"
        }
        assertEquals(self.relation.relation.childTable, self.relation.relation.parentTable)
    }

    @Test
    fun `upstream relation ambiguity remains degraded with no graph payload`() {
        val child = tableId(name = "child")
        val parent = tableId(name = "parent")
        val duplicate = fk(
            child,
            parent,
            "parent_id" to "id",
            name = OptionalValue.Absent,
        )
        val source = snapshot(
            table(
                child,
                columns = listOf(column("parent_id", false)),
                foreignKeys = known(frozenListOf(duplicate, duplicate.copy())),
            ),
            table(parent, "id"),
        )

        val upstream = RelationIdentificationCompiler.compile(source)
        val graph = ErdGraphCompiler.compile(source)

        assertTrue(upstream is ExportOutcome.Degraded)
        assertEquals(upstream, graph)
    }

    @Test
    fun `empty and single table graphs are deterministic and invent no relations`() {
        val empty = completeGraph(snapshot())
        assertEquals(origin, empty.origin)
        assertTrue(empty.tables.isEmpty())
        assertTrue(empty.relations.isEmpty())

        val singleSnapshot = snapshot(table(tableId(name = "only"), "id"))
        val first = completeGraph(singleSnapshot)
        val second = completeGraph(singleSnapshot)

        assertEquals(first, second)
        assertEquals(TableQualificationIntent.UNQUALIFIED, first.tables.single().qualification)
        assertTrue(first.relations.isEmpty())
    }

    @Test
    fun `graph semantic type surface is IntelliJ Mermaid and legacy-generator free`() {
        val semanticClasses = listOf(
            TableQualificationIntent::class.java,
            RelationIdentification::class.java,
            ErdGraphRelation::class.java,
            RelationIdentificationCompiler::class.java,
            ErdGraphTable::class.java,
            ErdGraph::class.java,
            ErdGraphCompiler::class.java,
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

    private fun tableId(
        name: String,
        catalog: String? = null,
        schema: String? = "sales",
    ): TableId = TableId(origin, catalog, schema, name)

    private fun column(name: String, nullable: Boolean) = ColumnSpec(name, known(nullable))

    private fun table(
        id: TableId,
        vararg columnNames: String,
        primaryKey: OptionalValue<PrimaryKeyFact> = OptionalValue.Absent,
        uniqueKeys: Evidence<FrozenList<UniqueKeyFact>> = known(frozenListOf()),
        foreignKeys: Evidence<FrozenList<ForeignKeyFact>> = known(frozenListOf()),
    ): TableSnapshot = table(
        id = id,
        columns = columnNames.map { column(it, false) },
        primaryKey = primaryKey,
        uniqueKeys = uniqueKeys,
        foreignKeys = foreignKeys,
    )

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
        name: OptionalValue<String> = presentName("fk_${child.name}_${parent.name}"),
        provenance: RelationProvenance = RelationProvenance.PHYSICAL,
    ): ForeignKeyFact = ForeignKeyFact(
        childTable = child,
        referencedTable = TableReferenceEvidence(
            origin = known(parent.origin),
            catalog = optional(parent.catalog),
            schema = optional(parent.schema),
            name = known(parent.name),
        ),
        name = name,
        provenance = known(provenance),
        mappings = known(
            FrozenList.copyOf(
                mappings.map { (childColumn, parentColumn) ->
                    ForeignKeyColumnMapping(ColumnId(child, childColumn), parentColumn)
                }
            )
        ),
    )

    private fun optional(value: String?): OptionalValue<String> =
        if (value == null) OptionalValue.Absent else OptionalValue.Present(value)

    private fun presentName(value: String): OptionalValue<String> = OptionalValue.Present(value)

    private fun snapshot(vararg tables: TableSnapshot) =
        SchemaSnapshot(origin, FrozenList.copyOf(tables.asList()))

    private fun completeGraph(snapshot: SchemaSnapshot): ErdGraph {
        val outcome = ErdGraphCompiler.compile(snapshot)
        assertTrue("Expected complete outcome but got $outcome", outcome is ExportOutcome.Complete)
        outcome as ExportOutcome.Complete<ErdGraph>
        return outcome.value
    }

    private fun <T> known(value: T): Evidence<T> = Evidence.Known(value)

    @Suppress("unused")
    private fun unavailable(code: String): Evidence<Nothing> =
        Evidence.Unavailable(CoreDiagnostic(code))
}
