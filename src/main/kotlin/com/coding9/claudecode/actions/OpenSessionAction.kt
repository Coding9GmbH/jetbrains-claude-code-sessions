package com.coding9.claudecode.actions

import com.coding9.claudecode.model.ClaudeSession
import com.coding9.claudecode.model.SessionState
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import java.io.File

/**
 * Logic for "opening" a Claude session:
 *
 * Priority order:
 *  1. Focus the actual terminal window where Claude is running
 *     (iTerm2 or Terminal.app, detected via the process's TTY)
 *  2. Claude is running inside PhpStorm's own terminal
 *     → bring the IDE window + terminal tab to the front
 *  3. Nothing worked → offer to open the project in the IDE
 */
object OpenSessionAction {

    private val log = thisLogger()

    fun openSession(callerProject: com.intellij.openapi.project.Project, session: ClaudeSession) {
        if (session.state == SessionState.FINISHED) {
            notify(callerProject, "Session '${session.projectName}' has already finished.", NotificationType.INFORMATION)
            return
        }

        // --- Step 1: focus the real terminal window (iTerm2 / Terminal.app) ---
        if (focusExternalTerminal(session)) return

        // --- Step 2: maybe it's running inside PhpStorm's built-in terminal ---
        val openProject = ProjectManager.getInstance().openProjects.firstOrNull { project ->
            val base = project.basePath ?: return@firstOrNull false
            File(base).canonicalPath == File(session.cwd).canonicalPath
        }

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

    // -------------------------------------------------------------------------
    // Step 1 – focus an open JetBrains project + Terminal tab
    // -------------------------------------------------------------------------

    private fun focusProjectTerminal(project: com.intellij.openapi.project.Project, session: ClaudeSession) {
        ApplicationManager.getApplication().invokeLater {
            try {
                // Bring the IDE frame to the front
                val frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)
                frame?.toFront()
                frame?.requestFocus()

                // Open / reveal the Terminal tool window
                val toolWindowManager = ToolWindowManager.getInstance(project)
                val terminalWindow = toolWindowManager.getToolWindow("Terminal")
                if (terminalWindow != null) {
                    terminalWindow.show()
                    // Try to find the tab whose PID matches
                    focusTerminalTabByPid(terminalWindow, session.pid)
                } else {
                    // Fallback: just show the project
                    notify(project, "Switched to project '${session.projectName}'.\nOpen the Terminal to interact with Claude.", NotificationType.INFORMATION)
                }
            } catch (e: Exception) {
                log.warn("Could not focus project terminal", e)
            }
        }
    }

    /**
     * Attempt to find the Terminal tab whose shell process matches `pid`
     * (or is a direct parent of `pid`).  Works with the JetBrains Terminal plugin.
     */
    private fun focusTerminalTabByPid(
        toolWindow: com.intellij.openapi.wm.ToolWindow,
        targetPid: Long
    ) {
        val contentManager = toolWindow.contentManager
        val tabCount = contentManager.contentCount

        // Walk each tab and check the process tree
        for (i in 0 until tabCount) {
            val content = contentManager.getContent(i) ?: continue
            val component = content.component

            // The JetBrains Terminal widget hierarchy ends with a TerminalWidget
            // whose processHandler holds the shell PID. We use reflection to stay
            // compatible across IDE versions without hard-coding internal class names.
            val shellPid = extractShellPid(component) ?: continue

            if (isClaudeSession(shellPid, targetPid)) {
                contentManager.setSelectedContent(content, true)
                return
            }
        }
        // No matching tab found – the session might be in an external terminal
    }

    /** Try to extract a PID from a Terminal component using reflection. */
    private fun extractShellPid(component: java.awt.Component): Long? {
        return try {
            // Walk fields looking for a ProcessHandler or similar
            var current: Any? = component
            for (depth in 0..5) {
                if (current == null) break
                val pidField = current.javaClass.declaredFields.firstOrNull { f ->
                    f.name.contains("pid", ignoreCase = true) && f.type == Long::class.javaPrimitiveType
                }
                if (pidField != null) {
                    pidField.isAccessible = true
                    return pidField.getLong(current)
                }
                // Recurse into the first interesting field
                current = current.javaClass.declaredFields.firstOrNull()?.let { f ->
                    f.isAccessible = true
                    f.get(current)
                }
            }
            null
        } catch (_: Exception) { null }
    }

    /**
     * Return true if `claudePid` is a descendant of `shellPid` (or equal).
     * Claude runs as a child of the shell that launched it.
     */
    private fun isClaudeSession(shellPid: Long, claudePid: Long): Boolean {
        if (shellPid == claudePid) return true
        return try {
            val output = ProcessBuilder("pgrep", "-P", shellPid.toString())
                .start().inputStream.bufferedReader().readText()
            output.lines().mapNotNull { it.trim().toLongOrNull() }.any { childPid ->
                childPid == claudePid || isClaudeSession(childPid, claudePid)
            }
        } catch (_: Exception) { false }
    }

    // -------------------------------------------------------------------------
    // Step 2 – bring external terminal window to front via AppleScript
    // -------------------------------------------------------------------------

    private fun focusExternalTerminal(session: ClaudeSession): Boolean {
        // Determine which terminal app has the process
        val tty = getProcessTty(session.pid) ?: return false

        // Try iTerm2 first, then Terminal.app
        return focusITerm2(session.pid, tty) || focusTerminalApp(session.pid, tty)
    }

    /** Get the TTY device of a process (e.g. "s006") */
    private fun getProcessTty(pid: Long): String? {
        return try {
            val output = ProcessBuilder("ps", "-p", pid.toString(), "-o", "tty=")
                .start().inputStream.bufferedReader().readText().trim()
            output.ifEmpty { null }
        } catch (_: Exception) { null }
    }

    private fun focusITerm2(pid: Long, tty: String): Boolean {
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

    private fun focusTerminalApp(pid: Long, tty: String): Boolean {
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
            val result = process.inputStream.bufferedReader().readText().trim()
            process.waitFor() == 0 && result == "true"
        } catch (e: Exception) {
            log.debug("AppleScript failed", e)
            false
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun notify(project: com.intellij.openapi.project.Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Claude Code Sessions")
            ?.createNotification("Claude Code Sessions", message, type)
            ?.notify(project)
    }
}
