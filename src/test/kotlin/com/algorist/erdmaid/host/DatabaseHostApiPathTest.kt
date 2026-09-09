package com.algorist.erdmaid.host

import com.algorist.erdmaid.core.ExportOutcome
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.extensions.PluginId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DatabaseHostApiPathTest {
    @Test
    fun `records exact maintained IDEA home for host API inventory`() {
        val application = ApplicationInfo.getInstance()
        assertEquals("2026.2.0.1", application.fullVersion)
        assertEquals("IU-262.8665.337", application.build.asString())

        val descriptor = PluginManagerCore.getPlugin(PluginId.getId("com.intellij.database"))
            ?: error("Bundled com.intellij.database plugin descriptor not found")
        val pluginRoot = descriptor.pluginPath.toAbsolutePath().normalize()
        val ideHome = pluginRoot.parent?.parent
            ?: error("Could not derive IDE home from DatabaseTools plugin root: $pluginRoot")

        assertTrue(Files.isDirectory(ideHome.resolve("plugins/DatabaseTools/lib")))
        assertTrue(Files.isRegularFile(ideHome.resolve("product-info.json")))

        val reports = Path.of("build/reports/hostApiEvidence/idea")
        Files.createDirectories(reports)
        Files.writeString(reports.resolve("idea-home.txt"), ideHome.toString() + "\n")
        Files.writeString(
            reports.resolve("target-evidence.txt"),
            "product=${application.versionName}\n" +
                "version=${application.fullVersion}\n" +
                "build=${application.build.asString()}\n" +
                "databasePlugin=${descriptor.pluginId.idString}\n" +
                "databasePluginVersion=${descriptor.version}\n"
        )
    }

    @Test
    fun `maintained IDEA runtime can invoke quarantined selection symbol`() {
        val outcome = DatabaseToolsCompatibility.expandedSelection(DataContext.EMPTY_CONTEXT)

        assertTrue(outcome is ExportOutcome.Complete)
        assertTrue((outcome as ExportOutcome.Complete).value.isEmpty())
    }
}
