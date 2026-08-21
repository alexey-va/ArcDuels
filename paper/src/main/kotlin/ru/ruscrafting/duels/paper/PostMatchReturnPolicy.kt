package ru.ruscrafting.duels.paper

enum class PostMatchReturnPolicy {
    PROMPT,
    AUTOMATIC,
    ;

    companion object {
        fun parse(raw: String): PostMatchReturnPolicy =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: error("post-match.return-policy must be PROMPT or AUTOMATIC")
    }
}

enum class ArenaPostMatchAction {
    RETURN_TO_ORIGIN,
    LOCAL_LOBBY,
    ;

    fun asReturnPolicy(): PostMatchReturnPolicy =
        when (this) {
            RETURN_TO_ORIGIN -> PostMatchReturnPolicy.AUTOMATIC
            LOCAL_LOBBY -> PostMatchReturnPolicy.PROMPT
        }

    companion object {
        fun parse(raw: String): ArenaPostMatchAction =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: error("arena post-match-action must be RETURN_TO_ORIGIN or LOCAL_LOBBY")
    }
}
