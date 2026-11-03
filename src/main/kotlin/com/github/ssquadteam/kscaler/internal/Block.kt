package com.github.ssquadteam.kscaler.internal

import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.sqrt

internal class Block(
    tag: Int,
    private val spikeNanos: Long,
) {
    private val prefix = MAGIC or ((tag and TAG_MASK) shl SEQ_BITS)

    private val sent = LongArray(CAPACITY)

    private val arrived = AtomicLongArray(CAPACITY)

    @Volatile var count = 0
        private set

    @Volatile var spiked = false
        private set

    private var pending = 0

    fun next(now: Long): Int? {
        val seq = count
        if (seq >= CAPACITY) return null
        sent[seq] = now
        count = seq + 1
        return prefix or seq
    }

    fun arrive(
        id: Int,
        now: Long,
    ) {
        if (id and PREFIX_MASK != prefix) return
        val seq = id and SEQ_MASK
        if (seq >= count) return
        arrived.set(seq, now)
        if (now - sent[seq] > spikeNanos) spiked = true
    }

    fun checkPending(now: Long) {
        while (pending < count && arrived.get(pending) != 0L) pending++
        if (pending < count && now - sent[pending] > spikeNanos) spiked = true
    }

    fun sample(interval: Double): Sample {
        val rtts = ArrayList<Double>(count)
        val times = ArrayList<Long>(count)
        for (seq in 0 until count) {
            val at = arrived.get(seq)
            if (at == 0L) continue
            rtts += (at - sent[seq]) / NANOS_PER_MS
            times += at
        }
        times.sort()
        val frames = ArrayList<Double>()
        var burst = -1L
        for (i in times.indices) {
            if (i == 0 || (times[i] - times[i - 1]) / NANOS_PER_MS > BURST_GAP_MS) {
                if (burst >= 0) frames += (times[i] - burst) / NANOS_PER_MS
                burst = times[i]
            }
        }
        val usable = frames.size >= MIN_FRAMES && percentile(frames, HALF) > interval * FRAME_FLOOR
        return Sample(Stats(rtts, median = false), if (usable) Stats(frames, median = true) else null, spiked)
    }

    companion object {
        private const val SEQ_BITS = 9

        const val CAPACITY = 1 shl SEQ_BITS

        private const val SEQ_MASK = CAPACITY - 1

        private const val TAG_MASK = 0xFFF

        private const val MAGIC = 0x6A000000

        private const val PREFIX_MASK = SEQ_MASK.inv()

        private const val NANOS_PER_MS = 1e6

        private const val BURST_GAP_MS = 1.5

        private const val MIN_FRAMES = 3

        private const val HALF = 0.5

        private const val FRAME_FLOOR = 1.6
    }
}

internal class Sample(
    val rtt: Stats,
    val frames: Stats?,
    val spiked: Boolean,
) {
    fun on(other: Sample): Stats = if (frames != null && other.frames != null) frames else rtt
}

internal class Stats(
    values: List<Double>,
    median: Boolean,
) {
    private val kept = trimmed(values)

    val center: Double = if (median) percentile(values, HALF) else kept.average().takeUnless { it.isNaN() } ?: 0.0

    val floor: Double = percentile(values, QUARTER)

    val p99: Double = percentile(values, P99)

    val se: Double =
        if (kept.size < 2) {
            Double.MAX_VALUE / 4
        } else {
            val mean = kept.average()
            sqrt(kept.sumOf { (it - mean) * (it - mean) } / (kept.size - 1) / kept.size)
        }

    private companion object {
        const val HALF = 0.5

        const val QUARTER = 0.25

        const val P99 = 0.99

        const val KEEP = 0.9

        fun trimmed(values: List<Double>): List<Double> {
            val sorted = values.sorted()
            return sorted.subList(0, maxOf(minOf(2, sorted.size), (sorted.size * KEEP).toInt()))
        }
    }
}

internal fun percentile(
    values: List<Double>,
    p: Double,
): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    return sorted[minOf(sorted.size - 1, (p * sorted.size).toInt())]
}
