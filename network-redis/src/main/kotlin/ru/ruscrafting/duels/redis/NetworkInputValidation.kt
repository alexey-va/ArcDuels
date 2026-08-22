package ru.ruscrafting.duels.redis

/** Java usernames plus RusCrafting's configured Floodgate `.` prefix. */
internal fun isSafeNetworkPlayerName(value: String): Boolean = value.matches(NETWORK_PLAYER_NAME_PATTERN)

/** Wall-clock rollback and subtraction overflow both fail closed. */
internal fun isFreshObservation(
    nowMillis: Long,
    observedAtMillis: Long,
    ttlMillis: Long,
): Boolean {
    if (ttlMillis <= 0L || nowMillis < observedAtMillis) return false
    val age = nowMillis - observedAtMillis
    return age >= 0L && age < ttlMillis
}

private val NETWORK_PLAYER_NAME_PATTERN = Regex("(?:[A-Za-z0-9_]{1,16}|\\.[A-Za-z0-9_]{1,16})")
