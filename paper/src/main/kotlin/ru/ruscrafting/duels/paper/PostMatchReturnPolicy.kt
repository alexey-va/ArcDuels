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
