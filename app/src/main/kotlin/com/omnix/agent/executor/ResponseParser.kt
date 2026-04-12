package com.omnix.agent.executor

/**
 * Parses THOUGHT/CMD structured responses from Gemma into discrete fields,
 * and maps command strings to user-visible narration / emoji labels.
 *
 * Extracted from AutonomyLoop to keep the main loop clean.
 */
object ResponseParser {

    fun extractThought(response: String): String =
        Regex("""THOUGHT:\s*(.+?)(?=CMD:|$)""", RegexOption.DOT_MATCHES_ALL)
            .find(response)?.groupValues?.get(1)?.trim() ?: ""

    fun extractCommand(response: String): String =
        Regex("""CMD:\s*(.+)""").find(response)?.groupValues?.get(1)?.trim() ?: ""

    /**
     * Extract the first argument from a command, supporting both quoted and bare-word forms.
     *
     * - `click_text "Send"` → `"Send"`
     * - `launch_app com.whatsapp` → `"com.whatsapp"`
     * - `type_focused hello world` → `"hello world"`
     */
    fun extractArg1(cmd: String, prefix: String): String {
        val rest = cmd.removePrefix(prefix).trim()
        return if (rest.startsWith("\"")) {
            rest.substringAfter("\"").substringBefore("\"")
        } else {
            rest
        }
    }

    fun commandToEmoji(cmd: String): String = when {
        cmd.startsWith("click_text") -> "👆 Tapping text..."
        cmd.startsWith("click_desc") -> "👆 Tapping button..."
        cmd.startsWith("click") -> "👆 Tapping element..."
        cmd.startsWith("type") -> "⌨️ Typing..."
        cmd.startsWith("bash") || cmd.startsWith("shell") -> "🖥️ Running shell..."
        cmd.startsWith("python") -> "🐍 Running Python..."
        cmd.startsWith("termux") -> "🖥️ Running in Termux..."
        cmd.startsWith("read_file") -> "📄 Reading file..."
        cmd.startsWith("write_file") -> "📝 Writing file..."
        cmd.startsWith("http_get") -> "🌐 Fetching URL..."
        cmd.startsWith("launch_app") -> "🚀 Launching app..."
        cmd.startsWith("swipe") -> "👆 Swiping..."
        cmd.startsWith("tap") -> "👆 Tapping..."
        cmd.startsWith("save_skill") -> "💾 Saving skill..."
        cmd == "back" -> "⬅️ Going back..."
        cmd == "home" -> "🏠 Going home..."
        cmd == "wait" -> "⏳ Waiting..."
        cmd.startsWith("done") -> "✅ Done!"
        else -> "⚙️ Executing..."
    }

    fun commandToNarration(cmd: String): String {
        val verb = cmd.split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: return "Working..."
        val arg = extractArg1(cmd, verb)
        return when (verb) {
            "click", "click_text", "click_desc" -> "Tapping $arg"
            "type", "type_focused" -> "Typing text"
            "swipe" -> "Swiping $arg"
            "launch_app" -> "Opening $arg"
            "bash", "shell" -> "Running a command"
            "python" -> "Running Python code"
            "back" -> "Going back"
            "home" -> "Going to home screen"
            "wait" -> "Waiting for the screen to load"
            "done" -> "Task complete"
            else -> "Working..."
        }
    }

    /** The set of verb strings handled by [UICommandExecutor]. */
    val uiVerbs = setOf("click", "click_text", "click_desc", "type", "type_focused", "tap", "swipe", "back", "home", "wait")

    /** The set of verb strings handled by [CodeCommandExecutor]. */
    val codeVerbs = setOf("bash", "shell", "python", "termux", "read_file", "write_file", "http_get", "launch_app")
}
