package com.algorist.erdmaid.generator

import com.algorist.erdmaid.generator.RelationResolver.RelationSpec
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RelationResolverTest : BasePlatformTestCase() {

    fun testDuplicateRelationsAreCollapsed() {
        val selectedTables = setOf("orders", "users")
        val relations = listOf(
            RelationSpec("orders", "users", "fk_orders_users", emptyList(), emptyList()),
            RelationSpec("orders", "users", "fk_orders_users", emptyList(), emptyList()),
        )

        val normalized = RelationResolver.normalize(relations, selectedTables)

        assertEquals(1, normalized.size)
        assertEquals("fk_orders_users", normalized.single().name)
    }

    fun testRelationsOutsideSelectionAreDropped() {
        val selectedTables = setOf("orders", "users")
        val relations = listOf(
            RelationSpec("orders", "users", "fk_orders_users", emptyList(), emptyList()),
            RelationSpec("orders", "audit_logs", "fk_orders_audit_logs", emptyList(), emptyList()),
        )

        val normalized = RelationResolver.normalize(relations, selectedTables)

        assertEquals(1, normalized.size)
        assertEquals("users", normalized.single().parentTableName)
    }

    fun testDistinctRelationsBetweenSameTablesAreKept() {
        val selectedTables = setOf("orders", "users")
        val relations = listOf(
            RelationSpec("orders", "users", "fk_orders_created_by", listOf("created_by"), listOf("id")),
            RelationSpec("orders", "users", "fk_orders_updated_by", listOf("updated_by"), listOf("id")),
        )

        val normalized = RelationResolver.normalize(relations, selectedTables)

        assertEquals(2, normalized.size)
    }

    fun testSameColumnsPreferNonBlankRelationName() {
        val selectedTables = setOf("orders", "users")
        val relations = listOf(
            RelationSpec("orders", "users", "", listOf("user_id"), listOf("id")),
            RelationSpec("orders", "users", "fk_orders_users", listOf("user_id"), listOf("id")),
        )

        val normalized = RelationResolver.normalize(relations, selectedTables)

        assertEquals(1, normalized.size)
        assertEquals("fk_orders_users", normalized.single().name)
    }
}
