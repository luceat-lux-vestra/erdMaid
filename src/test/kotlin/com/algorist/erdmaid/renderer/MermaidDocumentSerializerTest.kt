package com.algorist.erdmaid.renderer

import com.algorist.erdmaid.core.ColumnId
import com.algorist.erdmaid.core.CoreDiagnostic
import com.algorist.erdmaid.core.Evidence
import com.algorist.erdmaid.core.ExportOutcome
import com.algorist.erdmaid.core.FrozenList
import com.algorist.erdmaid.core.OptionalValue
import com.algorist.erdmaid.core.OriginId
import com.algorist.erdmaid.core.PrimaryKeyFact
import com.algorist.erdmaid.core.RawTypeMetadata
import com.algorist.erdmaid.core.RelationProvenance
import com.algorist.erdmaid.core.SchemaSnapshot
import com.algorist.erdmaid.core.TableId
import com.algorist.erdmaid.core.TableSnapshot
import com.algorist.erdmaid.core.ColumnSnapshot
import com.algorist.erdmaid.core.frozenListOf
import com.algorist.erdmaid.semantic.ConstrainedSemanticRelation
import com.algorist.erdmaid.semantic.ErdGraph
import com.algorist.erdmaid.semantic.ErdGraphCompiler
import com.algorist.erdmaid.semantic.ErdGraphRelation
import com.algorist.erdmaid.semantic.ErdGraphTable
import com.algorist.erdmaid.semantic.MultiplicitySemanticRelation
import com.algorist.erdmaid.semantic.RelationConstraintEvidence
import com.algorist.erdmaid.semantic.RelationIdentification
import com.algorist.erdmaid.semantic.RelationMultiplicity
import com.algorist.erdmaid.semantic.RelationMultiplicityBounds
import com.algorist.erdmaid.semantic.RelationMultiplicityMaximum
import com.algorist.erdmaid.semantic.RelationMultiplicityMinimum
import com.algorist.erdmaid.semantic.RelationTupleNullability
import com.algorist.erdmaid.semantic.RelationTupleUniqueness
import com.algorist.erdmaid.semantic.ResolvedForeignKeyColumnMapping
import com.algorist.erdmaid.semantic.SemanticRelation
import com.algorist.erdmaid.semantic.TableQualificationIntent
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MermaidDocumentSerializerTest {
    private val origin = OriginId("o")
    private val parent = tableId("A")
    private val child = tableId("B")
    private val parentId = "e_v6F_n_n_v41"
    private val childId = "e_v6F_n_n_v42"

    @Test
    fun `ordinary physical FK serializes one exact complete document`() {
        val graph = twoTableGraph(
            relation = relation(
                name = OptionalValue.Present("fk_ab"),
                provenance = RelationProvenance.PHYSICAL,
                identification = known(RelationIdentification.NON_IDENTIFYING),
                parents = bounds(RelationMultiplicityMinimum.ONE, RelationMultiplicityMaximum.ONE),
                children = bounds(RelationMultiplicityMinimum.ZERO, RelationMultiplicityMaximum.MANY),
            ),
        )

        assertEquals(
            """erDiagram
    $parentId["A"] {
        t_INT c_id PK
    }
    $childId["B"] {
        t_INT c_parent_u005F_id
    }
    %% FK: physical $childId.parent_id -> $parentId.id
    $parentId ||..o{ $childId : "physical:fk_ab"
""",
            complete(graph),
        )
    }

    @Test
    fun `identifying and non-identifying connectors have exact full documents`() {
        val identifying = twoTableGraph(
            relation = relation(
                name = OptionalValue.Absent,
                identification = known(RelationIdentification.IDENTIFYING),
            ),
        )
        val nonIdentifying = twoTableGraph(
            relation = relation(
                name = OptionalValue.Absent,
                identification = known(RelationIdentification.NON_IDENTIFYING),
            ),
        )
        val prefix = """erDiagram
    $parentId["A"] {
        t_INT c_id PK
    }
    $childId["B"] {
        t_INT c_parent_u005F_id
    }
"""

        assertEquals(
            prefix + "    $parentId ||--o{ $childId : \"physical\"\n",
            complete(identifying, includeColumnReferences = false),
        )
        assertEquals(
            prefix + "    $parentId ||..o{ $childId : \"physical\"\n",
            complete(nonIdentifying, includeColumnReferences = false),
        )
    }

    @Test
    fun `all four multiplicities serialize exact Mermaid punctuation`() {
        val cases = listOf(
            Triple(bounds(RelationMultiplicityMinimum.ONE, RelationMultiplicityMaximum.ONE), "||", "one-one"),
            Triple(bounds(RelationMultiplicityMinimum.ZERO, RelationMultiplicityMaximum.ONE), "|o", "zero-one"),
            Triple(bounds(RelationMultiplicityMinimum.ONE, RelationMultiplicityMaximum.MANY), "}|", "one-many"),
            Triple(bounds(RelationMultiplicityMinimum.ZERO, RelationMultiplicityMaximum.MANY), "}o", "zero-many"),
        )
        for ((parentBounds, parentEnd, label) in cases) {
            val graph = twoTableGraph(
                relation = relation(
                    name = OptionalValue.Present(label),
                    parents = parentBounds,
                    children = bounds(RelationMultiplicityMinimum.ZERO, RelationMultiplicityMaximum.MANY),
                ),
            )
            val expected = entityOnlyPrefix() +
                "    $parentId ${parentEnd}..o{ $childId : \"physical:$label\"\n"
            assertEquals(expected, complete(graph, includeColumnReferences = false))
        }
    }

    @Test
    fun `duplicate names use exact schema-qualified aliases`() {
        val first = tableId("T", schema = "s1")
        val second = tableId("T", schema = "s2")
        val graph = ErdGraph(
            origin = origin,
            tables = frozenListOf(
                graphTable(emptyTable(first), TableQualificationIntent.SCHEMA),
                graphTable(emptyTable(second), TableQualificationIntent.SCHEMA),
            ),
            relations = frozenListOf(),
        )

        assertEquals(
            """erDiagram
    e_v6F_n_v7331_v54["s1.T"] {
    }
    e_v6F_n_v7332_v54["s2.T"] {
    }
""",
            complete(graph),
        )

        val firstCatalog = tableId("T", schema = "s", catalog = "c1")
        val secondCatalog = tableId("T", schema = "s", catalog = "c2")
        val catalogGraph = ErdGraph(
            origin = origin,
            tables = frozenListOf(
                graphTable(emptyTable(firstCatalog), TableQualificationIntent.CATALOG_SCHEMA),
                graphTable(emptyTable(secondCatalog), TableQualificationIntent.CATALOG_SCHEMA),
            ),
            relations = frozenListOf(),
        )
        assertEquals(
            """erDiagram
    e_v6F_v6331_v73_v54["c1.s.T"] {
    }
    e_v6F_v6332_v73_v54["c2.s.T"] {
    }
""",
            complete(catalogGraph),
        )
    }

    @Test
    fun `reserved-looking name stays presentation alias while references use generated id`() {
        val id = tableId("class")
        val graph = ErdGraph(
            origin = origin,
            tables = frozenListOf(graphTable(emptyTable(id))),
            relations = frozenListOf(),
        )

        assertEquals(
            """erDiagram
    e_v6F_n_n_v636C617373["class"] {
    }
""",
            complete(graph),
        )
    }

    @Test
    fun `case whitespace and non-ASCII identities remain byte distinct`() {
        val spaced = tableId(" A ")
        val upper = tableId("A")
        val korean = tableId("가")
        val graph = ErdGraph(
            origin = origin,
            tables = frozenListOf(
                graphTable(emptyTable(spaced)),
                graphTable(emptyTable(upper)),
                graphTable(emptyTable(korean)),
            ),
            relations = frozenListOf(),
        )

        assertEquals(
            """erDiagram
    e_v6F_n_n_v204120[" A "] {
    }
    e_v6F_n_n_v41["A"] {
    }
    e_v6F_n_n_vEAB080["가"] {
    }
""",
            complete(graph),
        )
    }

    @Test
    fun `hostile table column relation and FK annotation text cannot inject syntax`() {
        val hostile = "x\rY\nZ\r\n%%\"\\{}[]|:<>\u0001\u2028~"
        val p = table(
            id = parent,
            columns = frozenListOf(column(parent, "id", 0, comment = OptionalValue.Present(hostile))),
            primaryKeyColumns = listOf("id"),
            comment = OptionalValue.Present(hostile),
        )
        val c = table(
            id = child,
            columns = frozenListOf(column(child, hostile, 0)),
        )
        val graph = ErdGraph(
            origin = origin,
            tables = frozenListOf(graphTable(p), graphTable(c)),
            relations = frozenListOf(
                relation(
                    childColumn = hostile,
                    name = OptionalValue.Present(hostile),
                    identification = known(RelationIdentification.NON_IDENTIFYING),
                )
            ),
        )
        val escaped = "x~u000D~Y~u000A~Z~u000D~~u000A~~u0025~~u0025~~u0022~~u005C~~u007B~~u007D~" +
            "~u005B~~u005D~~u007C~~u003A~~u003C~~u003E~~u0001~~u2028~~u007E~"
        val attributeComment = "x_u000D_Y_u000A_Z_u000D__u000A__u0025__u0025__u0022__u005C__u007B__u007D_" +
            "_u005B__u005D__u007C__u003A__u003C__u003E__u0001__u2028__u007E_"
        val encodedColumn = "c_x_u000D_Y_u000A_Z_u000D__u000A__u0025__u0025__u0022__u005C__u007B__u007D_[]" +
            "_u007C__u003A__u003C__u003E__u0001__u2028__u007E_"

        assertEquals(
            """erDiagram
    %% table-comment: $escaped
    $parentId["A"] {
        t_INT c_id PK "$attributeComment"
    }
    $childId["B"] {
        t_INT $encodedColumn
    }
    %% FK: physical $childId.$escaped -> $parentId.id
    $parentId ||..o{ $childId : "physical:$escaped"
""",
            complete(graph),
        )
    }

    @Test
    fun `composite FK preserves mapping order in exact annotation`() {
        val p = table(
            id = parent,
            columns = frozenListOf(column(parent, "id1", 0), column(parent, "id2", 1)),
            primaryKeyColumns = listOf("id1", "id2"),
        )
        val c = table(
            id = child,
            columns = frozenListOf(column(child, "a", 0), column(child, "b", 1)),
        )
        val graph = ErdGraph(
            origin = origin,
            tables = frozenListOf(graphTable(p), graphTable(c)),
            relations = frozenListOf(
                relation(
                    mappings = listOf("a" to "id2", "b" to "id1"),
                    name = OptionalValue.Present("composite"),
                )
            ),
        )

        val expected = """erDiagram
    $parentId["A"] {
        t_INT c_id1 PK
        t_INT c_id2 PK
    }
    $childId["B"] {
        t_INT c_a
        t_INT c_b
    }
    %% FK: physical $childId.a -> $parentId.id2, $childId.b -> $parentId.id1
    $parentId ||..o{ $childId : "physical:composite"
"""
        assertEquals(expected, complete(graph))
    }

    @Test
    fun `multiple FKs between same pair remain separate and ordered`() {
        val graph = ErdGraph(
            origin = origin,
            tables = frozenListOf(
                graphTable(table(parent, frozenListOf(column(parent, "id", 0)), listOf("id"))),
                graphTable(table(child, frozenListOf(column(child, "a", 0), column(child, "b", 1)))),
            ),
            relations = frozenListOf(
                relation(childColumn = "a", name = OptionalValue.Present("fk1")),
                relation(childColumn = "b", name = OptionalValue.Present("fk2")),
            ),
        )
        val expected = """erDiagram
    $parentId["A"] {
        t_INT c_id PK
    }
    $childId["B"] {
        t_INT c_a
        t_INT c_b
    }
    %% FK: physical $childId.a -> $parentId.id
    $parentId ||..o{ $childId : "physical:fk1"
    %% FK: physical $childId.b -> $parentId.id
    $parentId ||..o{ $childId : "physical:fk2"
"""
        assertEquals(expected, complete(graph))
    }

    @Test
    fun `self relation uses the same generated entity id at both endpoints`() {
        val self = tableId("A")
        val graph = ErdGraph(
            origin = origin,
            tables = frozenListOf(
                graphTable(table(self, frozenListOf(column(self, "id", 0), column(self, "parent", 1)), listOf("id")))
            ),
            relations = frozenListOf(
                relation(
                    childTable = self,
                    parentTable = self,
                    childColumn = "parent",
                    parentColumn = "id",
                    name = OptionalValue.Present("self"),
                )
            ),
        )
        assertEquals(
            """erDiagram
    $parentId["A"] {
        t_INT c_id PK
        t_INT c_parent
    }
    %% FK: physical $parentId.parent -> $parentId.id
    $parentId ||..o{ $parentId : "physical:self"
""",
            complete(graph),
        )
    }

    @Test
    fun `physical and virtual provenance remain visibly distinct`() {
        val graph = ErdGraph(
            origin = origin,
            tables = standardGraphTables(),
            relations = frozenListOf(
                relation(name = OptionalValue.Present("same"), provenance = RelationProvenance.PHYSICAL),
                relation(name = OptionalValue.Present("same"), provenance = RelationProvenance.VIRTUAL),
            ),
        )
        val expected = entityOnlyPrefix() +
            "    $parentId ||..o{ $childId : \"physical:same\"\n" +
            "    $parentId ||..o{ $childId : \"virtual:same\"\n"
        assertEquals(expected, complete(graph, includeColumnReferences = false))
    }

    @Test
    fun `relation name present empty absent and unavailable stay distinct`() {
        val presentEmpty = twoTableGraph(
            relation(name = OptionalValue.Present("")),
        )
        val absent = twoTableGraph(relation(name = OptionalValue.Absent))
        val unavailable = twoTableGraph(
            relation(name = OptionalValue.Unavailable(CoreDiagnostic("relation-name-read-failed"))),
        )

        assertEquals(
            entityOnlyPrefix() + "    $parentId ||..o{ $childId : \"physical:\"\n",
            complete(presentEmpty, includeColumnReferences = false),
        )
        assertEquals(
            entityOnlyPrefix() + "    $parentId ||..o{ $childId : \"physical\"\n",
            complete(absent, includeColumnReferences = false),
        )
        assertDegraded(unavailable, "mermaid-relation-name-unavailable", "relation-name-read-failed")
    }

    @Test
    fun `table and column comment empty absent and unavailable stay distinct`() {
        val tableEmpty = ErdGraph(
            origin = origin,
            tables = frozenListOf(graphTable(emptyTable(parent, OptionalValue.Present("")))),
            relations = frozenListOf(),
        )
        assertEquals(
            """erDiagram
    %% table-comment: 
    $parentId["A"] {
    }
""",
            complete(tableEmpty),
        )

        val tableAbsent = ErdGraph(
            origin = origin,
            tables = frozenListOf(graphTable(emptyTable(parent, OptionalValue.Absent))),
            relations = frozenListOf(),
        )
        assertEquals(
            """erDiagram
    $parentId["A"] {
    }
""",
            complete(tableAbsent),
        )

        val tableUnavailable = ErdGraph(
            origin = origin,
            tables = frozenListOf(
                graphTable(emptyTable(parent, OptionalValue.Unavailable(CoreDiagnostic("table-comment-read-failed"))))
            ),
            relations = frozenListOf(),
        )
        assertDegraded(tableUnavailable, "mermaid-table-comment-unavailable", "table-comment-read-failed")

        val columnEmpty = singleColumnGraph(OptionalValue.Present(""))
        assertEquals(
            """erDiagram
    $parentId["A"] {
        t_INT c_value ""
    }
""",
            complete(columnEmpty),
        )
        val columnAbsent = singleColumnGraph(OptionalValue.Absent)
        assertEquals(
            """erDiagram
    $parentId["A"] {
        t_INT c_value
    }
""",
            complete(columnAbsent),
        )
        val columnUnavailable = singleColumnGraph(
            OptionalValue.Unavailable(CoreDiagnostic("column-comment-read-failed"))
        )
        assertDegraded(columnUnavailable, "mermaid-column-comment-unavailable", "value:column-comment-read-failed")
    }

    @Test
    fun `unknown cardinality and identification both fail closed without payload`() {
        val unknownCardinality = twoTableGraph(
            relation(
                parents = RelationMultiplicityBounds(
                    minimum = Evidence.Unavailable(CoreDiagnostic("virtual-relation-does-not-prove-referential-integrity")),
                    maximum = known(RelationMultiplicityMaximum.ONE),
                )
            ),
        )
        val cardinalityOutcome = MermaidDocumentSerializer.serialize(unknownCardinality)
        assertTrue(cardinalityOutcome is ExportOutcome.Degraded)
        cardinalityOutcome as ExportOutcome.Degraded
        assertEquals("mermaid-cardinality-unavailable", cardinalityOutcome.diagnostics.values.single().code)

        val unknownIdentification = twoTableGraph(
            relation(
                identification = Evidence.Unavailable(CoreDiagnostic("identification-unverified")),
            ),
        )
        assertDegraded(
            unknownIdentification,
            "mermaid-relation-identification-unavailable",
            "identification-unverified",
        )
    }

    @Test
    fun `rendered alias collision propagates from token compiler`() {
        val first = tableId("T", schema = "s1")
        val second = tableId("T", schema = "s2")
        val graph = ErdGraph(
            origin = origin,
            tables = frozenListOf(
                graphTable(emptyTable(first), TableQualificationIntent.UNQUALIFIED),
                graphTable(emptyTable(second), TableQualificationIntent.UNQUALIFIED),
            ),
            relations = frozenListOf(),
        )
        assertDegraded(graph, "mermaid-entity-alias-collision", "T")
    }

    @Test
    fun `column reference option changes only deterministic FK annotation`() {
        val graph = twoTableGraph(relation(name = OptionalValue.Present("fk")))
        val withRefs = entityOnlyPrefix() +
            "    %% FK: physical $childId.parent_id -> $parentId.id\n" +
            "    $parentId ||..o{ $childId : \"physical:fk\"\n"
        val withoutRefs = entityOnlyPrefix() +
            "    $parentId ||..o{ $childId : \"physical:fk\"\n"

        assertEquals(withRefs, complete(graph, includeColumnReferences = true))
        assertEquals(withoutRefs, complete(graph, includeColumnReferences = false))
    }

    @Test
    fun `snapshot permutation and locale produce byte-identical document`() {
        val a = emptyTable(parent)
        val b = emptyTable(child)
        val original = Locale.getDefault()
        try {
            val left = complete(completeGraph(SchemaSnapshot(origin, frozenListOf(b, a))))
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val right = complete(completeGraph(SchemaSnapshot(origin, frozenListOf(a, b))))
            assertEquals(left, right)
            assertEquals(
                """erDiagram
    $parentId["A"] {
    }
    $childId["B"] {
    }
""",
                left,
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `zero entities is a failure and never a header-only success`() {
        val graph = ErdGraph(origin, frozenListOf(), frozenListOf())
        val outcome = MermaidDocumentSerializer.serialize(graph)
        assertTrue(outcome is ExportOutcome.Failure)
        outcome as ExportOutcome.Failure
        assertEquals("mermaid-empty-graph", outcome.diagnostics.values.single().code)
    }

    @Test
    fun `serializer public surface remains pure and independent of IntelliJ and legacy generator`() {
        val classes = listOf(
            MermaidDocumentOptions::class.java,
            MermaidDocumentSerializer::class.java,
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
    }

    private fun entityOnlyPrefix(): String = """erDiagram
    $parentId["A"] {
        t_INT c_id PK
    }
    $childId["B"] {
        t_INT c_parent_u005F_id
    }
"""

    private fun twoTableGraph(relation: ErdGraphRelation): ErdGraph = ErdGraph(
        origin = origin,
        tables = standardGraphTables(),
        relations = frozenListOf(relation),
    )

    private fun standardGraphTables(): FrozenList<ErdGraphTable> = frozenListOf(
        graphTable(
            table(
                id = parent,
                columns = frozenListOf(column(parent, "id", 0)),
                primaryKeyColumns = listOf("id"),
            )
        ),
        graphTable(
            table(
                id = child,
                columns = frozenListOf(column(child, "parent_id", 0)),
            )
        ),
    )

    private fun singleColumnGraph(comment: OptionalValue<String>): ErdGraph = ErdGraph(
        origin = origin,
        tables = frozenListOf(
            graphTable(
                table(
                    id = parent,
                    columns = frozenListOf(column(parent, "value", 0, comment = comment)),
                )
            )
        ),
        relations = frozenListOf(),
    )

    private fun relation(
        childTable: TableId = child,
        parentTable: TableId = parent,
        childColumn: String = "parent_id",
        parentColumn: String = "id",
        mappings: List<Pair<String, String>> = listOf(childColumn to parentColumn),
        name: OptionalValue<String> = OptionalValue.Absent,
        provenance: RelationProvenance = RelationProvenance.PHYSICAL,
        identification: Evidence<RelationIdentification> = known(RelationIdentification.NON_IDENTIFYING),
        parents: RelationMultiplicityBounds = bounds(
            RelationMultiplicityMinimum.ONE,
            RelationMultiplicityMaximum.ONE,
        ),
        children: RelationMultiplicityBounds = bounds(
            RelationMultiplicityMinimum.ZERO,
            RelationMultiplicityMaximum.MANY,
        ),
    ): ErdGraphRelation {
        val semantic = SemanticRelation(
            childTable = childTable,
            parentTable = parentTable,
            name = name,
            provenance = provenance,
            mappings = FrozenList.copyOf(
                mappings.map { (childName, parentName) ->
                    ResolvedForeignKeyColumnMapping(
                        child = ColumnId(childTable, childName),
                        parent = ColumnId(parentTable, parentName),
                    )
                }
            ),
        )
        return ErdGraphRelation(
            semantic = MultiplicitySemanticRelation(
                relation = ConstrainedSemanticRelation(
                    relation = semantic,
                    constraints = RelationConstraintEvidence(
                        childNullability = known(RelationTupleNullability.ALL_NON_NULL),
                        childUniqueness = known(RelationTupleUniqueness.NON_UNIQUE),
                        parentUniqueness = known(RelationTupleUniqueness.UNIQUE),
                    ),
                ),
                multiplicity = RelationMultiplicity(
                    parentsPerChild = parents,
                    childrenPerParent = children,
                ),
            ),
            identification = identification,
        )
    }

    private fun table(
        id: TableId,
        columns: FrozenList<ColumnSnapshot>,
        primaryKeyColumns: List<String> = emptyList(),
        comment: OptionalValue<String> = OptionalValue.Absent,
    ): TableSnapshot {
        val pk = if (primaryKeyColumns.isEmpty()) {
            OptionalValue.Absent
        } else {
            OptionalValue.Present(
                PrimaryKeyFact(
                    name = OptionalValue.Absent,
                    columns = FrozenList.copyOf(primaryKeyColumns.map { ColumnId(id, it) }),
                )
            )
        }
        return TableSnapshot(
            id = id,
            comment = comment,
            columns = columns,
            primaryKey = pk,
            uniqueKeys = known(frozenListOf()),
            foreignKeys = known(frozenListOf()),
        )
    }

    private fun emptyTable(
        id: TableId,
        comment: OptionalValue<String> = OptionalValue.Absent,
    ): TableSnapshot = table(id, frozenListOf(), comment = comment)

    private fun column(
        table: TableId,
        name: String,
        position: Int,
        type: String = "INT",
        comment: OptionalValue<String> = OptionalValue.Absent,
    ): ColumnSnapshot = ColumnSnapshot(
        id = ColumnId(table, name),
        sourcePosition = position,
        rawType = known(
            RawTypeMetadata(
                name = type,
                length = OptionalValue.Absent,
                precision = OptionalValue.Absent,
                scale = OptionalValue.Absent,
            )
        ),
        nullable = known(true),
        comment = comment,
    )

    private fun graphTable(
        table: TableSnapshot,
        qualification: TableQualificationIntent = TableQualificationIntent.UNQUALIFIED,
    ): ErdGraphTable = ErdGraphTable(table, qualification)

    private fun tableId(
        name: String,
        schema: String? = null,
        catalog: String? = null,
    ): TableId = TableId(
        origin = origin,
        catalog = catalog,
        schema = schema,
        name = name,
    )

    private fun bounds(
        minimum: RelationMultiplicityMinimum,
        maximum: RelationMultiplicityMaximum,
    ): RelationMultiplicityBounds = RelationMultiplicityBounds(known(minimum), known(maximum))

    private fun complete(
        graph: ErdGraph,
        includeColumnReferences: Boolean = true,
    ): String {
        val outcome = MermaidDocumentSerializer.serialize(
            graph,
            MermaidDocumentOptions(includeColumnReferences = includeColumnReferences),
        )
        assertTrue(outcome is ExportOutcome.Complete)
        return (outcome as ExportOutcome.Complete).value
    }

    private fun completeGraph(snapshot: SchemaSnapshot): ErdGraph {
        val outcome = ErdGraphCompiler.compile(snapshot)
        assertTrue(outcome is ExportOutcome.Complete)
        return (outcome as ExportOutcome.Complete).value
    }

    private fun assertDegraded(
        graph: ErdGraph,
        code: String,
        detail: String? = null,
    ) {
        val outcome = MermaidDocumentSerializer.serialize(graph)
        assertTrue(outcome is ExportOutcome.Degraded)
        outcome as ExportOutcome.Degraded
        assertEquals(code, outcome.diagnostics.values.single().code)
        assertEquals(detail, outcome.diagnostics.values.single().detail)
    }

    private fun <T> known(value: T): Evidence<T> = Evidence.Known(value)
}
