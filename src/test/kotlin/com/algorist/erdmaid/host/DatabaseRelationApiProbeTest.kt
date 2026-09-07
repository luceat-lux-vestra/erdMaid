package com.algorist.erdmaid.host

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.extensions.PluginId
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Temporary #80 probe. This branch is rewritten before a reviewable candidate is opened. */
class DatabaseRelationApiProbeTest {

    @Test
    fun dumpMaintainedDatabaseRelationSurface() {
        val loader = javaClass.classLoader
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId("com.intellij.database"))
            ?: error("Bundled com.intellij.database plugin descriptor not found")
        val pluginRoot = descriptor.pluginPath.toAbsolutePath().normalize()
        val jars = Files.walk(pluginRoot).use { stream ->
            stream.filter(Files::isRegularFile)
                .filter { it.fileName.toString().endsWith(".jar") }
                .sorted()
                .toList()
        }

        val classesByJar = linkedMapOf<Path, List<String>>()
        val candidatePattern = Regex(
            "(ForeignKey|Relation|Virtual|Reference|Constraint|Unique|Nullab|PrimaryKey|Index)",
            RegexOption.IGNORE_CASE,
        )
        for (jar in jars) {
            val names = ZipFile(jar.toFile()).use { zip ->
                zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("com/intellij/database/") }
                    .filter { it.endsWith(".class") }
                    .filterNot { it.endsWith("module-info.class") }
                    .map { it.removeSuffix(".class").replace('/', '.') }
                    .filter(candidatePattern::containsMatchIn)
                    .distinct()
                    .sorted()
                    .toList()
            }
            if (names.isNotEmpty()) classesByJar[jar] = names
        }

        val anchors = listOf(
            "com.intellij.database.model.ModelRelationManager",
            "com.intellij.database.model.DasForeignKey",
            "com.intellij.database.model.DasConstraint",
            "com.intellij.database.model.DasColumn",
            "com.intellij.database.model.DasColumn$Attribute",
            "com.intellij.database.model.DasTable",
        )

        val promisingNamePattern = Regex(
            "(Virtual.*(ForeignKey|Relation)|(ForeignKey|Relation).*Virtual|RelationProvider|ForeignKeyProvider|RelationManager|ForeignKeyManager|ReferenceProvider)",
            RegexOption.IGNORE_CASE,
        )
        val promising = classesByJar.values.flatten()
            .filter(promisingNamePattern::containsMatchIn)
            .distinct()
            .sorted()

        val report = buildString {
            val app = ApplicationInfo.getInstance()
            appendLine("PRODUCT=${app.versionName}")
            appendLine("VERSION=${app.fullVersion}")
            appendLine("BUILD=${app.build.asString()}")
            appendLine("PLUGIN_ID=${descriptor.pluginId.idString}")
            appendLine("PLUGIN_VERSION=${descriptor.version}")
            appendLine("PLUGIN_ROOT=$pluginRoot")
            appendLine("PLUGIN_JARS=${jars.size}")
            jars.forEach { appendLine("JAR=$it") }

            for (name in anchors) {
                appendClassSurface(name, loader)
            }

            appendLine()
            appendLine("=== DATABASETOOLS CANDIDATE CLASSES ===")
            var total = 0
            for ((jar, names) in classesByJar) {
                appendLine("--- JAR $jar (${names.size}) ---")
                for (name in names) {
                    appendLine(name)
                    total++
                }
            }
            appendLine("CANDIDATE_TOTAL=$total")

            appendLine()
            appendLine("=== PROMISING CLASS SURFACES (${promising.size}) ===")
            for (name in promising.take(120)) {
                appendClassSurface(name, loader)
            }
            if (promising.size > 120) appendLine("PROMISING_TRUNCATED=${promising.size - 120}")

            appendLine()
            appendLine("=== JAVAP ModelRelationManager ===")
            appendJavap(
                className = "com.intellij.database.model.ModelRelationManager",
                jars = jars,
            )
        }

        throw AssertionError("#80 DATABASE API PROBE V2\n$report")
    }

    private fun StringBuilder.appendClassSurface(name: String, loader: ClassLoader) {
        appendLine()
        appendLine("=== CLASS $name ===")
        val clazz = try {
            Class.forName(name, false, loader)
        } catch (error: Throwable) {
            appendLine("LOAD-ERROR=${error.javaClass.name}:${error.message}")
            return
        }
        appendLine("MODIFIERS=${Modifier.toString(clazz.modifiers)}")
        appendLine("INTERFACES=${clazz.interfaces.map { it.name }.sorted().joinToString(",")}")
        appendLine("SUPER=${clazz.superclass?.name ?: "<none>"}")
        appendLine("ANNOTATIONS=${clazz.annotations.map { it.annotationClass.java.name }.sorted().joinToString(",")}")
        val resourceName = "/${name.replace('.', '/')}.class"
        appendLine("RESOURCE=${clazz.getResource(resourceName)}")
        clazz.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }
            .sortedWith(compareBy({ it.name }, { it.toGenericString() }))
            .forEach { appendLine("METHOD=${it.toGenericString()}") }
        clazz.declaredFields
            .filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }
            .sortedBy { it.name }
            .forEach { field ->
                val constant = if (Modifier.isStatic(field.modifiers)) {
                    runCatching { field.get(null) }.getOrNull()?.toString()
                } else null
                appendLine("FIELD=${field.toGenericString()}${constant?.let { " VALUE=$it" } ?: ""}")
            }
    }

    private fun StringBuilder.appendJavap(className: String, jars: List<Path>) {
        val javaHome = Path.of(System.getProperty("java.home"))
        val javap = javaHome.resolve("bin").resolve("javap")
        if (!Files.isExecutable(javap)) {
            appendLine("JAVAP-UNAVAILABLE=$javap")
            return
        }
        val classPath = jars.joinToString(File.pathSeparator)
        val process = ProcessBuilder(
            javap.toString(),
            "-classpath", classPath,
            "-c", "-p", "-s",
            className,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        appendLine("JAVAP_EXIT=$exit")
        appendLine(output)
    }
}
