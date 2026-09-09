package com.algorist.erdmaid.host

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
import com.algorist.erdmaid.core.WorkCheckpoint
import com.algorist.erdmaid.renderer.MermaidDocumentOptions
import com.algorist.erdmaid.renderer.MermaidDocumentSerializer
import com.algorist.erdmaid.semantic.ErdGraph
import com.algorist.erdmaid.semantic.ErdGraphCompiler
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executable scale/lifecycle proof for product-baseline scenarios delegated by #92 to #93. */
class HostLargeWorkloadProofTest {

    @Test
    fun `1000 x 40 and 3000 relations are deterministic with separate scale measurements`() = runBlocking {
        val fixture = measured(::largeSnapshot)
        val snapshot = fixture.value
        assertEquals(TABLE_COUNT, snapshot.tables.size)
        assertEquals(TABLE_COUNT * COLUMNS_PER_TABLE, snapshot.tables.sumOf { it.columns.size })

        val semantic = measured { ErdGraphCompiler.compile(snapshot) }
        assertTrue(semantic.value is ExportOutcome.Complete)
        val graph = (semantic.value as ExportOutcome.Complete<ErdGraph>).value
        assertEquals(TABLE_COUNT, graph.tables.size)
        assertEquals(RELATION_COUNT, graph.relations.size)

        val rendered = measured { MermaidDocumentSerializer.serialize(graph) }
        assertTrue(rendered.value is ExportOutcome.Complete)
        val document = (rendered.value as ExportOutcome.Complete<String>).value
        assertTrue(document.isNotEmpty())

        val repeated = measured { MermaidDocumentSerializer.serialize(graph) }
        assertTrue(repeated.value is ExportOutcome.Complete)
        assertEquals(document, (repeated.value as ExportOutcome.Complete<String>).value)

        var published: String? = null
        var publicationCalls = 0
        val pipeline = measuredSuspend {
            HostExportPipeline.execute(
                capturedOutcome = ExportOutcome.Complete(
                    CapturedHostExport(snapshot, HostFreshnessToken(ORIGIN, 1L))
                ),
                publish = { actualDocument, _ ->
                    publicationCalls++
                    published = actualDocument
                    ExportOutcome.Complete(Unit)
                },
            )
        }
        assertTrue(pipeline.value is ExportOutcome.Complete)
        assertEquals(1, publicationCalls)
        assertEquals(document, published)

        writeEvidence(
            "large-selection.txt",
            listOf(
                "tables=$TABLE_COUNT",
                "columns_per_table=$COLUMNS_PER_TABLE",
                "columns=${TABLE_COUNT * COLUMNS_PER_TABLE}",
                "relations=$RELATION_COUNT",
                "fixture_ms=${fixture.elapsedNanos / NANOS_PER_MILLI}",
                "semantic_ms=${semantic.elapsedNanos / NANOS_PER_MILLI}",
                "render_ms=${rendered.elapsedNanos / NANOS_PER_MILLI}",
                "repeat_render_ms=${repeated.elapsedNanos / NANOS_PER_MILLI}",
                "pipeline_ms=${pipeline.elapsedNanos / NANOS_PER_MILLI}",
                "output_utf8_bytes=${document.toByteArray(Charsets.UTF_8).size}",
                "byte_identical_repeat=true",
                "pipeline_publication_matches_direct_render=true",
            ),
        )
    }

    @Test
    fun `large workload cancellation is observed during pure work and never publishes`() = runBlocking {
        val snapshot = largeSnapshot()
        val cancellationJob = Job()
        var checkpoints = 0
        val checkpoint = WorkCheckpoint {
            checkpoints++
            if (checkpoints == CANCEL_AT_CHECKPOINT) {
                cancellationJob.cancel()
            }
            cancellationJob.ensureActive()
        }
        var publicationCalls = 0

        val cancelled = measuredSuspend {
            HostExportPipeline.execute(
                capturedOutcome = ExportOutcome.Complete(
                    CapturedHostExport(snapshot, HostFreshnessToken(ORIGIN, 2L))
                ),
                publish = { _, _ ->
                    publicationCalls++
                    ExportOutcome.Complete(Unit)
                },
                options = MermaidDocumentOptions(),
                checkpoint = checkpoint,
            )
        }

        assertTrue(cancelled.value is ExportOutcome.Cancelled)
        assertEquals(CANCEL_AT_CHECKPOINT, checkpoints)
        assertEquals(0, publicationCalls)
        val cancellationMillis = cancelled.elapsedNanos / NANOS_PER_MILLI
        assertTrue(
            "Cancellation must be observed within ${CANCELLATION_BOUND_MILLIS}ms but took ${cancellationMillis}ms",
            cancellationMillis < CANCELLATION_BOUND_MILLIS,
        )

        var recoveryPublications = 0
        val recovery = HostExportPipeline.execute(
            capturedOutcome = ExportOutcome.Complete(
                CapturedHostExport(snapshot, HostFreshnessToken(ORIGIN, 3L))
            ),
            publish = { _, _ ->
                recoveryPublications++
                ExportOutcome.Complete(Unit)
            },
        )
        assertTrue(recovery is ExportOutcome.Complete)
        assertEquals(1, recoveryPublications)

        writeEvidence(
            "large-selection-cancelled.txt",
            listOf(
                "tables=$TABLE_COUNT",
                "columns_per_table=$COLUMNS_PER_TABLE",
                "columns=${TABLE_COUNT * COLUMNS_PER_TABLE}",
                "relations=$RELATION_COUNT",
                "cancel_at_checkpoint=$CANCEL_AT_CHECKPOINT",
                "observed_checkpoints=$checkpoints",
                "cancellation_ms=$cancellationMillis",
                "cancellation_bound_ms=$CANCELLATION_BOUND_MILLIS",
                "publication_calls_after_cancel=$publicationCalls",
                "recovery_publication_calls=$recoveryPublications",
            ),
        )
    }

    @Test
    fun `concurrent exports keep invocation state isolated`() = runBlocking {
        val leftOrigin = OriginId("concurrent-left")
        val rightOrigin = OriginId("concurrent-right")
        val leftToken = HostFreshnessToken(leftOrigin, 11L)
        val rightToken = HostFreshnessToken(rightOrigin, 22L)
        var leftPublishedToken: HostFreshnessToken? = null
        var rightPublishedToken: HostFreshnessToken? = null
        var leftDocument: String? = null
        var rightDocument: String? = null

        val results = listOf(
            async(Dispatchers.Default) {
                HostExportPipeline.execute(
                    capturedOutcome = ExportOutcome.Complete(
                        CapturedHostExport(singleTableSnapshot(leftOrigin, "left_table"), leftToken)
                    ),
                    publish = { document, token ->
                        leftDocument = document
                        leftPublishedToken = token
                        ExportOutcome.Complete(Unit)
                    },
                )
            },
            async(Dispatchers.Default) {
                HostExportPipeline.execute(
                    capturedOutcome = ExportOutcome.Complete(
                        CapturedHostExport(singleTableSnapshot(rightOrigin, "right_table"), rightToken)
                    ),
                    publish = { document, token ->
                        rightDocument = document
                        rightPublishedToken = token
                        ExportOutcome.Complete(Unit)
                    },
                )
            },
        ).awaitAll()

        assertTrue(results.all { it is ExportOutcome.Complete })
        assertEquals(leftToken, leftPublishedToken)
        assertEquals(rightToken, rightPublishedToken)
        assertTrue(leftDocument!!.contains("left_table"))
        assertTrue(rightDocument!!.contains("right_table"))
        assertTrue(!leftDocument!!.contains("right_table"))
        assertTrue(!rightDocument!!.contains("left_table"))
    }

    private fun largeSnapshot(): SchemaSnapshot {
        val tableIds = List(TABLE_COUNT) { index ->
            TableId(
                origin = ORIGIN,
                catalog = null,
                schema = null,
                name = "table_${index.toString().padStart(4, '0')}",
            )
        }
        val tables = ArrayList<TableSnapshot>(TABLE_COUNT)

        for (tableIndex in 0 until TABLE_COUNT) {
            val tableId = tableIds[tableIndex]
            val columns = FrozenList.copyOf(
                List(COLUMNS_PER_TABLE) { columnIndex ->
                    column(
                        tableId = tableId,
                        name = "column_${columnIndex.toString().padStart(2, '0')}",
                        position = columnIndex,
                    )
                }
            )
            val primaryKey = OptionalValue.Present(
                PrimaryKeyFact(
                    name = OptionalValue.Present("pk_${tableId.name}"),
                    columns = FrozenList.of(columns[0].id),
                )
            )
            val foreignKeys = ArrayList<ForeignKeyFact>(RELATIONS_PER_TABLE)
            for (offset in 1..RELATIONS_PER_TABLE) {
                val parent = tableIds[(tableIndex + offset) % TABLE_COUNT]
                foreignKeys += ForeignKeyFact(
                    childTable = tableId,
                    referencedTable = TableReferenceEvidence(
                        origin = Evidence.Known(ORIGIN),
                        catalog = OptionalValue.Absent,
                        schema = OptionalValue.Absent,
                        name = Evidence.Known(parent.name),
                    ),
                    name = OptionalValue.Present("fk_${tableId.name}_${parent.name}"),
                    provenance = Evidence.Known(RelationProvenance.PHYSICAL),
                    mappings = Evidence.Known(
                        FrozenList.of(
                            ForeignKeyColumnMapping(
                                child = columns[offset].id,
                                referencedColumnName = "column_00",
                            )
                        )
                    ),
                )
            }

            tables += TableSnapshot(
                id = tableId,
                comment = OptionalValue.Absent,
                columns = columns,
                primaryKey = primaryKey,
                uniqueKeys = Evidence.Known(FrozenList.copyOf(emptyList())),
                foreignKeys = Evidence.Known(FrozenList.copyOf(foreignKeys)),
            )
        }

        return SchemaSnapshot(
            origin = ORIGIN,
            tables = FrozenList.copyOf(tables),
        )
    }

    private fun singleTableSnapshot(origin: OriginId, name: String): SchemaSnapshot {
        val tableId = TableId(origin, null, null, name)
        return SchemaSnapshot(
            origin = origin,
            tables = FrozenList.of(
                TableSnapshot(
                    id = tableId,
                    comment = OptionalValue.Absent,
                    columns = FrozenList.of(column(tableId, "id", 0)),
                    primaryKey = OptionalValue.Absent,
                    uniqueKeys = Evidence.Known(FrozenList.copyOf(emptyList())),
                    foreignKeys = Evidence.Known(FrozenList.copyOf(emptyList())),
                )
            ),
        )
    }

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

    private fun writeEvidence(fileName: String, lines: List<String>) {
        val directory = Path.of("build", "reports", "proof")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve(fileName), lines.joinToString("\n", postfix = "\n"))
    }

    private inline fun <T> measured(block: () -> T): Measurement<T> {
        val started = System.nanoTime()
        val value = block()
        return Measurement(value, System.nanoTime() - started)
    }

    private suspend fun <T> measuredSuspend(block: suspend () -> T): Measurement<T> {
        val started = System.nanoTime()
        val value = block()
        return Measurement(value, System.nanoTime() - started)
    }

    private data class Measurement<T>(
        val value: T,
        val elapsedNanos: Long,
    )

    companion object {
        private val ORIGIN = OriginId("large-workload")
        private const val TABLE_COUNT = 1_000
        private const val COLUMNS_PER_TABLE = 40
        private const val RELATIONS_PER_TABLE = 3
        private const val RELATION_COUNT = TABLE_COUNT * RELATIONS_PER_TABLE
        private const val CANCEL_AT_CHECKPOINT = 30_000
        private const val CANCELLATION_BOUND_MILLIS = 10_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
