package com.coding9.claudecode.model

import java.time.Instant

enum class SessionState(val displayName: String, val description: String) {
    RUNNING("Running", "Claude is actively processing"),
    WAITING_FOR_INPUT("Waiting", "Claude is waiting for your message"),
    WAITING_FOR_ACCEPT("Accept needed", "Claude is waiting for your tool approval"),
    FINISHED("Finished", "Session has ended"),
    UNKNOWN("Unknown", "Status could not be determined")
}

enum class SessionEnvironment(val displayName: String) {
    JETBRAINS_TERMINAL("JetBrains"),
    EXTERNAL_TERMINAL("Terminal"),
    UNKNOWN("Unknown")
}

data class ClaudeSession(
    val pid: Long,
    val sessionId: String,
    val cwd: String,
    val startedAt: Long,             // epoch millis from sessions/<pid>.json
    val name: String = "",           // session name from sessions/<pid>.json (CLI 2.x)
    val nativeStatus: String = "",   // "busy" / "idle" from sessions/<pid>.json (CLI 2.x)
    val cliVersion: String = "",     // Claude Code version from sessions/<pid>.json
    var title: String = "",          // AI-generated title from the ai-title JSONL entry
    var model: String = "",          // model id of the last assistant turn, e.g. claude-fable-5
    var contextTokens: Long = 0,     // prompt tokens of the last turn (input + cache read/creation)
    var contextWindow: Long = 0,     // context window of the model in tokens
    var state: SessionState = SessionState.UNKNOWN,
    var lastActivityAt: Instant = Instant.now(),
    var lastAssistantMessage: String = "",  // last few chars to detect accept prompts
    var cpuPercent: Double = 0.0,
    var environment: SessionEnvironment = SessionEnvironment.UNKNOWN,
    var contextBytes: Long = 0,       // JSONL file size – fallback proxy for context usage
    var turnCount: Int = 0            // number of conversation turns
) {
    val projectName: String
        get() = cwd.trimEnd('/').split("/").lastOrNull()?.ifEmpty { cwd } ?: cwd

    /** Preferred display label: explicit session name, then AI title, then project dir name. */
    val displayName: String
        get() = name.ifEmpty { title.ifEmpty { projectName } }

    /** Short human-readable model label, e.g. "Fable 5", "Opus 4.8", "Sonnet 5". */
    val modelShortName: String
        get() {
            val m = model.removePrefix("claude-")
            if (m.isEmpty()) return ""
            val parts = m.split("-")
            val family = parts.first().replaceFirstChar { it.uppercaseChar() }
            val version = parts.drop(1).takeWhile { it.length <= 2 && it.all(Char::isDigit) }
                .take(2).joinToString(".")
            return if (version.isEmpty()) family else "$family $version"
        }

    val startedAtInstant: Instant
        get() = Instant.ofEpochMilli(startedAt)

    val isAlive: Boolean
        get() = state != SessionState.FINISHED

    /** encoded project dir name in ~/.claude/projects/ (all non-alphanumerics become dashes) */
    val encodedProjectPath: String
        get() = encodeProjectPath(cwd)

    companion object {
        fun encodeProjectPath(path: String): String =
            path.trimEnd('/').replace(Regex("[^A-Za-z0-9]"), "-")
    }
}
