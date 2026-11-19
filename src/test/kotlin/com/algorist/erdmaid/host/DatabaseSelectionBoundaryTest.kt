package com.algorist.erdmaid.host

import com.algorist.erdmaid.core.ExportOutcome
import com.intellij.database.model.DasTable
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbElement
import com.intellij.database.psi.DbTable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.util.ModificationTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

@Suppress("UnstableApiUsage")
class DatabaseSelectionBoundaryTest {

    @Test
    fun `nonexpanding preflight suppresses no selection and unsupported direct object`() {
        val context = DataContext { null }
        val source = source("one", 1L)
        fun eligible(nodes: Iterable<*>, direct: Iterable<*>) =
            DatabaseToolsCompatibility.hasPotentialExportSelection(
                context,
                selectedNodesReader = { nodes },
                directElementsReader = { direct },
            )

        assertEquals(false, eligible(emptyList<Any>(), emptyList<DbElement>()))
        assertEquals(false, eligible(listOf("node"), listOf(element())))
        assertEquals(true, eligible(listOf("node"), listOf(table(source))))
        // The Tables-group node has no direct DbElement; expanding it belongs to invocation.
        assertEquals(true, eligible(listOf("tables-group"), emptyList<DbElement>()))
    }

    @Test
    fun `nonexpanding preflight reads only the first directly selected item`() {
        val context = DataContext { null }
        val selected = table(source("one", 1L))
        var reads = 0
        val dangerousSelection = object : Iterable<DbElement> {
            override fun iterator(): Iterator<DbElement> = object : Iterator<DbElement> {
                override fun hasNext() = true
                override fun next(): DbElement {
                    reads++
                    check(reads == 1) { "EDT update must not enumerate the selection" }
                    return selected
                }
            }
        }
        assertTrue(
            DatabaseToolsCompatibility.hasPotentialExportSelection(
                context,
                selectedNodesReader = { listOf("node") },
                directElementsReader = { dangerousSelection },
            ),
        )
        assertEquals(1, reads)

        // Update preflight is only an eligibility hint. Full invocation still rejects
        // an entire mixed selection rather than silently retaining its first table.
        val full = JetBrainsDatabaseHost.classifyExpandedSelection(listOf(selected, element()))
        assertTrue(full is ExportOutcome.Unsupported)
    }

    @Test
    fun `nonexpanding preflight fails closed on unreadable host context`() {
        val context = DataContext { null }
        assertEquals(
            false,
            DatabaseToolsCompatibility.hasPotentialExportSelection(
                context,
                selectedNodesReader = { throw IllegalStateException("not available") },
                directElementsReader = { listOf(table(source("one", 1L))) },
            ),
        )
        assertEquals(
            false,
            DatabaseToolsCompatibility.hasPotentialExportSelection(
                context,
                selectedNodesReader = { listOf("node") },
                directElementsReader = { "not an iterable" },
            ),
        )
    }

    @Test
    fun `group expansion success and platform failure are different outcomes`() {
        val context = DataContext { null }
        val table = table(source("one", 1L))

        val success = DatabaseToolsCompatibility.expandedSelection(context) { listOf(table) }
        assertTrue(success is ExportOutcome.Complete)
        assertEquals(1, (success as ExportOutcome.Complete).value.size)

        val failure = DatabaseToolsCompatibility.expandedSelection(context) {
            throw IllegalStateException("simulated DatabaseTools failure")
        }
        assertTrue(failure is ExportOutcome.Failure)
        assertEquals(
            "selection-api-invocation-failed",
            (failure as ExportOutcome.Failure).diagnostics.values.single().code,
        )
    }

    @Test
    fun `legitimate empty selection is a silent no-export state`() {
        val result = JetBrainsDatabaseHost.classifyExpandedSelection(emptyList())

        assertTrue(result is ExportOutcome.NoExport)
    }

    @Test
    fun `mixed table and unsupported selection fails closed`() {
        val result = JetBrainsDatabaseHost.classifyExpandedSelection(
            listOf(table(source("one", 1L)), element()),
        )

        assertTrue(result is ExportOutcome.Unsupported)
        assertEquals(
            "selection-mixed-or-unsupported",
            (result as ExportOutcome.Unsupported).diagnostics.values.single().code,
        )
    }

    @Test
    fun `DbTable-typed view in mixed selection fails closed`() {
        val source = source("one", 1L)
        val result = JetBrainsDatabaseHost.classifyExpandedSelection(
            listOf(table(source), table(source, ObjectKind.VIEW)),
        )

        assertTrue(result is ExportOutcome.Unsupported)
        assertEquals(
            "selection-mixed-or-unsupported",
            (result as ExportOutcome.Unsupported).diagnostics.values.single().code,
        )
    }

    @Test
    fun `cross-origin selection is rejected`() {
        val result = JetBrainsDatabaseHost.classifyExpandedSelection(
            listOf(table(source("one", 1L)), table(source("two", 1L))),
        )

        assertTrue(result is ExportOutcome.Unsupported)
        assertEquals(
            "selection-cross-origin",
            (result as ExportOutcome.Unsupported).diagnostics.values.single().code,
        )
    }

    @Test
    fun `exact duplicate live table selection is normalized once`() {
        val table = table(source("one", 4L))

        val result = JetBrainsDatabaseHost.classifyExpandedSelection(listOf(table, table))

        assertTrue(result is ExportOutcome.Complete)
        assertEquals(1, (result as ExportOutcome.Complete).value.tables.size)
        assertEquals(4L, result.value.freshness.modificationCount)
    }

    @Test
    fun `distinct live table objects with the same display identity are never deduplicated`() {
        val source = source("one", 4L)
        val first = table(source)
        val second = table(source)

        val result = JetBrainsDatabaseHost.classifyExpandedSelection(listOf(first, second))

        assertTrue(result is ExportOutcome.Complete)
        result as ExportOutcome.Complete
        assertEquals(2, result.value.tables.size)
        assertTrue(result.value.tables[0] === first)
        assertTrue(result.value.tables[1] === second)
    }

    @Test
    fun `same origin changing during selection capture degrades`() {
        val first = table(source("one", 4L))
        val second = table(source("one", 5L))

        val result = JetBrainsDatabaseHost.classifyExpandedSelection(listOf(first, second))

        assertTrue(result is ExportOutcome.Degraded)
        assertEquals(
            "selection-origin-modified-during-capture",
            (result as ExportOutcome.Degraded).diagnostics.values.single().code,
        )
    }

    private fun source(uniqueId: String, modificationCount: Long): DbDataSource =
        proxy(DbDataSource::class.java) { method, _ ->
            when (method.name) {
                "getUniqueId" -> uniqueId
                "getModificationTracker" -> object : ModificationTracker {
                    override fun getModificationCount(): Long = modificationCount
                }
                "toString" -> "DbDataSource($uniqueId)"
                else -> defaultValue(method.returnType)
            }
        }

    private fun table(
        source: DbDataSource,
        kind: ObjectKind = ObjectKind.TABLE,
    ): DbTable {
        val dasTable = proxy(DasTable::class.java) { method, _ ->
            when (method.name) {
                "getKind" -> kind
                "getName" -> "orders"
                "toString" -> "DasTable(orders, $kind)"
                else -> defaultValue(method.returnType)
            }
        }
        return proxy(DbTable::class.java) { method, _ ->
            when (method.name) {
                "isValid" -> true
                "getDataSource" -> source
                "getDasObject" -> dasTable
                "getName" -> "orders"
                "toString" -> "DbTable(orders, $kind)"
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun element(): DbElement =
        proxy(DbElement::class.java) { method, _ ->
            when (method.name) {
                "isValid" -> true
                "toString" -> "DbElement"
                else -> defaultValue(method.returnType)
            }
        }

    private fun <T> proxy(
        type: Class<T>,
        handler: (Method, Array<out Any?>?) -> Any?,
    ): T {
        val invocationHandler = InvocationHandler { _, method, args -> handler(method, args) }
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
