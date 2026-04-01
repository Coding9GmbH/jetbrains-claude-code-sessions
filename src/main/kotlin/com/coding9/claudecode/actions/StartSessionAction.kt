package com.coding9.claudecode.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Starts a new Claude Code session in a terminal inside the current project.
 * Opens the Terminal tool window and runs `claude` in the project's base directory.
 */
class StartSessionAction : AnAction(
    "Start Claude in Current Project",
    "Open a terminal and start Claude Code in this project",
    AllIcons.Actions.Execute
) {
    private val log = thisLogger()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        startClaudeInProject(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    private fun startClaudeInProject(project: Project) {
        val basePath = project.basePath ?: return

        try {
            // Open the Terminal tool window
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val terminalWindow = toolWindowManager.getToolWindow("Terminal")

            if (terminalWindow != null) {
                terminalWindow.show()
                // Use the TerminalView service to create a new tab running claude
                openNewTerminalTab(project, basePath)
            } else {
                // Fallback: spawn an external terminal
                launchExternalTerminal(basePath)
            }
        } catch (e: Exception) {
            log.warn("Failed to start Claude session", e)
        }
    }

    private fun openNewTerminalTab(project: Project, basePath: String) {
        try {
            // Locate TerminalView via the service locator (avoids hard terminal plugin dependency)
            val terminalViewClass = Class.forName("org.jetbrains.plugins.terminal.TerminalView")
            val getInstance = terminalViewClass.getMethod("getInstance", Project::class.java)
            val terminalView = getInstance.invoke(null, project)

            val openTerminalIn = terminalViewClass.methods.firstOrNull { m ->
                m.name == "openTerminalIn" || m.name == "createNewSession"
            }

            if (openTerminalIn != null) {
                openTerminalIn.invoke(terminalView, basePath)
            } else {
                // Older API: create local terminal
                val createLocalTerminal = terminalViewClass.getMethod(
                    "createLocalShellWidget", String::class.java, String::class.java
                )
                createLocalTerminal.invoke(terminalView, basePath, "Claude")
            }
        } catch (_: ClassNotFoundException) {
            // Terminal plugin not available; fall back to external
            launchExternalTerminal(basePath)
        } catch (e: Exception) {
            log.warn("Could not open terminal tab", e)
            launchExternalTerminal(basePath)
        }
    }

    private fun launchExternalTerminal(basePath: String) {
        // macOS: open a new Terminal.app window in the given directory
        val script = """
            tell application "Terminal"
                do script "cd '$basePath' && claude"
                activate
            end tell
        """.trimIndent()
        ProcessBuilder("osascript", "-e", script).start()
    }
}
