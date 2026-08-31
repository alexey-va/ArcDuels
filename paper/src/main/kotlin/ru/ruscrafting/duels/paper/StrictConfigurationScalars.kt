package ru.ruscrafting.duels.paper

import org.bukkit.configuration.ConfigurationSection

internal fun ConfigurationSection.strictConfigurationSection(path: String): ConfigurationSection? {
    if (!contains(path)) return null
    return getConfigurationSection(path)
        ?: throw IllegalArgumentException("${qualifiedPath(path)} must be a configuration section")
}

internal fun ConfigurationSection.strictBoolean(
    path: String,
    defaultValue: Boolean,
): Boolean {
    if (!contains(path)) return defaultValue
    return get(path) as? Boolean
        ?: throw IllegalArgumentException("${qualifiedPath(path)} must be a boolean")
}

internal fun ConfigurationSection.strictInteger(
    path: String,
    defaultValue: Int,
): Int =
    if (contains(path)) strictInteger(path) else defaultValue

internal fun ConfigurationSection.strictInteger(path: String): Int {
    val configured = get(path)
    val value =
        when (configured) {
            is Byte, is Short, is Int, is Long -> (configured as Number).toLong()
            else -> throw IllegalArgumentException("${qualifiedPath(path)} must be an integer")
        }
    require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "${qualifiedPath(path)} is outside the integer range"
    }
    return value.toInt()
}

internal fun ConfigurationSection.strictLong(
    path: String,
    defaultValue: Long,
): Long {
    if (!contains(path)) return defaultValue
    val configured = get(path)
    return when (configured) {
        is Byte, is Short, is Int, is Long -> (configured as Number).toLong()
        else -> throw IllegalArgumentException("${qualifiedPath(path)} must be an integer")
    }
}

internal fun ConfigurationSection.strictNumber(
    path: String,
    defaultValue: Double,
): Double =
    if (contains(path)) strictNumber(path) else defaultValue

internal fun ConfigurationSection.strictNumber(path: String): Double {
    val value = (get(path) as? Number)?.toDouble()
        ?: throw IllegalArgumentException("${qualifiedPath(path)} must be a number")
    require(value.isFinite()) { "${qualifiedPath(path)} must be finite" }
    return value
}

internal fun ConfigurationSection.strictString(
    path: String,
    defaultValue: String,
): String {
    if (!contains(path)) return defaultValue
    return get(path) as? String
        ?: throw IllegalArgumentException("${qualifiedPath(path)} must be a string")
}

internal fun ConfigurationSection.strictStringList(path: String): List<String> {
    val configured = get(path)
    require(configured is List<*> && configured.all { value -> value is String }) {
        "${qualifiedPath(path)} must be a list of strings"
    }
    return configured.filterIsInstance<String>()
}

private fun ConfigurationSection.qualifiedPath(path: String): String =
    currentPath?.takeIf(String::isNotBlank)?.let { "$it.$path" } ?: path
