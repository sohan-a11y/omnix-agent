package com.omnix.agent.executor

/**
 * Determines whether a user message looks like an action request (command) vs
 * a conversational question — without touching any AI inference.
 *
 * Previously duplicated in two places inside OmnixOrchestrator.
 */
object IntentRouter {

    private val commandVerbs = setOf(
        "open", "launch", "start",
        "call", "phone", "dial",
        "send", "message", "text", "whatsapp",
        "play",
        "navigate", "go to", "take me to",
        "set alarm", "set reminder", "remind me",
        "search", "find", "look up",
        "transfer", "pay", "send money",
        "take photo", "take picture",
        "turn on", "turn off", "enable", "disable"
    )

    private val politePrefix = Regex(
        """^(please\s+)?((can|could|would|will)\s+you\s+|help\s+me\s+|i\s+want\s+you\s+to\s+)?"""
    )

    /** Returns true if [text] is more likely an actionable command than a question. */
    fun looksLikeActionRequest(text: String): Boolean {
        val stripped = text.lowercase().trim().replace(politePrefix, "")
        return commandVerbs.any { stripped.startsWith(it) }
    }

    /** Intent strings that indicate an app-launch request. */
    val launchIntents = setOf("launch_app", "open_settings", "open_app")
}
