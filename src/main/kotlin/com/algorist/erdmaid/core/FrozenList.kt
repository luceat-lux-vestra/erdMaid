package com.algorist.erdmaid.core

import java.util.Collections
import java.util.RandomAccess

/**
 * Ordered immutable collection owned by the canonical core.
 *
 * Construction snapshots the supplied collection so retaining and mutating the caller's source
 * cannot mutate canonical state after validation. The concrete type is intentional: canonical
 * data-class `copy()` methods cannot accept an arbitrary mutable [List] in place of this value.
 */
class FrozenList<out T : Any> private constructor(
    private val values: List<T>,
) : List<T> by values, RandomAccess {

    override fun equals(other: Any?): Boolean =
        other is List<*> && values == other

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = values.toString()

    companion object {
        fun <T : Any> copyOf(values: Collection<T>): FrozenList<T> {
            val snapshot = ArrayList<T>(values.size)
            values.forEach { value ->
                snapshot += requireNotNull(value) { "FrozenList elements must not be null" }
            }
            return FrozenList(Collections.unmodifiableList(snapshot))
        }

        fun <T : Any> of(vararg values: T): FrozenList<T> = copyOf(values.asList())
    }
}

fun <T : Any> frozenListOf(vararg values: T): FrozenList<T> = FrozenList.of(*values)
