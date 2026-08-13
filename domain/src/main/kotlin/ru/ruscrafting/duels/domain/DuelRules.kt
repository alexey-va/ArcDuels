package ru.ruscrafting.duels.domain

enum class DuelMode {
    KIT,
    OWN_INVENTORY,
}

data class DuelRules(
    val mode: DuelMode,
    val kitId: KitId? = null,
    val ranked: Boolean = false,
    val bestOf: Int = 1,
) {
    init {
        require(bestOf in 1..9 && bestOf % 2 == 1) { "bestOf must be an odd number between 1 and 9" }
        require((mode == DuelMode.KIT) == (kitId != null)) { "KIT mode requires a kit and OWN_INVENTORY forbids one" }
        require(!ranked || mode == DuelMode.KIT) { "Ranked own-inventory duels are not supported" }
    }

    val roundsToWin: Int get() = bestOf / 2 + 1
}
