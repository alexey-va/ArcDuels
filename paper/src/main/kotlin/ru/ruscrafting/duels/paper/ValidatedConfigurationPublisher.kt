package ru.ruscrafting.duels.paper

import org.bukkit.configuration.Configuration

/** Publishes the already validated tree without asking JavaPlugin to reread mutable disk state. */
internal fun publishValidatedConfiguration(
    target: Configuration,
    candidate: Configuration,
) {
    target.getKeys(false).toList().forEach { key -> target.set(key, null) }
    candidate.getKeys(true)
        .filter(candidate::isSet)
        .sortedBy { path -> path.count { character -> character == '.' } }
        .forEach { path ->
            if (candidate.isConfigurationSection(path)) {
                if (!target.isConfigurationSection(path)) target.createSection(path)
            } else {
                target.set(path, candidate.get(path))
            }
        }
    target.setDefaults(requireNotNull(candidate.defaults) { "Validated configuration defaults are missing" })
}
