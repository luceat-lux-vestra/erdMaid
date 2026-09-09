package com.algorist.erdmaid.core

/**
 * Invocation-local cooperative-work checkpoint for expensive pure pipeline stages.
 *
 * The pure core knows nothing about coroutines. Hosts may bind this callback to their own
 * cancellation mechanism; direct pure callers use [NONE]. Implementations must not retain host
 * objects or introduce shared state.
 */
internal fun interface WorkCheckpoint {
    fun check()

    companion object {
        val NONE = WorkCheckpoint { }
    }
}
