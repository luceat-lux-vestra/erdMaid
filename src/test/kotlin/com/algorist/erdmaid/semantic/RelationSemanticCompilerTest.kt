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
import com.algorist.erdmaid.core.UniqueKeyFact
import com.algorist.erdmaid.core.frozenListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationSemanticCompilerTest {

    private val origin = OriginId("ds-a")

    @Test
    fun `incomplete endpoint never binds to the only same-named selected table`() {
        val orders = tableId("orders", schema = "sales")
        val users = tableId("users", schema = "sales")
        val relation = foreignKey(
            child = orders,
            reference = TableReferenceEvidence(
                origin = Evidence.Known(origin),
                catalog = OptionalValue.Absent,
                schema = OptionalValue.Unavailable(CoreDiagnostic("fk-schema-unavailable")),
                name = Evidence.Known("users"),
            ),
            mappings = mappings(orders, "user_id" to "id"),
        )
        val snapshot = snapshot(
            table(orders, listOf("id", "user_id"), foreignKeys = Evidence.Known(frozenListOf(relation))),
            table(users, listOf("id")),
        )

        val outcome = RelationSemanticCompiler.compile(snapshot)

        assertDegraded(
            outcome,
            code = "relation-endpoint-identity-unavailable",
            detail = "schema:fk-schema-unavailable",
        )
    }

    @Test
    fun `exact selected endpoint resolves the intended same-named table`() {
        val orders = tableId("orders", schema = "sales")
        val salesUsers = tableId("users", schema = "sales")
        val archiveUsers = tableId("users", schema = "archive")
        val relation = foreignKey(
            child = orders,
            reference = exactReference(salesUsers),
            mappings = mappings(orders, "user_id" to "id"),
            name = OptionalValue.Present("fk_orders_users"),
        )
        val snapshot = snapshot(
            table(orders, listOf("id", "user_id"), foreignKeys = Evidence.Known(frozenListOf(relation))),
            table(archiveUsers, listOf("id")),
            table(salesUsers, listOf("id")),
        )

        val outcome = RelationSemanticCompiler.compile(snapshot)

        assertEquals(
            ExportOutcome.Complete(
                frozenListOf(
                    SemanticRelation(
                        childTable = orders,
                        parentTable = salesUsers,
                        name = OptionalValue.Present("fk_orders_users"),
                        provenance = RelationProvenance.PHYSICAL,
                        mappings = frozenListOf(
                            ResolvedForeignKeyColumnMapping(
                                child = ColumnId(orders, "user_id"),
                                parent = ColumnId(salesUsers, "id"),
                            )
                        ),
                    )
                )
            ),
            outcome,
        )
    }

    @Test
    fun `exact endpoint outside selection is omitted without same-name fallback`() {
        val orders = tableId("orders", schema = "sales")
        val salesUsers = tableId("users", schema = "sales")
        val archiveUsers = tableId("users", schema = "archive")
        val relation = foreignKey(
            child = orders,
            reference = exactReference(salesUsers),
            mappings = mappings(orders, "user_id" to "id"),
        )
        val snapshot = snapshot(
            table(orders, listOf("id", "user_id"), foreignKeys = Evidence.Known(frozenListOf(relation))),
            table(archiveUsers, listOf("id")),
        )

        val outcome = RelationSemanticCompiler.compile(snapshot)

        assertEquals(ExportOutcome.Complete(frozenListOf<SemanticRelation>()), outcome)
    }

    @Test
    fun `unresolved endpoint cannot hide behind outside-selection filtering`() {
        val orders = tableId("orders", schema = "sales")
        val relation = foreignKey(
            child = orders,
            reference = TableReferenceEvidence(
                origin = Evidence.Known(origin),
                catalog = OptionalValue.Absent,
                schema = OptionalValue.Unavailable(CoreDiagnostic("fk-schema-read-failed")),
                name = Evidence.Known("users"),
            ),
            mappings = mappings(orders, "user_id" to "id"),
        )
        val snapshot = snapshot(
            table(orders, listOf("id", "user_id"), foreignKeys = Evidence.Known(frozenListOf(relation)))
        )

        val outcome = RelationSemanticCompiler.compile(snapshot)

        assertDegraded(
            outcome,
            code = "relation-endpoint-identity-unavailable",
            detail = "schema:fk-schema-read-failed",
        )
    }

    @Test
    fun `exact cross-origin endpoint is degraded rather than omitted`() {
        val orders = tableId("orders", schema = "sales")
        val otherOrigin = OriginId("ds-b")
        val relation = foreignKey(
            child = orders,
            reference = TableReferenceEvidence(
                origin = Evidence.Known(otherOrigin),
                catalog = OptionalValue.Absent,
                schema = OptionalValue.Present("sales"),
                name = Evidence.Known("users"),
            ),
            mappings = mappings(orders, "user_id" to "id"),
        )
        val snapshot = snapshot(
            table(orders, listOf("id", "user_id"), foreignKeys = Evidence.Known(frozenListOf(relation)))
        )

        val outcome = RelationSemanticCompiler.compile(snapshot)

        assertDegraded(outcome, "relation-cross-origin-target")
    }

    @Test
    fun `composite mappings preserve pairwise source order`() {
        val lines = tableId("order_lines", schema = "sales")
        val orders = tableId("orders", schema = "sales")
        val relation = foreignKey(
            child = lines,
            reference = exactReference(orders),
            mappings = mappings(
                lines,
                "tenant_id" to "tenant_id",
                "order_id" to "id",
            ),
            name = OptionalValue.Present("fk_lines_order"),
        )
        val snapshot = snapshot(
            table(
                lines,
                listOf("tenant_id", "order_id"),
                foreignKeys = Evidence.Known(frozenListOf(relation)),
            ),
            table(orders, listOf("tenant_id", "id")),
        )

        val outcome = RelationSemanticCompiler.compile(snapshot)

        assertEquals(
            ExportOutcome.Complete(
                frozenListOf(
                    SemanticRelation(
                        childTable = lines,
                        parentTable = orders,
                        name = OptionalValue.Present("fk_lines_order"),
                        provenance = RelationProvenance.PHYSICAL,
                        mappings = frozenListOf(
                            ResolvedForeignKeyColumnMapping(
                                ColumnId(lines, "tenant_id"),
                                ColumnId(orders, "tenant_id"),
                            ),
                            ResolvedForeignKeyColumnMapping(
                                ColumnId(lines, "order_id"),
                                ColumnId(orders, "id"),
                            ),
                        ),
                    )
                )
            ),
            outcome,
        )
    }

    @Test
    fun `multiple foreign keys between the same pair remain distinct`() {
        val orders = tableId("orders", schema = "sales")
        val users = tableId("users", schema = "sales")
        val createdBy = foreignKey(
            child = orders,
            reference = exactReference(users),
            mappings = mappings(orders, "created_by" to "id"),
            name = OptionalValue.Present("fk_created_by"),
        )
        val updatedBy = foreignKey(
            child = orders,
            reference = exactReference(users),
            mappings = mappings(orders, "updated_by" to "id"),
            name = OptionalValue.Present("fk_updated_by"),
        )
        val snapshot = snapshot(
            table(
                orders,
                listOf("id", "created_by", "updated_by"),
                foreignKeys = Evidence.Known(frozenListOf(createdBy, updatedBy)),
            ),
            table(users, listOf("id")),
        )

        val outcome = RelationSemanticCompiler.compile(snapshot)

        assertEquals(
            ExportOutcome.Complete(
                frozenListOf(
                    SemanticRelation(
                        orders,
                        users,
                        OptionalValue.Present("fk_created_by"),
                        RelationProvenance.PHYSICAL,
                        frozenListOf(
                            ResolvedForeignKeyColumnMapping(
                                ColumnId(orders, "created_by"),
                                ColumnId(users, "id"),
                            )
                        ),
                    ),
                    SemanticRelation(
                        orders,
                        users,
                        OptionalValue.Present("fk_updated_by"),
                        RelationProvenance.PHYSICAL,
                        frozenListOf(
                            ResolvedForeignKeyColumnMapping(
                                ColumnId(orders, "updated_by"),
                                ColumnId(users, "id"),
                            )
                        ),
                    ),
                )
            ),
            outcome,
        )
    }

    @Test
    fun `self relation remains in the selected semantic set`() {
        val employee = tableId("employee", schema = "org")
        val relation = foreignKey(
            child = employee,
            reference = exactReference(employee),
            mappings = mappings(employee, "manager_id" to "id"),
            name = OptionalValue.Present("fk_employee_manager"),
        )
        val snapshot = snapshot(
            table(
                employee,
                listOf("id", "manager_id"),
                foreignKeys = Evidence.Known(frozenListOf(relation)),
            )
        )

        val outcome = RelationSemanticCompiler.compile(snapshot)

        assertEquals(
            ExportOutcome.Complete(
                frozenListOf(
                    SemanticRelation(
                        employee,
                        employee,
                        OptionalValue.Present("fk_employee_manager"),
                        RelationProvenance.PHYSICAL,
                        frozenListOf(
                            ResolvedForeignKeyColumnMapping(
                                ColumnId(employee, "manager_id"),
                                ColumnId(employee, "id"),
                            )
                        ),
                    )
                )
            ),
            outcome,
        )
    }

    @Test
    fun `selected relation with unavailable mappings is degraded`() {
        val orders = tableId("orders", schema = "sales")
        val users = tableId("users", schema = "sales")
        val relation = foreignKey(
            child = orders,
            reference = exactReference(users),
            mappings = Evidence.Unavailable(CoreDiagnostic("fk-mappings-read-failed")),
        )
        val snapshot = snapshot(
            table(orders, listOf("id", "user_id"), foreignKeys = Evidence.Known(frozenListOf(relation))),
            table(users, listOf("id")),
        )

        val outcome = RelationSemanticCompiler.compile(snapshot)

        assertDegraded(
            outcome,
            code = "relation-mappings-unavailable",
            detail = "fk-mappings-read-failed",
        )
    }

    @Test
    fun `selected relation with unavailable provenance is degraded`() {
        val orders = tableId("orders", schema = "sales")
        val users = tableId("users", schema = "sales")
        val relation = foreignKey(
            child = orders,
            reference = exactReference(users),
            mappings = mappings(orders, "user_id" to "id"),
            provenance = Evidence.Unavailable(CoreDiagnostic("fk-provenance-not-established")),
        )
        val snapshot = snapshot(
            table(orders, listOf("id", "user_id"), foreignKeys = Evidence.Known(frozenListOf(relation))),
            table(users, listOf("id")),
        )

        val outcome = RelationSemanticCompiler.compile(snapshot)

        assertDegraded(
            outcome,
            code = "relation-provenance-unavailable",
            detail = "fk-provenance-not-established",
        )
    }

    @Test
    fun `missing referenced parent column is degraded`() {
        val orders = tableId("orders", schema = "sales")
        val users = tableId("users", schema = "sales")
        val relation = foreignKey(
            child = orders,
            reference = exactReference(users),
            mappings = mappings(orders, "user_id" to "missing_id"),
        )
        val snapshot = snapshot(
            table(orders, listOf("id", "user_id"), foreignKeys = Evidence.Known(frozenListOf(relation))),
            table(users, listOf("id")),
        )

        val outcome = RelationSemanticCompiler.compile(snapshot)

        assertDegraded(outcome, "relation-parent-column-missing")
    }

    @Test
    fun `unavailable foreign key collection is degraded instead of known empty`() {
        val orders = tableId("orders", schema = "sales")
        val snapshot = snapshot(
            table(
                orders,
                listOf("id"),
                foreignKeys = Evidence.Unavailable(CoreDiagnostic("foreign-keys-read-failed")),
            )
        )

        val outcome = RelationSemanticCompiler.compile(snapshot)

        assertDegraded(
            outcome,
            code = "foreign-keys-unavailable",
            detail = "foreign-keys-read-failed",
        )
    }

    @Test
    fun `relation name availability is preserved but does not establish identity`() {
        val orders = tableId("orders", schema = "sales")
        val users = tableId("users", schema = "sales")
        val name = OptionalValue.Unavailable(CoreDiagnostic("relation-name-not-exposed"))
        val relation = foreignKey(
            child = orders,
            reference = exactReference(users),
            mappings = mappings(orders, "user_id" to "id"),
            name = name,
        )
        val snapshot = snapshot(
            table(orders, listOf("id", "user_id"), foreignKeys = Evidence.Known(frozenListOf(relation))),
            table(users, listOf("id")),
        )

        val outcome = RelationSemanticCompiler.compile(snapshot)

        assertEquals(
            ExportOutcome.Complete(
                frozenListOf(
                    SemanticRelation(
                        orders,
                        users,
                        name,
                        RelationProvenance.PHYSICAL,
                        frozenListOf(
                            ResolvedForeignKeyColumnMapping(
                                ColumnId(orders, "user_id"),
                                ColumnId(users, "id"),
                            )
                        ),
                    )
                )
            ),
            outcome,
        )
    }

    @Test
    fun `semantic type surface is IntelliJ Mermaid and legacy-generator free`() {
        val semanticClasses = listOf(
            ResolvedForeignKeyColumnMapping::class.java,
            SemanticRelation::class.java,
            RelationSemanticCompiler::class.java,
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

    private fun tableId(
        name: String,
        schema: String? = null,
        catalog: String? = null,
    ): TableId = TableId(origin, catalog, schema, name)

    private fun table(
        id: TableId,
        columnNames: List<String>,
        foreignKeys: Evidence<FrozenList<ForeignKeyFact>> = Evidence.Known(frozenListOf()),
    ): TableSnapshot = TableSnapshot(
        id = id,
        comment = OptionalValue.Absent,
        columns = FrozenList.copyOf(
            columnNames.mapIndexed { index, name ->
                ColumnSnapshot(
                    id = ColumnId(id, name),
                    sourcePosition = index,
                    rawType = Evidence.Known(RawTypeMetadata("text")),
                    nullable = Evidence.Known(true),
                    comment = OptionalValue.Absent,
                )
            }
        ),
        primaryKey = OptionalValue.Absent,
        uniqueKeys = Evidence.Known(frozenListOf<UniqueKeyFact>()),
        foreignKeys = foreignKeys,
    )

    private fun foreignKey(
        child: TableId,
        reference: TableReferenceEvidence,
        mappings: Evidence<FrozenList<ForeignKeyColumnMapping>>,
        provenance: Evidence<RelationProvenance> = Evidence.Known(RelationProvenance.PHYSICAL),
        name: OptionalValue<String> = OptionalValue.Absent,
    ): ForeignKeyFact = ForeignKeyFact(
        childTable = child,
        referencedTable = reference,
        name = name,
        provenance = provenance,
        mappings = mappings,
    )

    private fun mappings(
        child: TableId,
        vararg pairs: Pair<String, String>,
    ): Evidence<FrozenList<ForeignKeyColumnMapping>> = Evidence.Known(
        FrozenList.copyOf(
            pairs.map { (childName, parentName) ->
                ForeignKeyColumnMapping(
                    child = ColumnId(child, childName),
                    referencedColumnName = parentName,
                )
            }
        )
    )

    private fun exactReference(parent: TableId): TableReferenceEvidence = TableReferenceEvidence(
        origin = Evidence.Known(parent.origin),
        catalog = optionalPart(parent.catalog),
        schema = optionalPart(parent.schema),
        name = Evidence.Known(parent.name),
    )

    private fun optionalPart(value: String?): OptionalValue<String> =
        if (value == null) OptionalValue.Absent else OptionalValue.Present(value)

    private fun snapshot(vararg tables: TableSnapshot): SchemaSnapshot =
        SchemaSnapshot(origin, FrozenList.copyOf(tables.asList()))

    private fun assertDegraded(
        outcome: ExportOutcome<*>,
        code: String,
        detail: String? = null,
    ) {
        assertTrue("Expected degraded outcome but got $outcome", outcome is ExportOutcome.Degraded)
        val diagnostic = (outcome as ExportOutcome.Degraded).diagnostics.values.single()
        assertEquals(code, diagnostic.code)
        if (detail != null) {
            assertEquals(detail, diagnostic.detail)
        }
    }
}
