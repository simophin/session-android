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
    return channelFlow {
        val buffer = mutableListOf<T>()
        var bufferBeganAt = -1L

        collectLatest { value ->
            if (bufferBeganAt < 0) {
                bufferBeganAt = System.currentTimeMillis()
            }

            buffer.add(value)

            if (buffer.size < maxItems) {
                // If the buffer is not full, wait until the time limit is reached
                delay((System.currentTimeMillis() + timeoutMillis - bufferBeganAt).coerceAtLeast(0L))
            }

            // When we reach here, it's either the buffer is full, or the timeout has been reached:
            // send out the buffer and reset the state
            send(buffer.toList())
            buffer.clear()
            bufferBeganAt = -1L
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<Iterable<T>>.flatten(): Flow<T> = flatMapConcat { it.asFlow() }