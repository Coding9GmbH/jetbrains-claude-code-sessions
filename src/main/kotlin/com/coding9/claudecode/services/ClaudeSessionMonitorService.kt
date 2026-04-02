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
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
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

    // Caches — accessed only from the single executor thread
    private data class LineCountEntry(val lastModified: Long, val lineCount: Int)
    private data class EnvironmentEntry(val environment: SessionEnvironment, val timestamp: Long)
    private data class SessionFileEntry(val lastModified: Long, val session: ClaudeSession?)
    private data class JsonlFileEntry(val path: File?, val timestamp: Long)

    private val lineCountCache = HashMap<String, LineCountEntry>()           // filePath -> cached count
    private val environmentCache = HashMap<Long, EnvironmentEntry>()          // pid -> cached env
    private val sessionFileCache = HashMap<String, SessionFileEntry>()        // filePath -> cached parse
    private val jsonlFileCache = HashMap<String, JsonlFileEntry>()            // sessionId -> cached path

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

        // Evict cache entries for PIDs/sessions that no longer exist
        val activePids = fresh.map { it.pid }.toSet()
        val activeSessionIds = fresh.map { it.sessionId }.toSet()
        environmentCache.keys.removeAll { it !in activePids }
        jsonlFileCache.keys.removeAll { it !in activeSessionIds }

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
        val path = file.absolutePath
        val lastMod = file.lastModified()
        val cached = sessionFileCache[path]
        if (cached != null && cached.lastModified == lastMod) {
            // Return a copy so enrichSession can mutate it independently
            return cached.session?.copy()
        }
        val result = try {
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
        sessionFileCache[path] = SessionFileEntry(lastMod, result)
        return result?.copy()
    }

    private fun enrichSession(session: ClaudeSession) {
        val alive = ProcessHandle.of(session.pid).map { it.isAlive }.orElse(false)
        val jsonlFile = findJsonlFile(session)

        // Context usage: file size + fast line count
        if (jsonlFile != null && jsonlFile.exists()) {
            session.contextBytes = jsonlFile.length()
            session.turnCount = countFileLines(jsonlFile)
        }

        if (!alive) {
            session.state = SessionState.FINISHED
            // Still read last message for finished sessions
            val tailLines = if (jsonlFile != null) readTailLines(jsonlFile) else emptyList()
            session.lastAssistantMessage = extractLastAssistantSnippet(tailLines)
            return
        }

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

    /** Line count cached by file modification time — avoids re-reading the entire file every poll. */
    private fun countFileLines(file: File): Int {
        val path = file.absolutePath
        val lastMod = file.lastModified()
        val cached = lineCountCache[path]
        if (cached != null && cached.lastModified == lastMod) return cached.lineCount

        val count = try {
            file.bufferedReader().use { reader ->
                var c = 0
                while (reader.readLine() != null) c++
                c
            }
        } catch (_: Exception) { 0 }
        lineCountCache[path] = LineCountEntry(lastMod, count)
        return count
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
        val now = System.currentTimeMillis()
        val cached = environmentCache[pid]
        if (cached != null && (now - cached.timestamp) < ENV_CACHE_TTL_MS) return cached.environment

        val result = try {
            var handle: ProcessHandle = ProcessHandle.of(pid).orElse(null) ?: return SessionEnvironment.UNKNOWN
            if (!handle.isAlive) return SessionEnvironment.UNKNOWN

            var depth = 0
            while (depth < MAX_PROCESS_TREE_DEPTH) {
                val parent = handle.parent().orElse(null) ?: break
                val command = parent.info().command().orElse("")
                if (isJetBrainsProcess(command)) {
                    environmentCache[pid] = EnvironmentEntry(SessionEnvironment.JETBRAINS_TERMINAL, now)
                    return SessionEnvironment.JETBRAINS_TERMINAL
                }
                handle = parent
                depth++
            }
            SessionEnvironment.EXTERNAL_TERMINAL
        } catch (_: Exception) {
            SessionEnvironment.UNKNOWN
        }
        environmentCache[pid] = EnvironmentEntry(result, now)
        return result
    }

    private fun isJetBrainsProcess(command: String): Boolean {
        val lower = command.lowercase()
        return JETBRAINS_NAMES.any { lower.contains(it) }
    }

    // ------------------------------------------------------------------
    // File lookup
    // ------------------------------------------------------------------

    private fun findJsonlFile(session: ClaudeSession): File? {
        val now = System.currentTimeMillis()
        val cached = jsonlFileCache[session.sessionId]
        // Re-validate cached path: still exists? If yes, reuse. TTL for null results to retry.
        if (cached != null) {
            if (cached.path != null && cached.path.exists()) return cached.path
            if (cached.path == null && (now - cached.timestamp) < JSONL_CACHE_TTL_MS) return null
        }

        val projectsDir = File(System.getProperty("user.home"), ".claude/projects")
        val projectDir = File(projectsDir, session.encodedProjectPath).takeIf { it.isDirectory }
            ?: projectsDir.listFiles()?.firstOrNull { dir ->
                dir.isDirectory && cwdMatchesDir(session.cwd, dir.name)
            }
            ?: run { jsonlFileCache[session.sessionId] = JsonlFileEntry(null, now); return null }
        val direct = File(projectDir, "${session.sessionId}.jsonl")
        val result = if (direct.exists()) direct
            else projectDir.listFiles { f -> f.extension == "jsonl" }?.maxByOrNull { it.lastModified() }
        jsonlFileCache[session.sessionId] = JsonlFileEntry(result, now)
        return result
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
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            // Read output BEFORE waitFor to avoid deadlock on full pipe buffer
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return emptyMap()
            }
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

    // ------------------------------------------------------------------
    // Session history — scan JSONL files for past sessions
    // ------------------------------------------------------------------

    fun loadHistorySessions(): List<ClaudeSession> {
        val projectsDir = File(System.getProperty("user.home"), ".claude/projects")
        if (!projectsDir.isDirectory) return emptyList()

        val activeSessionIds = sessions.map { it.sessionId }.toSet()
        val historySessions = mutableListOf<ClaudeSession>()

        projectsDir.listFiles { f -> f.isDirectory }?.forEach { projectDir ->
            val jsonlFiles = projectDir.listFiles { f -> f.extension == "jsonl" } ?: return@forEach

            for (jsonlFile in jsonlFiles) {
                val sessionId = jsonlFile.nameWithoutExtension
                if (sessionId in activeSessionIds) continue
                if (jsonlFile.length() == 0L) continue

                val cwd = decodeProjectPath(projectDir.name)
                val lastModified = jsonlFile.lastModified()
                val startedAt = try {
                    val attrs = Files.readAttributes(jsonlFile.toPath(), BasicFileAttributes::class.java)
                    attrs.creationTime().toMillis()
                } catch (_: Exception) { lastModified }

                val fileSize = jsonlFile.length()
                val lineCount = countFileLines(jsonlFile)
                val tailLines = readTailLines(jsonlFile)
                val lastMessage = extractLastAssistantSnippet(tailLines)

                historySessions.add(ClaudeSession(
                    pid = 0L,
                    sessionId = sessionId,
                    cwd = cwd,
                    startedAt = startedAt,
                    state = SessionState.FINISHED,
                    lastActivityAt = Instant.ofEpochMilli(lastModified),
                    lastAssistantMessage = lastMessage,
                    contextBytes = fileSize,
                    turnCount = lineCount
                ))
            }
        }

        return historySessions.sortedByDescending { it.lastActivityAt }.take(MAX_HISTORY_SESSIONS)
    }

    /**
     * Decode an encoded project directory name back to a filesystem path.
     * The encoding is `cwd.replace("/", "-")`, so `/Users/alex/Work/proj` becomes `-Users-alex-Work-proj`.
     * We walk the filesystem to handle directory names that contain dashes.
     */
    private fun decodeProjectPath(encoded: String): String {
        val parts = encoded.trimStart('-').split("-")
        if (parts.isEmpty()) return encoded
        return tryReconstructPath(parts, "", 0) ?: ("/" + parts.joinToString("/"))
    }

    private fun tryReconstructPath(parts: List<String>, current: String, idx: Int): String? {
        if (idx >= parts.size) return current
        // Try longest match first to handle dashes in directory names
        for (end in parts.size downTo idx + 1) {
            val segment = parts.subList(idx, end).joinToString("-")
            val candidate = "$current/$segment"
            if (end == parts.size) {
                // Last segment(s) — accept if parent exists or if full path exists
                val parentExists = current.isEmpty() || File(current).isDirectory
                if (parentExists) return candidate
            } else if (File(candidate).isDirectory) {
                val result = tryReconstructPath(parts, candidate, end)
                if (result != null) return result
            }
        }
        return null
    }

    companion object {
        const val POLL_INTERVAL_SECONDS = 2L
        const val MAX_HISTORY_SESSIONS = 200
        const val ACTIVITY_THRESHOLD_SECONDS = 3
        const val CPU_RUNNING_THRESHOLD = 5.0
        const val TAIL_BUFFER_BYTES = 8 * 1024
        const val MAX_PROCESS_TREE_DEPTH = 15
        const val ENV_CACHE_TTL_MS = 30_000L       // environment detection cache: 30s
        const val JSONL_CACHE_TTL_MS = 10_000L     // null-result JSONL file lookup cache: 10s

        /** Estimated JSONL file size for a full 200K token context (~2MB with JSON overhead). */
        const val ESTIMATED_FULL_CONTEXT_BYTES = 2_000_000L

        private val JETBRAINS_NAMES = listOf(
            "idea", "phpstorm", "webstorm", "pycharm", "rubymine",
            "goland", "clion", "rider", "datagrip", "appcode", "fleet",
            "intellij", "android-studio", "studio"
        )

        fun getInstance(): ClaudeSessionMonitorService =
            ApplicationManager.getApplication().getService(ClaudeSessionMonitorService::class.java)
    }
}
