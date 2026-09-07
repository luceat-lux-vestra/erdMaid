package com.algorist.erdmaid.host

import com.intellij.database.model.ModelRelationManager
import org.junit.Test
import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.net.JarURLConnection
import java.util.jar.JarFile

/**
 * Temporary evidence probe for #82. This test intentionally fails so the exact shipped
 * DatabaseTools relation/FK API surface is captured in CI logs before the branch is rewritten
 * into a maintained, non-probe contract test.
 */
class DatabaseToolsApiProbeTest {

    @Test
    fun dumpRelationApiSurface() {
        val root = ModelRelationManager::class.java
        val related = linkedSetOf<Class<*>>()
        related += root

        root.declaredMethods.forEach { method ->
            collectClasses(method.genericReturnType, related)
            method.genericParameterTypes.forEach { collectClasses(it, related) }
        }

        val resource = root.getResource("/${root.name.replace('.', '/')}.class")
        val jarClassNames = if (resource?.protocol == "jar") {
            val connection = resource.openConnection() as JarURLConnection
            relevantModelClasses(connection.jarFile)
        } else {
            emptyList()
        }

        for (className in jarClassNames) {
            runCatching {
                Class.forName(className, false, root.classLoader)
            }.getOrNull()?.let(related::add)
        }

        val output = buildString {
            appendLine("=== DatabaseTools relation API probe ===")
            appendLine("root=${root.name}")
            appendLine("resource=$resource")
            appendLine("codeSource=${root.protectionDomain?.codeSource?.location}")
            appendLine("--- relevant jar classes ---")
            jarClassNames.forEach(::appendLine)
            appendLine("--- reflected signatures ---")
            related
                .sortedBy { it.name }
                .forEach { clazz ->
                    appendLine("CLASS ${clazz.name}")
                    clazz.declaredMethods
                        .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
                        .sortedBy { it.toGenericString() }
                        .forEach { appendLine("  ${it.toGenericString()}") }
                }
        }

        throw AssertionError(output)
    }

    private fun relevantModelClasses(jarFile: JarFile): List<String> =
        jarFile.entries().asSequence()
            .map { it.name }
            .filter { it.startsWith("com/intellij/database/model/") }
            .filter { it.endsWith(".class") && '$' !in it }
            .filter {
                it.contains("Relation", ignoreCase = true) ||
                    it.contains("ForeignKey", ignoreCase = true) ||
                    it.contains("Reference", ignoreCase = true) ||
                    it.contains("Constraint", ignoreCase = true)
            }
            .map { it.removeSuffix(".class").replace('/', '.') }
            .sorted()
            .toList()

    private fun collectClasses(type: Type, target: MutableSet<Class<*>>) {
        when (type) {
            is Class<*> -> target += type
            is ParameterizedType -> {
                collectClasses(type.rawType, target)
                type.actualTypeArguments.forEach { collectClasses(it, target) }
            }
            is WildcardType -> {
                type.upperBounds.forEach { collectClasses(it, target) }
                type.lowerBounds.forEach { collectClasses(it, target) }
            }
            is GenericArrayType -> collectClasses(type.genericComponentType, target)
        }
    }
}
