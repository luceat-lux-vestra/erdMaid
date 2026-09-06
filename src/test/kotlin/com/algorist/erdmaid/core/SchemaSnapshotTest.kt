package com.algorist.erdmaid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SchemaSnapshotTest {

    private val origin = OriginId("datasource-a")

    @Test
    fun `same table name across schemas and catalogs remains distinct`() {
        val sales = TableId(origin, catalog = "app", schema = "sales", name = "orders")
        val archive = TableId(origin, catalog = "app", schema = "archive", name = "orders")
        val reporting = TableId(origin, catalog = "reporting", schema = "sales", name = "orders")

        assertNotEquals(sales, archive)
        assertNotEquals(sales, reporting)
        assertNotEquals(archive, reporting)
    }

    @Test
    fun `canonical identity preserves case whitespace and quoted spelling exactly`() {
        val rawName = "  Order Item\"MixedCase  "
        val id = TableId(origin, catalog = "CAT", schema = "Sales", name = rawName)
        val column = ColumnId(id, "  Item ID  ")

        assertEquals(rawName, id.name)
        assertEquals("Sales", id.schema)
        assertEquals("  Item ID  ", column.name)
        assertNotEquals(id, id.copy(name = "  order item\"mixedcase  "))
    }

    @Test
    fun `missing catalog and schema remain absent rather than fabricated`() {
        val id = TableId(origin, catalog = null, schema = null, name = "orders")

        assertEquals(null, id.catalog)
        assertEquals(null, id.schema)
    }

    @Test
    fun `authoritative absence and unavailable metadata are distinct states`() {
        val absent: OptionalValue<String> = OptionalValue.Absent
        val unavailable: OptionalValue<String> = OptionalValue.Unavailable(
            CoreDiagnostic("metadata-read-failed")
        )

        assertNotEquals(absent, unavailable)
        assertTrue(absent is OptionalValue.Absent)
        assertTrue(unavailable is OptionalValue.Unavailable)
    }

    @Test
    fun `composite primary key preserves authoritative column order`() {
        val table = TableId(origin, "app", "sales", "order_line")
        val orderId = ColumnId(table, "order_id")
        val lineNo = ColumnId(table, "line_no")
        val key = PrimaryKeyFact(
            name = OptionalValue.Present("pk_order_line"),
            columns = listOf(orderId, lineNo),
        )

        assertEquals(listOf(orderId, lineNo), key.columns)
    }

    @Test
    fun `composite foreign key preserves ordered mappings`() {
        val child = TableId(origin, "app", "sales", "order_line")
        val orderId = ColumnId(child, "order_id")
        val tenantId = ColumnId(child, "tenant_id")
        val mappings = listOf(
            ForeignKeyColumnMapping(orderId, "id"),
            ForeignKeyColumnMapping(tenantId, "tenant_id"),
        )
        val foreignKey = ForeignKeyFact(
            childTable = child,
            referencedTable = TableReferenceEvidence(
                origin = Evidence.Known(origin),
                catalog = OptionalValue.Present("app"),
                schema = OptionalValue.Present("sales"),
                name = Evidence.Known("orders"),
            ),
            name = OptionalValue.Present("fk_order_line_order"),
            provenance = Evidence.Known(RelationProvenance.PHYSICAL),
            mappings = Evidence.Known(mappings),
        )

        assertEquals(mappings, (foreignKey.mappings as Evidence.Known).value)
    }

    @Test
    fun `incomplete foreign key target remains reference evidence instead of selected table identity`() {
        val child = TableId(origin, "app", "sales", "orders")
        val reference = TableReferenceEvidence(
            origin = Evidence.Unavailable(CoreDiagnostic("origin-not-exposed")),
            catalog = OptionalValue.Unavailable(CoreDiagnostic("catalog-not-exposed")),
            schema = OptionalValue.Unavailable(CoreDiagnostic("schema-not-exposed")),
            name = Evidence.Known("users"),
        )
        val foreignKey = ForeignKeyFact(
            childTable = child,
            referencedTable = reference,
            name = OptionalValue.Absent,
            provenance = Evidence.Unavailable(CoreDiagnostic("provenance-not-exposed")),
            mappings = Evidence.Known(
                listOf(ForeignKeyColumnMapping(ColumnId(child, "user_id"), "id"))
            ),
        )

        assertSame(reference, foreignKey.referencedTable)
        assertTrue(foreignKey.referencedTable.schema is OptionalValue.Unavailable)
        assertEquals("users", (foreignKey.referencedTable.name as Evidence.Known).value)
    }

    @Test
    fun `nullability and uniqueness distinguish known values from unavailable evidence`() {
        val table = TableId(origin, "app", "sales", "users")
        val id = ColumnSnapshot(
            id = ColumnId(table, "id"),
            sourcePosition = 0,
            rawType = Evidence.Known(RawTypeMetadata("bigint")),
            nullable = Evidence.Known(false),
            comment = OptionalValue.Absent,
        )
        val email = ColumnSnapshot(
            id = ColumnId(table, "email"),
            sourcePosition = 1,
            rawType = Evidence.Known(RawTypeMetadata("varchar")),
            nullable = Evidence.Unavailable(CoreDiagnostic("nullability-unavailable")),
            comment = OptionalValue.Absent,
        )
        val withKnownNoUniqueKeys = tableSnapshot(
            table = table,
            columns = listOf(id, email),
            uniqueKeys = Evidence.Known(emptyList()),
        )
        val withUnavailableUniqueKeys = tableSnapshot(
            table = table,
            columns = listOf(id, email),
            uniqueKeys = Evidence.Unavailable(CoreDiagnostic("unique-keys-unavailable")),
        )

        assertEquals(Evidence.Known(false), id.nullable)
        assertTrue(email.nullable is Evidence.Unavailable)
        assertEquals(Evidence.Known(emptyList<UniqueKeyFact>()), withKnownNoUniqueKeys.uniqueKeys)
        assertTrue(withUnavailableUniqueKeys.uniqueKeys is Evidence.Unavailable)
    }

    @Test
    fun `table snapshot preserves increasing source positions without reordering columns`() {
        val table = TableId(origin, null, "sales", "orders")
        val first = column(table, "created_at", sourcePosition = 3)
        val second = column(table, "id", sourcePosition = 7)
        val snapshot = tableSnapshot(table, columns = listOf(first, second))

        assertEquals(listOf("created_at", "id"), snapshot.columns.map { it.id.name })
        assertEquals(listOf(3, 7), snapshot.columns.map { it.sourcePosition })
    }

    @Test
    fun `table snapshot rejects reordered source positions rather than silently sorting`() {
        val table = TableId(origin, null, "sales", "orders")
        val later = column(table, "later", sourcePosition = 5)
        val earlier = column(table, "earlier", sourcePosition = 1)

        assertThrows(IllegalArgumentException::class.java) {
            tableSnapshot(table, columns = listOf(later, earlier))
        }
    }

    @Test
    fun `schema snapshot rejects duplicate canonical table identities`() {
        val table = TableId(origin, "app", "sales", "orders")
        val first = tableSnapshot(table)
        val duplicate = tableSnapshot(table)

        assertThrows(IllegalArgumentException::class.java) {
            SchemaSnapshot(origin, listOf(first, duplicate))
        }
    }

    @Test
    fun `schema snapshot rejects tables from a different origin`() {
        val foreignOrigin = OriginId("datasource-b")
        val local = tableSnapshot(TableId(origin, "app", "sales", "orders"))
        val foreign = tableSnapshot(TableId(foreignOrigin, "app", "sales", "users"))

        assertThrows(IllegalArgumentException::class.java) {
            SchemaSnapshot(origin, listOf(local, foreign))
        }
    }

    @Test
    fun `core public model has no IntelliJ Platform type dependency`() {
        val coreClasses = listOf(
            OriginId::class.java,
            CoreDiagnostic::class.java,
            OptionalValue::class.java,
            Evidence::class.java,
            TableId::class.java,
            ColumnId::class.java,
            RawTypeMetadata::class.java,
            ColumnSnapshot::class.java,
            PrimaryKeyFact::class.java,
            UniqueKeyFact::class.java,
            RelationProvenance::class.java,
            TableReferenceEvidence::class.java,
            ForeignKeyColumnMapping::class.java,
            ForeignKeyFact::class.java,
            TableSnapshot::class.java,
            SchemaSnapshot::class.java,
        )

        val referencedTypes = coreClasses.flatMap { type ->
            buildList {
                addAll(type.declaredFields.map { it.type })
                addAll(type.declaredMethods.map { it.returnType })
                addAll(type.declaredMethods.flatMap { it.parameterTypes.asList() })
                addAll(type.declaredConstructors.flatMap { it.parameterTypes.asList() })
            }
        }

        assertTrue(
            "Pure core must not reference IntelliJ Platform types",
            referencedTypes.none { it.name.startsWith("com.intellij.") },
        )
    }

    private fun column(
        table: TableId,
        name: String,
        sourcePosition: Int,
    ): ColumnSnapshot = ColumnSnapshot(
        id = ColumnId(table, name),
        sourcePosition = sourcePosition,
        rawType = Evidence.Known(RawTypeMetadata("text")),
        nullable = Evidence.Known(true),
        comment = OptionalValue.Absent,
    )

    private fun tableSnapshot(
        table: TableId,
        columns: List<ColumnSnapshot> = emptyList(),
        uniqueKeys: Evidence<List<UniqueKeyFact>> = Evidence.Known(emptyList()),
    ): TableSnapshot = TableSnapshot(
        id = table,
        comment = OptionalValue.Absent,
        columns = columns,
        primaryKey = OptionalValue.Absent,
        uniqueKeys = uniqueKeys,
        foreignKeys = Evidence.Known(emptyList()),
    )
}
