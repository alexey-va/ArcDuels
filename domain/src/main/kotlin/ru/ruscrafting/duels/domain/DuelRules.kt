package ru.ruscrafting.duels.domain

enum class DuelMode {
    KIT,
    OWN_INVENTORY,
}

enum class DuelObjectiveType {
    ELIMINATION,
    KING_OF_THE_HILL,
    SUMO,
}

data class CombatModifiers(
    val projectiles: Boolean = true,
    val consumables: Boolean = true,
    val enderPearls: Boolean = true,
    val naturalRegeneration: Boolean = true,
    val suddenDeathAfterSeconds: Int = 300,
    val kingOfTheHillCaptureSeconds: Int = 15,
) {
    init {
        require(suddenDeathAfterSeconds in 30..1_800) {
            "suddenDeathAfterSeconds must be between 30 and 1800"
        }
        require(kingOfTheHillCaptureSeconds in 5..120) {
            "kingOfTheHillCaptureSeconds must be between 5 and 120"
        }
    }
}

data class DuelRules(
    val mode: DuelMode,
    val kitId: KitId? = null,
    val ranked: Boolean = false,
    val bestOf: Int = 1,
    val objective: DuelObjectiveType = DuelObjectiveType.ELIMINATION,
    val modifiers: CombatModifiers = CombatModifiers(),
) {
    init {
        require(bestOf in 1..9 && bestOf % 2 == 1) { "bestOf must be an odd number between 1 and 9" }
        require((mode == DuelMode.KIT) == (kitId != null)) { "KIT mode requires a kit and OWN_INVENTORY forbids one" }
        require(!ranked || mode == DuelMode.KIT) { "Ranked own-inventory duels are not supported" }
        require(objective != DuelObjectiveType.SUMO || mode == DuelMode.KIT) { "SUMO requires a controlled kit" }
    }

    val roundsToWin: Int get() = bestOf / 2 + 1
}
