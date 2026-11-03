package com.github.ssquadteam.kscaler.internal

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EvidenceTest {
    private fun sample(
        frame: Double,
        jitter: Double = 0.3,
        spiked: Boolean = false,
    ): Sample {
        val frames = List(40) { frame + if (it % 2 == 0) jitter else -jitter }
        return Sample(Stats(frames, median = false), Stats(frames, median = true), spiked)
    }

    @Test
    fun `a clear stall is slow and no stall is flat`() {
        val slow = Evidence(4.0).apply { add(sample(33.0), sample(40.0)) }
        val flat = Evidence(4.0).apply { add(sample(33.0), sample(33.2)) }
        assertTrue(slow.slow(2.6))
        assertFalse(slow.flat(2.6))
        assertTrue(flat.flat(2.6))
        assertFalse(flat.slow(2.6))
    }

    @Test
    fun `noise as big as the stall is neither slow nor flat`() {
        val noisy = Evidence(4.0).apply { add(sample(33.0, jitter = 30.0), sample(37.0, jitter = 30.0)) }
        assertFalse(noisy.slow(2.6))
        assertFalse(noisy.flat(2.6))
    }

    @Test
    fun `a spike during a slower block counts as slow`() {
        val spiked = Evidence(4.0).apply { add(sample(33.0), sample(34.0, spiked = true)) }
        assertTrue(spiked.slow(2.6))
        assertFalse(spiked.flat(2.6))
    }

    @Test
    fun `an empty side reads as flat, never slow`() {
        val empty = Evidence(4.0)
        assertTrue(empty.flat(2.6))
        assertFalse(empty.slow(2.6))
    }
}
