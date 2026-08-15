package com.Zrips.CMI.Containers

import org.bukkit.entity.Player
import java.util.UUID

class CMIUser(
    val playerId: UUID,
) {
    fun removeBossBar(key: String) {
        removedBossBars.getOrPut(playerId, ::mutableListOf) += key
    }

    companion object {
        private val removedBossBars = mutableMapOf<UUID, MutableList<String>>()

        @JvmStatic
        fun getUser(player: Player): CMIUser = CMIUser(player.uniqueId)

        fun removedBossBars(playerId: UUID): List<String> = removedBossBars[playerId].orEmpty()

        fun reset() = removedBossBars.clear()
    }
}
