package com.coding9.claudecode.actions

import com.coding9.claudecode.model.ClaudeSession
import com.coding9.claudecode.model.SessionEnvironment
import com.coding9.claudecode.model.SessionState
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.*

/**
 * Logic for "opening" a Claude session:
 *
 * Active sessions:
 *  1. Focus the actual terminal window where Claude is running
 *     (iTerm2 or Terminal.app, detected via the process's TTY)
 *  2. Claude is running inside the IDE's own terminal
 *     → bring the IDE window + terminal tab to the front
 *  3. Nothing worked → offer to open the project in the IDE
 *
 * Finished sessions:
 *  → Show a dialog to choose between Terminal or JetBrains IDE,
 *    then resume with `claude --resume <sessionId>`
 */
object OpenSessionAction {

    private val log = thisLogger()
    private val IS_MAC = System.getProperty("os.name", "").lowercase().contains("mac")
    private val IS_WINDOWS = System.getProperty("os.name", "").lowercase().contains("win")

    fun openSession(callerProject: Project, session: ClaudeSession) {
        if (session.state == SessionState.FINISHED) {
            showResumeDialog(callerProject, session)
            return
        }

        // --- Step 1: focus the real terminal window (iTerm2 / Terminal.app) ---
        // Skip if we already know it's running inside the IDE's built-in terminal
        if (IS_MAC && session.environment != SessionEnvironment.JETBRAINS_TERMINAL && focusExternalTerminal(session)) return

        // --- Step 2: maybe it's running inside the IDE's built-in terminal ---
        val openProject = findOpenProject(session.cwd)

        if (openProject != null) {
            focusProjectTerminal(openProject, session)
            return
        }

        // --- Step 3: fallback – just show where the session is ---
        notify(
            callerProject,
            "${session.projectName} is running in an external terminal.\n${session.cwd}\nPID: ${session.pid}",
            NotificationType.INFORMATION
        )
    }

    // =========================================================================
    // Resume dialog for finished sessions
    // =========================================================================

    fun showResumeDialog(callerProject: Project, session: ClaudeSession) {
        ApplicationManager.getApplication().invokeLater {
            val dialog = ResumeChoiceDialog(session)
            dialog.isVisible = true

            when (dialog.choice) {
                ResumeChoice.TERMINAL -> resumeInExternalTerminal(session)
                ResumeChoice.JETBRAINS -> resumeInJetBrains(callerProject, session)
                ResumeChoice.CANCELLED -> { /* do nothing */ }
            }
        }
    }

    private enum class ResumeChoice { TERMINAL, JETBRAINS, CANCELLED }

    private class ResumeChoiceDialog(session: ClaudeSession) : JDialog(
        null as Frame?, "Resume Session", true
    ) {
        var choice: ResumeChoice = ResumeChoice.CANCELLED
            private set

        init {
            defaultCloseOperation = DISPOSE_ON_CLOSE
            isResizable = false

            val content = JPanel(BorderLayout(0, JBUI.scale(12)))
            content.border = JBUI.Borders.empty(16, 20)

            // Header
            val headerLabel = JLabel(
                "<html><b>Resume session in '${session.projectName}'</b><br>" +
                "<span style='color:gray;font-size:11px'>${session.cwd}</span></html>"
            )
            headerLabel.icon = AllIcons.Actions.Restart
            headerLabel.iconTextGap = JBUI.scale(8)
            content.add(headerLabel, BorderLayout.NORTH)

            // Buttons panel
            val buttonsPanel = JPanel(GridLayout(1, 2, JBUI.scale(10), 0))

            val terminalBtn = createChoiceButton(
                "Terminal",
                "Open in external terminal",
                AllIcons.Debugger.Console
            ) {
                choice = ResumeChoice.TERMINAL
                dispose()
            }

            val jetbrainsBtn = createChoiceButton(
                "JetBrains IDE",
                "Open project & terminal in IDE",
                AllIcons.Nodes.IdeaProject
            ) {
                choice = ResumeChoice.JETBRAINS
                dispose()
            }

            buttonsPanel.add(terminalBtn)
            buttonsPanel.add(jetbrainsBtn)
            content.add(buttonsPanel, BorderLayout.CENTER)

            // Cancel
            val cancelBtn = JButton("Cancel")
            cancelBtn.addActionListener { dispose() }
            val cancelPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
            cancelPanel.add(cancelBtn)
            content.add(cancelPanel, BorderLayout.SOUTH)

            contentPane = content
            pack()
            setLocationRelativeTo(null)
        }

        private fun createChoiceButton(
            title: String, subtitle: String, icon: Icon, action: () -> Unit
        ): JButton {
            val btn = JButton("<html><center><b>$title</b><br><span style='font-size:10px;color:gray'>$subtitle</span></center></html>")
            btn.icon = icon
            btn.horizontalTextPosition = SwingConstants.CENTER
            btn.verticalTextPosition = SwingConstants.BOTTOM
            btn.iconTextGap = JBUI.scale(6)
            btn.preferredSize = Dimension(JBUI.scale(180), JBUI.scale(80))
            btn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            btn.addActionListener { action() }
            return btn
        }
    }

    // =========================================================================
    // Resume in external terminal
    // =========================================================================

    private fun resumeInExternalTerminal(session: ClaudeSession) {
        val escapedCwd = session.cwd.replace("'", "\\'")
        val resumeCmd = "cd '$escapedCwd' && claude --resume '${session.sessionId}'"

        try {
            when {
                IS_MAC -> {
                    val script = """
                        tell application "Terminal"
                            do script "$resumeCmd"
                            activate
                        end tell
                    """.trimIndent()
                    ProcessBuilder("osascript", "-e", script).start()
                }
                IS_WINDOWS -> {
                    try {
                        ProcessBuilder("wt", "-d", session.cwd, "cmd", "/c", "claude --resume ${session.sessionId}").start()
                    } catch (_: Exception) {
                        ProcessBuilder("cmd", "/c", "start", "cmd", "/k", "cd /d \"${session.cwd}\" && claude --resume ${session.sessionId}").start()
                    }
                }
                else -> {
                    // Linux: try common terminal emulators
                    val terminals = listOf(
                        listOf("gnome-terminal", "--", "bash", "-c", "$resumeCmd; exec bash"),
                        listOf("konsole", "-e", "bash", "-c", "$resumeCmd; exec bash"),
                        listOf("xfce4-terminal", "-e", "bash -c '$resumeCmd; exec bash'"),
                        listOf("xterm", "-e", "bash", "-c", "$resumeCmd; exec bash"),
                        listOf("x-terminal-emulator", "-e", "bash", "-c", "$resumeCmd; exec bash")
                    )
                    for (cmd in terminals) {
                        try {
                            ProcessBuilder(cmd).start()
                            return
                        } catch (_: Exception) { /* try next */ }
                    }
                    log.warn("Could not find a terminal emulator to resume session")
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to launch external terminal for resume", e)
        }
    }

    // =========================================================================
    // Resume in JetBrains IDE
    // =========================================================================

    private fun resumeInJetBrains(callerProject: Project, session: ClaudeSession) {
        // Check if the project is already open in any IDE window
        val openProject = findOpenProject(session.cwd)

        if (openProject != null) {
            createResumeTerminalTab(openProject, session)
            return
        }

        // Project not open → detect installed JetBrains IDEs and let user pick
        val installedIdes = detectInstalledIdes()
        if (installedIdes.isEmpty()) {
            // Fallback: open in current IDE
            openProjectInCurrentIde(callerProject, session)
            return
        }

        ApplicationManager.getApplication().invokeLater {
            val ideNames = installedIdes.map { it.name }.toTypedArray()
            val selected = JOptionPane.showInputDialog(
                null,
                "Select JetBrains IDE to open '${session.projectName}':",
                "Choose IDE",
                JOptionPane.QUESTION_MESSAGE,
                AllIcons.Nodes.IdeaProject,
                ideNames,
                ideNames.firstOrNull()
            ) as? String ?: return@invokeLater

            val ide = installedIdes.first { it.name == selected }
            launchIdeWithResume(ide, session)
        }
    }

    data class JetBrainsIde(val name: String, val command: String, val appPath: String?)

    private fun detectInstalledIdes(): List<JetBrainsIde> {
        val ides = mutableListOf<JetBrainsIde>()
        val knownApps = linkedMapOf(
            "PhpStorm" to "phpstorm",
            "IntelliJ IDEA" to "idea",
            "IntelliJ IDEA CE" to "idea",
            "WebStorm" to "webstorm",
            "PyCharm" to "pycharm",
            "GoLand" to "goland",
            "CLion" to "clion",
            "RubyMine" to "rubymine",
            "Rider" to "rider",
            "DataGrip" to "datagrip",
            "Android Studio" to "studio"
        )

        if (IS_MAC) {
            // macOS: check /Applications for .app bundles
            val appsDir = File("/Applications")
            if (appsDir.isDirectory) {
                appsDir.listFiles()?.forEach { app ->
                    if (!app.name.endsWith(".app")) return@forEach
                    for ((name, cmd) in knownApps) {
                        if (app.name.contains(name, ignoreCase = true)) {
                            ides.add(JetBrainsIde(app.nameWithoutExtension, cmd, app.absolutePath))
                        }
                    }
                }
            }
        }

        // Also check PATH for CLI launchers (Linux + macOS Toolbox)
        val whichCmd = if (IS_WINDOWS) "where" else "which"
        for ((name, cmd) in knownApps) {
            if (ides.any { it.command == cmd }) continue
            try {
                val result = ProcessBuilder(whichCmd, cmd).start()
                val path = result.inputStream.bufferedReader().use { it.readText() }.trim()
                result.waitFor(3, TimeUnit.SECONDS)
                if (result.exitValue() == 0 && path.isNotEmpty()) {
                    ides.add(JetBrainsIde(name, cmd, null))
                }
            } catch (_: Exception) { /* not found */ }
        }

        return ides
    }

    private fun launchIdeWithResume(ide: JetBrainsIde, session: ClaudeSession) {
        val resumeCmd = "claude --resume '${session.sessionId}'"

        try {
            val process = if (ide.appPath != null) {
                // macOS: open -a "AppPath" <projectDir>
                ProcessBuilder("open", "-a", ide.appPath, session.cwd).start()
            } else {
                // CLI launcher: <command> <projectDir>
                ProcessBuilder(ide.command, session.cwd).start()
            }
            process.waitFor(10, TimeUnit.SECONDS)

            // Copy the resume command to clipboard so the user can paste it in the terminal
            CopyPasteManager.getInstance().setContents(StringSelection(resumeCmd))
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Claude Code Sessions")
                ?.createNotification(
                    "Claude Code Sessions",
                    "Opening '${session.projectName}' in ${ide.name}.\n" +
                    "Resume command copied to clipboard:\n$resumeCmd\n\n" +
                    "Open a terminal in the IDE and paste to resume.",
                    NotificationType.INFORMATION
                )?.notify(null)
        } catch (e: Exception) {
            log.warn("Failed to launch IDE: ${ide.name}", e)
        }
    }

    private fun openProjectInCurrentIde(callerProject: Project, session: ClaudeSession) {
        ApplicationManager.getApplication().invokeLater {
            try {
                ProjectManager.getInstance().loadAndOpenProject(session.cwd)?.let { newProject ->
                    // Use a Swing Timer (auto-stops since isRepeats=false) to wait for project init
                    Timer(2000) { createResumeTerminalTab(newProject, session) }.apply {
                        isRepeats = false
                        start()
                    }
                }
            } catch (e: Exception) {
                log.warn("Could not open project: ${session.cwd}", e)
                notify(callerProject, "Could not open project: ${session.cwd}", NotificationType.ERROR)
            }
        }
    }

    private fun createResumeTerminalTab(project: Project, session: ClaudeSession) {
        ApplicationManager.getApplication().invokeLater {
            try {
                // Focus the IDE window
                val frame = WindowManager.getInstance().getFrame(project)
                frame?.toFront()
                frame?.requestFocus()

                // Open Terminal tool window
                val toolWindowManager = ToolWindowManager.getInstance(project)
                val terminalWindow = toolWindowManager.getToolWindow("Terminal")

                if (terminalWindow != null) {
                    terminalWindow.show()
                    // Create a new terminal tab with the resume command
                    createTerminalWithCommand(project, session.cwd, "claude --resume '${session.sessionId}'")
                } else {
                    notify(project, "Terminal not available. Run manually:\nclaude --resume '${session.sessionId}'", NotificationType.INFORMATION)
                }
            } catch (e: Exception) {
                log.warn("Could not create resume terminal tab", e)
            }
        }
    }

    private fun createTerminalWithCommand(project: Project, basePath: String, command: String) {
        try {
            val terminalViewClass = Class.forName("org.jetbrains.plugins.terminal.TerminalView")
            val getInstance = terminalViewClass.getMethod("getInstance", Project::class.java)
            val terminalView = getInstance.invoke(null, project)

            // Try to create a new shell widget
            val createMethod = terminalViewClass.methods.firstOrNull { m ->
                m.name == "createLocalShellWidget" && m.parameterCount >= 2
            }

            if (createMethod != null) {
                val widget = createMethod.invoke(terminalView, basePath, "Claude Resume")
                // Try to send the command to the widget
                sendCommandToWidget(widget, command)
            } else {
                // Fallback: copy command to clipboard and notify
                CopyPasteManager.getInstance().setContents(StringSelection(command))
                notify(project, "Resume command copied to clipboard:\n$command", NotificationType.INFORMATION)
            }
        } catch (_: ClassNotFoundException) {
            CopyPasteManager.getInstance().setContents(StringSelection(command))
            notify(project, "Terminal plugin not found. Resume command copied to clipboard:\n$command", NotificationType.INFORMATION)
        } catch (e: Exception) {
            log.warn("Could not create terminal with command", e)
            CopyPasteManager.getInstance().setContents(StringSelection(command))
            notify(project, "Resume command copied to clipboard:\n$command", NotificationType.INFORMATION)
        }
    }

    private fun sendCommandToWidget(widget: Any?, command: String) {
        if (widget == null) return
        try {
            // Try ShellTerminalWidget.executeCommand (newer API)
            val executeMethod = widget.javaClass.methods.firstOrNull { m ->
                m.name == "executeCommand" && m.parameterCount == 1
            }
            if (executeMethod != null) {
                executeMethod.invoke(widget, command)
                return
            }

            // Fallback: try sendCommandToExecute
            val sendMethod = widget.javaClass.methods.firstOrNull { m ->
                m.name == "sendCommandToExecute" && m.parameterCount == 1
            }
            if (sendMethod != null) {
                sendMethod.invoke(widget, command)
                return
            }

            // Last resort: type the command via the terminal's input
            val typedMethod = widget.javaClass.methods.firstOrNull { m ->
                m.name == "writePlainMessage" || m.name == "writeCharacter"
            }
            if (typedMethod != null) {
                typedMethod.invoke(widget, command + "\n")
            }
        } catch (e: Exception) {
            log.debug("Could not send command to terminal widget: ${e.message}")
        }
    }

    // =========================================================================
    // Focus active session in JetBrains project terminal
    // =========================================================================

    private fun focusProjectTerminal(project: Project, session: ClaudeSession) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val frame = WindowManager.getInstance().getFrame(project)
                frame?.toFront()
                frame?.requestFocus()

                val toolWindowManager = ToolWindowManager.getInstance(project)
                val terminalWindow = toolWindowManager.getToolWindow("Terminal")
                if (terminalWindow != null) {
                    terminalWindow.show()
                    focusTerminalTabByPid(terminalWindow, session.pid)
                } else {
                    notify(project, "Switched to project '${session.projectName}'.\nOpen the Terminal to interact with Claude.", NotificationType.INFORMATION)
                }
            } catch (e: Exception) {
                log.warn("Could not focus project terminal", e)
            }
        }
    }

    private fun focusTerminalTabByPid(
        toolWindow: com.intellij.openapi.wm.ToolWindow,
        targetPid: Long
    ) {
        // Get the TTY of the Claude process and all sibling PIDs on that TTY.
        // This is more reliable than trying to match the exact PID via reflection,
        // because the terminal tab owns the shell process (Claude's parent), not Claude itself.
        val tty = getProcessTty(targetPid)
        val ttyPids: Set<Long> = if (tty != null) getPidsOnTty(tty) else emptySet()
        val shellPid = ProcessHandle.of(targetPid).flatMap { it.parent() }.map { it.pid() }.orElse(-1L)

        val contentManager = toolWindow.contentManager
        for (i in 0 until contentManager.contentCount) {
            val content = contentManager.getContent(i) ?: continue
            val tabPid = extractAnyPid(content.component) ?: continue
            if (tabPid == targetPid || tabPid == shellPid || tabPid in ttyPids) {
                contentManager.setSelectedContent(content, true)
                return
            }
        }
    }

    /** Return all PIDs running on the given tty (e.g. "s002"). */
    private fun getPidsOnTty(tty: String): Set<Long> {
        return try {
            val devTty = if (tty.startsWith("/dev/")) tty else "/dev/$tty"
            val process = ProcessBuilder("ps", "-t", devTty, "-o", "pid=").start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(3, TimeUnit.SECONDS)
            output.lines().mapNotNull { it.trim().toLongOrNull() }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    /**
     * Walk the component tree and its object fields (breadth-first, bounded depth)
     * looking for anything that looks like a process PID.
     * Tries: getPid() method, Process.pid(), Long fields with pid-like names,
     * and recursion into Process/Handler/Connector fields.
     */
    private fun extractAnyPid(root: java.awt.Component): Long? {
        val visited = HashSet<Int>()  // identity hash codes to avoid cycles
        return findPidInObject(root, 0, visited)
    }

    private fun findPidInObject(obj: Any?, depth: Int, visited: MutableSet<Int>): Long? {
        if (obj == null || depth > 8) return null
        if (!visited.add(System.identityHashCode(obj))) return null

        val cls = obj.javaClass

        // Strategy 1: If it's a java.lang.Process, call .pid() (Java 9+)
        if (obj is Process) {
            try { val p = obj.pid(); if (p > 0) return p } catch (_: Exception) {}
        }

        // Strategy 2: Look for a getPid() / pid() method returning long
        for (methodName in listOf("getPid", "pid", "getShellPid")) {
            try {
                val m = cls.methods.firstOrNull { it.name == methodName && it.parameterCount == 0
                        && (it.returnType == Long::class.javaPrimitiveType || it.returnType == Long::class.javaObjectType) }
                if (m != null) {
                    val v = m.invoke(obj)
                    val pid = (v as? Long) ?: (v as? Number)?.toLong()
                    if (pid != null && pid > 0) return pid
                }
            } catch (_: Exception) {}
        }

        // Collect all declared fields across the class hierarchy
        val allFields = generateSequence(cls as Class<*>?) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .toList()

        // Strategy 3: Long fields with pid-like names
        for (field in allFields) {
            val name = field.name.lowercase()
            if (!name.contains("pid") && name != "shellpid") continue
            if (field.type != Long::class.javaPrimitiveType && field.type != Long::class.javaObjectType) continue
            field.isAccessible = true
            try {
                val v = if (field.type == Long::class.javaPrimitiveType) field.getLong(obj) else field.get(obj) as? Long ?: continue
                if (v > 0) return v
            } catch (_: Exception) {}
        }

        // Strategy 4: Recurse into Process / Handler / Connector / Runner fields
        for (field in allFields) {
            val typeName = field.type.name
            if (!typeName.contains("Process") && !typeName.contains("Handler")
                && !typeName.contains("Connector") && !typeName.contains("Runner")) continue
            field.isAccessible = true
            try {
                val v = field.get(obj) ?: continue
                val result = findPidInObject(v, depth + 1, visited)
                if (result != null) return result
            } catch (_: Exception) {}
        }

        // Strategy 5: Recurse into AWT component children (shallow)
        if (obj is java.awt.Container && depth < 4) {
            for (child in obj.components) {
                val result = findPidInObject(child, depth + 1, visited)
                if (result != null) return result
            }
        }

        return null
    }

    // =========================================================================
    // Focus external terminal window via AppleScript (macOS only)
    // =========================================================================

    private fun focusExternalTerminal(session: ClaudeSession): Boolean {
        val tty = getProcessTty(session.pid) ?: return false
        return focusITerm2(tty) || focusTerminalApp(tty)
    }

    private fun getProcessTty(pid: Long): String? {
        return try {
            val process = ProcessBuilder("ps", "-p", pid.toString(), "-o", "tty=").start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor(3, TimeUnit.SECONDS)
            output.ifEmpty { null }
        } catch (_: Exception) { null }
    }

    private fun focusITerm2(tty: String): Boolean {
        val script = """
            tell application "System Events"
                if exists (processes where name is "iTerm2") then
                    tell application "iTerm2"
                        activate
                        repeat with w in windows
                            repeat with t in tabs of w
                                repeat with pane in sessions of t
                                    if tty of pane contains "$tty" then
                                        tell w to select
                                        select t
                                        return true
                                    end if
                                end repeat
                            end repeat
                        end repeat
                    end tell
                end if
            end tell
            return false
        """.trimIndent()
        return runAppleScript(script)
    }

    private fun focusTerminalApp(tty: String): Boolean {
        val script = """
            tell application "System Events"
                if exists (processes where name is "Terminal") then
                    tell application "Terminal"
                        activate
                        repeat with w in windows
                            repeat with t in tabs of w
                                if tty of t contains "$tty" then
                                    set selected tab of w to t
                                    set index of w to 1
                                    return true
                                end if
                            end repeat
                        end repeat
                    end tell
                end if
            end tell
            return false
        """.trimIndent()
        return runAppleScript(script)
    }

    private fun runAppleScript(script: String): Boolean {
        return try {
            val process = ProcessBuilder("osascript", "-e", script).start()
            val result = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor(5, TimeUnit.SECONDS)
            process.exitValue() == 0 && result == "true"
        } catch (e: Exception) {
            log.debug("AppleScript failed", e)
            false
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun findOpenProject(cwd: String): Project? {
        return ProjectManager.getInstance().openProjects.firstOrNull { project ->
            val base = project.basePath ?: return@firstOrNull false
            try { File(base).canonicalPath == File(cwd).canonicalPath } catch (_: Exception) { false }
        }
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Claude Code Sessions")
            ?.createNotification("Claude Code Sessions", message, type)
            ?.notify(project)
    }
}
