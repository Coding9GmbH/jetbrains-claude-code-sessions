package com.coding9.claudecode.services

import com.coding9.claudecode.model.ClaudeSession
import com.coding9.claudecode.model.SessionEnvironment
import com.coding9.claudecode.model.SessionState
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.Disposable
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

fun interface SessionsListener {
    fun onSessions(sessions: List<ClaudeSession>)
}

@Service(Service.Level.APP)
class ClaudeSessionMonitorService : Disposable {

    private val log = thisLogger()
    private val gson = Gson()

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        ThreadFactory { r -> Thread(r, "claude-session-monitor").also { it.isDaemon = true } }
    )

    private val listeners = CopyOnWriteArrayList<SessionsListener>()

    // Only accessed from the single executor thread — no synchronization needed
    private var lastKnownSessions: Map<Long, SessionState> = emptyMap()

    @Volatile private var started = false
    @Volatile private var scheduledFuture: ScheduledFuture<*>? = null

    @Volatile
    var sessions: List<ClaudeSession> = emptyList()
        private set

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Synchronized
    fun start() {
        if (started) {
            // Trigger an immediate one-shot refresh (e.g. Refresh button)
            executor.submit(::safePoll)
            return
        }
        started = true
        scheduledFuture = executor.scheduleWithFixedDelay(::safePoll, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    override fun dispose() {
        started = false
        scheduledFuture?.cancel(false)
        executor.shutdownNow()
        listeners.clear()
    }

    fun addListener(listener: SessionsListener) = listeners.add(listener)
    fun removeListener(listener: SessionsListener) = listeners.remove(listener)

    // ------------------------------------------------------------------
    // Polling — wrapped in a broad catch so the scheduled task never dies
    // ------------------------------------------------------------------

    private fun safePoll() {
        try {
            poll()
        } catch (t: Throwable) {
            // Catching Throwable prevents the ScheduledExecutorService from
            // silently cancelling the task on Error (e.g. OutOfMemoryError)
            log.error("Claude session monitor poll crashed", t)
        }
    }

    private fun poll() {
        val fresh = loadSessions()
        sessions = fresh
        detectStateChanges(fresh)
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it.onSessions(fresh) }
        }
    }

    // ------------------------------------------------------------------
    // Session loading
    // ------------------------------------------------------------------

    private fun loadSessions(): List<ClaudeSession> {
        val sessionsDir = File(System.getProperty("user.home"), ".claude/sessions")
        if (!sessionsDir.isDirectory) return emptyList()

        val jsonFiles = sessionsDir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        val parsed = jsonFiles.mapNotNull { parseSessionFile(it) }
        if (parsed.isEmpty()) return emptyList()

        // One batched ps(1) call for all PIDs to get CPU percentages
        val cpuMap = batchGetCpu(parsed.map { it.pid })

        parsed.forEach { session ->
            session.cpuPercent = cpuMap[session.pid] ?: 0.0
            enrichSession(session)
        }
        return parsed
    }

    private fun parseSessionFile(file: File): ClaudeSession? {
        return try {
            val json = gson.fromJson(file.readText(), JsonObject::class.java)
            val pid = json.get("pid")?.asLong ?: return null
            val sessionId = json.get("sessionId")?.asString ?: return null
            val cwd = json.get("cwd")?.asString ?: return null
            val startedAt = json.get("startedAt")?.asLong ?: 0L
            ClaudeSession(pid = pid, sessionId = sessionId, cwd = cwd, startedAt = startedAt)
        } catch (e: Exception) {
            log.warn("Could not parse session file ${file.name}: ${e.message}")
            null
        }
    }

    private fun enrichSession(session: ClaudeSession) {
        val alive = ProcessHandle.of(session.pid).map { it.isAlive }.orElse(false)
        if (!alive) {
            session.state = SessionState.FINISHED
            return
        }

        val jsonlFile = findJsonlFile(session)
        val lastModified = jsonlFile?.lastModified()?.let { Instant.ofEpochMilli(it) }

        if (lastModified != null) session.lastActivityAt = lastModified

        val secondsSinceActivity = if (lastModified != null)
            Instant.now().epochSecond - lastModified.epochSecond
        else Long.MAX_VALUE

        // Read tail lines once and reuse for both state detection and last message
        val tailLines = if (jsonlFile != null) readTailLines(jsonlFile) else emptyList()

        session.state = when {
            secondsSinceActivity < ACTIVITY_THRESHOLD_SECONDS || session.cpuPercent > CPU_RUNNING_THRESHOLD -> SessionState.RUNNING
            else -> determineWaitState(tailLines)
        }

        session.lastAssistantMessage = extractLastAssistantSnippet(tailLines)
        session.environment = detectEnvironment(session.pid)
    }

    // ------------------------------------------------------------------
    // JSONL helpers — read only the tail to avoid loading entire histories
    // ------------------------------------------------------------------

    /** Read the last few KB of a file and return the non-blank lines. */
    private fun readTailLines(file: File): List<String> {
        val size = file.length()
        if (size == 0L) return emptyList()
        val readSize = minOf(TAIL_BUFFER_BYTES.toLong(), size).toInt()
        val buffer = ByteArray(readSize)
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(size - readSize)
                raf.readFully(buffer)
            }
        } catch (e: Exception) {
            log.debug("Could not read tail of ${file.name}: ${e.message}")
            return emptyList()
        }
        return String(buffer, Charsets.UTF_8).lines().filter { it.isNotBlank() }
    }

    private fun determineWaitState(tailLines: List<String>): SessionState {
        val lastLine = tailLines.lastOrNull() ?: return SessionState.WAITING_FOR_INPUT
        return try {
            val entry = gson.fromJson(lastLine, JsonObject::class.java)
            val message = entry.getAsJsonObject("message") ?: return SessionState.WAITING_FOR_INPUT
            val role = message.get("role")?.asString
            val content = message.getAsJsonArray("content")
            when {
                role == "assistant" -> {
                    val hasToolUse = content?.any { it.asJsonObject?.get("type")?.asString == "tool_use" } ?: false
                    if (hasToolUse) SessionState.WAITING_FOR_ACCEPT else SessionState.WAITING_FOR_INPUT
                }
                role == "user" -> SessionState.RUNNING
                else -> SessionState.WAITING_FOR_INPUT
            }
        } catch (e: JsonSyntaxException) {
            SessionState.WAITING_FOR_INPUT
        }
    }

    private fun extractLastAssistantSnippet(tailLines: List<String>): String {
        val lastAssistantLine = tailLines.lastOrNull { line ->
            try {
                gson.fromJson(line, JsonObject::class.java)
                    .getAsJsonObject("message")?.get("role")?.asString == "assistant"
            } catch (_: Exception) { false }
        } ?: return ""

        return try {
            val entry = gson.fromJson(lastAssistantLine, JsonObject::class.java)
            val content = entry.getAsJsonObject("message")
                ?.getAsJsonArray("content")
                ?.firstOrNull()?.asJsonObject
            when (content?.get("type")?.asString) {
                "text" -> content.get("text")?.asString?.takeLast(120)?.trimStart() ?: ""
                "tool_use" -> "Using tool: ${content.get("name")?.asString ?: "unknown"}"
                else -> ""
            }
        } catch (_: Exception) { "" }
    }

    // ------------------------------------------------------------------
    // Environment detection – walk the process tree to find JetBrains
    // ------------------------------------------------------------------

    private fun detectEnvironment(pid: Long): SessionEnvironment {
        return try {
            var handle: ProcessHandle = ProcessHandle.of(pid).orElse(null) ?: return SessionEnvironment.UNKNOWN
            if (!handle.isAlive) return SessionEnvironment.UNKNOWN

            var depth = 0
            while (depth < MAX_PROCESS_TREE_DEPTH) {
                val parent = handle.parent().orElse(null) ?: break
                val command = parent.info().command().orElse("")
                if (isJetBrainsProcess(command)) return SessionEnvironment.JETBRAINS_TERMINAL
                handle = parent
                depth++
            }
            SessionEnvironment.EXTERNAL_TERMINAL
        } catch (_: Exception) {
            SessionEnvironment.UNKNOWN
        }
    }

    private fun isJetBrainsProcess(command: String): Boolean {
        val lower = command.lowercase()
        return JETBRAINS_NAMES.any { lower.contains(it) }
    }

    // ------------------------------------------------------------------
    // File lookup
    // ------------------------------------------------------------------

    private fun findJsonlFile(session: ClaudeSession): File? {
        val projectsDir = File(System.getProperty("user.home"), ".claude/projects")
        val projectDir = File(projectsDir, session.encodedProjectPath).takeIf { it.isDirectory }
            ?: projectsDir.listFiles()?.firstOrNull { dir ->
                dir.isDirectory && cwdMatchesDir(session.cwd, dir.name)
            }
            ?: return null
        val direct = File(projectDir, "${session.sessionId}.jsonl")
        if (direct.exists()) return direct
        return projectDir.listFiles { f -> f.extension == "jsonl" }?.maxByOrNull { it.lastModified() }
    }

    private fun cwdMatchesDir(cwd: String, dirName: String): Boolean {
        val encoded = cwd.trimEnd('/').replace("/", "-").trimStart('-')
        return dirName.trimStart('-') == encoded || dirName == cwd.trimEnd('/').replace("/", "-")
    }

    // ------------------------------------------------------------------
    // CPU — one ps(1) call for all PIDs at once
    // ------------------------------------------------------------------

    private fun batchGetCpu(pids: List<Long>): Map<Long, Double> {
        if (pids.isEmpty()) return emptyMap()
        return try {
            val cmd = listOf("ps", "-p", pids.joinToString(","), "-o", "pid=,%cpu=")
            val process = ProcessBuilder(cmd).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(5, TimeUnit.SECONDS)
            output.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 2) parts[0].toLongOrNull()?.let { it to (parts[1].toDoubleOrNull() ?: 0.0) }
                    else null
                }
                .toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ------------------------------------------------------------------
    // State change notifications
    // ------------------------------------------------------------------

    private fun detectStateChanges(current: List<ClaudeSession>) {
        for (session in current) {
            val prev = lastKnownSessions[session.pid] ?: continue
            if (prev == session.state) continue
            when (session.state) {
                SessionState.WAITING_FOR_INPUT ->
                    notify(session, "Claude is waiting for your input", NotificationType.INFORMATION)
                SessionState.WAITING_FOR_ACCEPT ->
                    notify(session, "Claude needs tool approval", NotificationType.WARNING)
                SessionState.FINISHED ->
                    notify(session, "Claude session finished", NotificationType.INFORMATION)
                else -> {}
            }
        }
        lastKnownSessions = current.associateBy({ it.pid }, { it.state })
    }

    private fun notify(session: ClaudeSession, message: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            try {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Claude Code Sessions")
                    ?.createNotification(title = session.projectName, content = message, type = type)
                    ?.notify(null)
            } catch (e: Exception) {
                log.debug("Notification failed: ${e.message}")
            }
        }
    }

    companion object {
        const val POLL_INTERVAL_SECONDS = 2L
        const val ACTIVITY_THRESHOLD_SECONDS = 3
        const val CPU_RUNNING_THRESHOLD = 5.0
        const val TAIL_BUFFER_BYTES = 8 * 1024
        const val MAX_PROCESS_TREE_DEPTH = 15

        private val JETBRAINS_NAMES = listOf(
            "idea", "phpstorm", "webstorm", "pycharm", "rubymine",
            "goland", "clion", "rider", "datagrip", "appcode", "fleet",
            "intellij", "android-studio", "studio"
        )

        fun getInstance(): ClaudeSessionMonitorService =
            ApplicationManager.getApplication().getService(ClaudeSessionMonitorService::class.java)
    }
}
