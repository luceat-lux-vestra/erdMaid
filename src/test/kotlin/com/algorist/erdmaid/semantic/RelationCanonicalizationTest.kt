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
import com.algorist.erdmaid.core.RawTypeMetadata
import com.algorist.erdmaid.core.RelationProvenance
import com.algorist.erdmaid.core.SchemaSnapshot
import com.algorist.erdmaid.core.TableId
import com.algorist.erdmaid.core.TableReferenceEvidence
import com.algorist.erdmaid.core.TableSnapshot
import com.algorist.erdmaid.core.frozenListOf
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationCanonicalizationTest {
    private val origin = OriginId("ds-a")

    @Test
    fun `table and provider iteration order produce exactly equal canonical output`() {
        val audit = tableId("audit")
        val orders = tableId("orders")
        val users = tableId("users")
        val created = fk(orders, users, "fk_created", "created_by" to "id")
        val updated = fk(orders, users, "fk_updated", "updated_by" to "id")
        val auditOrder = fk(audit, orders, "fk_audit_order", "order_id" to "id")

        val first = snapshot(
            table(users, "id"),
            table(orders, "id", "created_by", "updated_by", foreignKeys = arrayOf(updated, created)),
            table(audit, "id", "order_id", foreignKeys = arrayOf(auditOrder)),
        )
        val second = snapshot(
            table(audit, "id", "order_id", foreignKeys = arrayOf(auditOrder)),
            table(orders, "id", "created_by", "updated_by", foreignKeys = arrayOf(created, updated)),
            table(users, "id"),
        )
        val expected = complete(
            semantic(audit, orders, "fk_audit_order", "order_id" to "id"),
            semantic(orders, users, "fk_created", "created_by" to "id"),
            semantic(orders, users, "fk_updated", "updated_by" to "id"),
        )

        assertEquals(expected, RelationSemanticCompiler.compile(first))
        assertEquals(expected, RelationSemanticCompiler.compile(second))
    }

    @Test
    fun `exact duplicate named discovery collapses once`() {
        val orders = tableId("orders")
        val users = tableId("users")
        val relation = fk(orders, users, "fk_orders_users", "user_id" to "id")
        val snapshot = snapshot(
            table(orders, "id", "user_id", foreignKeys = arrayOf(relation, relation)),
            table(users, "id"),
        )

        assertEquals(
            complete(semantic(orders, users, "fk_orders_users", "user_id" to "id")),
            RelationSemanticCompiler.compile(snapshot),
        )
    }

    @Test
    fun `provenance mappings and authoritative names each preserve distinct relations`() {
        val orders = tableId("orders")
        val users = tableId("users")
        val relations = arrayOf(
            fk(orders, users, "same", "user_id" to "id", provenance = RelationProvenance.VIRTUAL),
            fk(orders, users, "same", "updated_by" to "id"),
            fk(orders, users, "z_name", "user_id" to "id"),
            fk(orders, users, "a_name", "user_id" to "id"),
            fk(orders, users, "same", "user_id" to "id"),
        )
        val snapshot = snapshot(
            table(orders, "id", "user_id", "updated_by", foreignKeys = relations),
            table(users, "id"),
        )

        assertEquals(
            complete(
                semantic(orders, users, "same", "updated_by" to "id"),
                semantic(orders, users, "a_name", "user_id" to "id"),
                semantic(orders, users, "same", "user_id" to "id"),
                semantic(orders, users, "z_name", "user_id" to "id"),
                semantic(
                    orders,
                    users,
                    "same",
                    "user_id" to "id",
                    provenance = RelationProvenance.VIRTUAL,
                ),
            ),
            RelationSemanticCompiler.compile(snapshot),
        )
    }

    @Test
    fun `absent duplicate identity degrades instead of guessing`() {
        val orders = tableId("orders")
        val users = tableId("users")
        val relation = fk(orders, users, OptionalValue.Absent, "user_id" to "id")
        val snapshot = snapshot(
            table(orders, "id", "user_id", foreignKeys = arrayOf(relation, relation.copy())),
            table(users, "id"),
        )

        assertDegraded(
            RelationSemanticCompiler.compile(snapshot),
            "relation-identity-ambiguous",
            "name-states=absent",
        )
    }

    @Test
    fun `unavailable duplicate identity is not collapsed into absent`() {
        val orders = tableId("orders")
        val users = tableId("users")
        val name = OptionalValue.Unavailable(CoreDiagnostic("relation-name-read-failed"))
        val relation = fk(orders, users, name, "user_id" to "id")
        val snapshot = snapshot(
            table(orders, "id", "user_id", foreignKeys = arrayOf(relation, relation.copy())),
            table(users, "id"),
        )

        assertDegraded(
            RelationSemanticCompiler.compile(snapshot),
            "relation-identity-ambiguous",
            "name-states=unavailable",
        )
    }

    @Test
    fun `mixed present and absent identity evidence degrades`() {
        val orders = tableId("orders")
        val users = tableId("users")
        val named = fk(orders, users, "fk_orders_users", "user_id" to "id")
        val unnamed = named.copy(name = OptionalValue.Absent)
        val snapshot = snapshot(
            table(orders, "id", "user_id", foreignKeys = arrayOf(named, unnamed)),
            table(users, "id"),
        )

        assertDegraded(
            RelationSemanticCompiler.compile(snapshot),
            "relation-identity-ambiguous",
            "name-states=absent,present",
        )
    }

    @Test
    fun `composite mapping order remains source faithful after relation sorting`() {
        val lines = tableId("order_lines")
        val orders = tableId("orders")
        val relation = fk(
            lines,
            orders,
            "fk_lines_orders",
            "z_tenant" to "z_tenant",
            "a_order" to "a_id",
        )
        val snapshot = snapshot(
            table(lines, "z_tenant", "a_order", foreignKeys = arrayOf(relation)),
            table(orders, "z_tenant", "a_id"),
        )

        assertEquals(
            complete(
                semantic(
                    lines,
                    orders,
                    "fk_lines_orders",
                    "z_tenant" to "z_tenant",
                    "a_order" to "a_id",
                )
            ),
            RelationSemanticCompiler.compile(snapshot),
        )
    }

    @Test
    fun `case whitespace and default locale cannot change relation identity or order`() {
        val orders = tableId("orders", schema = "Sales")
        val users = tableId("users", schema = "Sales")
        val upper = fk(orders, users, "I User", "user_id" to "id")
        val lower = fk(orders, users, "i user ", "user_id" to "id")
        val dotted = fk(orders, users, "İ user", "user_id" to "id")
        val snapshot = snapshot(
            table(orders, "id", "user_id", foreignKeys = arrayOf(dotted, lower, upper)),
            table(users, "id"),
        )
        val originalLocale = Locale.getDefault()

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val turkish = RelationSemanticCompiler.compile(snapshot)
            Locale.setDefault(Locale.US)
            val us = RelationSemanticCompiler.compile(snapshot)

            assertEquals(us, turkish)
            assertEquals(
                complete(
                    semantic(orders, users, "I User", "user_id" to "id"),
                    semantic(orders, users, "i user ", "user_id" to "id"),
                    semantic(orders, users, "İ user", "user_id" to "id"),
                ),
                turkish,
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `single unavailable name remains complete without an identity collision`() {
        val orders = tableId("orders")
        val users = tableId("users")
        val name = OptionalValue.Unavailable(CoreDiagnostic("relation-name-not-exposed"))
        val relation = fk(orders, users, name, "user_id" to "id")
        val snapshot = snapshot(
            table(orders, "id", "user_id", foreignKeys = arrayOf(relation)),
            table(users, "id"),
        )

        assertEquals(
            complete(semantic(orders, users, name, "user_id" to "id")),
            RelationSemanticCompiler.compile(snapshot),
        )
    }

    @Test
    fun `canonicalization type surface remains IntelliJ Mermaid and legacy-generator free`() {
        val referencedTypes = RelationSemanticCompiler::class.java.declaredMethods.flatMap { method ->
            listOf(method.returnType) + method.parameterTypes.asList()
        }

        assertTrue(referencedTypes.none { it.name.startsWith("com.intellij.") })
        assertTrue(referencedTypes.none { it.name.contains("mermaid", ignoreCase = true) })
        assertTrue(referencedTypes.none { it.name.startsWith("com.algorist.erdmaid.generator.") })
    }

    private fun tableId(name: String, schema: String = "sales") =
        TableId(origin, catalog = null, schema = schema, name = name)

    private fun snapshot(vararg tables: TableSnapshot) =
        SchemaSnapshot(origin, FrozenList.copyOf(tables.asList()))

    private fun table(
        id: TableId,
        vararg columnNames: String,
        foreignKeys: Array<ForeignKeyFact> = emptyArray(),
    ) = TableSnapshot(
        id = id,
        comment = OptionalValue.Absent,
        columns = FrozenList.copyOf(
            columnNames.mapIndexed { index, name ->
                ColumnSnapshot(
                    ColumnId(id, name),
                    index,
                    Evidence.Known(RawTypeMetadata("type")),
                    Evidence.Known(false),
                    OptionalValue.Absent,
                )
            }
        ),
        primaryKey = OptionalValue.Absent,
        uniqueKeys = Evidence.Known(frozenListOf()),
        foreignKeys = Evidence.Known(FrozenList.copyOf(foreignKeys.asList())),
    )

    private fun fk(
        child: TableId,
        parent: TableId,
        name: String,
        vararg mappings: Pair<String, String>,
        provenance: RelationProvenance = RelationProvenance.PHYSICAL,
    ) = fk(child, parent, OptionalValue.Present(name), *mappings, provenance = provenance)

    private fun fk(
        child: TableId,
        parent: TableId,
        name: OptionalValue<String>,
        vararg mappings: Pair<String, String>,
        provenance: RelationProvenance = RelationProvenance.PHYSICAL,
    ) = ForeignKeyFact(
        child,
        exactReference(parent),
        name,
        Evidence.Known(provenance),
        Evidence.Known(
            FrozenList.copyOf(
                mappings.map { (childColumn, parentColumn) ->
                    ForeignKeyColumnMapping(ColumnId(child, childColumn), parentColumn)
                }
            )
        ),
    )

    private fun exactReference(table: TableId) = TableReferenceEvidence(
        Evidence.Known(table.origin),
        table.catalog?.let { OptionalValue.Present(it) } ?: OptionalValue.Absent,
        table.schema?.let { OptionalValue.Present(it) } ?: OptionalValue.Absent,
        Evidence.Known(table.name),
    )

    private fun semantic(
        child: TableId,
        parent: TableId,
        name: String,
        vararg mappings: Pair<String, String>,
        provenance: RelationProvenance = RelationProvenance.PHYSICAL,
    ) = semantic(child, parent, OptionalValue.Present(name), *mappings, provenance = provenance)

    private fun semantic(
        child: TableId,
        parent: TableId,
        name: OptionalValue<String>,
        vararg mappings: Pair<String, String>,
        provenance: RelationProvenance = RelationProvenance.PHYSICAL,
    ) = SemanticRelation(
        child,
        parent,
        name,
        provenance,
        FrozenList.copyOf(
            mappings.map { (childColumn, parentColumn) ->
                ResolvedForeignKeyColumnMapping(
                    ColumnId(child, childColumn),
                    ColumnId(parent, parentColumn),
                )
            }
        ),
    )

    private fun complete(vararg relations: SemanticRelation) =
        ExportOutcome.Complete(FrozenList.copyOf(relations.asList()))

    private fun assertDegraded(
        outcome: ExportOutcome<FrozenList<SemanticRelation>>,
        code: String,
        detail: String,
    ) {
        assertTrue(outcome is ExportOutcome.Degraded)
        outcome as ExportOutcome.Degraded
        assertEquals(listOf(CoreDiagnostic(code, detail)), outcome.diagnostics.values)
    }
}
