package ru.ruscrafting.duels.paper

internal class DuelCommandPolicy {
    fun isAllowed(commandLine: String): Boolean {
        val parts = commandLine.trim().removePrefix("/").split(WHITESPACE).filter(String::isNotBlank)
        if (parts.isEmpty()) return false
        val root = parts.first().substringAfter(':').lowercase()
        if (root in CHAT_COMMANDS) return true
        if (root !in DUEL_COMMANDS) return false
        return parts.getOrNull(1)?.lowercase() in SAFE_DUEL_SUBCOMMANDS
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val CHAT_COMMANDS = setOf("msg", "tell", "w", "whisper", "r", "reply")
        val DUEL_COMMANDS = setOf("duel", "duels", "дуэль")
        val SAFE_DUEL_SUBCOMMANDS = setOf("leave", "покинуть", "stats", "статы")
    }
}
