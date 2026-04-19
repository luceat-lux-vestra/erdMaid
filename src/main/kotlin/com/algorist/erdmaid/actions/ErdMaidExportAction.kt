package com.algorist.erdmaid.actions

import com.intellij.ide.actions.CopyAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys.PSI_ELEMENT_ARRAY
import com.intellij.openapi.project.DumbAwareAction
import com.algorist.erdmaid.MermaidGenerator

class ErdMaidExportAction : DumbAwareAction() {
    override fun update(e: AnActionEvent) {
        val elements = e.getData(PSI_ELEMENT_ARRAY)?.filterIsInstance<com.intellij.database.psi.DbTable>() ?: emptyList()
        e.presentation.isEnabledAndVisible = elements.isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val elements = e.getData(PSI_ELEMENT_ARRAY)?.filterIsInstance<com.intellij.database.psi.DbTable>() ?: emptyList()
        if (elements.isEmpty()) return

        try {
            val mermaidCode = MermaidGenerator.generate(elements)
            CopyAction.copyToClipboard(mermaidCode)

            val group = com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("erdMaidNotification")
            group.createNotification(
                "erdMaid", 
                com.intellij.notification.NotificationType.INFORMATION,
                "Mermaid ERD copied to clipboard"
            ).notify(e.project)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }
}
