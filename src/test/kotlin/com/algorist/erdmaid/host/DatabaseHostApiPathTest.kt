package com.algorist.erdmaid.host

import com.algorist.erdmaid.core.ExportOutcome
import com.intellij.openapi.actionSystem.DataContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

class DatabaseHostApiPathTest {
    @Test
    fun `records exact maintained IDEA home for host API inventory`() {
        val ideHome = Path.of(
            System.getProperty("idea.home.path")
                ?: error("idea.home.path is not configured for the IntelliJ Platform test runtime")
        ).toAbsolutePath().normalize()

        val productInfo = sequenceOf(
            ideHome.resolve("product-info.json"),
            ideHome.resolve("Resources/product-info.json"),
        ).firstOrNull(Files::isRegularFile)
            ?: error("product-info.json not found under IDE home: $ideHome")

        val productInfoText = Files.readString(productInfo)

        fun productInfoValue(name: String): String =
            Regex("\\\"$name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .find(productInfoText)
                ?.groupValues
                ?.get(1)
                ?: error("Missing '$name' in $productInfo")

        val product = productInfoValue("name")
        val version = productInfoValue("version")
        val productCode = productInfoValue("productCode")
        val buildNumber = productInfoValue("buildNumber")
        val build = "$productCode-$buildNumber"

        assertEquals("IntelliJ IDEA", product)
        assertEquals("2026.2.0.1", version)
        assertEquals("IU-262.8665.337", build)

        val databaseToolsLib = ideHome.resolve("plugins/DatabaseTools/lib")
        assertTrue(
            "DatabaseTools lib directory not found: $databaseToolsLib",
            Files.isDirectory(databaseToolsLib),
        )

        val databasePluginJar = Files.list(databaseToolsLib).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                .filter { jar ->
                    ZipFile(jar.toFile()).use { zip ->
                        val entry = zip.getEntry("META-INF/plugin.xml") ?: return@use false
                        zip.getInputStream(entry).bufferedReader().use { reader ->
                            reader.readText().contains("<id>com.intellij.database</id>")
                        }
                    }
                }
                .findFirst()
                .orElseThrow {
                    IllegalStateException(
                        "Bundled com.intellij.database plugin descriptor not found under $databaseToolsLib"
                    )
                }
        }

        val databasePluginXml = ZipFile(databasePluginJar.toFile()).use { zip ->
            val entry = zip.getEntry("META-INF/plugin.xml")
                ?: error("META-INF/plugin.xml not found in $databasePluginJar")
            zip.getInputStream(entry).bufferedReader().use { it.readText() }
        }

        fun pluginXmlValue(name: String): String =
            Regex("<$name>\\s*([^<]+?)\\s*</$name>")
                .find(databasePluginXml)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?: error("Missing <$name> in $databasePluginJar")

        val databasePluginId = pluginXmlValue("id")
        val databasePluginVersion = pluginXmlValue("version")

        assertEquals("com.intellij.database", databasePluginId)
        assertEquals("262.8665.337", databasePluginVersion)

        val reports = Path.of("build/reports/hostApiEvidence/idea")
        Files.createDirectories(reports)
        Files.writeString(reports.resolve("idea-home.txt"), ideHome.toString() + "\n")
        Files.writeString(
            reports.resolve("target-evidence.txt"),
            "product=$product\n" +
                "version=$version\n" +
                "build=$build\n" +
                "databasePlugin=$databasePluginId\n" +
                "databasePluginVersion=$databasePluginVersion\n"
        )
    }

    @Test
    fun `maintained IDEA runtime can invoke quarantined selection symbol`() {
        val outcome = DatabaseToolsCompatibility.expandedSelection(DataContext.EMPTY_CONTEXT)

        assertTrue(outcome is ExportOutcome.Complete)
        assertTrue((outcome as ExportOutcome.Complete).value.isEmpty())
    }
}
