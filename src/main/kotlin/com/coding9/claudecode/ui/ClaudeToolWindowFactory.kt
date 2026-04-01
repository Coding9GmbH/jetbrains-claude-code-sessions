package com.coding9.claudecode.ui

import com.coding9.claudecode.services.ClaudeSessionMonitorService
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ClaudeToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Panel registers its own listener and disposes it automatically via Disposer
        val panel = ClaudeSessionPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        // Ensure the monitor is running (no-op if already started)
        ClaudeSessionMonitorService.getInstance().start()
    }

    override fun shouldBeAvailable(project: Project) = true
}
