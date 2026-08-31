package ru.ruscrafting.duels.redis

import ru.ruscrafting.duels.domain.DuelMode

internal fun requireKitCatalogFingerprint(value: String): String =
    value.also {
        require(it.matches(SHA256_HEX)) { "Kit fingerprint must be lowercase SHA-256 hex" }
    }

internal fun requireKitFingerprintForMode(
    mode: DuelMode,
    fingerprint: String?,
) {
    when (mode) {
        DuelMode.KIT -> requireKitCatalogFingerprint(requireNotNull(fingerprint) { "KIT mode requires a kit fingerprint" })
        DuelMode.OWN_INVENTORY -> require(fingerprint == null) { "OWN_INVENTORY mode forbids a kit fingerprint" }
    }
}

private val SHA256_HEX = Regex("[0-9a-f]{64}")
