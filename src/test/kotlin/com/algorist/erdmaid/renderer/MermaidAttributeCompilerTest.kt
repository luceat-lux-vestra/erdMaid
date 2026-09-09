package com.algorist.erdmaid.renderer

import com.algorist.erdmaid.core.ColumnId
import com.algorist.erdmaid.core.ColumnSnapshot
import com.algorist.erdmaid.core.CoreDiagnostic
import com.algorist.erdmaid.core.Evidence
import com.algorist.erdmaid.core.ExportOutcome
import com.algorist.erdmaid.core.FrozenList
import com.algorist.erdmaid.core.OptionalValue
import com.algorist.erdmaid.core.OriginId
import com.algorist.erdmaid.core.PrimaryKeyFact
import com.algorist.erdmaid.core.RawTypeMetadata
import com.algorist.erdmaid.core.SchemaSnapshot
import com.algorist.erdmaid.core.TableId
import com.algorist.erdmaid.core.TableSnapshot
import com.algorist.erdmaid.core.frozenListOf
import com.algorist.erdmaid.semantic.ErdGraph
import com.algorist.erdmaid.semantic.ErdGraphCompiler
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MermaidAttributeCompilerTest {
    private val origin = OriginId("ds-a")

    @Test
    fun `source column order is preserved exactly`() {
        val id = tableId("Users")
        val table = table(
            id,
            listOf(
                column(id, "z_col", 0),
                column(id, "a_col", 4),
                column(id, "middle", 9),
            ),
        )

        val tokens = completeAttributes(graph(table)).entities.single().columns

        assertEquals(listOf("z_col", "a_col", "middle"), tokens.map { it.columnId.name })
        assertEquals(listOf("c_z_u005F_col", "c_a_u005F_col", "c_middle"), tokens.map { it.name })
    }

    @Test
    fun `required raw type unavailable degrades with no attribute payload`() {
        val id = tableId("Users")
        val column = ColumnSnapshot(
            id = ColumnId(id, "id"),
            sourcePosition = 0,
            rawType = Evidence.Unavailable(CoreDiagnostic("host-type-read-failed")),
            nullable = known(false),
            comment = OptionalValue.Absent,
        )

        val outcome = MermaidAttributeCompiler.compile(graph(table(id, listOf(column))))

        assertTrue(outcome is ExportOutcome.Degraded)
        outcome as ExportOutcome.Degraded
        assertEquals("mermaid-column-type-unavailable", outcome.diagnostics.values.single().code)
        assertEquals("id:host-type-read-failed", outcome.diagnostics.values.single().detail)
    }

    @Test
    fun `type details preserve present absent and fixed L P S order without dialect inference`() {
        val all = rawType(
            name = "number_raw",
            length = OptionalValue.Present(12),
            precision = OptionalValue.Present(10),
            scale = OptionalValue.Present(-2),
        )
        val onlyPrecision = rawType(name = "varchar", precision = OptionalValue.Present(7))

        assertEquals(
            ExportOutcome.Complete("t_number_u005F_raw__L12__P10__S-2"),
            MermaidAttributeCompiler.encodeType(all),
        )
        assertEquals(
            ExportOutcome.Complete("t_varchar__P7"),
            MermaidAttributeCompiler.encodeType(onlyPrecision),
        )
    }

    @Test
    fun `unavailable optional type detail degrades instead of becoming absent or zero`() {
        val raw = rawType(
            name = "varchar",
            length = OptionalValue.Unavailable(CoreDiagnostic("length-read-failed")),
        )

        val outcome = MermaidAttributeCompiler.encodeType(raw)

        assertTrue(outcome is ExportOutcome.Degraded)
        outcome as ExportOutcome.Degraded
        assertEquals("mermaid-type-detail-unavailable", outcome.diagnostics.values.single().code)
        assertEquals("length:length-read-failed", outcome.diagnostics.values.single().detail)
    }

    @Test
    fun `hostile raw type text is encoded into Mermaid attribute grammar`() {
        val outcome = MermaidAttributeCompiler.encodeType(rawType("n um_%{}\"\\\r\n"))
        assertTrue(outcome is ExportOutcome.Complete)
        val token = (outcome as ExportOutcome.Complete).value

        assertEquals(
            "t_n_u0020_um_u005F__u0025__u007B__u007D__u0022__u005C__u000D__u000A_",
            token,
        )
        assertTrue(token.first().isLetter())
        assertTrue(token.none { it == ' ' || it == '\r' || it == '\n' || it == '%' || it == '"' || it == '\\' })
    }

    @Test
    fun `authoritative composite primary key marks exactly its canonical columns`() {
        val id = tableId("OrderLine")
        val orderId = column(id, "order_id", 0)
        val lineNo = column(id, "line_no", 1)
        val note = column(id, "note", 2)
        val table = table(
            id = id,
            columns = listOf(orderId, lineNo, note),
            primaryKey = OptionalValue.Present(
                PrimaryKeyFact(
                    name = OptionalValue.Present("pk_order_line"),
                    columns = frozenListOf(orderId.id, lineNo.id),
                )
            ),
        )

        val tokens = completeAttributes(graph(table)).entities.single().columns

        assertEquals(listOf(true, true, false), tokens.map { it.primaryKey })
    }

    @Test
    fun `authoritative absent primary key marks none while unavailable primary key degrades`() {
        val id = tableId("NoPk")
        val absent = table(id, listOf(column(id, "id", 0)))
        assertEquals(
            listOf(false),
            completeAttributes(graph(absent)).entities.single().columns.map { it.primaryKey },
        )

        val unavailable = absent.copy(
            primaryKey = OptionalValue.Unavailable(CoreDiagnostic("pk-read-failed"))
        )
        val outcome = MermaidAttributeCompiler.compile(graph(unavailable))
        assertTrue(outcome is ExportOutcome.Degraded)
        outcome as ExportOutcome.Degraded
        assertEquals("mermaid-primary-key-unavailable", outcome.diagnostics.values.single().code)
    }

    @Test
    fun `present empty comment remains distinct from absent and unavailable`() {
        val id = tableId("Users")
        val present = table(id, listOf(column(id, "id", 0, comment = OptionalValue.Present(""))))
        val absent = table(id, listOf(column(id, "id", 0, comment = OptionalValue.Absent)))
        val unavailable = table(
            id,
            listOf(
                column(
                    id,
                    "id",
                    0,
                    comment = OptionalValue.Unavailable(CoreDiagnostic("comment-read-failed")),
                )
            ),
        )

        assertEquals("", completeAttributes(graph(present)).entities.single().columns.single().comment)
        assertNull(completeAttributes(graph(absent)).entities.single().columns.single().comment)

        val outcome = MermaidAttributeCompiler.compile(graph(unavailable))
        assertTrue(outcome is ExportOutcome.Degraded)
        outcome as ExportOutcome.Degraded
        assertEquals("mermaid-column-comment-unavailable", outcome.diagnostics.values.single().code)
    }

    @Test
    fun `comment escape is injective and never emits Mermaid tilde word syntax`() {
        assertEquals(
            "safe_u005F__u007E__u0022_",
            MermaidAttributeCompiler.encodeComment("safe_~\""),
        )
        assertEquals(
            "_u005F_u0022_u005F_",
            MermaidAttributeCompiler.encodeComment("_u0022_"),
        )
        assertNotEquals(
            MermaidAttributeCompiler.encodeComment("_u0022_"),
            MermaidAttributeCompiler.encodeComment("\""),
        )
        assertTrue('~' !in MermaidAttributeCompiler.encodeComment("~unsafe~"))
    }

    @Test
    fun `hostile column name and comment cannot create Mermaid structure while Unicode stays readable`() {
        val id = tableId("Users")
        val hostileName = "사용자 id\r\n%%{}[]|:<>_"
        val hostileComment = "설명\r\n%%\"\\{}[]|:<> "
        val table = table(
            id,
            listOf(
                column(
                    id,
                    hostileName,
                    0,
                    comment = OptionalValue.Present(hostileComment),
                )
            ),
        )

        val token = completeAttributes(graph(table)).entities.single().columns.single()

        assertEquals(
            "c_사용자_u0020_id_u000D__u000A__u0025__u0025__u007B__u007D_[]" +
                "_u007C__u003A__u003C__u003E__u005F_",
            token.name,
        )
        assertEquals(
            "설명_u000D__u000A__u0025__u0025__u0022__u005C__u007B__u007D__u005B__u005D_" +
                "_u007C__u003A__u003C__u003E__u0001__u2028_",
            token.comment,
        )
        assertTrue(token.name.none { it == '\r' || it == '\n' || it == '%' || it == '|' || it == ':' || it == '<' || it == '>' })
        assertTrue(token.comment!!.startsWith("설명"))
        assertTrue("\r" !in token.comment!! && "\n" !in token.comment!! && "%%" !in token.comment!! && "\"" !in token.comment!!)
        assertTrue("{" !in token.comment!! && "}" !in token.comment!! && "|" !in token.comment!! && "~" !in token.comment!!)
    }

    @Test
    fun `safe Unicode and exact case stay distinct and escape form is injective`() {
        assertEquals("c_사용자", MermaidAttributeCompiler.encodeAttributeIdentifier('c', "사용자"))
        assertNotEquals(
            MermaidAttributeCompiler.encodeAttributeIdentifier('c', "Users"),
            MermaidAttributeCompiler.encodeAttributeIdentifier('c', "users"),
        )
        assertNotEquals(
            MermaidAttributeCompiler.encodeAttributeIdentifier('c', "_u0041_"),
            MermaidAttributeCompiler.encodeAttributeIdentifier('c', "A"),
        )
    }

    @Test
    fun `defensive post encoding collision detector identifies ambiguous tokens`() {
        val id = tableId("Users")
        val first = attribute(ColumnId(id, "first"), renderedName = "c_same")
        val second = attribute(ColumnId(id, "second"), renderedName = "c_same")

        assertEquals(
            "c_same",
            MermaidAttributeCompiler.firstColumnNameCollision(listOf(first, second)),
        )
    }

    @Test
    fun `locale change leaves exact attribute token set unchanged`() {
        val id = tableId("LocaleCase")
        val table = table(
            id,
            listOf(
                column(id, "I_column", 0, type = rawType("INTEGER")),
                column(id, "ı_column", 1, type = rawType("tür")),
            ),
        )
        val original = Locale.getDefault()
        try {
            val left = completeAttributes(graph(table))
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val right = completeAttributes(graph(table))
            assertEquals(left, right)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `rendering does not mutate canonical column type comment or key facts`() {
        val id = tableId("Users")
        val originalColumn = column(
            id = id,
            name = " id ",
            position = 0,
            type = rawType(" raw type ", length = OptionalValue.Present(4)),
            comment = OptionalValue.Present(" comment "),
        )
        val original = table(
            id = id,
            columns = listOf(originalColumn),
            primaryKey = OptionalValue.Present(
                PrimaryKeyFact(OptionalValue.Absent, frozenListOf(originalColumn.id))
            ),
        )
        val before = original.copy()

        completeAttributes(graph(original))

        assertEquals(before, original)
    }

    @Test
    fun `renderer attribute type surface contains no IntelliJ reflection or legacy generator dependency`() {
        val classes = listOf(
            MermaidAttributeToken::class.java,
            MermaidEntityAttributeTokens::class.java,
            MermaidAttributeTokenSet::class.java,
            MermaidAttributeCompiler::class.java,
        )
        val referenced = classes.flatMap { type ->
            buildList {
                addAll(type.declaredFields.map { it.type })
                addAll(type.declaredMethods.map { it.returnType })
                addAll(type.declaredMethods.flatMap { method -> method.parameterTypes.toList() })
            }
        }

        assertTrue(referenced.none { it.name.startsWith("com.intellij.") })
        assertTrue(referenced.none { it.name.contains("generator", ignoreCase = true) })
        assertTrue(referenced.none { it.name.startsWith("java.lang.reflect.") })
    }

    private fun completeAttributes(graph: ErdGraph): MermaidAttributeTokenSet {
        val outcome = MermaidAttributeCompiler.compile(graph)
        assertTrue(outcome is ExportOutcome.Complete)
        return (outcome as ExportOutcome.Complete).value
    }

    private fun graph(table: TableSnapshot): ErdGraph {
        val outcome = ErdGraphCompiler.compile(
            SchemaSnapshot(origin = origin, tables = frozenListOf(table))
        )
        assertTrue(outcome is ExportOutcome.Complete)
        return (outcome as ExportOutcome.Complete).value
    }

    private fun table(
        id: TableId,
        columns: List<ColumnSnapshot>,
        primaryKey: OptionalValue<PrimaryKeyFact> = OptionalValue.Absent,
    ): TableSnapshot = TableSnapshot(
        id = id,
        comment = OptionalValue.Absent,
        columns = FrozenList.copyOf(columns),
        primaryKey = primaryKey,
        uniqueKeys = known(frozenListOf()),
        foreignKeys = known(frozenListOf()),
    )

    private fun tableId(name: String): TableId =
        TableId(origin = origin, catalog = null, schema = null, name = name)

    private fun column(
        id: TableId,
        name: String,
        position: Int,
        type: RawTypeMetadata = rawType("text"),
        comment: OptionalValue<String> = OptionalValue.Absent,
    ): ColumnSnapshot = ColumnSnapshot(
        id = ColumnId(id, name),
        sourcePosition = position,
        rawType = known(type),
        nullable = known(false),
        comment = comment,
    )

    private fun rawType(
        name: String,
        length: OptionalValue<Int> = OptionalValue.Absent,
        precision: OptionalValue<Int> = OptionalValue.Absent,
        scale: OptionalValue<Int> = OptionalValue.Absent,
    ): RawTypeMetadata = RawTypeMetadata(name, length, precision, scale)

    private fun attribute(columnId: ColumnId, renderedName: String): MermaidAttributeToken =
        MermaidAttributeToken(
            columnId = columnId,
            type = "t_text",
            name = renderedName,
            primaryKey = false,
            comment = null,
        )

    private fun <T> known(value: T): Evidence<T> = Evidence.Known(value)
}
