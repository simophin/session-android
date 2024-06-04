package org.thoughtcrime.securesms.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapConcat

/**
 * Buffers items from the flow and emits them in batches. The batch will have size [maxItems] and
 * time [timeoutMillis] limit.
 */
fun <T> Flow<T>.timedBuffer(timeoutMillis: Long, maxItems: Int): Flow<List<T>> {
    class State(
        var buffer: MutableList<T> = mutableListOf(),
        var deadline: Long = -1L
    )

    return scanTransformLatest(State()) { state, value ->
        val now = System.currentTimeMillis()

        if (state.buffer.isEmpty()) {
            state.deadline = now + timeoutMillis
        }

        state.buffer.add(value)

        if (state.buffer.size < maxItems && now < state.deadline) {
            // If the buffer is not full and the timeout has not expired, keep waiting
            // until the deadline is reached. This delay will get cancelled and our
            // transform function will be called again for the new value.
            delay(state.deadline - now)
        }

        // When we reach here, the buffer is either full or the timeout has expired,
        // at which point we emit our buffer.
        val result = state.buffer
        state.buffer = mutableListOf()
        state.deadline = -1L

        result
    }
}

/**
 * Scans the flow with a state and transform the latest value with the state.
 * This function is similar to [Flow.scan] but it differs in a major ways:
 * 1. The accumulator used by this function returns the transformed value rather than the state.
 *    This then potentially requires the state to be mutable.
 * 2. The transform function will get cancelled for the new value (this is where the name "latest" comes from).
 */
fun <T, S, R> Flow<T>.scanTransformLatest(state: S, transform: suspend (acc: S, value: T) -> R): Flow<R> {
    return channelFlow {
        collectLatest { value ->
            send(transform(state, value))
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<Iterable<T>>.flatten(): Flow<T> = flatMapConcat { it.asFlow() }