package ru.ruscrafting.duels.paper

import ru.arc.config.Config
import java.nio.file.Path

internal object ArcDuelsConfigDefaults {
    private const val RESOURCE = "config.yml"
    private val operatorOwnedRoots = setOf("arenas")

    fun mergeMissing(dataRoot: Path): Boolean =
        Config(dataRoot, RESOURCE).mergeMissingFromBundled(RESOURCE, operatorOwnedRoots)
}
