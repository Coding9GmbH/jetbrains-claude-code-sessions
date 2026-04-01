package com.coding9.claudecode.actions

import com.coding9.claudecode.services.ClaudeSessionMonitorService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class RefreshAction : AnAction("Refresh Sessions", "Refresh the Claude Code session list", AllIcons.Actions.Refresh) {
    override fun actionPerformed(e: AnActionEvent) {
        ClaudeSessionMonitorService.getInstance().start()
    }
}
