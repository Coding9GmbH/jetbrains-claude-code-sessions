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
    val startedAt: Long,             // epoch millis from sessions/*.json
    var state: SessionState = SessionState.UNKNOWN,
    var lastActivityAt: Instant = Instant.now(),
    var lastAssistantMessage: String = "",  // last few chars to detect accept prompts
    var cpuPercent: Double = 0.0,
    var environment: SessionEnvironment = SessionEnvironment.UNKNOWN
) {
    val projectName: String
        get() = cwd.trimEnd('/').split("/").lastOrNull()?.ifEmpty { cwd } ?: cwd

    val startedAtInstant: Instant
        get() = Instant.ofEpochMilli(startedAt)

    val isAlive: Boolean
        get() = state != SessionState.FINISHED

    /** encoded project dir name in ~/.claude/projects/ */
    val encodedProjectPath: String
        get() = cwd.trimEnd('/').replace("/", "-")
}
