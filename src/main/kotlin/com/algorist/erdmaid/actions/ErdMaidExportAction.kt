package com.algorist.erdmaid.actions

import com.algorist.erdmaid.generator.MermaidGenerator
import com.intellij.database.model.DasTable
import com.intellij.database.view.DatabaseView
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import java.awt.datatransfer.StringSelection

class ErdMaidExportAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val presentation = e.presentation
        val project = e.project ?: return

        val databaseView = DatabaseView.getInstance(project)
        val selection = databaseView.selectionSet
        val tables = selection.filterIsInstance<DasTable>()

        presentation.isEnabledAndVisible = tables.isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val databaseView = DatabaseView.getInstance(project)
        val selection = databaseView.selectionSet
        val tables = selection.filterIsInstance<DasTable>()

        if (tables.isEmpty()) return

        val mermaid = MermaidGenerator.generate(tables.toList())

        CopyPasteManager.getInstance().setContents(StringSelection(mermaid))

        Notifications.Bus.notify(
            Notification(
                "erdMaidNotification",
                "Mermaid ERD copied to clipboard",
                "${tables.size} table(s) exported",
                NotificationType.INFORMATION
            ),
            project
        )
    }
}
