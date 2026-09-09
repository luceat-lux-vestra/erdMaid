package com.algorist.erdmaid.host

import com.algorist.erdmaid.core.CoreDiagnostic
import com.algorist.erdmaid.core.CoreDiagnostics
import com.algorist.erdmaid.core.ExportOutcome
import com.algorist.erdmaid.core.OriginId
import com.algorist.erdmaid.core.SchemaSnapshot
import com.algorist.erdmaid.core.WorkCheckpoint
import com.algorist.erdmaid.renderer.MermaidDocumentOptions
import com.algorist.erdmaid.renderer.MermaidDocumentSerializer
import com.algorist.erdmaid.semantic.ErdGraphCompiler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Plain value proving which datasource model version supplied one immutable export snapshot. */
data class HostFreshnessToken(
    val origin: OriginId,
    val modificationCount: Long,
)

data class CapturedHostExport(
    val snapshot: SchemaSnapshot,
    val freshness: HostFreshnessToken,
) {
    init {
        require(snapshot.origin == freshness.origin) {
            "Snapshot and freshness token must describe the same origin"
        }
    }
}

/**
 * Host orchestration with no IntelliJ dependencies.
 *
 * A publish callback is reachable only from [ExportOutcome.Complete]. This gives the JetBrains
 * shell one structural publication gate instead of relying on every failure branch to remember
 * not to touch the clipboard.
 */
internal object HostExportPipeline {
    suspend fun execute(
        capturedOutcome: ExportOutcome<CapturedHostExport>,
        publish: suspend (String, HostFreshnessToken) -> ExportOutcome<Unit>,
        options: MermaidDocumentOptions = MermaidDocumentOptions(),
    ): ExportOutcome<Unit> {
        val executionContext = currentCoroutineContext()
        return execute(
            capturedOutcome = capturedOutcome,
            publish = publish,
            options = options,
            checkpoint = WorkCheckpoint { executionContext.ensureActive() },
        )
    }

    internal suspend fun execute(
        capturedOutcome: ExportOutcome<CapturedHostExport>,
        publish: suspend (String, HostFreshnessToken) -> ExportOutcome<Unit>,
        options: MermaidDocumentOptions,
        checkpoint: WorkCheckpoint,
    ): ExportOutcome<Unit> {
        val captured = when (val outcome = capturedOutcome) {
            is ExportOutcome.Complete -> outcome.value
            ExportOutcome.NoExport -> return ExportOutcome.NoExport
            is ExportOutcome.Degraded -> return ExportOutcome.Degraded(outcome.diagnostics)
            is ExportOutcome.Unsupported -> return ExportOutcome.Unsupported(outcome.diagnostics)
            is ExportOutcome.Failure -> return ExportOutcome.Failure(outcome.diagnostics)
            ExportOutcome.Cancelled -> return ExportOutcome.Cancelled
        }

        val graph = try {
            when (val outcome = ErdGraphCompiler.compile(captured.snapshot, checkpoint)) {
                is ExportOutcome.Complete -> outcome.value
                ExportOutcome.NoExport -> return ExportOutcome.NoExport
                is ExportOutcome.Degraded -> return ExportOutcome.Degraded(outcome.diagnostics)
                is ExportOutcome.Unsupported -> return ExportOutcome.Unsupported(outcome.diagnostics)
                is ExportOutcome.Failure -> return ExportOutcome.Failure(outcome.diagnostics)
                ExportOutcome.Cancelled -> return ExportOutcome.Cancelled
            }
        } catch (_: CancellationException) {
            return ExportOutcome.Cancelled
        }

        val document = try {
            when (val outcome = MermaidDocumentSerializer.serialize(graph, options, checkpoint)) {
                is ExportOutcome.Complete -> outcome.value
                ExportOutcome.NoExport -> return ExportOutcome.NoExport
                is ExportOutcome.Degraded -> return ExportOutcome.Degraded(outcome.diagnostics)
                is ExportOutcome.Unsupported -> return ExportOutcome.Unsupported(outcome.diagnostics)
                is ExportOutcome.Failure -> return ExportOutcome.Failure(outcome.diagnostics)
                ExportOutcome.Cancelled -> return ExportOutcome.Cancelled
            }
        } catch (_: CancellationException) {
            return ExportOutcome.Cancelled
        }

        try {
            checkpoint.check()
        } catch (_: CancellationException) {
            return ExportOutcome.Cancelled
        }

        if (document.isEmpty()) {
            return failure("host-empty-complete-document")
        }

        return try {
            publish(document, captured.freshness)
        } catch (_: CancellationException) {
            ExportOutcome.Cancelled
        } catch (failure: RuntimeException) {
            failure("host-publication-failed", failure.javaClass.name)
        } catch (failure: LinkageError) {
            failure("host-publication-failed", failure.javaClass.name)
        }
    }

    private fun failure(code: String, detail: String? = null): ExportOutcome.Failure =
        ExportOutcome.Failure(CoreDiagnostics.of(CoreDiagnostic(code, detail)))
}

/** Final non-suspending gate used on EDT after a freshness check. */
internal object HostPublicationGate {
    fun publish(
        document: String,
        fresh: Boolean,
        writeClipboard: (String) -> Unit,
    ): ExportOutcome<Unit> {
        if (!fresh) {
            return ExportOutcome.Degraded(
                CoreDiagnostics.of(CoreDiagnostic("publication-stale-snapshot"))
            )
        }
        if (document.isEmpty()) {
            return ExportOutcome.Failure(
                CoreDiagnostics.of(CoreDiagnostic("publication-empty-document"))
            )
        }
        return try {
            writeClipboard(document)
            ExportOutcome.Complete(Unit)
        } catch (_: CancellationException) {
            ExportOutcome.Cancelled
        } catch (failure: RuntimeException) {
            ExportOutcome.Failure(
                CoreDiagnostics.of(
                    CoreDiagnostic("publication-write-failed", failure.javaClass.name)
                )
            )
        } catch (failure: LinkageError) {
            ExportOutcome.Failure(
                CoreDiagnostics.of(
                    CoreDiagnostic("publication-write-failed", failure.javaClass.name)
                )
            )
        }
    }
}
