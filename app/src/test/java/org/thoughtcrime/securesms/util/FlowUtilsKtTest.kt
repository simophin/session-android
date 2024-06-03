package org.thoughtcrime.securesms.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*

import org.junit.Test

class FlowUtilsKtTest {

    @Test
    fun `timedBuffer should emit buffer when it's full`() = runTest {
        // Given
        val flow = flowOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val timeoutMillis = 1000L
        val maxItems = 5

        // When
        val result = flow.timedBuffer(timeoutMillis, maxItems).toCollection(mutableListOf())

        // Then
        assertEquals(2, result.size)
        assertEquals(listOf(1, 2, 3, 4, 5), result[0])
        assertEquals(listOf(6, 7, 8, 9, 10), result[1])
    }

    @Test
    fun `timedBuffer should emit buffer when timeout expires`() = runTest {
        // Given
        val flow = flow {
            emit(1)
            emit(2)
            emit(3)
            delay(200)
            emit(4)
        }
        val timeoutMillis = 100L
        val maxItems = 5

        // When
        val result = flow.timedBuffer(timeoutMillis, maxItems).toCollection(mutableListOf())

        // Then
        assertEquals(2, result.size)
        assertEquals(listOf(1, 2, 3), result[0])
        assertEquals(listOf(4), result[1])
    }
}