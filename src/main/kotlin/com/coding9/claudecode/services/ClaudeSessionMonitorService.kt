package com.coding9.claudecode.services

import com.coding9.claudecode.model.ClaudeSession
import com.coding9.claudecode.model.SessionEnvironment
import com.coding9.claudecode.model.SessionState
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.coding9.claudecode.actions.OpenSessionAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.ProjectManager
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
    private data class JsonlScan(
        val lineCount: Int,
        val title: String,
        val model: String,
        val contextTokens: Long,
        val permissionMode: String
    )
    private data class JsonlScanEntry(val lastModified: Long, val scan: JsonlScan)
    private data class EnvironmentEntry(val environment: SessionEnvironment, val timestamp: Long)
    private data class SessionFileEntry(val lastModified: Long, val session: ClaudeSession?)
    private data class JsonlFileEntry(val path: File?, val timestamp: Long)

    private val jsonlScanCache = HashMap<String, JsonlScanEntry>()           // filePath -> cached scan
    private val decodedPathCache = HashMap<String, String>()                  // encoded dir name -> decoded path
    private val environmentCache = HashMap<Long, EnvironmentEntry>()          // pid -> cached env
    private val sessionFileCache = HashMap<String, SessionFileEntry>()        // filePath -> cached parse
    private val jsonlFileCache = HashMap<String, JsonlFileEntry>()            // sessionId -> cached path

    @Volatile private var started = false
    @Volatile private var scheduledFuture: ScheduledFuture<*>? = null

    @Volatile
    var sessions: List<ClaudeSession> = emptyList()
        private set

    // Only active (running) sessions — used internally by loadHistorySessions() to exclude them
    // Accessed only from the executor thread
    private var activeSessions: List<ClaudeSession> = emptyList()

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
        activeSessions = fresh
        val history = loadHistorySessions()
        val combined = fresh + history
        sessions = combined
        detectStateChanges(combined)

        // Evict cache entries for PIDs/sessions that no longer exist
        val activePids = fresh.map { it.pid }.toSet()
        val allSessionIds = combined.map { it.sessionId }.toSet()
        environmentCache.keys.removeAll { it !in activePids }
        jsonlFileCache.keys.removeAll { it !in allSessionIds }

        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it.onSessions(combined) }
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
            ClaudeSession(
                pid = pid,
                sessionId = sessionId,
                cwd = cwd,
                startedAt = startedAt,
                name = json.get("name")?.asString ?: "",
                nativeStatus = json.get("status")?.asString ?: "",
                cliVersion = json.get("version")?.asString ?: ""
            )
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

        var scan: JsonlScan? = null
        if (jsonlFile != null && jsonlFile.exists()) {
            session.contextBytes = jsonlFile.length()
            scan = scanJsonlFile(jsonlFile)
            applyScan(session, scan)
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

        // The CLI (2.x) reports its own busy/idle state in sessions/<pid>.json —
        // use it directly and only fall back to mtime/CPU heuristics for older CLIs.
        session.state = when (session.nativeStatus) {
            "busy" -> SessionState.RUNNING
            "idle" -> determineWaitState(tailLines, scan?.permissionMode ?: "")
            else -> {
                val waitState = determineWaitState(tailLines, scan?.permissionMode ?: "")
                // Tools (bash, file ops, etc.) can run for tens of seconds without writing to the
                // JSONL. Use a longer inactivity threshold before declaring WAITING_FOR_ACCEPT,
                // so we don't falsely show "Accept needed" while the tool is still executing.
                val threshold = if (waitState == SessionState.WAITING_FOR_ACCEPT)
                    TOOL_RUNNING_THRESHOLD_SECONDS
                else
                    ACTIVITY_THRESHOLD_SECONDS
                when {
                    secondsSinceActivity < threshold || session.cpuPercent > CPU_RUNNING_THRESHOLD -> SessionState.RUNNING
                    else -> waitState
                }
            }
        }

        session.lastAssistantMessage = extractLastAssistantSnippet(tailLines)
        session.environment = detectEnvironment(session.pid)
    }

    private fun applyScan(session: ClaudeSession, scan: JsonlScan) {
        session.turnCount = scan.lineCount
        if (session.title.isEmpty()) session.title = scan.title
        session.model = scan.model
        session.contextTokens = scan.contextTokens
        session.contextWindow = contextWindowFor(scan.model)
    }

    /** Context window in tokens for a model id. Sonnet 5 and [1m] variants have a 1M window. */
    private fun contextWindowFor(model: String): Long = when {
        model.isEmpty() -> 0L
        model.contains("sonnet-5") || model.contains("[1m]") -> CONTEXT_WINDOW_1M
        else -> CONTEXT_WINDOW_DEFAULT
    }

    /**
     * Single cached pass over the JSONL transcript (invalidated by mtime): counts lines and
     * collects the latest ai-title, permission mode and the last assistant turn's model/usage.
     * Lines are pre-filtered with cheap substring checks; only two lines get JSON-parsed.
     */
    private fun scanJsonlFile(file: File): JsonlScan {
        val path = file.absolutePath
        val lastMod = file.lastModified()
        val cached = jsonlScanCache[path]
        if (cached != null && cached.lastModified == lastMod) return cached.scan

        var count = 0
        var lastAssistantLine: String? = null
        var lastTitleLine: String? = null
        var lastPermissionLine: String? = null
        try {
            file.bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    count++
                    when {
                        line.contains("\"type\":\"assistant\"") &&
                            !line.contains("\"isSidechain\":true") -> lastAssistantLine = line
                        line.contains("\"type\":\"ai-title\"") -> lastTitleLine = line
                        line.contains("\"type\":\"permission-mode\"") -> lastPermissionLine = line
                    }
                    line = reader.readLine()
                }
            }
        } catch (_: Exception) { /* partial scan is fine */ }

        var title = ""
        var model = ""
        var contextTokens = 0L
        var permissionMode = ""
        try {
            lastTitleLine?.let { title = gson.fromJson(it, JsonObject::class.java).get("aiTitle")?.asString ?: "" }
        } catch (_: Exception) { }
        try {
            lastPermissionLine?.let {
                permissionMode = gson.fromJson(it, JsonObject::class.java).get("permissionMode")?.asString ?: ""
            }
        } catch (_: Exception) { }
        try {
            lastAssistantLine?.let { line ->
                val message = gson.fromJson(line, JsonObject::class.java).getAsJsonObject("message")
                model = message?.get("model")?.asString ?: ""
                val usage = message?.getAsJsonObject("usage")
                if (usage != null) {
                    contextTokens = (usage.get("input_tokens")?.asLong ?: 0L) +
                        (usage.get("cache_read_input_tokens")?.asLong ?: 0L) +
                        (usage.get("cache_creation_input_tokens")?.asLong ?: 0L)
                }
            }
        } catch (_: Exception) { }

        val scan = JsonlScan(count, title, model, contextTokens, permissionMode)
        jsonlScanCache[path] = JsonlScanEntry(lastMod, scan)
        return scan
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

    /**
     * Determine what an idle session is waiting for by walking the transcript tail backwards
     * to the last real conversation entry. JSONL lines are type-discriminated (user, assistant,
     * system, attachment, ai-title, file-history-*, ...) — everything except user/assistant
     * turns is skipped, as are meta and sidechain (subagent) lines.
     */
    private fun determineWaitState(tailLines: List<String>, permissionMode: String): SessionState {
        for (line in tailLines.asReversed()) {
            val entry = try {
                gson.fromJson(line, JsonObject::class.java)
            } catch (_: JsonSyntaxException) {
                continue  // first tail line may be cut off mid-JSON
            } ?: continue
            val type = entry.get("type")?.asString ?: continue
            if (type != "user" && type != "assistant") continue
            if (entry.get("isSidechain")?.asBoolean == true) continue
            if (entry.get("isMeta")?.asBoolean == true) continue
            val message = entry.getAsJsonObject("message") ?: continue

            if (type == "user") {
                // A user turn (prompt or tool_result) means Claude has work to do next
                return SessionState.RUNNING
            }

            val content = message.get("content")?.takeIf { it.isJsonArray }?.asJsonArray
            val hasToolUse = content?.any {
                it.isJsonObject && it.asJsonObject.get("type")?.asString == "tool_use"
            } ?: false
            // In auto-accepting permission modes a trailing tool_use is not waiting on the user
            val autoAccepts = permissionMode == "bypassPermissions" || permissionMode == "acceptEdits"
            return if (hasToolUse && !autoAccepts) SessionState.WAITING_FOR_ACCEPT
                   else SessionState.WAITING_FOR_INPUT
        }
        return SessionState.WAITING_FOR_INPUT
    }

    private fun extractLastAssistantSnippet(tailLines: List<String>): String {
        for (line in tailLines.asReversed()) {
            val entry = try {
                gson.fromJson(line, JsonObject::class.java)
            } catch (_: Exception) {
                continue
            } ?: continue
            if (entry.get("type")?.asString != "assistant") continue
            if (entry.get("isSidechain")?.asBoolean == true) continue

            val content = entry.getAsJsonObject("message")
                ?.get("content")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.filter { it.isJsonObject }?.map { it.asJsonObject }
                ?: continue
            val text = content.lastOrNull { it.get("type")?.asString == "text" }
                ?.get("text")?.asString
            if (!text.isNullOrBlank()) return text.takeLast(120).trimStart()
            val toolUse = content.lastOrNull { it.get("type")?.asString == "tool_use" }
            if (toolUse != null) return "Using tool: ${toolUse.get("name")?.asString ?: "unknown"}"
        }
        return ""
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
        val encoded = ClaudeSession.encodeProjectPath(cwd)
        // Also accept the legacy encoding (only slashes replaced) for old project dirs
        val legacy = cwd.trimEnd('/').replace("/", "-")
        return dirName == encoded || dirName == legacy ||
               dirName.trimStart('-') == encoded.trimStart('-')
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
                val notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup("Claude Code Sessions")
                    ?.createNotification(
                        title = session.displayName,
                        content = message,
                        type = type
                    ) ?: return@invokeLater

                notification.addAction(object : AnAction("Open") {
                    override fun actionPerformed(e: AnActionEvent) {
                        notification.expire()
                        val project = findProjectForSession(session)
                            ?: ProjectManager.getInstance().openProjects.firstOrNull()
                            ?: return
                        OpenSessionAction.openSession(project, session)
                    }
                })

                notification.notify(findProjectForSession(session))
            } catch (e: Exception) {
                log.debug("Notification failed: ${e.message}")
            }
        }
    }

    private fun findProjectForSession(session: ClaudeSession): com.intellij.openapi.project.Project? {
        return ProjectManager.getInstance().openProjects.firstOrNull { project ->
            val base = project.basePath ?: return@firstOrNull false
            try { File(base).canonicalPath == File(session.cwd).canonicalPath } catch (_: Exception) { false }
        }
    }

    // ------------------------------------------------------------------
    // Session history — scan JSONL files for past sessions
    // ------------------------------------------------------------------

    fun loadHistorySessions(): List<ClaudeSession> {
        val projectsDir = File(System.getProperty("user.home"), ".claude/projects")
        if (!projectsDir.isDirectory) return emptyList()

        val activeSessionIds = activeSessions.map { it.sessionId }.toSet()
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
                val scan = scanJsonlFile(jsonlFile)
                val tailLines = readTailLines(jsonlFile)
                val lastMessage = extractLastAssistantSnippet(tailLines)

                val session = ClaudeSession(
                    pid = 0L,
                    sessionId = sessionId,
                    cwd = cwd,
                    startedAt = startedAt,
                    state = SessionState.FINISHED,
                    lastActivityAt = Instant.ofEpochMilli(lastModified),
                    lastAssistantMessage = lastMessage,
                    contextBytes = fileSize
                )
                applyScan(session, scan)
                historySessions.add(session)
            }
        }

        return historySessions.sortedByDescending { it.lastActivityAt }.take(MAX_HISTORY_SESSIONS)
    }

    /**
     * Decode an encoded project directory name back to a filesystem path.
     * The encoding replaces every non-alphanumeric character with `-`, so
     * `/Users/alex/Work/my.proj` becomes `-Users-alex-Work-my-proj`. Since that is lossy,
     * we walk the real filesystem and match directory names by their encoded form
     * (handles dashes and dots in directory names).
     */
    private fun decodeProjectPath(encoded: String): String {
        decodedPathCache[encoded]?.let { return it }
        val target = encoded.trimStart('-')
        val decoded = matchEncodedPath(File("/"), target)
            ?: ("/" + target.split("-").joinToString("/"))
        decodedPathCache[encoded] = decoded
        return decoded
    }

    private fun matchEncodedPath(dir: File, remaining: String): String? {
        if (remaining.isEmpty()) return dir.absolutePath
        val entries = dir.listFiles { f -> f.isDirectory } ?: return null
        // Longest names first so "my-app-v2" wins over "my-app" when both match
        for (entry in entries.sortedByDescending { it.name.length }) {
            val enc = entry.name.replace(Regex("[^A-Za-z0-9]"), "-")
            if (enc.isEmpty()) continue
            if (remaining == enc) return entry.absolutePath
            if (remaining.startsWith("$enc-")) {
                matchEncodedPath(entry, remaining.removePrefix("$enc-"))?.let { return it }
            }
        }
        // Deleted leaf directory: accept the remainder as a single segment under an existing parent
        return if (!remaining.contains('-')) "${dir.absolutePath.trimEnd('/')}/$remaining" else null
    }

    companion object {
        const val POLL_INTERVAL_SECONDS = 2L
        const val MAX_HISTORY_SESSIONS = 200
        const val ACTIVITY_THRESHOLD_SECONDS = 3
        const val TOOL_RUNNING_THRESHOLD_SECONDS = 30  // tools can run for 30s+ without JSONL writes
        const val CPU_RUNNING_THRESHOLD = 5.0
        const val TAIL_BUFFER_BYTES = 8 * 1024
        const val MAX_PROCESS_TREE_DEPTH = 15
        const val ENV_CACHE_TTL_MS = 30_000L       // environment detection cache: 30s
        const val JSONL_CACHE_TTL_MS = 10_000L     // null-result JSONL file lookup cache: 10s

        /** Default model context window (tokens). */
        const val CONTEXT_WINDOW_DEFAULT = 200_000L

        /** 1M context window (Sonnet 5 native, and `[1m]` model variants). */
        const val CONTEXT_WINDOW_1M = 1_000_000L

        /**
         * Fallback only (no usage data in the transcript): estimated JSONL file size
         * for a full 200K token context (~2MB with JSON overhead).
         */
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
