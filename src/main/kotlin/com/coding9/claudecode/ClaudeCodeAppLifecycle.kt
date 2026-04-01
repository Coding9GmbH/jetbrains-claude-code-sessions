package com.coding9.claudecode

import com.coding9.claudecode.services.ClaudeSessionMonitorService
import com.intellij.ide.AppLifecycleListener

/**
 * Starts the Claude session monitor as soon as the IDE is fully loaded,
 * so the tool window already shows sessions when the user opens it.
 */
class ClaudeCodeAppLifecycle : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        ClaudeSessionMonitorService.getInstance().start()
    }
}
