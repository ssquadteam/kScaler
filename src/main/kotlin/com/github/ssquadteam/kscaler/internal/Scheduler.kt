package com.github.ssquadteam.kscaler.internal

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level

internal class Scheduler(
    private val plugin: Plugin,
) {
    private val threads = AtomicInteger()

    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors
            .newFixedThreadPool(THREADS) { runnable ->
                Thread(runnable, "kscaler-${threads.incrementAndGet()}").apply { isDaemon = true }
            }.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun launch(
        name: String,
        block: suspend CoroutineScope.() -> Unit,
    ): Job =
        scope.launch(CoroutineName(name)) {
            runCatching { block() }.onFailure { plugin.logger.log(Level.WARNING, "$name failed", it) }
        }

    fun atPlayer(
        player: Player,
        task: () -> Unit,
    ) {
        if (!plugin.isEnabled) return
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            task()
            return
        }
        player.scheduler.execute(plugin, task, null, 1)
    }

    fun shutdown() {
        scope.cancel()
        dispatcher.close()
    }

    private companion object {
        const val THREADS = 2
    }
}
