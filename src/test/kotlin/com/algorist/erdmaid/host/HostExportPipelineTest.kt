package com.algorist.erdmaid.host

import com.algorist.erdmaid.core.ColumnId
import com.algorist.erdmaid.core.ColumnSnapshot
import com.algorist.erdmaid.core.CoreDiagnostic
import com.algorist.erdmaid.core.CoreDiagnostics
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostExportPipelineTest {
    private val origin = OriginId("origin-1")
    private val token = HostFreshnessToken(origin, 7L)

    @Test
    fun `complete export reaches publication exactly once`() = runBlocking {
        var calls = 0
        var published: String? = null

        val result = HostExportPipeline.execute(
            capturedOutcome = ExportOutcome.Complete(CapturedHostExport(completeSnapshot(), token)),
            publish = { document, actualToken ->
                calls++
                assertEquals(token, actualToken)
                published = document
                ExportOutcome.Complete(Unit)
            },
        )

        assertTrue(result is ExportOutcome.Complete)
        assertEquals(1, calls)
        assertEquals(
            """erDiagram
    e_v6F726967696E2D31_n_n_v6F7264657273["orders"] {
        t_INT c_id
    }
""",
            published,
        )
    }

    @Test
    fun `ordinary physical relation publishes one exact complete document`() = runBlocking {
        var calls = 0
        var published: String? = null

        val result = HostExportPipeline.execute(
            capturedOutcome = ExportOutcome.Complete(
                CapturedHostExport(relationSnapshot(RelationProvenance.PHYSICAL), token)
            ),
            publish = { document, actualToken ->
                calls++
                assertEquals(token, actualToken)
                published = document
                ExportOutcome.Complete(Unit)
            },
        )

        assertTrue(result is ExportOutcome.Complete)
        assertEquals(1, calls)
        assertEquals(
            """erDiagram
    e_v6F726967696E2D31_n_n_v6F7264657273["orders"] {
        t_INT c_id PK
        t_INT c_user_u005F_id
    }
    e_v6F726967696E2D31_n_n_v7573657273["users"] {
        t_INT c_id PK
    }
    %% FK: physical e_v6F726967696E2D31_n_n_v6F7264657273.user_id -> e_v6F726967696E2D31_n_n_v7573657273.id
    e_v6F726967696E2D31_n_n_v7573657273 ||..o{ e_v6F726967696E2D31_n_n_v6F7264657273 : "physical:fk_orders_users"
""",
            published,
        )
    }

    @Test
    fun `complete multi-schema duplicate names publish one exact qualified document`() = runBlocking {
        val archive = TableId(origin, null, "archive", "orders")
        val sales = TableId(origin, null, "sales", "orders")
        val snapshot = SchemaSnapshot(
            origin = origin,
            tables = FrozenList.of(simpleTable(sales), simpleTable(archive)),
        )
        var calls = 0
        var published: String? = null

        val result = HostExportPipeline.execute(
            capturedOutcome = ExportOutcome.Complete(CapturedHostExport(snapshot, token)),
            publish = { document, actualToken ->
                calls++
                assertEquals(token, actualToken)
                published = document
                ExportOutcome.Complete(Unit)
            },
        )

        assertTrue(result is ExportOutcome.Complete)
        assertEquals(1, calls)
        assertEquals(
            """erDiagram
    e_v6F726967696E2D31_n_v61726368697665_v6F7264657273["archive.orders"] {
        t_INT c_id
    }
    e_v6F726967696E2D31_n_v73616C6573_v6F7264657273["sales.orders"] {
        t_INT c_id
    }
""",
            published,
        )
    }

    @Test
    fun `permuted snapshot table order publishes byte-identical document`() = runBlocking {
        val alpha = simpleTable(TableId(origin, null, null, "alpha"))
        val zeta = simpleTable(TableId(origin, null, null, "zeta"))
        val snapshots = listOf(
            SchemaSnapshot(origin, FrozenList.of(zeta, alpha)),
            SchemaSnapshot(origin, FrozenList.of(alpha, zeta)),
        )
        val published = ArrayList<String>()

        for (snapshot in snapshots) {
            val result = HostExportPipeline.execute(
                capturedOutcome = ExportOutcome.Complete(CapturedHostExport(snapshot, token)),
                publish = { document, _ ->
                    published += document
                    ExportOutcome.Complete(Unit)
                },
            )
            assertTrue(result is ExportOutcome.Complete)
        }

        assertEquals(2, published.size)
        assertEquals(published[0], published[1])
    }

    @Test
    fun `non-complete capture outcomes never reach publication`() = runBlocking {
        val outcomes = listOf<ExportOutcome<CapturedHostExport>>(
            ExportOutcome.NoExport,
            ExportOutcome.Degraded(CoreDiagnostics.of(CoreDiagnostic("degraded"))),
            ExportOutcome.Unsupported(CoreDiagnostics.of(CoreDiagnostic("unsupported"))),
            ExportOutcome.Failure(CoreDiagnostics.of(CoreDiagnostic("failure"))),
            ExportOutcome.Cancelled,
        )

        for (capture in outcomes) {
            var calls = 0
            val result = HostExportPipeline.execute(
                capturedOutcome = capture,
                publish = { _, _ ->
                    calls++
                    ExportOutcome.Complete(Unit)
                },
            )
            assertEquals(0, calls)
            if (capture is ExportOutcome.NoExport) {
                assertTrue(result is ExportOutcome.NoExport)
            }
        }
    }

    @Test
    fun `virtual relation with unavailable cardinality degrades before publication`() = runBlocking {
        var calls = 0

        val result = HostExportPipeline.execute(
            capturedOutcome = ExportOutcome.Complete(
                CapturedHostExport(relationSnapshot(RelationProvenance.VIRTUAL), token)
            ),
            publish = { _, _ ->
                calls++
                ExportOutcome.Complete(Unit)
            },
        )

        assertTrue(result is ExportOutcome.Degraded)
        result as ExportOutcome.Degraded
        assertEquals("mermaid-cardinality-unavailable", result.diagnostics.values.single().code)
        assertEquals(
            "minimum:relation-parents-per-child-minimum-unavailable",
            result.diagnostics.values.single().detail,
        )
        assertEquals(0, calls)
    }

    @Test
    fun `semantic degradation leaves publication unreachable`() = runBlocking {
        val tableId = TableId(origin, null, null, "orders")
        val badSnapshot = SchemaSnapshot(
            origin = origin,
            tables = FrozenList.of(
                simpleTable(
                    tableId,
                    Evidence.Unavailable(CoreDiagnostic("foreign-keys-read-failed")),
                )
            ),
        )
        var calls = 0

        val result = HostExportPipeline.execute(
            capturedOutcome = ExportOutcome.Complete(CapturedHostExport(badSnapshot, token)),
            publish = { _, _ ->
                calls++
                ExportOutcome.Complete(Unit)
            },
        )

        assertTrue(result is ExportOutcome.Degraded)
        result as ExportOutcome.Degraded
        assertEquals("foreign-keys-unavailable", result.diagnostics.values.single().code)
        assertEquals("foreign-keys-read-failed", result.diagnostics.values.single().detail)
        assertEquals(0, calls)
    }

    @Test
    fun `renderer degradation leaves publication unreachable`() = runBlocking {
        val tableId = TableId(origin, null, null, "orders")
        val badSnapshot = SchemaSnapshot(
            origin,
            FrozenList.of(
                TableSnapshot(
                    id = tableId,
                    comment = OptionalValue.Absent,
                    columns = FrozenList.of(
                        ColumnSnapshot(
                            id = ColumnId(tableId, "id"),
                            sourcePosition = 0,
                            rawType = Evidence.Unavailable(CoreDiagnostic("type-read-failed")),
                            nullable = Evidence.Known(false),
                            comment = OptionalValue.Absent,
                        )
                    ),
                    primaryKey = OptionalValue.Absent,
                    uniqueKeys = Evidence.Known(FrozenList.copyOf(emptyList())),
                    foreignKeys = Evidence.Known(FrozenList.copyOf(emptyList())),
                )
            ),
        )
        var calls = 0

        val result = HostExportPipeline.execute(
            capturedOutcome = ExportOutcome.Complete(CapturedHostExport(badSnapshot, token)),
            publish = { _, _ ->
                calls++
                ExportOutcome.Complete(Unit)
            },
        )

        assertTrue(result is ExportOutcome.Degraded)
        assertEquals(0, calls)
    }

    @Test
    fun `publication callback exception becomes typed failure`() = runBlocking {
        val result = HostExportPipeline.execute(
            capturedOutcome = ExportOutcome.Complete(CapturedHostExport(completeSnapshot(), token)),
            publish = { _, _ -> throw IllegalStateException("simulated publication failure") },
        )

        assertTrue(result is ExportOutcome.Failure)
        assertEquals(
            "host-publication-failed",
            (result as ExportOutcome.Failure).diagnostics.values.single().code,
        )
    }

    @Test
    fun `publication cancellation remains cancelled`() = runBlocking {
        val result = HostExportPipeline.execute(
            capturedOutcome = ExportOutcome.Complete(CapturedHostExport(completeSnapshot(), token)),
            publish = { _, _ -> throw CancellationException("cancel") },
        )

        assertTrue(result is ExportOutcome.Cancelled)
    }

    @Test
    fun `stale publication preserves previous clipboard and fresh publication writes once`() {
        var clipboard = "keep-me"
        var writes = 0

        val stale = HostPublicationGate.publish("new", fresh = false) {
            writes++
            clipboard = it
        }
        assertTrue(stale is ExportOutcome.Degraded)
        assertEquals("keep-me", clipboard)
        assertEquals(0, writes)

        val fresh = HostPublicationGate.publish("new", fresh = true) {
            writes++
            clipboard = it
        }
        assertTrue(fresh is ExportOutcome.Complete)
        assertEquals("new", clipboard)
        assertEquals(1, writes)
    }

    @Test
    fun `clipboard exception is typed instead of escaping`() {
        var attempts = 0

        val result = HostPublicationGate.publish("new", fresh = true) {
            attempts++
            throw IllegalStateException("simulated clipboard failure")
        }

        assertTrue(result is ExportOutcome.Failure)
        assertEquals(1, attempts)
        assertEquals(
            "publication-write-failed",
            (result as ExportOutcome.Failure).diagnostics.values.single().code,
        )
    }

    @Test
    fun `empty complete payload cannot mutate clipboard`() {
        var clipboard = "keep-me"
        var writes = 0

        val result = HostPublicationGate.publish("", fresh = true) {
            writes++
            clipboard = it
        }

        assertTrue(result is ExportOutcome.Failure)
        assertEquals("keep-me", clipboard)
        assertEquals(0, writes)
    }

    private fun completeSnapshot(): SchemaSnapshot = SchemaSnapshot(
        origin = origin,
        tables = FrozenList.of(simpleTable(TableId(origin, null, null, "orders"))),
    )

    private fun relationSnapshot(provenance: RelationProvenance): SchemaSnapshot {
        val orders = TableId(origin, null, null, "orders")
        val users = TableId(origin, null, null, "users")
        val ordersId = column(orders, "id", 0)
        val userId = column(orders, "user_id", 1)
        val usersId = column(users, "id", 0)
        val relation = ForeignKeyFact(
            childTable = orders,
            referencedTable = TableReferenceEvidence(
                origin = Evidence.Known(origin),
                catalog = OptionalValue.Absent,
                schema = OptionalValue.Absent,
                name = Evidence.Known("users"),
            ),
            name = OptionalValue.Present(
                if (provenance == RelationProvenance.PHYSICAL) "fk_orders_users" else "ide_orders_users"
            ),
            provenance = Evidence.Known(provenance),
            mappings = Evidence.Known(
                FrozenList.of(ForeignKeyColumnMapping(userId.id, "id"))
            ),
        )
        return SchemaSnapshot(
            origin = origin,
            tables = FrozenList.of(
                table(
                    orders,
                    FrozenList.of(ordersId, userId),
                    primaryKey(ordersId.id),
                    Evidence.Known(FrozenList.of(relation)),
                ),
                table(
                    users,
                    FrozenList.of(usersId),
                    primaryKey(usersId.id),
                    Evidence.Known(FrozenList.copyOf(emptyList())),
                ),
            ),
        )
    }

    private fun simpleTable(
        tableId: TableId,
        foreignKeys: Evidence<FrozenList<ForeignKeyFact>> =
            Evidence.Known(FrozenList.copyOf(emptyList())),
    ): TableSnapshot = table(
        tableId,
        FrozenList.of(column(tableId, "id", 0)),
        OptionalValue.Absent,
        foreignKeys,
    )

    private fun table(
        tableId: TableId,
        columns: FrozenList<ColumnSnapshot>,
        primaryKey: OptionalValue<PrimaryKeyFact>,
        foreignKeys: Evidence<FrozenList<ForeignKeyFact>>,
    ): TableSnapshot = TableSnapshot(
        id = tableId,
        comment = OptionalValue.Absent,
        columns = columns,
        primaryKey = primaryKey,
        uniqueKeys = Evidence.Known(FrozenList.copyOf(emptyList())),
        foreignKeys = foreignKeys,
    )

    private fun column(tableId: TableId, name: String, position: Int): ColumnSnapshot =
        ColumnSnapshot(
            id = ColumnId(tableId, name),
            sourcePosition = position,
            rawType = Evidence.Known(
                RawTypeMetadata(
                    name = "INT",
                    length = OptionalValue.Absent,
                    precision = OptionalValue.Absent,
                    scale = OptionalValue.Absent,
                )
            ),
            nullable = Evidence.Known(false),
            comment = OptionalValue.Absent,
        )

    private fun primaryKey(column: ColumnId): OptionalValue<PrimaryKeyFact> =
        OptionalValue.Present(
            PrimaryKeyFact(
                name = OptionalValue.Absent,
                columns = FrozenList.of(column),
            )
        )
}
