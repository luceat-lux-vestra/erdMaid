package com.algorist.erdmaid.host

import com.algorist.erdmaid.core.ColumnId
import com.algorist.erdmaid.core.Evidence
import com.algorist.erdmaid.core.FrozenList
import com.algorist.erdmaid.core.OriginId
import com.algorist.erdmaid.core.TableId
import com.algorist.erdmaid.core.UniqueKeyFact
import com.intellij.database.model.DasColumn
import com.intellij.database.model.DasIndex
import com.intellij.database.model.DasTable
import com.intellij.database.model.DasTableKey
import com.intellij.database.model.MultiRef
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

@Suppress("UnstableApiUsage")
class DatabaseMetadataEvidenceTest {
    private val origin = OriginId("ds-a")
    private val tableId = TableId(origin, "catalog", "schema", "users")

    @Test
    fun `alternate table key is unique only when candidate-key evidence agrees`() {
        val tenant = column("tenant_id")
        val email = column("email")
        val table = table(
            mapOf(
                "tenant_id" to setOf(DasColumn.Attribute.CANDIDATE_KEY),
                "email" to setOf(DasColumn.Attribute.CANDIDATE_KEY),
            )
        )
        val key = tableKey("uk_users_tenant_email", primary = false, "tenant_id", "email")
        val ids = mapOf(
            "tenant_id" to ColumnId(tableId, "tenant_id"),
            "email" to ColumnId(tableId, "email"),
        )

        val result = invokeCaptureUniqueKeys(
            table = table,
            columns = listOf(tenant, email),
            columnIds = ids,
            columnObjects = mapOf("tenant_id" to tenant, "email" to email),
            tableKeys = listOf(key),
            primaryKeys = emptyList(),
        )

        assertTrue(result is Evidence.Known)
        val facts = (result as Evidence.Known).value
        assertEquals(1, facts.size)
        assertEquals(
            listOf(ids.getValue("tenant_id"), ids.getValue("email")),
            facts.single().columns.toList(),
        )
    }

    @Test
    fun `non-primary table key without candidate marker stays unavailable`() {
        val email = column("email")
        val table = table(mapOf("email" to emptySet()))
        val key = tableKey("uk_users_email", primary = false, "email")

        val result = invokeCaptureUniqueKeys(
            table = table,
            columns = listOf(email),
            columnIds = mapOf("email" to ColumnId(tableId, "email")),
            columnObjects = mapOf("email" to email),
            tableKeys = listOf(key),
            primaryKeys = emptyList(),
        )

        assertUnavailable(result, "non-primary-table-key-not-candidate-marked")
    }

    @Test
    fun `candidate marker without grouped table key stays unavailable`() {
        val email = column("email")
        val table = table(mapOf("email" to setOf(DasColumn.Attribute.CANDIDATE_KEY)))

        val result = invokeCaptureUniqueKeys(
            table = table,
            columns = listOf(email),
            columnIds = mapOf("email" to ColumnId(tableId, "email")),
            columnObjects = mapOf("email" to email),
            tableKeys = emptyList(),
            primaryKeys = emptyList(),
        )

        assertUnavailable(result, "candidate-key-column-without-table-key")
    }

    @Test
    fun `absence of every uniqueness evidence source is known empty`() {
        val id = column("id")
        val table = table(mapOf("id" to emptySet()))

        val result = invokeCaptureUniqueKeys(
            table = table,
            columns = listOf(id),
            columnIds = mapOf("id" to ColumnId(tableId, "id")),
            columnObjects = mapOf("id" to id),
            tableKeys = emptyList(),
            primaryKeys = emptyList(),
            indices = emptyList(),
        )

        assertTrue(result is Evidence.Known)
        assertTrue((result as Evidence.Known).value.isEmpty())
    }

    @Test
    fun `unreconciled unique index prevents a known non-unique claim`() {
        val email = column("email")
        val table = table(mapOf("email" to emptySet()))

        val result = invokeCaptureUniqueKeys(
            table = table,
            columns = listOf(email),
            columnIds = mapOf("email" to ColumnId(tableId, "email")),
            columnObjects = mapOf("email" to email),
            tableKeys = emptyList(),
            primaryKeys = emptyList(),
            indices = listOf(index(unique = true, functionBased = false, "email")),
        )

        assertUnavailable(result, "unreconciled-unique-index")
    }

    @Test
    fun `function based unique index without stronger key evidence stays unavailable`() {
        val email = column("email")
        val table = table(mapOf("email" to emptySet()))

        val result = invokeCaptureUniqueKeys(
            table = table,
            columns = listOf(email),
            columnIds = mapOf("email" to ColumnId(tableId, "email")),
            columnObjects = mapOf("email" to email),
            tableKeys = emptyList(),
            primaryKeys = emptyList(),
            indices = listOf(index(unique = true, functionBased = true, "email")),
        )

        assertUnavailable(result, "function-based-unique-index-unreconciled")
    }

    @Test
    fun `unique index with unresolved columns stays unavailable`() {
        val id = column("id")
        val table = table(mapOf("id" to emptySet()))

        val result = invokeCaptureUniqueKeys(
            table = table,
            columns = listOf(id),
            columnIds = mapOf("id" to ColumnId(tableId, "id")),
            columnObjects = mapOf("id" to id),
            tableKeys = emptyList(),
            primaryKeys = emptyList(),
            indices = listOf(index(unique = true, functionBased = false, "missing")),
        )

        assertUnavailable(result, "unique-index-column-unresolved")
    }

    @Test
    fun `unique index matching proven alternate key is corroboration not duplicate authority`() {
        val tenant = column("tenant_id")
        val email = column("email")
        val table = table(
            mapOf(
                "tenant_id" to setOf(DasColumn.Attribute.CANDIDATE_KEY),
                "email" to setOf(DasColumn.Attribute.CANDIDATE_KEY),
            )
        )
        val key = tableKey("uk_users_tenant_email", primary = false, "tenant_id", "email")
        val ids = mapOf(
            "tenant_id" to ColumnId(tableId, "tenant_id"),
            "email" to ColumnId(tableId, "email"),
        )

        val result = invokeCaptureUniqueKeys(
            table = table,
            columns = listOf(tenant, email),
            columnIds = ids,
            columnObjects = mapOf("tenant_id" to tenant, "email" to email),
            tableKeys = listOf(key),
            primaryKeys = emptyList(),
            indices = listOf(index(unique = true, functionBased = false, "email", "tenant_id")),
        )

        assertTrue(result is Evidence.Known)
        val facts = (result as Evidence.Known).value
        assertEquals(1, facts.size)
        assertEquals("uk_users_tenant_email", (facts.single().name as com.algorist.erdmaid.core.OptionalValue.Present).value)
    }

    @Test
    fun `unique index matching primary key does not invent an alternate key`() {
        val id = column("id")
        val table = table(mapOf("id" to setOf(DasColumn.Attribute.CANDIDATE_KEY)))
        val primary = tableKey("pk_users", primary = true, "id")

        val result = invokeCaptureUniqueKeys(
            table = table,
            columns = listOf(id),
            columnIds = mapOf("id" to ColumnId(tableId, "id")),
            columnObjects = mapOf("id" to id),
            tableKeys = listOf(primary),
            primaryKeys = listOf(primary),
            indices = listOf(index(unique = true, functionBased = false, "id")),
        )

        assertTrue(result is Evidence.Known)
        assertTrue((result as Evidence.Known).value.isEmpty())
    }

    @Test
    fun `non-unique index does not turn known empty uniqueness into unavailable`() {
        val email = column("email")
        val table = table(mapOf("email" to emptySet()))

        val result = invokeCaptureUniqueKeys(
            table = table,
            columns = listOf(email),
            columnIds = mapOf("email" to ColumnId(tableId, "email")),
            columnObjects = mapOf("email" to email),
            tableKeys = emptyList(),
            primaryKeys = emptyList(),
            indices = listOf(index(unique = false, functionBased = false, "email")),
        )

        assertTrue(result is Evidence.Known)
        assertTrue((result as Evidence.Known).value.isEmpty())
    }

    @Test
    fun `referenced origin requires datasource-owned resolution and matching origin`() {
        val refTable = proxy(DasTable::class.java) { method, _ ->
            when (method.name) {
                "getName" -> "users"
                else -> defaultValue(method.returnType)
            }
        }

        lateinit var sourceA: DbDataSource
        val elementA = proxy(DbElement::class.java) { method, _ ->
            when (method.name) {
                "getDataSource" -> sourceA
                else -> defaultValue(method.returnType)
            }
        }
        sourceA = dataSource("ds-a") { elementA }

        val known = invokeReferencedOrigin(sourceA, refTable)
        assertEquals(Evidence.Known(OriginId("ds-a")), known)

        val sourceB = dataSource("ds-b") { null }
        val mismatchedElement = proxy(DbElement::class.java) { method, _ ->
            when (method.name) {
                "getDataSource" -> sourceB
                else -> defaultValue(method.returnType)
            }
        }
        val mismatchedSource = dataSource("ds-a") { mismatchedElement }
        assertOriginUnavailable(
            invokeReferencedOrigin(mismatchedSource, refTable),
            "datasource-origin-mismatch",
        )

        val unresolved = dataSource("ds-a") { null }
        assertOriginUnavailable(
            invokeReferencedOrigin(unresolved, refTable),
            "datasource-element-not-found",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeCaptureUniqueKeys(
        table: DasTable,
        columns: List<DasColumn>,
        columnIds: Map<String, ColumnId>,
        columnObjects: Map<String, DasColumn>,
        tableKeys: List<DasTableKey>,
        primaryKeys: List<DasTableKey>,
        indices: List<DasIndex> = emptyList(),
    ): Evidence<FrozenList<UniqueKeyFact>> {
        val method = JetBrainsDatabaseHost::class.java.declaredMethods.single {
            it.name == "captureUniqueKeys"
        }
        method.isAccessible = true
        return method.invoke(
            JetBrainsDatabaseHost,
            table,
            columns,
            columnIds,
            columnObjects,
            tableKeys,
            primaryKeys,
            indices,
        ) as Evidence<FrozenList<UniqueKeyFact>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeReferencedOrigin(
        source: DbDataSource,
        refTable: DasTable,
    ): Evidence<OriginId> {
        val method = JetBrainsDatabaseHost::class.java.declaredMethods.single {
            it.name == "referencedOrigin"
        }
        method.isAccessible = true
        return method.invoke(JetBrainsDatabaseHost, source, refTable) as Evidence<OriginId>
    }

    private fun assertUnavailable(
        result: Evidence<FrozenList<UniqueKeyFact>>,
        detail: String,
    ) {
        assertTrue(result is Evidence.Unavailable)
        result as Evidence.Unavailable
        assertEquals("metadata-unique-key-authority-unavailable", result.diagnostic.code)
        assertEquals(detail, result.diagnostic.detail)
    }

    private fun assertOriginUnavailable(result: Evidence<OriginId>, detail: String) {
        assertTrue(result is Evidence.Unavailable)
        result as Evidence.Unavailable
        assertEquals("foreign-key-parent-origin-unavailable", result.diagnostic.code)
        assertEquals(detail, result.diagnostic.detail)
    }

    private fun column(name: String): DasColumn =
        proxy(DasColumn::class.java) { method, _ ->
            when (method.name) {
                "getName" -> name
                else -> defaultValue(method.returnType)
            }
        }

    private fun table(attrs: Map<String, Set<DasColumn.Attribute>>): DasTable =
        proxy(DasTable::class.java) { method, args ->
            when (method.name) {
                "getColumnAttrs" -> {
                    val column = args?.single() as DasColumn
                    attrs[column.name].orEmpty()
                }
                "getName" -> "users"
                else -> defaultValue(method.returnType)
            }
        }

    private fun tableKey(
        name: String,
        primary: Boolean,
        vararg columns: String,
    ): DasTableKey = proxy(DasTableKey::class.java) { method, _ ->
        when (method.name) {
            "getName" -> name
            "isPrimary" -> primary
            "getColumnsRef" -> multiRef(columns.toList())
            else -> defaultValue(method.returnType)
        }
    }

    private fun index(
        unique: Boolean,
        functionBased: Boolean,
        vararg columns: String,
    ): DasIndex = proxy(DasIndex::class.java) { method, _ ->
        when (method.name) {
            "isUnique" -> unique
            "isFunctionBased" -> functionBased
            "getColumnsRef" -> multiRef(columns.toList())
            else -> defaultValue(method.returnType)
        }
    }

    private fun multiRef(names: List<String>): MultiRef<*> =
        proxy(MultiRef::class.java) { method, _ ->
            when (method.name) {
                "names" -> names
                "size" -> names.size
                "resolveObjects" -> emptyList<Any>()
                else -> defaultValue(method.returnType)
            }
        }

    private fun dataSource(
        uniqueId: String,
        find: () -> DbElement?,
    ): DbDataSource = proxy(DbDataSource::class.java) { method, _ ->
        when (method.name) {
            "getUniqueId" -> uniqueId
            "findElement" -> find()
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
