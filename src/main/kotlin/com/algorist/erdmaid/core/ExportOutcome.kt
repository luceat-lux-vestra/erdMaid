package com.algorist.erdmaid.core

import java.util.Collections

/**
 * Ordered, non-empty diagnostics for terminal export states that require an explanation.
 *
 * The constructor owns an unmodifiable snapshot of the supplied collection so later caller
 * mutation cannot rewrite a terminal diagnosis after the outcome has been created.
 */
class CoreDiagnostics private constructor(
    values: Collection<CoreDiagnostic>,
) {
    val values: List<CoreDiagnostic> = Collections.unmodifiableList(values.toList())

    init {
        require(this.values.isNotEmpty()) { "Terminal diagnostics must not be empty" }
    }

    override fun equals(other: Any?): Boolean =
        other is CoreDiagnostics && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "CoreDiagnostics(values=$values)"

    companion object {
        fun of(first: CoreDiagnostic, vararg rest: CoreDiagnostic): CoreDiagnostics =
            CoreDiagnostics(listOf(first, *rest))

        fun from(values: Collection<CoreDiagnostic>): CoreDiagnostics = CoreDiagnostics(values)
    }
}

/**
 * Terminal export vocabulary shared by the pure pipeline and host orchestration.
 *
 * [Complete] is deliberately the only variant that can carry a non-null publishable payload.
 * [NoExport] is a successful UI state with intentionally no export payload, used for cases such as
 * a legitimate empty selection. Degraded/unsupported/failed/cancelled/no-export work cannot carry
 * partial output to clipboard publication accidentally.
 */
sealed interface ExportOutcome<out T : Any> {
    data class Complete<T : Any>(val value: T) : ExportOutcome<T>

    data object NoExport : ExportOutcome<Nothing>

    data class Degraded(val diagnostics: CoreDiagnostics) : ExportOutcome<Nothing>

    data class Unsupported(val diagnostics: CoreDiagnostics) : ExportOutcome<Nothing>

    data class Failure(val diagnostics: CoreDiagnostics) : ExportOutcome<Nothing>

    data object Cancelled : ExportOutcome<Nothing>
}
