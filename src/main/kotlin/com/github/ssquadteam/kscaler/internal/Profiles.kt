package com.github.ssquadteam.kscaler.internal

import com.github.ssquadteam.kscaler.Profile
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class Profiles(
    private val scheduler: Scheduler,
    private val key: () -> NamespacedKey,
) {
    private val online = ConcurrentHashMap<UUID, Profile>()

    operator fun get(id: UUID): Profile? = online[id]

    fun load(player: Player) {
        val stored = player.persistentDataContainer.get(key(), PersistentDataType.STRING) ?: return
        Profile.decode(stored)?.let { online[player.uniqueId] = it }
    }

    fun unload(id: UUID) {
        online.remove(id)
    }

    operator fun set(
        player: Player,
        profile: Profile,
    ) {
        if (online.put(player.uniqueId, profile) == profile) return
        val value = profile.encode()
        scheduler.atPlayer(player) { player.persistentDataContainer.set(key(), PersistentDataType.STRING, value) }
    }

    fun remove(player: Player) {
        online.remove(player.uniqueId)
        scheduler.atPlayer(player) { player.persistentDataContainer.remove(key()) }
    }

    fun medianStrength(): Int? {
        if (online.size < MIN_FOR_MEDIAN) return null
        val sorted = online.values.map { it.strength }.sorted()
        return sorted[sorted.size / 2]
    }

    private companion object {
        const val MIN_FOR_MEDIAN = 3
    }
}
