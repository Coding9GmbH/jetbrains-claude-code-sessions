package com.coding9.claudecode.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ClaudeSessionTest {

    @Test
    fun `encodeProjectPath replaces all non-alphanumerics with dashes`() {
        assertEquals("-Users-alex-Work-proj", ClaudeSession.encodeProjectPath("/Users/alex/Work/proj"))
        assertEquals("-Users-alex-Work-my-proj", ClaudeSession.encodeProjectPath("/Users/alex/Work/my.proj"))
        assertEquals("-Users-alex-Work-my-app-v2", ClaudeSession.encodeProjectPath("/Users/alex/Work/my-app_v2/"))
    }

    @Test
    fun `modelShortName derives readable labels from model ids`() {
        assertEquals("Fable 5", session(model = "claude-fable-5").modelShortName)
        assertEquals("Opus 4.8", session(model = "claude-opus-4-8").modelShortName)
        assertEquals("Sonnet 5", session(model = "claude-sonnet-5").modelShortName)
        assertEquals("Haiku 4.5", session(model = "claude-haiku-4-5-20251001").modelShortName)
        assertEquals("", session(model = "").modelShortName)
    }

    @Test
    fun `displayName prefers name then title then project dir`() {
        assertEquals("my-session", session(name = "my-session", title = "A title").displayName)
        assertEquals("A title", session(title = "A title").displayName)
        assertEquals("proj", session().displayName)
    }

    private fun session(name: String = "", title: String = "", model: String = "") = ClaudeSession(
        pid = 1L, sessionId = "s", cwd = "/Users/alex/Work/proj", startedAt = 0L,
        name = name, title = title, model = model
    )
}
