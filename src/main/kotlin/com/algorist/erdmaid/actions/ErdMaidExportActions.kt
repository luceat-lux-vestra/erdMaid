package com.algorist.erdmaid.actions

import com.algorist.erdmaid.core.CoreDiagnostic
import com.algorist.erdmaid.core.ExportOutcome
import com.algorist.erdmaid.host.CapturedHostExport
import com.algorist.erdmaid.host.HostExportPipeline
import com.algorist.erdmaid.host.HostFreshnessToken
import com.algorist.erdmaid.host.HostPublicationGate
import com.algorist.erdmaid.host.JetBrainsDatabaseHost
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.datatransfer.StringSelection
import kotlin.coroutines.coroutineContext

/** Stateless 2026.2 host entry point. All database work starts only after invocation. */
open class ErdMaidExportAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        // This is deliberately selection-agnostic: group expansion is an invocation-time operation
        // and must never run on the frequently-called update path.
        e.presentation.isEnabledAndVisible = project != null && !project.isDisposed
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (project.isDisposed) return

        val scope = e.coroutineScope
        var invocationContext: DataContext? = e.dataContext
        scope.launch {
            val outcome = try {
                withBackgroundProgress(project, "Exporting Mermaid ERD", cancellable = true) {
                    val captured: ExportOutcome<CapturedHostExport> = try {
                        val context = invocationContext
                            ?: return@withBackgroundProgress ExportOutcome.Cancelled
                        JetBrainsDatabaseHost.capture(project, context)
                    } finally {
                        // Do not retain the action DataContext while pure semantic/render work runs.
                        invocationContext = null
                    }

                    HostExportPipeline.execute(
                        capturedOutcome = captured,
                        publish = { document, token -> publishIfFresh(project, document, token) },
                    )
                }
            } catch (_: CancellationException) {
                ExportOutcome.Cancelled
            }

            if (outcome !== ExportOutcome.Cancelled && outcome !== ExportOutcome.NoExport) {
                withContext(Dispatchers.EDT) {
                    if (!project.isDisposed) notifyTerminal(project, outcome)
                }
            }
        }
    }

    private suspend fun publishIfFresh(
        project: Project,
        document: String,
        token: HostFreshnessToken,
    ): ExportOutcome<Unit> = withContext(Dispatchers.EDT) {
        coroutineContext.ensureActive()
        if (project.isDisposed) return@withContext ExportOutcome.Cancelled

        // No suspension occurs between this model-version check and clipboard mutation. On the
        // serialized EDT publication boundary a stale completion therefore cannot pass an old
        // check and publish after a later UI/model callback has already invalidated the snapshot.
        when (val freshness = JetBrainsDatabaseHost.validateFreshness(project, token)) {
            is ExportOutcome.Complete -> HostPublicationGate.publish(
                document = document,
                fresh = freshness.value,
                writeClipboard = { text ->
                    CopyPasteManager.getInstance().setContents(StringSelection(text))
                },
            )
            ExportOutcome.NoExport -> ExportOutcome.NoExport
            is ExportOutcome.Degraded -> ExportOutcome.Degraded(freshness.diagnostics)
            is ExportOutcome.Unsupported -> ExportOutcome.Unsupported(freshness.diagnostics)
            is ExportOutcome.Failure -> ExportOutcome.Failure(freshness.diagnostics)
            ExportOutcome.Cancelled -> ExportOutcome.Cancelled
        }
    }

    private fun notifyTerminal(project: Project, outcome: ExportOutcome<Unit>) {
        when (outcome) {
            is ExportOutcome.Complete -> notify(
                project,
                "Mermaid ERD copied to clipboard",
                NotificationType.INFORMATION,
            )
            ExportOutcome.NoExport -> Unit
            is ExportOutcome.Degraded -> expectedFailure(
                project,
                "Export stopped because required database metadata is unavailable",
                outcome.diagnostics.values.first(),
            )
            is ExportOutcome.Unsupported -> expectedFailure(
                project,
                "The current database selection cannot be exported",
                outcome.diagnostics.values.first(),
            )
            is ExportOutcome.Failure -> expectedFailure(
                project,
                "erdMaid could not read the DatabaseTools context",
                outcome.diagnostics.values.first(),
            )
            ExportOutcome.Cancelled -> Unit
        }
    }

    private fun expectedFailure(
        project: Project,
        message: String,
        diagnostic: CoreDiagnostic,
    ) {
        LOG.warn("erdMaid export stopped: ${diagnostic.code}")
        notify(project, "$message (${diagnostic.code})", NotificationType.WARNING)
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("erdMaidNotification")
            .createNotification("erdMaid", message, type)
            .notify(project)
    }

    companion object {
        private val LOG = Logger.getInstance(ErdMaidExportAction::class.java)
    }
}
