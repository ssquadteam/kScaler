package com.github.ssquadteam.kscaler.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlockTest {
    private val ms = 1_000_000L

    private fun pinged(
        block: Block,
        count: Int,
        intervalMs: Long,
    ): List<Pair<Int, Long>> =
        List(count) { i -> requireNotNull(block.next(i * intervalMs * ms)) to i * intervalMs * ms }

    @Test
    fun `pongs answered once per frame give the frame time`() {
        val block = Block(1, 1_000 * ms)
        val frame = 10L
        pinged(block, 100, 4).forEach { (id, sent) ->
            val answered = (sent / (frame * ms) + 1) * frame * ms + ms
            block.arrive(id, answered)
        }
        val sample = block.sample(4.0)
        val frames = requireNotNull(sample.frames)
        assertEquals(frame.toDouble(), frames.center, 0.01)
        assertFalse(sample.spiked)
    }

    @Test
    fun `very high fps falls back to round trip time`() {
        val block = Block(2, 1_000 * ms)
        pinged(block, 100, 4).forEach { (id, sent) -> block.arrive(id, sent + 2 * ms) }
        val sample = block.sample(4.0)
        assertNull(sample.frames)
        assertEquals(2.0, sample.rtt.center, 0.01)
    }

    @Test
    fun `pongs from another block are ignored`() {
        val block = Block(3, 1_000 * ms)
        val other = Block(4, 1_000 * ms)
        val (id, sent) = pinged(other, 1, 4).single()
        pinged(block, 1, 4)
        block.arrive(id, sent + ms)
        assertTrue(block.sample(4.0).rtt.center == 0.0)
    }

    @Test
    fun `a late pong flags a spike`() {
        val block = Block(5, 50 * ms)
        val (id, sent) = pinged(block, 1, 4).single()
        block.arrive(id, sent + 80 * ms)
        assertTrue(block.sample(4.0).spiked)
    }

    @Test
    fun `an unanswered ping past the limit flags a spike`() {
        val block = Block(6, 50 * ms)
        pinged(block, 1, 4)
        block.checkPending(60 * ms)
        assertTrue(block.spiked)
    }
}
