package com.algorist.erdmaid.generator

import com.algorist.erdmaid.generator.MermaidGenerator.ColumnSpec
import com.algorist.erdmaid.generator.RelationResolver.RelationSpec
import com.algorist.erdmaid.generator.MermaidGenerator.TableSpec
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

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun col(
        name: String,
        type: String,
        isPk: Boolean = false,
        comment: String? = null
    ) = ColumnSpec(name, type, isPk, comment)

    private fun relation(name: String, parentTable: String, childTable: String) =
        RelationSpec(childTable, parentTable, name, emptyList(), emptyList())

    private fun table(
        name: String,
        comment: String? = null,
        columns: List<ColumnSpec> = emptyList(),
        relations: List<RelationSpec> = emptyList()
    ) = TableSpec(name, comment, columns, relations)

    // ── empty input ───────────────────────────────────────────────────────────

    fun testEmptyTableListProducesHeader() {
        val result = MermaidGenerator.buildDiagram(emptyList())
        assertEquals("erDiagram\n", result)
    }

    // ── table comment ─────────────────────────────────────────────────────────

    fun testTableWithCommentEmitsMermaidCommentLine() {
        val result = MermaidGenerator.buildDiagram(listOf(table("orders", comment = "주문 테이블")))
        assertTrue("Expected Mermaid comment line", result.contains("%% 주문 테이블"))
    }

    fun testTableWithoutCommentHasNoCommentLine() {
        val result = MermaidGenerator.buildDiagram(listOf(table("orders", comment = null)))
        assertFalse("Unexpected %% comment line", result.contains("%%"))
    }

    // ── primary key ───────────────────────────────────────────────────────────

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

    // ── foreign key relation ──────────────────────────────────────────────────

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

    // ── type normalisation ────────────────────────────────────────────────────

    fun testTypeWhitespaceReplacedWithUnderscore() {
        val t = table("items", columns = listOf(col("price", "double precision")))
        val result = MermaidGenerator.buildDiagram(listOf(t))
        assertTrue(result.contains("double_precision price"))
        assertFalse("Original whitespace type must not appear", result.contains("double precision"))
    }

    // ── comment quote sanitisation ────────────────────────────────────────────

    fun testColumnCommentDoubleQuoteReplacedWithSingleQuote() {
        val t = table("logs", columns = listOf(col("msg", "varchar", comment = "user \"input\" field")))
        val result = MermaidGenerator.buildDiagram(listOf(t))
        // Sanitized form: surrounding double-quotes intact, embedded double-quotes → single-quotes
        assertTrue(result.contains("\"user 'input' field\""))
        // The raw comment text with embedded double-quotes must not appear verbatim in the output
        assertFalse("Embedded double-quotes in comment must be sanitised", result.contains("user \"input\" field"))
    }
}
