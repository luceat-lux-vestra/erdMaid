package com.algorist.erdmaid.generator

import com.algorist.erdmaid.generator.MermaidGenerator.ColumnSpec
import com.algorist.erdmaid.generator.MermaidGenerator.TableSpec
import com.algorist.erdmaid.generator.MermaidSanitizer.Context
import com.algorist.erdmaid.generator.RelationResolver.RelationSpec
import com.algorist.erdmaid.generator.RelationResolver.TableIdentity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MermaidSanitizationTest : BasePlatformTestCase() {

    private fun identity(name: String, schema: String? = null, catalog: String? = null) =
        TableIdentity(catalog = catalog, schema = schema, name = name)

    private fun col(
        name: String,
        type: String,
        isPk: Boolean = false,
        comment: String? = null,
    ) = ColumnSpec(name, type, isPk, comment)

    private fun table(
        name: String,
        comment: String? = null,
        columns: List<ColumnSpec> = emptyList(),
        relations: List<RelationSpec> = emptyList(),
    ) = TableSpec(identity(name), comment, columns, relations)

    fun testSanitizationIsIdempotentForEveryContext() {
        val hostile = "quote \" percent %% slash \\ cr\r lf\n crlf\r\n tab\t back\b vertical\u000B nul\u0000 (x)"

        for (context in Context.values()) {
            val once = MermaidSanitizer.sanitize(hostile, context)
            val twice = MermaidSanitizer.sanitize(once, context)
            assertEquals("Sanitizer must be idempotent for $context", once, twice)
            assertFalse("CR must not survive $context", once.contains('\r'))
            assertFalse("LF must not survive $context", once.contains('\n'))
            assertFalse("ASCII percent must not survive $context", once.contains('%'))
            assertFalse("Double quote must not survive $context", once.contains('"'))
            assertFalse("Backslash must not survive $context", once.contains('\\'))
        }
    }

    fun testCrLfAndCrLfPairBecomeOneVisibleMarkerEach() {
        val sanitized = MermaidSanitizer.sanitize(
            "CR\rLF\nCRLF\r\nEND",
            Context.LINE_COMMENT,
        )
        assertEquals("CR␤LF␤CRLF␤END", sanitized)
    }

    fun testAttributeTokenNeutralizesStructureWithoutDroppingContent() {
        val sanitized = MermaidSanitizer.sanitize(
            "9{a}|:<>\"%\\ name",
            Context.ATTRIBUTE_TOKEN,
        )
        assertEquals("_9｛a｝｜：＜＞＇％＼_name", sanitized)
    }

    fun testAttributeTokenPreservesUnsupportedNonAsciiAsCodeUnit() {
        val sanitized = MermaidSanitizer.sanitize(
            "amount\u00A0€",
            Context.ATTRIBUTE_TOKEN,
        )
        assertEquals("amount_u00A0_€", sanitized)
        assertEquals(sanitized, MermaidSanitizer.sanitize(sanitized, Context.ATTRIBUTE_TOKEN))
    }

    fun testAttributeKeyKeywordsAreNeutralizedAsTokens() {
        val result = MermaidGenerator.buildDiagram(
            listOf(
                table(
                    "keys",
                    columns = listOf(col(name = "FK", type = "PK", isPk = true)),
                )
            )
        )

        val expected =
            "erDiagram\n" +
                "    keys {\n" +
                "        _PK _FK PK\n" +
                "    }\n\n"
        assertEquals(expected, result)
    }

    fun testReservedEntityKeywordIsQuotedAtBlockAndRelationEndpoint() {
        val orders = identity("orders")
        val style = identity("StYlE")
        val result = MermaidGenerator.buildDiagram(
            listOf(
                TableSpec(
                    identity = orders,
                    comment = null,
                    columns = emptyList(),
                    relations = listOf(RelationSpec(orders, style, "fk_orders_style")),
                ),
                TableSpec(style, null, emptyList(), emptyList()),
            )
        )

        val expected =
            "erDiagram\n" +
                "    orders {\n" +
                "    }\n\n" +
                "    \"StYlE\" {\n" +
                "    }\n\n" +
                "    \"StYlE\" ||--o{ orders : \"fk_orders_style\"\n"
        assertEquals(expected, result)
    }

    fun testMultilineTableCommentCannotInjectAnotherStatement() {
        val result = MermaidGenerator.buildDiagram(
            listOf(table("orders", comment = "first\r\nEVIL ||--o{ TARGET : injected\nlast"))
        )

        val expected =
            "erDiagram\n" +
                "%% first␤EVIL ||--o{ TARGET : injected␤last\n" +
                "    orders {\n" +
                "    }\n\n"
        assertEquals(expected, result)
    }

    fun testHostileColumnTokenAndCommentAreContextSanitized() {
        val result = MermaidGenerator.buildDiagram(
            listOf(
                table(
                    "logs",
                    columns = listOf(
                        col(
                            name = "co}l name",
                            type = "va{r|char:<>",
                            comment = "line1\rline2 %% \"quoted\" {|:<> (ready)",
                        )
                    ),
                )
            )
        )

        val expected =
            "erDiagram\n" +
                "    logs {\n" +
                "        va｛r｜char：＜＞ co｝l_name \"line1␤line2 ％％ 'quoted' {|:<> （ready）\"\n" +
                "    }\n\n"
        assertEquals(expected, result)
    }

    fun testRelationLabelCannotEscapeItsQuotedLine() {
        val orders = identity("orders")
        val users = identity("users")
        val result = MermaidGenerator.buildDiagram(
            listOf(
                TableSpec(
                    identity = orders,
                    comment = null,
                    columns = emptyList(),
                    relations = listOf(
                        RelationSpec(
                            childTable = orders,
                            parentTable = users,
                            name = "fk \"quoted\"\n%% : {|<>",
                        )
                    ),
                ),
                TableSpec(users, null, emptyList(), emptyList()),
            )
        )

        val expected =
            "erDiagram\n" +
                "    orders {\n" +
                "    }\n\n" +
                "    users {\n" +
                "    }\n\n" +
                "    users ||--o{ orders : \"fk 'quoted'␤％％ : {|<>\"\n"
        assertEquals(expected, result)
    }

    fun testDetailedForeignKeyCommentNeutralizesEndpointMetadata() {
        val orders = identity("orders")
        val users = identity("users")
        val result = MermaidGenerator.buildDiagram(
            listOf(
                TableSpec(
                    identity = orders,
                    comment = null,
                    columns = emptyList(),
                    relations = listOf(
                        RelationSpec(
                            childTable = orders,
                            parentTable = users,
                            name = "fk_orders_users",
                            childColumns = listOf("user_id\nEVIL %%"),
                            parentColumns = listOf("id\rnext"),
                        )
                    ),
                ),
                TableSpec(users, null, emptyList(), emptyList()),
            ),
            MermaidGenerator.MermaidRenderOptions(includeColumnReferences = true),
        )

        val expected =
            "erDiagram\n" +
                "    orders {\n" +
                "    }\n\n" +
                "    users {\n" +
                "    }\n\n" +
                "%% FK: orders.user_id␤EVIL ％％ -> users.id␤next\n" +
                "    users ||--o{ orders : \"fk_orders_users\"\n"
        assertEquals(expected, result)
    }

    fun testEntityNameNeutralizesPercentBackslashAndNewline() {
        val result = MermaidGenerator.buildDiagram(
            listOf(table("ord%ers\\archive\nnext"))
        )

        val expected =
            "erDiagram\n" +
                "    \"ord％ers＼archive␤next\" {\n" +
                "    }\n\n"
        assertEquals(expected, result)
    }

    fun testEntityCollisionIntroducedBySanitizationFailsClosed() {
        try {
            MermaidGenerator.buildDiagram(
                listOf(
                    table("orders%"),
                    table("orders％"),
                )
            )
            fail("Expected sanitized entity-name collision to fail closed")
        } catch (ex: IllegalArgumentException) {
            assertEquals("Ambiguous rendered table identity: orders％", ex.message)
        }
    }

    fun testColumnCollisionIntroducedByNormalizationFailsClosed() {
        try {
            MermaidGenerator.buildDiagram(
                listOf(
                    table(
                        "orders",
                        columns = listOf(
                            col("line item", "varchar"),
                            col("line_item", "varchar"),
                        ),
                    )
                )
            )
            fail("Expected sanitized column-name collision to fail closed")
        } catch (ex: IllegalArgumentException) {
            assertEquals("Ambiguous rendered column identity in orders: line_item", ex.message)
        }
    }

    fun testOrdinaryUnicodeMetadataRemainsReadableAndDeterministic() {
        val specs = listOf(
            table(
                "주문",
                comment = "주문 테이블",
                columns = listOf(col("상태", "varchar", comment = "결제 완료")),
            )
        )
        val expected =
            "erDiagram\n" +
                "%% 주문 테이블\n" +
                "    \"주문\" {\n" +
                "        varchar 상태 \"결제 완료\"\n" +
                "    }\n\n"

        assertEquals(expected, MermaidGenerator.buildDiagram(specs))
        assertEquals(expected, MermaidGenerator.buildDiagram(specs))
    }
}
