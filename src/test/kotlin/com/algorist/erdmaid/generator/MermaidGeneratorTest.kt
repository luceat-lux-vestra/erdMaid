package com.algorist.erdmaid.generator

import com.algorist.erdmaid.generator.MermaidGenerator.ColumnSpec
import com.algorist.erdmaid.generator.MermaidGenerator.TableSpec
import com.algorist.erdmaid.generator.RelationResolver.RelationSpec
import com.algorist.erdmaid.generator.RelationResolver.TableIdentity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Unit tests for MermaidGenerator's core diagram-building logic.
 *
 * Because [MermaidGenerator.generate] accepts live [com.intellij.database.model.DasTable]
 * objects from the IntelliJ DB runtime, the string-building logic is extracted into the
 * internal [MermaidGenerator.buildDiagram] method that takes plain [TableSpec] data
 * classes, making it straightforward to test without mocking the DB plugin API.
 */
class MermaidGeneratorTest : BasePlatformTestCase() {

    private fun identity(
        name: String,
        schema: String? = null,
        catalog: String? = null,
    ) = TableIdentity(catalog = catalog, schema = schema, name = name)

    private fun col(
        name: String,
        type: String,
        isPk: Boolean = false,
        comment: String? = null
    ) = ColumnSpec(name, type, isPk, comment)

    private fun relation(name: String, parentTable: String, childTable: String) =
        RelationSpec(
            childTable = identity(childTable),
            parentTable = identity(parentTable),
            name = name,
            childColumns = emptyList(),
            parentColumns = emptyList(),
        )

    private fun table(
        name: String,
        comment: String? = null,
        columns: List<ColumnSpec> = emptyList(),
        relations: List<RelationSpec> = emptyList(),
        schema: String? = null,
        catalog: String? = null,
    ) = TableSpec(identity(name, schema, catalog), comment, columns, relations)

    private class LengthType(private val length: Int) {
        fun getLength(): Int = length
    }

    private class PrecisionScaleType(
        private val precision: Int,
        private val scale: Int,
    ) {
        fun getPrecision(): Int = precision
        fun getScale(): Int = scale
    }

    private class PrecisionOnlyType(private val precision: Int) {
        fun getPrecision(): Int = precision
    }

    fun testEmptyTableListProducesHeader() {
        val result = MermaidGenerator.buildDiagram(emptyList())
        assertEquals("erDiagram\n", result)
    }

    fun testTableWithCommentEmitsMermaidCommentLine() {
        val result = MermaidGenerator.buildDiagram(listOf(table("orders", comment = "주문 테이블")))
        assertTrue("Expected Mermaid comment line", result.contains("%% 주문 테이블"))
    }

    fun testTableWithoutCommentHasNoCommentLine() {
        val result = MermaidGenerator.buildDiagram(listOf(table("orders", comment = null)))
        assertFalse("Unexpected %% comment line", result.contains("%%"))
    }

    fun testSinglePrimaryKeyIsMarked() {
        val t = table(
            "users",
            columns = listOf(col("id", "bigint", isPk = true), col("name", "varchar"))
        )
        val result = MermaidGenerator.buildDiagram(listOf(t))
        assertTrue(result.contains("bigint id PK"))
        assertFalse("Non-PK column must not be marked PK", result.contains("varchar name PK"))
    }

    fun testCompositePrimaryKeyBothColumnsMarked() {
        val t = table(
            "order_items",
            columns = listOf(
                col("order_id", "bigint", isPk = true),
                col("item_id", "bigint", isPk = true),
                col("quantity", "int")
            )
        )
        val result = MermaidGenerator.buildDiagram(listOf(t))
        assertTrue(result.contains("bigint order_id PK"))
        assertTrue(result.contains("bigint item_id PK"))
        assertFalse(result.contains("int quantity PK"))
    }

    fun testForeignKeyRelationLineIsEmitted() {
        val orders = table("orders", relations = listOf(relation("fk_orders_users", "users", "orders")))
        val users = table("users")
        val result = MermaidGenerator.buildDiagram(listOf(orders, users))
        assertTrue(result.contains("    users ||--o{ orders : \"fk_orders_users\""))
    }

    fun testBlankForeignKeyNameUsesEmptyQuotes() {
        val orders = table("orders", relations = listOf(relation("", "users", "orders")))
        val result = MermaidGenerator.buildDiagram(listOf(orders, table("users")))
        assertTrue(result.contains("    users ||--o{ orders : \"\""))
    }

    fun testEntityNamesWithSpacesAreQuoted() {
        val t = table("order items", columns = listOf(col("item id", "bigint")))
        val result = MermaidGenerator.buildDiagram(listOf(t))
        assertTrue(result.contains("    \"order items\" {"))
        assertTrue(result.contains("bigint item_id"))
    }

    fun testDetailedModeEmitsColumnReferenceComments() {
        val ordersIdentity = identity("orders")
        val usersIdentity = identity("users")
        val orders = TableSpec(
            identity = ordersIdentity,
            comment = null,
            columns = emptyList(),
            relations = listOf(
                RelationSpec(
                    childTable = ordersIdentity,
                    parentTable = usersIdentity,
                    name = "fk_orders_users",
                    childColumns = listOf("user_id"),
                    parentColumns = listOf("id"),
                )
            )
        )
        val users = table("users")
        val result = MermaidGenerator.buildDiagram(
            listOf(orders, users),
            MermaidGenerator.MermaidRenderOptions(includeColumnReferences = true)
        )
        assertTrue(result.contains("%% FK: orders.user_id -> users.id"))
    }

    fun testDuplicateNamesAcrossSchemasRenderDistinctEntitiesAndExactEndpoint() {
        val salesOrders = identity("orders", schema = "sales")
        val archiveOrders = identity("orders", schema = "archive")
        val users = identity("users", schema = "sales")
        val result = MermaidGenerator.buildDiagram(
            listOf(
                TableSpec(
                    identity = salesOrders,
                    comment = null,
                    columns = emptyList(),
                    relations = listOf(
                        RelationSpec(
                            childTable = salesOrders,
                            parentTable = users,
                            name = "fk_sales_orders_users",
                            childColumns = listOf("user_id"),
                            parentColumns = listOf("id"),
                        )
                    ),
                ),
                TableSpec(archiveOrders, null, emptyList(), emptyList()),
                TableSpec(users, null, emptyList(), emptyList()),
            )
        )

        val expected = """
            erDiagram
                "sales.orders" {
                }

                "archive.orders" {
                }

                users {
                }

                users ||--o{ "sales.orders" : "fk_sales_orders_users"
        """.trimIndent() + "\n"
        assertEquals(expected, result)
    }

    fun testDetailedReferenceUsesQualifiedEndpoint() {
        val salesOrders = identity("orders", schema = "sales")
        val archiveOrders = identity("orders", schema = "archive")
        val users = identity("users", schema = "sales")
        val result = MermaidGenerator.buildDiagram(
            listOf(
                TableSpec(
                    identity = salesOrders,
                    comment = null,
                    columns = emptyList(),
                    relations = listOf(
                        RelationSpec(
                            childTable = salesOrders,
                            parentTable = users,
                            name = "fk_sales_orders_users",
                            childColumns = listOf("user_id"),
                            parentColumns = listOf("id"),
                        )
                    ),
                ),
                TableSpec(archiveOrders, null, emptyList(), emptyList()),
                TableSpec(users, null, emptyList(), emptyList()),
            ),
            MermaidGenerator.MermaidRenderOptions(includeColumnReferences = true),
        )

        val expected = """
            erDiagram
                "sales.orders" {
                }

                "archive.orders" {
                }

                users {
                }

            %% FK: sales.orders.user_id -> users.id
                users ||--o{ "sales.orders" : "fk_sales_orders_users"
        """.trimIndent() + "\n"
        assertEquals(expected, result)
    }

    fun testSingleSchemaOutputRemainsUnqualifiedAndByteStable() {
        val orders = identity("orders", schema = "sales")
        val users = identity("users", schema = "sales")
        val specs = listOf(
            TableSpec(
                identity = orders,
                comment = null,
                columns = listOf(col("id", "bigint", isPk = true)),
                relations = listOf(RelationSpec(orders, users, "fk_orders_users")),
            ),
            TableSpec(users, null, emptyList(), emptyList()),
        )

        val expected = """
            erDiagram
                orders {
                    bigint id PK
                }

                users {
                }

                users ||--o{ orders : "fk_orders_users"
        """.trimIndent() + "\n"
        assertEquals(expected, MermaidGenerator.buildDiagram(specs))
        assertEquals(expected, MermaidGenerator.buildDiagram(specs))
    }

    fun testCatalogQualificationIsUsedOnlyWhenSchemaCannotDisambiguate() {
        val tenantAOrders = identity("orders", schema = "sales", catalog = "tenant_a")
        val tenantBOrders = identity("orders", schema = "sales", catalog = "tenant_b")
        val result = MermaidGenerator.buildDiagram(
            listOf(
                TableSpec(tenantAOrders, null, emptyList(), emptyList()),
                TableSpec(tenantBOrders, null, emptyList(), emptyList()),
            )
        )

        val expected = """
            erDiagram
                "tenant_a.sales.orders" {
                }

                "tenant_b.sales.orders" {
                }

        """.trimIndent() + "\n"
        assertEquals(expected, result)
    }

    fun testSchemaQualificationOmitsCatalogWhenSchemaAlreadyDisambiguates() {
        val salesOrders = identity("orders", schema = "sales", catalog = "primary")
        val archiveOrders = identity("orders", schema = "archive", catalog = "history")
        val result = MermaidGenerator.buildDiagram(
            listOf(
                TableSpec(salesOrders, null, emptyList(), emptyList()),
                TableSpec(archiveOrders, null, emptyList(), emptyList()),
            )
        )

        val expected = """
            erDiagram
                "sales.orders" {
                }

                "archive.orders" {
                }

        """.trimIndent() + "\n"
        assertEquals(expected, result)
    }

    fun testRenderedNameCollisionFailsClosedBeforeDiagramEmission() {
        val qualifiedOrders = identity("orders", schema = "sales")
        val archiveOrders = identity("orders", schema = "archive")
        val literalQualifiedName = identity("sales.orders")

        try {
            MermaidGenerator.buildDiagram(
                listOf(
                    TableSpec(qualifiedOrders, null, emptyList(), emptyList()),
                    TableSpec(archiveOrders, null, emptyList(), emptyList()),
                    TableSpec(literalQualifiedName, null, emptyList(), emptyList()),
                )
            )
            fail("Expected rendered-name collision to fail closed")
        } catch (ex: IllegalArgumentException) {
            assertEquals("Ambiguous rendered table identity: sales.orders", ex.message)
        }
    }

    fun testInputOrderProducesDeterministicExactOutput() {
        val archiveOrders = identity("orders", schema = "archive")
        val salesOrders = identity("orders", schema = "sales")
        val specs = listOf(
            TableSpec(archiveOrders, null, emptyList(), emptyList()),
            TableSpec(salesOrders, null, emptyList(), emptyList()),
        )
        val expected = """
            erDiagram
                "archive.orders" {
                }

                "sales.orders" {
                }

        """.trimIndent() + "\n"

        assertEquals(expected, MermaidGenerator.buildDiagram(specs))
        assertEquals(expected, MermaidGenerator.buildDiagram(specs))
    }

    fun testTypeWhitespaceReplacedWithUnderscore() {
        val t = table("items", columns = listOf(col("price", "double precision")))
        val result = MermaidGenerator.buildDiagram(listOf(t))
        assertTrue(result.contains("double_precision price"))
        assertFalse("Original whitespace type must not appear", result.contains("double precision"))
    }

    fun testLengthIsAppendedForSizedCharacterTypes() {
        val rendered = MermaidGenerator.renderColumnType("varchar", LengthType(255))
        assertEquals("varchar(255)", rendered)
    }

    fun testPrecisionAndScaleAreAppendedForNumericTypes() {
        val rendered = MermaidGenerator.renderColumnType("decimal", PrecisionScaleType(10, 2))
        assertEquals("decimal(10_2)", rendered)
    }

    fun testPrecisionIsAppendedForTimestampTypes() {
        val rendered = MermaidGenerator.renderColumnType("timestamp", PrecisionOnlyType(6))
        assertEquals("timestamp(6)", rendered)
    }

    fun testUnsizedTypesDoNotPickUpLengthSuffix() {
        val rendered = MermaidGenerator.renderColumnType("int", LengthType(11))
        assertEquals("int", rendered)
    }

    fun testColumnCommentDoubleQuoteReplacedWithSingleQuote() {
        val t = table("logs", columns = listOf(col("msg", "varchar", comment = "user \"input\" field")))
        val result = MermaidGenerator.buildDiagram(listOf(t))
        assertTrue(result.contains("\"user 'input' field\""))
        assertFalse("Embedded double-quotes in comment must be sanitised", result.contains("user \"input\" field"))
    }

    fun testColumnCommentParenthesesAreSanitizedForMermaidSafety() {
        val t = table("logs", columns = listOf(col("msg", "varchar", comment = "state (ready)")))
        val result = MermaidGenerator.buildDiagram(listOf(t))
        assertTrue(result.contains("\"state （ready）\""))
    }
}
