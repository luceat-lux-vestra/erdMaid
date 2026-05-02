package com.algorist.erdmaid.actions

import com.intellij.database.psi.DbElement
import com.intellij.database.psi.DbTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class ErdMaidExportActionTest {

    private val action = ErdMaidExportAction()

    @Test
    fun `uses database selection when it already contains tables`() {
        val primarySelection = listOf(newDbTable("orders"))
        val fallbackSelection = arrayOf(newDbTable("customers"))

        val result = action.resolveSelectedTables(primarySelection, fallbackSelection)

        assertEquals(listOf("orders"), result.map { it.name })
    }

    @Test
    fun `falls back to psi elements when database selection has no tables`() {
        val primarySelection = listOf(newDbElement())
        val fallbackSelection = arrayOf<Any>(newDbTable("customers"), "not-a-table")

        val result = action.resolveSelectedTables(primarySelection, fallbackSelection)

        assertEquals(listOf("customers"), result.map { it.name })
    }

    @Test
    fun `returns empty list when no tables exist in either selection source`() {
        val primarySelection = listOf(newDbElement())
        val fallbackSelection = arrayOf<Any>("plain-string", 42)

        val result = action.resolveSelectedTables(primarySelection, fallbackSelection)

        assertTrue(result.isEmpty())
    }

    private fun newDbTable(name: String): DbTable =
        proxy(DbTable::class.java) { method, _ ->
            when (method.name) {
                "getName" -> name
                "toString" -> "DbTable($name)"
                else -> defaultValue(method.returnType)
            }
        }

    private fun newDbElement(): DbElement =
        proxy(DbElement::class.java) { method, _ ->
            when (method.name) {
                "toString" -> "DbElement"
                else -> defaultValue(method.returnType)
            }
        }

    private fun <T> proxy(
        type: Class<T>,
        handler: (Method, Array<out Any?>?) -> Any?,
    ): T {
        val invocationHandler = InvocationHandler { _, method, args ->
            handler(method, args)
        }
        return type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type), invocationHandler))!!
    }

    private fun defaultValue(returnType: Class<*>): Any? = when (returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
