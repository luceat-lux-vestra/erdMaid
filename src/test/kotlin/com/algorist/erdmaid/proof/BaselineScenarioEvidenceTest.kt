package com.algorist.erdmaid.proof

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fail-closed traceability from the architecture-neutral product baseline to executable evidence.
 *
 * This does not replace the referenced tests. It prevents a scenario from silently becoming
 * unowned when the baseline or test suite changes. Every current baseline scenario is bound to an
 * exact executable evidence marker; no #93 delegation remains after the large-workload proof.
 */
class BaselineScenarioEvidenceTest {

    @Test
    fun `every product baseline scenario has an exact maintained evidence owner`() {
        val baseline = Files.readString(Path.of("docs", "product-baseline-scenarios.yaml"))
        val scenarioIds = SCENARIO_ID.findAll(baseline).map { it.groupValues[1] }.toList()

        assertEquals(EVIDENCE.keys.toList(), scenarioIds)

        for ((scenarioId, owners) in EVIDENCE) {
            assertTrue("Scenario $scenarioId must have evidence ownership", owners.isNotEmpty())
            for (owner in owners) {
                when (owner) {
                    is EvidenceOwner.Test -> {
                        val source = Path.of(owner.path)
                        assertTrue("Missing evidence source for $scenarioId: $source", Files.isRegularFile(source))
                        val text = Files.readString(source)
                        assertTrue(
                            "Missing evidence marker for $scenarioId in ${owner.path}: ${owner.marker}",
                            owner.marker in text,
                        )
                    }
                    is EvidenceOwner.Delegated ->
                        throw AssertionError("Scenario $scenarioId remains delegated to ${owner.issue}")
                }
            }
        }

        val delegated = EVIDENCE
            .filterValues { owners -> owners.any { it is EvidenceOwner.Delegated } }
            .keys
        assertTrue("No product baseline scenario may remain delegated", delegated.isEmpty())
    }

    private sealed interface EvidenceOwner {
        data class Test(val path: String, val marker: String) : EvidenceOwner
        data class Delegated(val issue: String) : EvidenceOwner
    }

    companion object {
        private val SCENARIO_ID = Regex("(?m)^\\s*- id:\\s*([a-z0-9-]+)\\s*$")

        private const val CORE =
            "src/test/kotlin/com/algorist/erdmaid/core/SchemaSnapshotTest.kt"
        private const val HOST =
            "src/test/kotlin/com/algorist/erdmaid/host/HostExportPipelineTest.kt"
        private const val LARGE_HOST =
            "src/test/kotlin/com/algorist/erdmaid/host/HostLargeWorkloadProofTest.kt"
        private const val SELECTION =
            "src/test/kotlin/com/algorist/erdmaid/host/DatabaseSelectionBoundaryTest.kt"
        private const val GRAPH =
            "src/test/kotlin/com/algorist/erdmaid/semantic/ErdGraphCompilerTest.kt"
        private const val RELATION =
            "src/test/kotlin/com/algorist/erdmaid/semantic/RelationSemanticCompilerTest.kt"
        private const val CANONICAL_RELATION =
            "src/test/kotlin/com/algorist/erdmaid/semantic/RelationCanonicalizationTest.kt"
        private const val MULTIPLICITY =
            "src/test/kotlin/com/algorist/erdmaid/semantic/RelationMultiplicityCompilerTest.kt"
        private const val RENDERER =
            "src/test/kotlin/com/algorist/erdmaid/renderer/MermaidDocumentSerializerTest.kt"
        private const val ATTRIBUTE =
            "src/test/kotlin/com/algorist/erdmaid/renderer/MermaidAttributeCompilerTest.kt"
        private const val GRAMMAR =
            "src/test/kotlin/com/algorist/erdmaid/renderer/MermaidGrammarFixtureBindingTest.kt"

        private fun test(path: String, marker: String): EvidenceOwner = EvidenceOwner.Test(path, marker)

        private val EVIDENCE = linkedMapOf(
            "ordinary-single-schema" to listOf(
                test(HOST, "fun `ordinary physical relation publishes one exact complete document`"),
                test(RENDERER, "fun `ordinary physical FK serializes one exact complete document`"),
            ),
            "selection-order-invariance" to listOf(
                test(HOST, "fun `permuted snapshot table order publishes byte-identical document`"),
                test(GRAPH, "fun `permuted snapshot table order produces exactly equal graph`"),
            ),
            "duplicate-name-across-schemas" to listOf(
                test(HOST, "fun `complete multi-schema duplicate names publish one exact qualified document`"),
                test(GRAPH, "fun `same name across distinct schemas requires schema qualification intent`"),
                test(RELATION, "fun `exact selected endpoint resolves the intended same-named table`"),
            ),
            "duplicate-name-across-catalogs" to listOf(
                test(CORE, "fun `same table name across schemas and catalogs remains distinct`"),
                test(GRAPH, "fun `same schema and name across catalogs requires catalog schema qualification intent`"),
                test(RENDERER, "fun `duplicate names use exact schema-qualified aliases`"),
            ),
            "cross-datasource-selection" to listOf(
                test(SELECTION, "fun `cross-origin selection is rejected`"),
                test(HOST, "fun `non-complete capture outcomes never reach publication`"),
            ),
            "exact-duplicate-ui-selection" to listOf(
                test(SELECTION, "fun `exact duplicate live table selection is normalized once`"),
                test(SELECTION, "fun `distinct live table objects with the same display identity are never deduplicated`"),
                test(CORE, "fun `schema snapshot rejects duplicate canonical table identities`"),
            ),
            "quoted-case-sensitive-identifiers" to listOf(
                test(CORE, "fun `canonical identity preserves case whitespace and quoted spelling exactly`"),
                test(GRAPH, "fun `exact-case table names remain distinct and unqualified`"),
                test(RENDERER, "fun `case whitespace and non-ASCII identities remain byte distinct`"),
                test(ATTRIBUTE, "fun `safe Unicode and exact case stay distinct and escape form is injective`"),
            ),
            "composite-primary-and-foreign-key" to listOf(
                test(CORE, "fun `composite primary key preserves authoritative column order`"),
                test(CORE, "fun `composite foreign key preserves ordered mappings`"),
                test(GRAPH, "fun `graph preserves exact upstream relation collection including identification composite multiple self and provenance`"),
                test(RENDERER, "fun `composite FK preserves mapping order in exact annotation`"),
            ),
            "multiple-foreign-keys-same-pair" to listOf(
                test(CANONICAL_RELATION, "fun `table and provider iteration order produce exactly equal canonical output`"),
                test(RELATION, "fun `multiple foreign keys between the same pair remain distinct`"),
                test(RENDERER, "fun `multiple FKs between same pair remain separate and ordered`"),
            ),
            "self-reference" to listOf(
                test(RELATION, "fun `self relation remains in the selected semantic set`"),
                test(RENDERER, "fun `self relation uses the same generated entity id at both endpoints`"),
            ),
            "relation-endpoint-outside-selection" to listOf(
                test(RELATION, "fun `exact endpoint outside selection is omitted without same-name fallback`"),
                test(RELATION, "fun `unresolved endpoint cannot hide behind outside-selection filtering`"),
            ),
            "physical-and-virtual-relation-provenance" to listOf(
                test(CANONICAL_RELATION, "fun `provenance mappings and authoritative names each preserve distinct relations`"),
                test(RENDERER, "fun `physical and virtual provenance remain visibly distinct`"),
                test(HOST, "fun `virtual relation with unavailable cardinality degrades before publication`"),
                test(RENDERER, "fun `unknown cardinality and identification both fail closed without payload`"),
            ),
            "virtual-relation-cardinality-unavailable" to listOf(
                test(MULTIPLICITY, "fun `virtual relation never upgrades non-null tuple to mandatory parent participation`"),
                test(RENDERER, "fun `unknown cardinality and identification both fail closed without payload`"),
                test(HOST, "fun `virtual relation with unavailable cardinality degrades before publication`"),
            ),
            "structural-metadata-read-failure" to listOf(
                test(HOST, "fun `semantic degradation leaves publication unreachable`"),
                test(RELATION, "fun `unavailable foreign key collection is degraded instead of known empty`"),
                test(CORE, "fun `authoritative absence and unavailable metadata are distinct states`"),
            ),
            "optional-type-detail-absent" to listOf(
                test(ATTRIBUTE, "fun `type details preserve present absent and fixed L P S order without dialect inference`"),
                test(HOST, "fun `complete export reaches publication exactly once`"),
            ),
            "hostile-metadata" to listOf(
                test(RENDERER, "fun `hostile table column relation and FK annotation text cannot inject syntax`"),
                test(ATTRIBUTE, "fun `hostile column name and comment cannot create Mermaid structure while Unicode stays readable`"),
                test(GRAMMAR, "fun `hostile encoded metadata fixture is exact serializer output`"),
            ),
            "mixed-table-and-view-selection" to listOf(
                test(SELECTION, "fun `mixed table and unsupported selection fails closed`"),
                test(HOST, "fun `non-complete capture outcomes never reach publication`"),
            ),
            "legitimate-empty-selection" to listOf(
                test(SELECTION, "fun `legitimate empty selection is a silent no-export state`"),
                test(HOST, "fun `non-complete capture outcomes never reach publication`"),
            ),
            "selection-platform-failure" to listOf(
                test(SELECTION, "fun `group expansion success and platform failure are different outcomes`"),
                test(HOST, "fun `non-complete capture outcomes never reach publication`"),
            ),
            "large-selection" to listOf(
                test(
                    LARGE_HOST,
                    "fun `1000 x 40 and 3000 relations are deterministic with separate scale measurements`",
                )
            ),
            "large-selection-cancelled" to listOf(
                test(
                    LARGE_HOST,
                    "fun `large workload cancellation is observed during pure work and never publishes`",
                )
            ),
        )
    }
}
