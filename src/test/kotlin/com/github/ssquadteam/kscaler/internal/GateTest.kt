package com.github.ssquadteam.kscaler.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Collections

class GateTest {
    @Test
    fun `gate holds the limit and serves priority first`() =
        runBlocking {
            val gate = Gate(1)
            val order = Collections.synchronizedList(mutableListOf<String>())
            val release = CompletableDeferred<Unit>()
            val first = launch { gate.pass(false) { release.await() } }
            yield()
            val low = launch { gate.pass(false) { order += "low" } }
            yield()
            val high = launch { gate.pass(true) { order += "high" } }
            yield()
            assertEquals(emptyList<String>(), order.toList())
            release.complete(Unit)
            first.join()
            low.join()
            high.join()
            assertEquals(listOf("high", "low"), order.toList())
        }
}
