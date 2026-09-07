package com.algorist.erdmaid.renderer

import com.algorist.erdmaid.core.ColumnId
import com.algorist.erdmaid.core.CoreDiagnostic
import com.algorist.erdmaid.core.Evidence
import com.algorist.erdmaid.core.ExportOutcome
import com.algorist.erdmaid.core.OptionalValue
import com.algorist.erdmaid.core.OriginId
import com.algorist.erdmaid.core.RelationProvenance
import com.algorist.erdmaid.core.SchemaSnapshot
import com.algorist.erdmaid.core.TableId
import com.algorist.erdmaid.core.TableSnapshot
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MermaidTokenCompilerTest {
    private val origin = OriginId("ds-a")

    @Test
    fun `all known multiplicity combinations map to official Mermaid left and right markers`() {
        assertCardinality(RelationMultiplicityMinimum.ONE, RelationMultiplicityMaximum.ONE, "||", "||")
        assertCardinality(RelationMultiplicityMinimum.ZERO, RelationMultiplicityMaximum.ONE, "|o", "o|")
        assertCardinality(RelationMultiplicityMinimum.ONE, RelationMultiplicityMaximum.MANY, "}|", "|{")
        assertCardinality(RelationMultiplicityMinimum.ZERO, RelationMultiplicityMaximum.MANY, "}o", "o{")
    }

    @Test
    fun `unavailable multiplicity remains unavailable instead of falling back to legacy markers`() {
        val missingMinimum = MermaidTokenCompiler.cardinalityToken(
            RelationMultiplicityBounds(
                minimum = Evidence.Unavailable(CoreDiagnostic("minimum-missing")),
                maximum = known(RelationMultiplicityMaximum.MANY),
            ),
            MermaidCardinalitySide.LEFT,
        )
        val missingMaximum = MermaidTokenCompiler.cardinalityToken(
            RelationMultiplicityBounds(
                minimum = known(RelationMultiplicityMinimum.ONE),
                maximum = Evidence.Unavailable(CoreDiagnostic("maximum-missing")),
            ),
            MermaidCardinalitySide.RIGHT,
        )

        assertEquals(
            Evidence.Unavailable(
                CoreDiagnostic("mermaid-cardinality-unavailable", "minimum:minimum-missing")
            ),
            missingMinimum,
        )
        assertEquals(
            Evidence.Unavailable(
                CoreDiagnostic("mermaid-cardinality-unavailable", "maximum:maximum-missing")
            ),
            missingMaximum,
        )
    }

    @Test
    fun `qualification intent selects only approved canonical identity components`() {
        val unqualified = MermaidTokenCompiler.entityToken(
            graphTable(tableId(name = "Users", catalog = "catalog", schema = "sales"), TableQualificationIntent.UNQUALIFIED)
        )
        val schema = MermaidTokenCompiler.entityToken(
            graphTable(tableId(name = "Users", catalog = "catalog", schema = "sales"), TableQualificationIntent.SCHEMA)
        )
        val catalogSchema = MermaidTokenCompiler.entityToken(
            graphTable(tableId(name = "Users", catalog = "catalog", schema = "sales"), TableQualificationIntent.CATALOG_SCHEMA)
        )
        val absentSchema = MermaidTokenCompiler.entityToken(
            graphTable(tableId(name = "Users", catalog = "catalog", schema = null), TableQualificationIntent.SCHEMA)
        )

        assertEquals("Users", unqualified.alias)
        assertEquals("sales.Users", schema.alias)
        assertEquals("catalog.sales.Users", catalogSchema.alias)
        assertEquals("~null~.Users", absentSchema.alias)
    }

    @Test
    fun `hostile metadata is encoded without losing readable Unicode`() {
        val hostile = "사용자\r\n%%\"\\{}[]|:<>.\u0001\u2028"
        val token = MermaidTokenCompiler.entityToken(
            graphTable(tableId(name = hostile), TableQualificationIntent.UNQUALIFIED)
        )

        assertEquals(
            "사용자~u000D~~u000A~~u0025~~u0025~~u0022~~u005C~~u007B~~u007D~" +
                "~u005B~~u005D~~u007C~~u003A~~u003C~~u003E~~u002E~~u0001~~u2028~",
            token.alias,
        )
        assertTrue(token.id.all { it.isLetterOrDigit() || it == '_' })
        assertTrue("\r" !in token.alias && "\n" !in token.alias && "%%" !in token.alias)
    }

    @Test
    fun `exact case and null identity slots remain distinct`() {
        val upper = MermaidTokenCompiler.entityToken(
            graphTable(tableId(name = "Users", schema = null), TableQualificationIntent.UNQUALIFIED)
        )
        val lower = MermaidTokenCompiler.entityToken(
            graphTable(tableId(name = "users", schema = null), TableQualificationIntent.UNQUALIFIED)
        )
        val absentSchema = MermaidTokenCompiler.entityToken(
            graphTable(tableId(name = "Users", schema = null), TableQualificationIntent.SCHEMA)
        )
        val literalNullSchema = MermaidTokenCompiler.entityToken(
            graphTable(tableId(name = "Users", schema = "~null~"), TableQualificationIntent.SCHEMA)
        )

        assertNotEquals(upper.id, lower.id)
        assertNotEquals(absentSchema.id, literalNullSchema.id)
        assertNotEquals(absentSchema.alias, literalNullSchema.alias)
    }

    @Test
    fun `relationship tokens use parent left and child right Mermaid orientation`() {
        val parent = tableId(name = "Parent")
        val child = tableId(name = "Child")
        val relation = multiplicityRelation(
            child = child,
            parent = parent,
            parentsPerChild = bounds(RelationMultiplicityMinimum.ONE, RelationMultiplicityMaximum.ONE),
            childrenPerParent = bounds(RelationMultiplicityMinimum.ZERO, RelationMultiplicityMaximum.MANY),
        )
        val graph = ErdGraph(
            origin = origin,
            tables = frozenListOf(
                graphTable(child, TableQualificationIntent.UNQUALIFIED),
                graphTable(parent, TableQualificationIntent.UNQUALIFIED),
            ),
            relations = frozenListOf(graphRelation(relation)),
        )

        val tokenSet = completeTokens(graph)
        val relationship = tokenSet.relationships.single()
        val entityByTable = tokenSet.entities.associateBy { it.tableId }

        assertEquals(entityByTable.getValue(parent).id, relationship.parentEntityId)
        assertEquals(entityByTable.getValue(child).id, relationship.childEntityId)
        assertEquals("||", relationship.parentEnd)
        assertEquals("o{", relationship.childEnd)
        assertEquals(graph.relations.single(), relationship.relation)
        assertTrue("||--o{" !in relationship.parentEnd + relationship.childEnd)
    }

    @Test
    fun `unknown relationship bound degrades the whole token set without partial payload`() {
        val parent = tableId(name = "Parent")
        val child = tableId(name = "Child")
        val relation = multiplicityRelation(
            child = child,
            parent = parent,
            parentsPerChild = RelationMultiplicityBounds(
                minimum = Evidence.Unavailable(CoreDiagnostic("host-nullability-unverified")),
                maximum = known(RelationMultiplicityMaximum.ONE),
            ),
            childrenPerParent = bounds(RelationMultiplicityMinimum.ZERO, RelationMultiplicityMaximum.MANY),
        )
        val graph = ErdGraph(
            origin = origin,
            tables = frozenListOf(
                graphTable(child, TableQualificationIntent.UNQUALIFIED),
                graphTable(parent, TableQualificationIntent.UNQUALIFIED),
            ),
            relations = frozenListOf(graphRelation(relation)),
        )

        val outcome = MermaidTokenCompiler.compile(graph)
        assertTrue(outcome is ExportOutcome.Degraded)
        outcome as ExportOutcome.Degraded
        assertEquals("mermaid-cardinality-unavailable", outcome.diagnostics.values.single().code)
        assertEquals(
            "minimum:host-nullability-unverified",
            outcome.diagnostics.values.single().detail,
        )
    }

    @Test
    fun `rendered alias collision fails closed instead of inventing a suffix`() {
        val first = tableId(name = "Users", schema = "a")
        val second = tableId(name = "Users", schema = "b")
        val graph = ErdGraph(
            origin = origin,
            tables = frozenListOf(
                graphTable(first, TableQualificationIntent.UNQUALIFIED),
                graphTable(second, TableQualificationIntent.UNQUALIFIED),
            ),
            relations = frozenListOf(),
        )

        val outcome = MermaidTokenCompiler.compile(graph)
        assertTrue(outcome is ExportOutcome.Degraded)
        outcome as ExportOutcome.Degraded
        assertEquals("mermaid-entity-alias-collision", outcome.diagnostics.values.single().code)
    }

    @Test
    fun `snapshot permutations and default locale changes produce exactly equal token sets`() {
        val alpha = table(tableId(name = "alpha", schema = "a"))
        val zeta = table(tableId(name = "zeta", schema = "z"))
        val originalLocale = Locale.getDefault()
        try {
            val left = completeTokens(completeGraph(SchemaSnapshot(origin, frozenListOf(zeta, alpha))))
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val right = completeTokens(completeGraph(SchemaSnapshot(origin, frozenListOf(alpha, zeta))))
            assertEquals(left, right)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `tokenization never mutates canonical table identity`() {
        val id = tableId(name = " Users ", catalog = "Cat", schema = "Schema")
        val before = id.copy()
        MermaidTokenCompiler.entityToken(
            graphTable(id, TableQualificationIntent.CATALOG_SCHEMA)
        )
        assertEquals(before, id)
    }

    @Test
    fun `renderer token type surface contains no IntelliJ or legacy generator dependency`() {
        val classes = listOf(
            MermaidEntityToken::class.java,
            MermaidRelationshipToken::class.java,
            MermaidTokenSet::class.java,
            MermaidTokenCompiler::class.java,
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

    private fun assertCardinality(
        minimum: RelationMultiplicityMinimum,
        maximum: RelationMultiplicityMaximum,
        expectedLeft: String,
        expectedRight: String,
    ) {
        val value = bounds(minimum, maximum)
        assertEquals(
            Evidence.Known(expectedLeft),
            MermaidTokenCompiler.cardinalityToken(value, MermaidCardinalitySide.LEFT),
        )
        assertEquals(
            Evidence.Known(expectedRight),
            MermaidTokenCompiler.cardinalityToken(value, MermaidCardinalitySide.RIGHT),
        )
    }

    private fun completeGraph(snapshot: SchemaSnapshot): ErdGraph {
        val outcome = ErdGraphCompiler.compile(snapshot)
        assertTrue(outcome is ExportOutcome.Complete)
        return (outcome as ExportOutcome.Complete).value
    }

    private fun completeTokens(graph: ErdGraph): MermaidTokenSet {
        val outcome = MermaidTokenCompiler.compile(graph)
        assertTrue(outcome is ExportOutcome.Complete)
        return (outcome as ExportOutcome.Complete).value
    }

    private fun graphTable(id: TableId, qualification: TableQualificationIntent): ErdGraphTable =
        ErdGraphTable(snapshot = table(id), qualification = qualification)

    private fun table(id: TableId): TableSnapshot = TableSnapshot(
        id = id,
        comment = OptionalValue.Absent,
        columns = frozenListOf(),
        primaryKey = OptionalValue.Absent,
        uniqueKeys = known(frozenListOf()),
        foreignKeys = known(frozenListOf()),
    )

    private fun tableId(
        name: String,
        catalog: String? = null,
        schema: String? = null,
    ): TableId = TableId(origin = origin, catalog = catalog, schema = schema, name = name)

    private fun bounds(
        minimum: RelationMultiplicityMinimum,
        maximum: RelationMultiplicityMaximum,
    ): RelationMultiplicityBounds = RelationMultiplicityBounds(
        minimum = known(minimum),
        maximum = known(maximum),
    )

    private fun graphRelation(semantic: MultiplicitySemanticRelation): ErdGraphRelation =
        ErdGraphRelation(
            semantic = semantic,
            identification = known(RelationIdentification.NON_IDENTIFYING),
        )

    private fun multiplicityRelation(
        child: TableId,
        parent: TableId,
        parentsPerChild: RelationMultiplicityBounds,
        childrenPerParent: RelationMultiplicityBounds,
    ): MultiplicitySemanticRelation {
        val semantic = SemanticRelation(
            childTable = child,
            parentTable = parent,
            name = OptionalValue.Absent,
            provenance = RelationProvenance.PHYSICAL,
            mappings = frozenListOf(
                ResolvedForeignKeyColumnMapping(
                    child = ColumnId(child, "parent_id"),
                    parent = ColumnId(parent, "id"),
                )
            ),
        )
        return MultiplicitySemanticRelation(
            relation = ConstrainedSemanticRelation(
                relation = semantic,
                constraints = RelationConstraintEvidence(
                    childNullability = known(RelationTupleNullability.ALL_NON_NULL),
                    childUniqueness = known(RelationTupleUniqueness.NON_UNIQUE),
                    parentUniqueness = known(RelationTupleUniqueness.UNIQUE),
                ),
            ),
            multiplicity = RelationMultiplicity(
                parentsPerChild = parentsPerChild,
                childrenPerParent = childrenPerParent,
            ),
        )
    }

    private fun <T> known(value: T): Evidence<T> = Evidence.Known(value)
}
