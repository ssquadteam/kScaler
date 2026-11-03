package com.github.ssquadteam.kscaler.internal

import kotlin.math.sqrt

internal class Evidence(
    private val sigmas: Double,
) {
    private val diffs = ArrayList<Double>()

    private val variances = ArrayList<Double>()

    private var spiked = false

    var base = 0.0
        private set

    fun add(
        idle: Sample,
        on: Sample,
    ) {
        val a = idle.on(on)
        val b = on.on(idle)
        base = a.center
        diffs += b.center - a.center
        variances += a.se * a.se + b.se * b.se
        spiked = spiked || (on.spiked && b.center > a.center)
    }

    private val diff get() = if (diffs.isEmpty()) 0.0 else diffs.average()

    private val se get() = if (diffs.isEmpty()) Double.MAX_VALUE else sqrt(variances.average() / diffs.size)

    fun slow(minDiff: Double): Boolean = spiked || (diff >= minDiff && diff >= sigmas * se)

    fun flat(minDiff: Double): Boolean = !spiked && diff < minDiff
}
