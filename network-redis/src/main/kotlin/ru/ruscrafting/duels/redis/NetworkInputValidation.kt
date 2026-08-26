package ru.ruscrafting.duels.redis

import ru.arc.network.NetworkPlayerName

/** Java usernames plus RusCrafting's configured Floodgate `.` prefix. */
internal fun isSafeNetworkPlayerName(value: String): Boolean = NetworkPlayerName.parseOrNull(value) != null

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
