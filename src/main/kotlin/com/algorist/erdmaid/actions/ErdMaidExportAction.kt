package com.algorist.erdmaid.actions

import com.algorist.erdmaid.generator.MermaidGenerator
import com.intellij.database.psi.DbElement
import com.intellij.database.psi.DbTable
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformDataKeys.PSI_ELEMENT_ARRAY
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import java.awt.datatransfer.StringSelection

class ErdMaidExportAction : DumbAwareAction() {
    override fun update(e: AnActionEvent) {
        val elements = selectedTables(e)
        e.presentation.isEnabledAndVisible = elements.isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val elements = selectedTables(e)
        if (elements.isEmpty()) return

        try {
            val mermaidCode = MermaidGenerator.generate(elements)

            CopyPasteManager.getInstance().setContents(StringSelection(mermaidCode))

            val group = NotificationGroupManager.getInstance()
                .getNotificationGroup("erdMaidNotification")
            group.createNotification(
                "erdMaid",
                "Mermaid ERD copied to clipboard",
                NotificationType.INFORMATION
            ).notify(e.project)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    private fun selectedTables(e: AnActionEvent): List<DbTable> =
        selectedDbElements(e)
            .filterIsInstance<DbTable>()
            .ifEmpty {
                e.getData(PSI_ELEMENT_ARRAY)?.filterIsInstance<DbTable>().orEmpty()
            }

    private fun selectedDbElements(e: AnActionEvent): List<DbElement> =
        runCatching {
            val contextFun = Class.forName("com.intellij.database.view.DatabaseContextFun")
            val method = contextFun.getMethod("getSelectedDbElementsExpandingGroups", DataContext::class.java)
            val selection = method.invoke(null, e.dataContext) as? Iterable<*>
            selection?.filterIsInstance<DbElement>()
        }.getOrNull().orEmpty()
}
