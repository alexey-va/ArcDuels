package ru.ruscrafting.duels.redis

import ru.arc.network.NetworkPlayerName

/** Java usernames plus RusCrafting's configured Floodgate `.` prefix. */
internal fun isSafeNetworkPlayerName(value: String): Boolean = NetworkPlayerName.parseOrNull(value) != null
