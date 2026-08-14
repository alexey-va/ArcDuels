package ru.ruscrafting.duels.paper

internal enum class PlayerDataSyncProvider {
    NONE,
    HUSKSYNC,
    ;

    val requiresReadinessEvent: Boolean
        get() = this == HUSKSYNC

    val sharesInventoryBetweenServers: Boolean
        get() = this == HUSKSYNC

    companion object {
        fun resolve(
            configured: String,
            huskSyncEnabled: Boolean,
        ): PlayerDataSyncProvider =
            when (configured.trim().uppercase()) {
                "AUTO" -> if (huskSyncEnabled) HUSKSYNC else NONE
                "HUSKSYNC" -> {
                    require(huskSyncEnabled) {
                        "player-data-sync.provider is HUSKSYNC, but the HuskSync plugin is not enabled"
                    }
                    HUSKSYNC
                }
                "NONE" -> NONE
                else -> error("player-data-sync.provider must be AUTO, HUSKSYNC, or NONE")
            }
    }
}
