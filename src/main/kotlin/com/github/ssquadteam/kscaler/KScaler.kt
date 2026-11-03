package com.github.ssquadteam.kscaler

import com.github.ssquadteam.kscaler.internal.Block
import com.github.ssquadteam.kscaler.internal.Clients
import com.github.ssquadteam.kscaler.internal.Evidence
import com.github.ssquadteam.kscaler.internal.Gate
import com.github.ssquadteam.kscaler.internal.PingChannel
import com.github.ssquadteam.kscaler.internal.Profiles
import com.github.ssquadteam.kscaler.internal.Sample
import com.github.ssquadteam.kscaler.internal.Scheduler
import com.github.ssquadteam.kscaler.internal.Ticker
import kotlinx.coroutines.delay
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import kotlin.math.sqrt

class KScaler(
    private val plugin: Plugin,
    @Volatile var settings: KScalerSettings = KScalerSettings(),
) : Listener {
    var onResult: (Player, ScaleResult) -> Unit = { _, _ -> }

    private val scheduler = Scheduler(plugin)

    private val pings =
        PingChannel.create("kscaler_${plugin.name.lowercase()}").also {
            if (it == null) plugin.logger.warning("GUI scale detection unavailable")
        }

    private val profiles = Profiles(scheduler) { NamespacedKey(settings.namespace, "gui_scale") }

    private val sessions = ConcurrentHashMap<UUID, Session>()

    private val busy = ConcurrentHashMap.newKeySet<UUID>()

    private val attempts = ConcurrentHashMap<UUID, Int>()

    private val tags = AtomicInteger()

    private val ticker = Ticker(PING_INTERVAL_MS)

    private val calibrations = Gate(MAX_CALIBRATIONS)

    private val checks = Gate(MAX_CHECKS)

    private val active get() = pings != null

    val available: Boolean get() = active

    val font: Key get() = Key.key(settings.namespace, "scale")

    fun fit(player: Player): Fit? = profiles[player.uniqueId]?.fit

    fun profile(player: Player): Profile? = profiles[player.uniqueId]

    fun isNarrow(player: Player): Boolean = active && profiles[player.uniqueId]?.fit == Fit.NARROW

    fun supports(player: Player): Boolean = active && Clients.supports(player)

    fun isProbing(player: Player): Boolean = player.uniqueId in busy

    fun request(
        player: Player,
        onChange: suspend () -> Unit = {},
    ) {
        if (!active || !Clients.supports(player)) return
        if (profiles[player.uniqueId] == null) {
            calibrate(player, onChange, priority = true)
        } else {
            exclusive(player) { checks.pass(priority = true) { check(player, onChange) } }
        }
    }

    fun reset(player: Player) {
        profiles.remove(player)
        attempts.remove(player.uniqueId)
    }

    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        Bukkit.getOnlinePlayers().forEach { player -> scheduler.atPlayer(player) { profiles.load(player) } }
    }

    fun close() {
        HandlerList.unregisterAll(this)
        ticker.shutdown()
        scheduler.shutdown()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        profiles.load(player)
        if (!active || !settings.calibrateOnJoin || profiles[player.uniqueId] != null) return
        scheduler.launch("gui-scale-join") {
            delay(JOIN_DELAY_MS)
            calibrate(player, redraw = {})
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPack(event: PlayerResourcePackStatusEvent) {
        if (settings.calibrateOnJoin &&
            event.status == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED
        ) {
            calibrate(event.player, redraw = {})
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        sessions.remove(id)
        attempts.remove(id)
        profiles.unload(id)
    }

    private fun calibrate(
        player: Player,
        redraw: suspend () -> Unit,
        priority: Boolean = false,
    ) {
        if (!active || profiles[player.uniqueId] != null || !Clients.supports(player)) return
        if ((attempts[player.uniqueId] ?: 0) >= MAX_ATTEMPTS) return
        exclusive(player) {
            if (profiles[player.uniqueId] != null) return@exclusive
            attempts.merge(player.uniqueId, 1, Int::plus)
            val started = System.currentTimeMillis()
            val done = calibrations.pass(priority) { calibrateNow(player) }
            report(player, if (done) ScaleResult.Kind.CALIBRATED else ScaleResult.Kind.FAILED, started)
            if (done) {
                redraw()
            } else if (player.isOnline) {
                scheduler.launch("gui-scale-retry") {
                    delay(RETRY_DELAY_MS)
                    calibrate(player, redraw, priority)
                }
            }
        }
    }

    private fun exclusive(
        player: Player,
        block: suspend () -> Unit,
    ) {
        if (!busy.add(player.uniqueId)) return
        scheduler.launch("gui-scale") {
            try {
                block()
            } finally {
                busy.remove(player.uniqueId)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun check(
        player: Player,
        redraw: suspend () -> Unit,
    ) {
        val saved = profiles[player.uniqueId] ?: return
        val session = session(player) ?: return
        val started = System.currentTimeMillis()
        var strength = (saved.strength + VERIFY_MARGIN).coerceAtMost(MAX_STRENGTH)
        val window = (saved.frame * VERIFY_FRAMES).toLong()
        var match = Evidence(VERIFY_SE)
        var mismatch = Evidence(VERIFY_SE)
        var frame = saved.frame
        var reading = Reading.UNSURE
        var tries = 0
        while (reading == Reading.UNSURE && tries < VERIFY_TRIES && player.isOnline) {
            if (tries > 0) delay(SETTLE_MS)
            tries++
            val idle = session.sample(window.coerceIn(VERIFY_IDLE_MS, MAX_WINDOW_MS), 0, Long.MAX_VALUE)
            val spike = spikeNanos(idle)
            val sides = listOf(saved.fit.matchSide(), saved.fit.mismatchSide()).shuffled()
            for (side in sides) {
                player.sendActionBar(marker(color(side, strength)))
                val on = session.sample(window.coerceIn(VERIFY_ON_MS, MAX_WINDOW_MS), WARMUP_MS, spike)
                player.sendActionBar(Component.empty())
                delay(SETTLE_MS)
                (if (side == saved.fit.matchSide()) match else mismatch).add(idle, on)
                trace(player) { "check ${side.name} strength $strength ${on.on(idle).center - idle.on(on).center}ms over ${idle.on(on).center}ms" }
            }
            frame = match.base
            val minDiff = minDiff(frame)
            reading =
                when {
                    match.slow(minDiff) && mismatch.flat(minDiff) -> Reading.NORMAL
                    mismatch.slow(minDiff) && match.flat(minDiff) -> Reading.SLOW
                    else -> Reading.UNSURE
                }
            if (reading == Reading.UNSURE && match.flat(minDiff) && mismatch.flat(minDiff) && strength < MAX_STRENGTH) {
                strength = (strength + COARSE_STEP).coerceAtMost(MAX_STRENGTH)
                match = Evidence(VERIFY_SE)
                mismatch = Evidence(VERIFY_SE)
            }
        }
        val fit = if (reading == Reading.SLOW) saved.fit.other() else saved.fit
        profiles[player] = saved.copy(fit = fit, strength = maxOf(saved.strength, strength - VERIFY_MARGIN), frame = frame)
        val kind =
            when (reading) {
                Reading.SLOW -> ScaleResult.Kind.CHANGED
                Reading.NORMAL -> ScaleResult.Kind.CONFIRMED
                Reading.UNSURE -> ScaleResult.Kind.UNSURE
            }
        report(player, kind, started)
        if (fit != saved.fit) redraw()
    }

    private fun report(
        player: Player,
        kind: ScaleResult.Kind,
        started: Long,
    ) {
        runCatching { onResult(player, ScaleResult(kind, profiles[player.uniqueId], System.currentTimeMillis() - started)) }
            .onFailure { plugin.logger.log(Level.WARNING, "GUI scale result listener failed", it) }
    }

    private inline fun trace(
        player: Player,
        message: () -> String,
    ) {
        if (settings.debug) plugin.logger.info("[kScaler] ${player.name} ${message()}")
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun calibrateNow(player: Player): Boolean {
        val session = session(player) ?: return false
        val started = System.currentTimeMillis()
        val idle = session.sample(CALIBRATE_IDLE_MS, 0, Long.MAX_VALUE)
        val spike = spikeNanos(idle)
        var strength =
            profiles.medianStrength()?.let { (it - HEAD_START).coerceIn(START_STRENGTH, MAX_STRENGTH) }
                ?: START_STRENGTH
        var fine = false
        var streak = 0
        var streakSign = 0
        var pairs = 0
        while (player.isOnline && strength <= MAX_STRENGTH && System.currentTimeMillis() - started < TIMEOUT_MS) {
            val samples = arrayOfNulls<Sample>(2)
            var spikedSide: Side? = null
            val narrowFirst = ThreadLocalRandom.current().nextBoolean()
            for (k in 0..1) {
                val side = if ((k == 0) == narrowFirst) Side.NARROW else Side.WIDE
                val marker = marker(color(side, strength))
                player.sendActionBar(marker)
                val sample = session.sample(BLOCK_MS, WARMUP_MS, spike)
                player.sendActionBar(Component.empty())
                delay(SETTLE_MS)
                if (sample.spiked) {
                    spikedSide = side
                    break
                }
                samples[side.ordinal] = sample
            }
            if (spikedSide != null) {
                trace(player) { "calibrate strength $strength spiked on ${spikedSide.name}" }
                strength = maxOf(START_STRENGTH - 2 * COARSE_STEP, strength - COARSE_STEP)
                fine = true
                streak = 0
                pairs = 0
                continue
            }
            val narrow = requireNotNull(samples[Side.NARROW.ordinal])
            val wide = requireNotNull(samples[Side.WIDE.ordinal])
            val n = narrow.on(wide)
            val w = wide.on(narrow)
            val base = if (narrow.frames != null && wide.frames != null) idle.frames ?: idle.rtt else idle.rtt
            val floor = base.floor
            val minDiff = minDiff(floor)
            val d = n.center - w.center
            val sign =
                when {
                    d >= minDiff && w.center - floor < minDiff -> 1
                    -d >= minDiff && n.center - floor < minDiff -> -1
                    else -> 0
                }
            trace(player) { "calibrate strength $strength narrow ${n.center}ms wide ${w.center}ms floor ${floor}ms sign $sign" }
            if (!fine) {
                when {
                    sign != 0 && sign == streakSign -> {
                        fine = true
                        streak = 2
                    }
                    sign != 0 -> streakSign = sign
                    else -> {
                        strength += if (streakSign != 0) FINE_STEP else COARSE_STEP
                        streakSign = 0
                    }
                }
                continue
            }
            pairs++
            if (sign != 0 && sign == streakSign) {
                streak++
            } else {
                streakSign = sign
                streak = if (sign == 0) 0 else 1
            }
            if (streak >= STREAK) {
                val fit = if (streakSign > 0) Fit.NARROW else Fit.WIDE
                profiles[player] = Profile(fit, strength, idle.on(idle).center)
                return true
            }
            if (pairs >= PAIRS_PER_STEP && streak < 2) {
                strength += FINE_STEP
                pairs = 0
                streak = 0
            }
        }
        return false
    }

    private fun marker(color: TextColor): Component =
        Component
            .text(KScalerPack.MARKER)
            .font(font)
            .color(color)
            .shadowColor(ShadowColor.none())

    private fun session(player: Player): Session? {
        val channel = pings ?: return null
        if (!player.isOnline) return null
        return sessions.computeIfAbsent(player.uniqueId) {
            val listener = channel.listenerOf(player)
            Session(player, channel, listener, channel.tap(channel.channelOf(listener)))
        }
    }

    private fun color(
        side: Side,
        strength: Int,
    ): TextColor =
        TextColor.color(
            side.red,
            (settings.threshold / WIDTH_UNIT).coerceIn(1, MAX_CHANNEL - 1),
            strength.coerceIn(1, MAX_CHANNEL),
        )

    private fun spikeNanos(idle: Sample): Long = ((idle.rtt.p99 + SPIKE_MS) * NANOS_PER_MS).toLong()

    private fun minDiff(base: Double): Double = maxOf(MIN_DIFF_MS, base * MIN_DIFF_RATIO)

    private inner class Session(
        private val player: Player,
        private val channel: PingChannel,
        private val listener: Any,
        private val tap: PingChannel.Tap,
    ) {
        suspend fun sample(
            lengthMs: Long,
            warmupMs: Long,
            spikeNanos: Long,
        ): Sample {
            val block = Block(tags.incrementAndGet(), spikeNanos)
            tap.block = block
            val start = System.nanoTime()
            ticker.run(
                block,
                start + warmupMs * NANOS_PER_MS.toLong(),
                start + lengthMs * NANOS_PER_MS.toLong(),
                player::isOnline,
            ) { channel.ping(listener, it) }
            delay(TAIL_MS)
            tap.block = null
            return block.sample(PING_INTERVAL_MS.toDouble())
        }
    }

    private enum class Reading { SLOW, NORMAL, UNSURE }

    private enum class Side(
        val red: Int,
    ) {
        NARROW(241),
        WIDE(242),
    }

    private fun Fit.matchSide(): Side = if (this == Fit.NARROW) Side.NARROW else Side.WIDE

    private fun Fit.mismatchSide(): Side = if (this == Fit.NARROW) Side.WIDE else Side.NARROW

    private companion object {
        const val PING_INTERVAL_MS = 4L
        const val TAIL_MS = 40L
        const val WARMUP_MS = 150L
        const val SETTLE_MS = 250L
        const val BLOCK_MS = 450L
        const val CALIBRATE_IDLE_MS = 800L
        const val VERIFY_IDLE_MS = 200L
        const val VERIFY_ON_MS = 400L
        const val MAX_WINDOW_MS = 1500L
        const val VERIFY_FRAMES = 8
        const val VERIFY_MARGIN = 10
        const val VERIFY_SE = 4.0
        const val VERIFY_TRIES = 3
        const val START_STRENGTH = 150
        const val HEAD_START = 20
        const val MAX_STRENGTH = 240
        const val COARSE_STEP = 10
        const val FINE_STEP = 5
        const val STREAK = 4
        const val PAIRS_PER_STEP = 3
        const val TIMEOUT_MS = 40_000L
        const val JOIN_DELAY_MS = 3_000L
        const val RETRY_DELAY_MS = 10_000L
        const val MAX_ATTEMPTS = 3
        const val MAX_CALIBRATIONS = 24
        const val MAX_CHECKS = 16
        const val SPIKE_MS = 50.0
        const val MIN_DIFF_MS = 1.0
        const val MIN_DIFF_RATIO = 0.08
        const val NANOS_PER_MS = 1e6
        const val WIDTH_UNIT = 4
        const val MAX_CHANNEL = 255
    }
}
