package com.github.ssquadteam.kscaler.internal

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

internal object Clients {
    private const val FIRST_DIALOG_PROTOCOL = 771

    private val viaVersion: ((UUID) -> Int)? by lazy {
        if (Bukkit.getPluginManager().getPlugin("ViaVersion") == null) return@lazy null
        runCatching {
            val api = Class.forName("com.viaversion.viaversion.api.Via").getMethod("getAPI").invoke(null)
            val method = api.javaClass.getMethod("getPlayerVersion", UUID::class.java)
            val lookup: (UUID) -> Int = { id -> method.invoke(api, id) as Int }
            lookup
        }.getOrNull()
    }

    fun supports(player: Player): Boolean {
        if (player.uniqueId.mostSignificantBits == 0L) return false
        val lookup = viaVersion ?: return true
        val protocol = runCatching { lookup(player.uniqueId) }.getOrDefault(Int.MAX_VALUE)
        return protocol < 0 || protocol >= FIRST_DIALOG_PROTOCOL
    }
}
