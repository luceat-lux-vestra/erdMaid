package com.algorist.erdmaid.actions

import com.algorist.erdmaid.generator.MermaidGenerator
import com.algorist.erdmaid.generator.MermaidGenerator.MermaidRenderOptions
import com.intellij.database.psi.DbElement
import com.intellij.database.psi.DbTable
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformDataKeys.PSI_ELEMENT_ARRAY
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import java.awt.datatransfer.StringSelection
import java.lang.reflect.Method

abstract class BaseErdMaidExportAction(
    private val renderOptions: MermaidRenderOptions,
) : DumbAwareAction() {
    override fun update(e: AnActionEvent) {
        val elements = selectedTables(e)
        e.presentation.isEnabledAndVisible = elements.isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val elements = selectedTables(e)
        if (elements.isEmpty()) return

        try {
            val mermaidCode = MermaidGenerator.generate(e.project, elements, renderOptions)

            CopyPasteManager.getInstance().setContents(StringSelection(mermaidCode))

            NotificationGroupManager.getInstance()
                .getNotificationGroup("erdMaidNotification")
                .createNotification(
                    "erdMaid",
                    "Mermaid ERD copied to clipboard",
                    NotificationType.INFORMATION
                ).notify(e.project)
        } catch (ex: Exception) {
            LOG.error("Failed to generate Mermaid ERD", ex)
            NotificationGroupManager.getInstance()
                .getNotificationGroup("erdMaidNotification")
                .createNotification(
                    "erdMaid",
                    "Failed to generate Mermaid ERD: ${ex.message}",
                    NotificationType.ERROR
                ).notify(e.project)
        }
    }

    private fun selectedTables(e: AnActionEvent): List<DbTable> =
        resolveSelectedTables(
            selectedDbElements = selectedDbElements(e),
            psiElements = e.getData(PSI_ELEMENT_ARRAY),
        )

    private fun selectedDbElements(e: AnActionEvent): List<DbElement> =
        runCatching {
            val selection = REFLECT_METHOD?.invoke(null, e.dataContext) as? Iterable<*>
            selection?.filterIsInstance<DbElement>()
        }.getOrNull().orEmpty()

    internal fun resolveSelectedTables(
        selectedDbElements: Iterable<DbElement>?,
        psiElements: Array<out Any>?,
    ): List<DbTable> {
        val dbTables = selectedDbElements?.filterIsInstance<DbTable>().orEmpty()
        if (dbTables.isNotEmpty()) return dbTables

        return psiElements?.filterIsInstance<DbTable>().orEmpty()
    }

    companion object {
        private val LOG = Logger.getInstance(BaseErdMaidExportAction::class.java)

        // Cached once on first use so that the update() hot path avoids repeated
        // Class.forName / getMethod lookups on every UI refresh.
        private val REFLECT_METHOD: Method? by lazy {
            runCatching {
                val cls = Class.forName("com.intellij.database.view.DatabaseContextFun")
                cls.getMethod("getSelectedDbElementsExpandingGroups", DataContext::class.java)
            }.getOrNull()
        }
    }
}

class ErdMaidExportAction : BaseErdMaidExportAction(MermaidRenderOptions())

class ErdMaidExportActionWithColumnReferences :
    BaseErdMaidExportAction(MermaidRenderOptions(includeColumnReferences = true))
