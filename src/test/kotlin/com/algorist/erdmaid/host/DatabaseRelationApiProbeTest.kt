package com.algorist.erdmaid.host

import com.intellij.openapi.application.ApplicationInfo
import org.junit.Test
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Temporary #80 probe. This branch is rewritten before a reviewable candidate is opened. */
class DatabaseRelationApiProbeTest {

    @Test
    fun dumpMaintainedDatabaseRelationSurface() {
        val loader = javaClass.classLoader
        val report = buildString {
            val app = ApplicationInfo.getInstance()
            appendLine("PRODUCT=${app.versionName}")
            appendLine("VERSION=${app.fullVersion}")
            appendLine("BUILD=${app.build.asString()}")

            val anchors = listOf(
                "com.intellij.database.model.ModelRelationManager",
                "com.intellij.database.model.DasForeignKey",
                "com.intellij.database.model.DasColumn",
                "com.intellij.database.model.DasTable",
            )

            val codeSources = linkedSetOf<Path>()
            for (name in anchors) {
                appendLine()
                appendLine("=== ANCHOR $name ===")
                val clazz = try {
                    Class.forName(name, false, loader)
                } catch (error: Throwable) {
                    appendLine("LOAD-ERROR=${error.javaClass.name}:${error.message}")
                    continue
                }
                appendLine("MODIFIERS=${Modifier.toString(clazz.modifiers)}")
                appendLine("INTERFACES=${clazz.interfaces.map { it.name }.sorted().joinToString(",")}")
                appendLine("SUPER=${clazz.superclass?.name ?: "<none>"}")
                appendLine("ANNOTATIONS=${clazz.annotations.map { it.annotationClass.java.name }.sorted().joinToString(",")}")
                clazz.protectionDomain?.codeSource?.location?.let { location ->
                    appendLine("SOURCE=$location")
                    runCatching { Path.of(location.toURI()) }.getOrNull()?.let(codeSources::add)
                }
                clazz.declaredMethods
                    .filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }
                    .sortedWith(compareBy({ it.name }, { it.toGenericString() }))
                    .forEach { method ->
                        appendLine("METHOD=${Modifier.toString(method.modifiers)} ${method.toGenericString()}")
                    }
                clazz.declaredFields
                    .filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }
                    .sortedBy { it.name }
                    .forEach { field ->
                        appendLine("FIELD=${Modifier.toString(field.modifiers)} ${field.toGenericString()}")
                    }
            }

            val candidatePattern = Regex(
                "(ForeignKey|Relation|Virtual|Reference|Constraint|Unique|Nullab)",
                RegexOption.IGNORE_CASE,
            )
            val candidateNames = linkedSetOf<String>()
            for (source in codeSources.sortedBy(Path::toString)) {
                appendLine()
                appendLine("=== SOURCE-CANDIDATES $source ===")
                if (Files.isRegularFile(source) && source.fileName.toString().endsWith(".jar")) {
                    ZipFile(source.toFile()).use { zip ->
                        zip.entries().asSequence()
                            .map { it.name }
                            .filter { it.startsWith("com/intellij/database/") }
                            .filter { it.endsWith(".class") }
                            .filterNot { '$' in it }
                            .map { it.removeSuffix(".class").replace('/', '.') }
                            .filter(candidatePattern::containsMatchIn)
                            .sorted()
                            .forEach(candidateNames::add)
                    }
                } else if (Files.isDirectory(source)) {
                    Files.walk(source).use { paths ->
                        paths.filter(Files::isRegularFile)
                            .map { source.relativize(it).toString().replace('\\', '/') }
                            .filter { it.startsWith("com/intellij/database/") }
                            .filter { it.endsWith(".class") }
                            .filter { '$' !in it }
                            .map { it.removeSuffix(".class").replace('/', '.') }
                            .filter(candidatePattern::containsMatchIn)
                            .sorted()
                            .forEach(candidateNames::add)
                    }
                }
            }

            appendLine()
            appendLine("=== CANDIDATE-CLASS-NAMES (${candidateNames.size}) ===")
            candidateNames.take(400).forEach { appendLine(it) }
            if (candidateNames.size > 400) appendLine("...TRUNCATED:${candidateNames.size - 400}")
        }

        throw AssertionError("#80 DATABASE API PROBE\n$report")
    }
}
