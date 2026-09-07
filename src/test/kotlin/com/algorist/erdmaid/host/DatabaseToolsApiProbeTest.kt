package com.algorist.erdmaid.host

import com.intellij.database.model.DasForeignKey
import com.intellij.database.model.ModelRelationManager
import org.junit.Test
import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * Temporary evidence probe for #82. This test intentionally fails so the exact shipped
 * DatabaseTools relation/FK API surface is captured in CI logs before the branch is rewritten
 * into a maintained, non-probe contract test.
 */
class DatabaseToolsApiProbeTest {

    @Test
    fun dumpRelationApiSurface() {
        val relationManager = ModelRelationManager::class.java
        val foreignKey = DasForeignKey::class.java
        val related = linkedSetOf<Class<*>>(relationManager, foreignKey)

        listOf(relationManager, foreignKey).forEach { clazz ->
            clazz.declaredMethods.forEach { method ->
                collectClasses(method.genericReturnType, related)
                method.genericParameterTypes.forEach { collectClasses(it, related) }
            }
        }

        val output = buildString {
            appendLine("=== DatabaseTools relation API probe ===")
            appendLine("relationManager=${relationManager.name}")
            appendLine("relationManagerResource=${relationManager.getResource("/${relationManager.name.replace('.', '/')}.class")}")
            appendLine("relationManagerCodeSource=${relationManager.protectionDomain?.codeSource?.location}")
            appendLine("foreignKey=${foreignKey.name}")
            appendLine("foreignKeyResource=${foreignKey.getResource("/${foreignKey.name.replace('.', '/')}.class")}")
            appendLine("foreignKeyCodeSource=${foreignKey.protectionDomain?.codeSource?.location}")
            appendLine("--- reflected signatures ---")
            related
                .filter { it.name.startsWith("com.intellij.database") }
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
