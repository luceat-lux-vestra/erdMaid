package com.algorist.erdmaid.host

import com.algorist.erdmaid.core.ColumnId
import com.algorist.erdmaid.core.ColumnSnapshot
import com.algorist.erdmaid.core.CoreDiagnostic
import com.algorist.erdmaid.core.CoreDiagnostics
import com.algorist.erdmaid.core.Evidence
import com.algorist.erdmaid.core.ExportOutcome
import com.algorist.erdmaid.core.FrozenList
import com.algorist.erdmaid.core.OptionalValue
import com.algorist.erdmaid.core.OriginId
import com.algorist.erdmaid.core.RawTypeMetadata
import com.algorist.erdmaid.core.SchemaSnapshot
import com.algorist.erdmaid.core.TableId
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
        assertTrue(published!!.startsWith("erDiagram\n"))
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

    private fun completeSnapshot(): SchemaSnapshot {
        val tableId = TableId(origin, null, null, "orders")
        return SchemaSnapshot(
            origin = origin,
            tables = FrozenList.of(
                TableSnapshot(
                    id = tableId,
                    comment = OptionalValue.Absent,
                    columns = FrozenList.of(
                        ColumnSnapshot(
                            id = ColumnId(tableId, "id"),
                            sourcePosition = 0,
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
                    ),
                    primaryKey = OptionalValue.Absent,
                    uniqueKeys = Evidence.Known(FrozenList.copyOf(emptyList())),
                    foreignKeys = Evidence.Known(FrozenList.copyOf(emptyList())),
                )
            ),
        )
    }
}
