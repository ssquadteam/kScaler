package com.github.ssquadteam.kscaler.internal

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class Ticker(
    intervalMs: Long,
) {
    private val runs = ConcurrentHashMap.newKeySet<Run>()

    private val executor =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "kscaler").apply { isDaemon = true }
        }

    init {
        executor.scheduleAtFixedRate(::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    suspend fun run(
        block: Block,
        warmNanos: Long,
        endNanos: Long,
        alive: () -> Boolean,
        ping: (Int) -> Unit,
    ) {
        val run = Run(block, warmNanos, endNanos, alive, ping)
        runs += run
        run.done.await()
    }

    fun shutdown() {
        executor.shutdownNow()
        runs.forEach { it.done.complete(Unit) }
        runs.clear()
    }

    private fun tick() {
        val now = System.nanoTime()
        for (run in runs) {
            val finished =
                runCatching {
                    if (now >= run.end || run.block.spiked || !run.alive()) {
                        true
                    } else {
                        if (now >= run.warm) run.block.next(now)?.let(run.ping)
                        run.block.checkPending(now)
                        false
                    }
                }.getOrDefault(true)
            if (finished) {
                runs.remove(run)
                run.done.complete(Unit)
            }
        }
    }

    private class Run(
        val block: Block,
        val warm: Long,
        val end: Long,
        val alive: () -> Boolean,
        val ping: (Int) -> Unit,
    ) {
        val done = CompletableDeferred<Unit>()
    }
}

internal class Gate(
    private val limit: Int,
) {
    private val lock = Any()

    private var used = 0

    private val urgent = ArrayDeque<CompletableDeferred<Unit>>()

    private val waiting = ArrayDeque<CompletableDeferred<Unit>>()

    suspend fun <T> pass(
        priority: Boolean,
        block: suspend () -> T,
    ): T {
        val ticket =
            synchronized(lock) {
                if (used < limit) {
                    used++
                    null
                } else {
                    CompletableDeferred<Unit>().also { (if (priority) urgent else waiting).addLast(it) }
                }
            }
        ticket?.await()
        try {
            return block()
        } finally {
            synchronized(lock) {
                val next = urgent.removeFirstOrNull() ?: waiting.removeFirstOrNull()
                if (next == null) used-- else next.complete(Unit)
            }
        }
    }
}
