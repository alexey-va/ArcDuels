package com.Zrips.CMI.Containers

import org.bukkit.entity.Player
import java.util.UUID

class CMIUser(
    val playerId: UUID,
) {
    companion object {
        @JvmStatic
        fun getUser(player: Player): CMIUser = CMIUser(player.uniqueId)
    }
}
