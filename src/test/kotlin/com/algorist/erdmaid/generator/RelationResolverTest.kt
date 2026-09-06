package com.algorist.erdmaid.generator

import com.algorist.erdmaid.generator.RelationResolver.RelationSpec
import com.algorist.erdmaid.generator.RelationResolver.TableIdentity
import com.algorist.erdmaid.generator.RelationResolver.TableReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RelationResolverTest : BasePlatformTestCase() {

    private fun table(
        name: String,
        schema: String? = null,
        catalog: String? = null,
    ) = TableIdentity(catalog = catalog, schema = schema, name = name)

    private fun relation(
        child: TableIdentity,
        parent: TableIdentity,
        name: String,
        childColumns: List<String> = emptyList(),
        parentColumns: List<String> = emptyList(),
    ) = RelationSpec(child, parent, name, childColumns, parentColumns)

    fun testDuplicateRelationsAreCollapsed() {
        val orders = table("orders", schema = "sales")
        val users = table("users", schema = "sales")
        val selectedTables = setOf(orders, users)
        val expected = relation(orders, users, "fk_orders_users")

        val normalized = RelationResolver.normalize(
            relations = listOf(expected, expected),
            selectedTables = selectedTables,
        )

        assertEquals(listOf(expected), normalized)
    }

    fun testRelationsOutsideSelectionAreDropped() {
        val orders = table("orders", schema = "sales")
        val users = table("users", schema = "sales")
        val auditLogs = table("audit_logs", schema = "audit")
        val selectedTables = setOf(orders, users)
        val expected = relation(orders, users, "fk_orders_users")

        val normalized = RelationResolver.normalize(
            relations = listOf(
                expected,
                relation(orders, auditLogs, "fk_orders_audit_logs"),
            ),
            selectedTables = selectedTables,
        )

        assertEquals(listOf(expected), normalized)
    }

    fun testDistinctRelationsBetweenSameTablesAreKept() {
        val orders = table("orders", schema = "sales")
        val users = table("users", schema = "sales")
        val relations = listOf(
            relation(orders, users, "fk_orders_created_by", listOf("created_by"), listOf("id")),
            relation(orders, users, "fk_orders_updated_by", listOf("updated_by"), listOf("id")),
        )

        val normalized = RelationResolver.normalize(relations, setOf(orders, users))

        assertEquals(relations, normalized)
    }

    fun testCompositeForeignKeysRemainDistinctByOrderedColumnPairs() {
        val lines = table("order_lines", schema = "sales")
        val orders = table("orders", schema = "sales")
        val relations = listOf(
            relation(
                lines,
                orders,
                "fk_lines_order",
                childColumns = listOf("tenant_id", "order_id"),
                parentColumns = listOf("tenant_id", "id"),
            ),
            relation(
                lines,
                orders,
                "fk_lines_previous_order",
                childColumns = listOf("tenant_id", "previous_order_id"),
                parentColumns = listOf("tenant_id", "id"),
            ),
        )

        val normalized = RelationResolver.normalize(relations, setOf(lines, orders))

        assertEquals(relations, normalized)
    }

    fun testSameColumnsPreferNonBlankRelationName() {
        val orders = table("orders", schema = "sales")
        val users = table("users", schema = "sales")
        val unnamed = relation(orders, users, "", listOf("user_id"), listOf("id"))
        val named = relation(orders, users, "fk_orders_users", listOf("user_id"), listOf("id"))

        val normalized = RelationResolver.normalize(listOf(unnamed, named), setOf(orders, users))

        assertEquals(listOf(named), normalized)
    }

    fun testSameNamedTablesAcrossSchemasDoNotCollapseDuringNormalization() {
        val salesOrders = table("orders", schema = "sales")
        val archiveOrders = table("orders", schema = "archive")
        val users = table("users", schema = "sales")
        val relations = listOf(
            relation(salesOrders, users, "fk_sales_orders_users", listOf("user_id"), listOf("id")),
            relation(archiveOrders, users, "fk_archive_orders_users", listOf("user_id"), listOf("id")),
        )

        val normalized = RelationResolver.normalize(
            relations,
            setOf(salesOrders, archiveOrders, users),
        )

        assertEquals(relations, normalized)
    }

    fun testSchemaHintResolvesOnlyTheMatchingSameNamedTable() {
        val salesOrders = table("orders", schema = "sales")
        val archiveOrders = table("orders", schema = "archive")

        val resolved = RelationResolver.resolveTableReference(
            TableReference(catalog = null, schema = "sales", name = "orders"),
            setOf(salesOrders, archiveOrders),
        )

        assertEquals(salesOrders, resolved)
    }

    fun testMissingQualificationResolvesWhenNameIsUnique() {
        val orders = table("orders", schema = "sales")
        val users = table("users", schema = "sales")

        val resolved = RelationResolver.resolveTableReference(
            TableReference(catalog = null, schema = null, name = "users"),
            setOf(orders, users),
        )

        assertEquals(users, resolved)
    }

    fun testMissingQualificationDoesNotGuessBetweenDuplicateNames() {
        val salesOrders = table("orders", schema = "sales")
        val archiveOrders = table("orders", schema = "archive")

        val resolved = RelationResolver.resolveTableReference(
            TableReference(catalog = null, schema = null, name = "orders"),
            setOf(salesOrders, archiveOrders),
        )

        assertNull(resolved)
    }

    fun testCatalogHintDisambiguatesSameSchemaAndName() {
        val tenantAOrders = table("orders", schema = "sales", catalog = "tenant_a")
        val tenantBOrders = table("orders", schema = "sales", catalog = "tenant_b")

        val resolved = RelationResolver.resolveTableReference(
            TableReference(catalog = "tenant_b", schema = "sales", name = "orders"),
            setOf(tenantAOrders, tenantBOrders),
        )

        assertEquals(tenantBOrders, resolved)
    }

    fun testIdentifierMatchingPreservesExactCaseAndWhitespace() {
        val quotedLike = table("Order Items", schema = "Sales Schema")
        val lowerCase = table("order items", schema = "Sales Schema")

        val exact = RelationResolver.resolveTableReference(
            TableReference(catalog = null, schema = "Sales Schema", name = "Order Items"),
            setOf(quotedLike, lowerCase),
        )
        val wrongCase = RelationResolver.resolveTableReference(
            TableReference(catalog = null, schema = "sales schema", name = "Order Items"),
            setOf(quotedLike, lowerCase),
        )

        assertEquals(quotedLike, exact)
        assertNull(wrongCase)
    }
}
