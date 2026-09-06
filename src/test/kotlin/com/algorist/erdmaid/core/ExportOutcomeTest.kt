package com.algorist.erdmaid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Modifier

class ExportOutcomeTest {

    @Test
    fun `complete is the only terminal variant that carries a payload`() {
        val complete: ExportOutcome<String> = ExportOutcome.Complete("erDiagram")
        val degraded: ExportOutcome<String> = ExportOutcome.Degraded(
            CoreDiagnostics.of(CoreDiagnostic("structural-metadata-unavailable"))
        )
        val unsupported: ExportOutcome<String> = ExportOutcome.Unsupported(
            CoreDiagnostics.of(CoreDiagnostic("cross-origin-selection"))
        )
        val failure: ExportOutcome<String> = ExportOutcome.Failure(
            CoreDiagnostics.of(CoreDiagnostic("selection-platform-failure"))
        )
        val cancelled: ExportOutcome<String> = ExportOutcome.Cancelled

        assertEquals("erDiagram", (complete as ExportOutcome.Complete).value)
        listOf(degraded, unsupported, failure, cancelled).forEach { outcome ->
            assertTrue(outcome !is ExportOutcome.Complete)
        }

        assertEquals(setOf("value"), instanceFieldNames(ExportOutcome.Complete::class.java))
        assertEquals(setOf("diagnostics"), instanceFieldNames(ExportOutcome.Degraded::class.java))
        assertEquals(setOf("diagnostics"), instanceFieldNames(ExportOutcome.Unsupported::class.java))
        assertEquals(setOf("diagnostics"), instanceFieldNames(ExportOutcome.Failure::class.java))
        assertTrue(instanceFieldNames(ExportOutcome.Cancelled::class.java).isEmpty())
    }

    @Test
    fun `diagnostic terminal outcomes reject an empty diagnosis`() {
        assertThrows(IllegalArgumentException::class.java) {
            CoreDiagnostics.from(emptyList())
        }
    }

    @Test
    fun `terminal diagnostics snapshot the supplied collection`() {
        val source = mutableListOf(CoreDiagnostic("first"))
        val diagnostics = CoreDiagnostics.from(source)

        source += CoreDiagnostic("later")

        assertEquals(listOf(CoreDiagnostic("first")), diagnostics.values)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (diagnostics.values as MutableList<CoreDiagnostic>).add(CoreDiagnostic("mutation"))
        }
    }

    @Test
    fun `structural unavailable evidence maps to degraded without becoming absence`() {
        val unavailable = OptionalValue.Unavailable(CoreDiagnostic("primary-key-unavailable"))
        val outcome: ExportOutcome<SchemaSnapshot> = ExportOutcome.Degraded(
            CoreDiagnostics.of(unavailable.diagnostic)
        )

        assertTrue(unavailable is OptionalValue.Unavailable)
        assertNotEquals(OptionalValue.Absent, unavailable)
        assertEquals(
            "primary-key-unavailable",
            (outcome as ExportOutcome.Degraded).diagnostics.values.single().code,
        )
    }

    @Test
    fun `cancellation remains distinct from failure`() {
        val failure = ExportOutcome.Failure(
            CoreDiagnostics.of(CoreDiagnostic("selection-platform-failure"))
        )

        assertNotEquals(ExportOutcome.Cancelled, failure)
    }

    @Test
    fun `omitted raw type details fail closed as unavailable`() {
        val type = RawTypeMetadata("varchar")

        assertEquals(
            OptionalValue.Unavailable(CoreDiagnostic("type-length-not-established")),
            type.length,
        )
        assertEquals(
            OptionalValue.Unavailable(CoreDiagnostic("type-precision-not-established")),
            type.precision,
        )
        assertEquals(
            OptionalValue.Unavailable(CoreDiagnostic("type-scale-not-established")),
            type.scale,
        )
    }

    @Test
    fun `authoritative absent type detail remains distinct from unavailable detail`() {
        val absent = RawTypeMetadata(
            name = "varchar",
            length = OptionalValue.Absent,
            precision = OptionalValue.Absent,
            scale = OptionalValue.Absent,
        )
        val unavailable = RawTypeMetadata("varchar")

        assertNotEquals(absent.length, unavailable.length)
        assertTrue(absent.length is OptionalValue.Absent)
        assertTrue(unavailable.length is OptionalValue.Unavailable)
    }

    private fun instanceFieldNames(type: Class<*>): Set<String> =
        type.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .mapTo(linkedSetOf()) { it.name }

    @Test
    fun `terminal outcome surface remains pure core only`() {
        val outcomeClasses = listOf(
            CoreDiagnostics::class.java,
            ExportOutcome::class.java,
            ExportOutcome.Complete::class.java,
            ExportOutcome.Degraded::class.java,
            ExportOutcome.Unsupported::class.java,
            ExportOutcome.Failure::class.java,
            ExportOutcome.Cancelled::class.java,
        )

        val referencedTypes = outcomeClasses.flatMap { type ->
            buildList {
                addAll(type.declaredFields.map { it.type })
                addAll(type.declaredMethods.map { it.returnType })
                addAll(type.declaredMethods.flatMap { it.parameterTypes.asList() })
                addAll(type.declaredConstructors.flatMap { it.parameterTypes.asList() })
            }
        }

        assertTrue(referencedTypes.none { it.name.startsWith("com.intellij.") })
        assertTrue(referencedTypes.none { Throwable::class.java.isAssignableFrom(it) })
        assertTrue(referencedTypes.none { it.name.contains("clipboard", ignoreCase = true) })
        assertTrue(referencedTypes.none { it.name.contains("notification", ignoreCase = true) })
        assertTrue(referencedTypes.none { it.name.contains("mermaid", ignoreCase = true) })
    }
}
