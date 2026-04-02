package com.coding9.claudecode.ui

import com.coding9.claudecode.actions.OpenSessionAction
import com.coding9.claudecode.actions.RefreshAction
import com.coding9.claudecode.actions.StartSessionAction
import com.coding9.claudecode.model.ClaudeSession
import com.coding9.claudecode.model.SessionEnvironment
import com.coding9.claudecode.model.SessionState
import com.coding9.claudecode.services.ClaudeSessionMonitorService
import com.coding9.claudecode.services.ClaudeSessionMonitorService.Companion.ESTIMATED_FULL_CONTEXT_BYTES
import com.coding9.claudecode.services.SessionsListener
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

class ClaudeSessionPanel(private val project: Project, parentDisposable: Disposable) :
    JPanel(BorderLayout()), Disposable {

    private val tableModel = SessionTableModel()
    private val table = JBTable(tableModel)
    private val statusLabel = JBLabel("")
    private val searchField = SearchTextField(false)
    private val rowSorter = TableRowSorter<AbstractTableModel>(tableModel)

    // Keep an explicit reference so we can remove the exact same listener instance
    private val sessionsListener = SessionsListener { sessions -> updateSessions(sessions) }

    init {
        border = JBUI.Borders.empty()
        setupTable()
        setupSearch()
        setupKeyboardShortcuts()

        val topPanel = JPanel(BorderLayout()).apply {
            add(buildToolbar(), BorderLayout.CENTER)
            add(searchField, BorderLayout.EAST)
        }

        add(topPanel, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(buildStatusBar(), BorderLayout.SOUTH)

        val monitor = ClaudeSessionMonitorService.getInstance()
        monitor.addListener(sessionsListener)
        // Show whatever is already loaded
        updateSessions(monitor.sessions)

        // Tie our lifetime to the tool window / project
        Disposer.register(parentDisposable, this)
    }

    override fun dispose() {
        ClaudeSessionMonitorService.getInstance().removeListener(sessionsListener)
    }

    // -------------------------------------------------------------------------
    // Table setup
    // -------------------------------------------------------------------------

    private fun setupTable() {
        table.apply {
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
            rowHeight = JBUI.scale(34)
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            emptyText.text = "No active Claude Code sessions"
            autoCreateRowSorter = false
            setRowSorter(rowSorter)

            // Column widths: Project | Env | Status | Context | CPU | Last message | Duration | Started | Action
            columnModel.getColumn(COL_PROJECT).apply { minWidth = JBUI.scale(130); preferredWidth = JBUI.scale(150) }
            columnModel.getColumn(COL_ENV).apply { minWidth = JBUI.scale(70); preferredWidth = JBUI.scale(80); maxWidth = JBUI.scale(90) }
            columnModel.getColumn(COL_STATUS).preferredWidth = JBUI.scale(110)
            columnModel.getColumn(COL_CONTEXT).apply { minWidth = JBUI.scale(80); preferredWidth = JBUI.scale(95); maxWidth = JBUI.scale(110) }
            columnModel.getColumn(COL_CPU).apply { minWidth = JBUI.scale(42); preferredWidth = JBUI.scale(50); maxWidth = JBUI.scale(60) }
            columnModel.getColumn(COL_MESSAGE).preferredWidth = JBUI.scale(155)
            columnModel.getColumn(COL_DURATION).preferredWidth = JBUI.scale(60)
            columnModel.getColumn(COL_STARTED).preferredWidth = JBUI.scale(48)
            columnModel.getColumn(COL_ACTION).apply { minWidth = JBUI.scale(60); preferredWidth = JBUI.scale(70); maxWidth = JBUI.scale(80) }

            // Custom renderers
            columnModel.getColumn(COL_PROJECT).cellRenderer = ProjectBadgeRenderer()
            columnModel.getColumn(COL_ENV).cellRenderer = EnvironmentRenderer()
            columnModel.getColumn(COL_STATUS).cellRenderer = StateTextRenderer()
            columnModel.getColumn(COL_CONTEXT).cellRenderer = ContextBarRenderer()
            columnModel.getColumn(COL_CPU).cellRenderer = CpuRenderer()
            columnModel.getColumn(COL_MESSAGE).cellRenderer = LastMessageRenderer()
            columnModel.getColumn(COL_DURATION).cellRenderer = DurationRenderer()
            columnModel.getColumn(COL_STARTED).cellRenderer = TimeRenderer()
            columnModel.getColumn(COL_ACTION).cellRenderer = ActionButtonRenderer()

            // Default sort: state priority ascending
            this@ClaudeSessionPanel.rowSorter.sortKeys = listOf(RowSorter.SortKey(COL_STATUS, SortOrder.ASCENDING))

            // Mouse listener for clicks and hover
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val viewRow = rowAtPoint(e.point)
                    val col = columnAtPoint(e.point)
                    if (viewRow < 0) return
                    val modelRow = convertRowIndexToModel(viewRow)

                    // Click on action button column OR double-click anywhere
                    if (col == COL_ACTION || (e.clickCount == 2 && e.button == MouseEvent.BUTTON1)) {
                        val session = tableModel.getSession(modelRow)
                        if (session.state == SessionState.FINISHED) {
                            OpenSessionAction.showResumeDialog(project, session)
                        } else {
                            OpenSessionAction.openSession(project, session)
                        }
                    }
                }

                // Right-click: context menu
                override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
                override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)

                private fun maybeShowPopup(e: MouseEvent) {
                    if (!e.isPopupTrigger) return
                    val viewRow = rowAtPoint(e.point)
                    if (viewRow < 0) return
                    table.selectionModel.setSelectionInterval(viewRow, viewRow)
                    val modelRow = convertRowIndexToModel(viewRow)
                    buildContextMenu(tableModel.getSession(modelRow)).show(table, e.x, e.y)
                }

                override fun mouseMoved(e: MouseEvent) {
                    cursor = if (rowAtPoint(e.point) >= 0) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                             else Cursor.getDefaultCursor()
                }
            })
        }
    }

    // -------------------------------------------------------------------------
    // Keyboard shortcuts
    // -------------------------------------------------------------------------

    private fun setupKeyboardShortcuts() {
        // Enter: open/resume selected session
        table.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> {
                        val viewRow = table.selectedRow
                        if (viewRow >= 0) {
                            val modelRow = table.convertRowIndexToModel(viewRow)
                            val session = tableModel.getSession(modelRow)
                            if (session.state == SessionState.FINISHED) {
                                OpenSessionAction.showResumeDialog(project, session)
                            } else {
                                OpenSessionAction.openSession(project, session)
                            }
                            e.consume()
                        }
                    }
                    KeyEvent.VK_F5 -> {
                        ClaudeSessionMonitorService.getInstance().start()
                        e.consume()
                    }
                    KeyEvent.VK_DELETE -> {
                        val viewRow = table.selectedRow
                        if (viewRow >= 0) {
                            val modelRow = table.convertRowIndexToModel(viewRow)
                            val session = tableModel.getSession(modelRow)
                            if (session.state != SessionState.FINISHED) {
                                confirmKillSession(session)
                            }
                            e.consume()
                        }
                    }
                }
            }
        })

        // Ctrl+F / Cmd+F: focus search field
        val focusSearchAction = "focusSearch"
        table.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx),
            focusSearchAction
        )
        table.actionMap.put(focusSearchAction, object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                searchField.requestFocusInWindow()
            }
        })
    }

    // -------------------------------------------------------------------------
    // Search / filter
    // -------------------------------------------------------------------------

    private fun setupSearch() {
        searchField.textEditor.emptyText.text = "Filter sessions..."
        searchField.preferredSize = Dimension(JBUI.scale(200), searchField.preferredSize.height)

        searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                applyFilter(searchField.text.trim())
            }
        })

        // Escape in search field: clear and return focus to table
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    searchField.text = ""
                    table.requestFocusInWindow()
                    e.consume()
                }
            }
        })
    }

    private fun applyFilter(query: String) {
        if (query.isEmpty()) {
            rowSorter.rowFilter = null
        } else {
            val lowerQuery = query.lowercase()
            rowSorter.rowFilter = object : RowFilter<AbstractTableModel, Int>() {
                override fun include(entry: Entry<out AbstractTableModel, out Int>): Boolean {
                    val session = tableModel.getSession(entry.identifier)
                    return session.projectName.lowercase().contains(lowerQuery) ||
                           session.cwd.lowercase().contains(lowerQuery) ||
                           session.lastAssistantMessage.lowercase().contains(lowerQuery) ||
                           session.state.displayName.lowercase().contains(lowerQuery) ||
                           session.environment.displayName.lowercase().contains(lowerQuery)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Context menu
    // -------------------------------------------------------------------------

    private fun buildContextMenu(session: ClaudeSession): JPopupMenu {
        val menu = JPopupMenu()

        // Header (non-clickable project info)
        val header = JMenuItem("${session.projectName}  [PID ${session.pid}]")
        header.isEnabled = false
        header.font = header.font.deriveFont(Font.BOLD)
        menu.add(header)
        menu.addSeparator()

        // Open / focus (active sessions) or Resume (finished sessions)
        if (session.state == SessionState.FINISHED) {
            val resumeItem = JMenuItem("Resume Session", AllIcons.Actions.Restart)
            resumeItem.addActionListener { OpenSessionAction.showResumeDialog(project, session) }
            menu.add(resumeItem)
        } else {
            val openItem = JMenuItem("Open Session", AllIcons.Actions.Execute)
            openItem.addActionListener { OpenSessionAction.openSession(project, session) }
            menu.add(openItem)
        }

        // Reveal in Finder / File Manager
        val revealText = if (System.getProperty("os.name", "").lowercase().contains("mac")) "Reveal in Finder" else "Open in File Manager"
        val finderItem = JMenuItem(revealText, AllIcons.Actions.ShowAsTree)
        finderItem.addActionListener {
            try { Desktop.getDesktop().open(File(session.cwd)) }
            catch (_: Exception) { /* ignore */ }
        }
        menu.add(finderItem)

        menu.addSeparator()

        // Copy path
        val copyPathItem = JMenuItem("Copy Path", AllIcons.Actions.Copy)
        copyPathItem.addActionListener {
            CopyPasteManager.getInstance().setContents(StringSelection(session.cwd))
        }
        menu.add(copyPathItem)

        // Copy PID
        val copyPidItem = JMenuItem("Copy PID")
        copyPidItem.addActionListener {
            CopyPasteManager.getInstance().setContents(StringSelection(session.pid.toString()))
        }
        menu.add(copyPidItem)

        // Copy Session ID
        val copySessionIdItem = JMenuItem("Copy Session ID")
        copySessionIdItem.addActionListener {
            CopyPasteManager.getInstance().setContents(StringSelection(session.sessionId))
        }
        menu.add(copySessionIdItem)

        // Kill session
        if (session.state != SessionState.FINISHED) {
            menu.addSeparator()
            val killItem = JMenuItem("Kill Session", AllIcons.Process.Stop)
            killItem.addActionListener { confirmKillSession(session) }
            menu.add(killItem)
        }

        return menu
    }

    private fun confirmKillSession(session: ClaudeSession) {
        val confirm = JOptionPane.showConfirmDialog(
            table,
            "Kill Claude session in '${session.projectName}'?\nPID: ${session.pid}",
            "Kill Session",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (confirm == JOptionPane.YES_OPTION) {
            try {
                ProcessHandle.of(session.pid).ifPresent { it.destroy() }
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    table,
                    "Failed to kill session: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Toolbar
    // -------------------------------------------------------------------------

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(RefreshAction())
            addSeparator()
            add(StartSessionAction())
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("ClaudeSessionsToolbar", group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    // -------------------------------------------------------------------------
    // Status bar
    // -------------------------------------------------------------------------

    private fun buildStatusBar(): JComponent {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 6)
            add(statusLabel, BorderLayout.WEST)
        }
    }

    // -------------------------------------------------------------------------
    // Public API called by the monitor
    // -------------------------------------------------------------------------

    fun updateSessions(sessions: List<ClaudeSession>) {
        tableModel.updateData(sessions)
        val running = sessions.count { it.state == SessionState.RUNNING }
        val waiting = sessions.count {
            it.state == SessionState.WAITING_FOR_INPUT || it.state == SessionState.WAITING_FOR_ACCEPT
        }
        val finished = sessions.count { it.state == SessionState.FINISHED }
        statusLabel.text = when {
            sessions.isEmpty() -> "No active sessions"
            else -> "${sessions.size} session(s)  \u2022  $running running  \u2022  $waiting waiting  \u2022  $finished finished"
        }
    }

    // -------------------------------------------------------------------------
    // Table model
    // -------------------------------------------------------------------------

    private inner class SessionTableModel : AbstractTableModel() {
        private var data: List<ClaudeSession> = emptyList()
        private val columns = arrayOf("Project", "Env", "Status", "Context", "CPU", "Last message", "Duration", "Started", "")

        fun updateData(sessions: List<ClaudeSession>) {
            val oldSize = data.size
            val newSize = sessions.size
            data = sessions
            when {
                oldSize == newSize -> {
                    // Same row count — update existing rows without resetting selection/scroll
                    if (newSize > 0) fireTableRowsUpdated(0, newSize - 1)
                }
                else -> {
                    // Structure changed — must do a full refresh
                    fireTableDataChanged()
                }
            }
        }

        fun getSession(row: Int): ClaudeSession = data[row]

        override fun getRowCount() = data.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(col: Int) = columns[col]
        override fun isCellEditable(row: Int, col: Int) = false

        override fun getColumnClass(col: Int): Class<*> = when (col) {
            COL_PROJECT -> ClaudeSession::class.java
            COL_ENV -> SessionEnvironment::class.java
            COL_STATUS -> SessionState::class.java
            COL_CONTEXT -> ClaudeSession::class.java
            COL_CPU -> java.lang.Double::class.java
            COL_MESSAGE -> String::class.java
            COL_DURATION -> ClaudeSession::class.java
            COL_STARTED -> Instant::class.java
            COL_ACTION -> ClaudeSession::class.java
            else -> Any::class.java
        }

        override fun getValueAt(row: Int, col: Int): Any {
            val s = data[row]
            return when (col) {
                COL_PROJECT -> s
                COL_ENV -> s.environment
                COL_STATUS -> s.state
                COL_CONTEXT -> s
                COL_CPU -> s.cpuPercent
                COL_MESSAGE -> s.lastAssistantMessage
                COL_DURATION -> s
                COL_STARTED -> s.startedAtInstant
                COL_ACTION -> s
                else -> ""
            }
        }
    }

    // -------------------------------------------------------------------------
    // Badge icon – colored rounded rect with project initial
    // -------------------------------------------------------------------------

    private inner class ProjectBadgeIcon(private val letter: Char, private val bg: Color) : Icon {
        private val size = JBUI.scale(20)

        override fun getIconWidth() = size
        override fun getIconHeight() = size

        override fun paintIcon(c: Component, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            // Rounded background
            g2.color = bg
            g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), size.toFloat(), size.toFloat(), 6f, 6f))

            // Letter
            g2.color = Color.WHITE
            val font = g2.font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            g2.font = font
            val fm = g2.getFontMetrics(font)
            val tx = x + (size - fm.charWidth(letter)) / 2
            val ty = y + (size + fm.ascent - fm.descent) / 2
            g2.drawString(letter.toString(), tx, ty)
            g2.dispose()
        }
    }

    // Colors used for project badges – picked by hash of project name
    private val BADGE_PALETTE = arrayOf(
        JBColor(Color(0x4285F4), Color(0x5C9DF5)), // blue
        JBColor(Color(0xE53935), Color(0xEF5350)), // red
        JBColor(Color(0x43A047), Color(0x4CAF50)), // green
        JBColor(Color(0xFB8C00), Color(0xFFA726)), // orange
        JBColor(Color(0x8E24AA), Color(0xAB47BC)), // purple
        JBColor(Color(0x00ACC1), Color(0x26C6DA)), // cyan
        JBColor(Color(0x6D4C41), Color(0x8D6E63)), // brown
        JBColor(Color(0x3949AB), Color(0x5C6BC0)), // indigo
    )

    private fun badgeColor(name: String): Color {
        val idx = Math.abs(name.hashCode()) % BADGE_PALETTE.size
        return BADGE_PALETTE[idx]
    }

    // -------------------------------------------------------------------------
    // Cell renderers
    // -------------------------------------------------------------------------

    private inner class ProjectBadgeRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int
        ): Component {
            val session = value as? ClaudeSession ?: return super.getTableCellRendererComponent(
                table, value, isSelected, hasFocus, row, col)

            val label = super.getTableCellRendererComponent(
                table, session.projectName, isSelected, hasFocus, row, col) as JLabel

            val initial = session.projectName.firstOrNull()?.uppercaseChar() ?: '?'
            label.icon = ProjectBadgeIcon(initial, badgeColor(session.projectName))
            label.iconTextGap = JBUI.scale(6)
            label.font = label.font.deriveFont(Font.BOLD)
            label.border = JBUI.Borders.empty(0, 6)
            label.toolTipText = "<html><b>${session.projectName}</b><br>${session.cwd}<br>PID: ${session.pid}<br>Session: ${session.sessionId}</html>"

            if (!isSelected && session.state == SessionState.FINISHED) {
                label.foreground = UIUtil.getLabelDisabledForeground()
            }

            return label
        }
    }

    private inner class EnvironmentRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int
        ): Component {
            val env = value as? SessionEnvironment ?: SessionEnvironment.UNKNOWN
            val label = super.getTableCellRendererComponent(
                table, env.displayName, isSelected, hasFocus, row, col) as JLabel

            label.icon = when (env) {
                SessionEnvironment.JETBRAINS_TERMINAL -> AllIcons.Nodes.IdeaProject
                SessionEnvironment.EXTERNAL_TERMINAL -> AllIcons.Debugger.Console
                SessionEnvironment.UNKNOWN -> AllIcons.General.QuestionDialog
            }
            label.iconTextGap = JBUI.scale(4)
            label.border = JBUI.Borders.empty(0, 4)
            label.font = label.font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            label.toolTipText = when (env) {
                SessionEnvironment.JETBRAINS_TERMINAL -> "Running in JetBrains IDE terminal"
                SessionEnvironment.EXTERNAL_TERMINAL -> "Running in external terminal (iTerm2, Terminal.app, etc.)"
                SessionEnvironment.UNKNOWN -> "Environment could not be determined"
            }

            // Dim for finished sessions
            val modelRow = table.convertRowIndexToModel(row)
            val session = tableModel.getSession(modelRow)
            if (!isSelected && session.state == SessionState.FINISHED) {
                label.foreground = UIUtil.getLabelDisabledForeground()
            }

            return label
        }
    }

    private inner class StateTextRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int
        ): Component {
            val state = value as? SessionState ?: SessionState.UNKNOWN
            val label = super.getTableCellRendererComponent(
                table, state.displayName, isSelected, hasFocus, row, col) as JLabel

            label.icon = stateIcon(state)
            label.iconTextGap = JBUI.scale(4)
            label.border = JBUI.Borders.empty(0, 4)
            label.toolTipText = state.description
            if (!isSelected) label.foreground = stateColor(state)

            return label
        }

        private fun stateIcon(state: SessionState) = when (state) {
            SessionState.RUNNING            -> AllIcons.Actions.Execute
            SessionState.WAITING_FOR_INPUT  -> AllIcons.Debugger.Db_set_breakpoint
            SessionState.WAITING_FOR_ACCEPT -> AllIcons.General.BalloonWarning
            SessionState.FINISHED           -> AllIcons.Process.Stop
            SessionState.UNKNOWN            -> AllIcons.General.QuestionDialog
        }

        private fun stateColor(state: SessionState): Color = when (state) {
            SessionState.RUNNING            -> JBColor(Color(0, 140, 55), Color(70, 190, 110))
            SessionState.WAITING_FOR_INPUT  -> JBColor(Color(175, 115, 0), Color(215, 155, 0))
            SessionState.WAITING_FOR_ACCEPT -> JBColor(Color(195, 50, 0), Color(235, 90, 30))
            SessionState.FINISHED           -> UIUtil.getLabelDisabledForeground()
            SessionState.UNKNOWN            -> UIUtil.getLabelDisabledForeground()
        }
    }

    private inner class ContextBarRenderer : javax.swing.table.TableCellRenderer {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int
        ): Component {
            val session = value as? ClaudeSession
            val contextBytes = session?.contextBytes ?: 0L
            val turnCount = session?.turnCount ?: 0
            val percent = if (contextBytes > 0)
                minOf(100, (contextBytes * 100 / ESTIMATED_FULL_CONTEXT_BYTES).toInt())
            else 0

            return ContextBarPanel(percent, turnCount, contextBytes, isSelected, table)
        }
    }

    /** A compact panel that draws a colored progress bar with text overlay. */
    private inner class ContextBarPanel(
        private val percent: Int,
        private val turnCount: Int,
        private val contextBytes: Long,
        private val isSelected: Boolean,
        private val table: JTable
    ) : JPanel() {

        init {
            isOpaque = true
            border = JBUI.Borders.empty(4, 4)
            toolTipText = buildTooltip()
        }

        private fun buildTooltip(): String {
            val sizeKb = contextBytes / 1024
            return "<html>Context: ~$percent%<br>" +
                   "${turnCount} turns<br>" +
                   "${sizeKb} KB / ${ESTIMATED_FULL_CONTEXT_BYTES / 1024} KB</html>"
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val insets = insets
            val barX = insets.left
            val barY = insets.top + JBUI.scale(2)
            val barW = width - insets.left - insets.right
            val barH = height - insets.top - insets.bottom - JBUI.scale(4)

            // Background
            background = if (isSelected) table.selectionBackground else table.background

            // Track (empty bar)
            val trackColor = if (isSelected) table.selectionBackground.darker() else JBColor(Color(0xE8E8E8), Color(0x3C3F41))
            g2.color = trackColor
            g2.fillRoundRect(barX, barY, barW, barH, 4, 4)

            // Filled portion
            if (percent > 0) {
                val fillW = maxOf(2, barW * percent / 100)
                g2.color = barColor(percent, isSelected)
                g2.fillRoundRect(barX, barY, fillW, barH, 4, 4)
            }

            // Text overlay: "42% · 18t"
            val text = if (contextBytes > 0) "$percent% \u00B7 ${turnCount}t" else "\u2014"
            val font = g2.font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            g2.font = font
            val fm = g2.getFontMetrics(font)
            val textX = barX + (barW - fm.stringWidth(text)) / 2
            val textY = barY + (barH + fm.ascent - fm.descent) / 2

            g2.color = if (isSelected) table.selectionForeground
                       else if (percent > 60) Color.WHITE
                       else UIUtil.getLabelForeground()
            g2.drawString(text, textX, textY)

            g2.dispose()
        }

        private fun barColor(pct: Int, selected: Boolean): Color = when {
            pct >= 85 -> JBColor(Color(0xE53935), Color(0xEF5350))  // red – near limit
            pct >= 60 -> JBColor(Color(0xFB8C00), Color(0xFFA726))  // orange – getting full
            pct >= 30 -> JBColor(Color(0x4285F4), Color(0x5C9DF5))  // blue – moderate
            else      -> JBColor(Color(0x43A047), Color(0x4CAF50))  // green – plenty left
        }
    }

    private inner class CpuRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int
        ): Component {
            val cpu = (value as? Double) ?: 0.0
            val text = if (cpu > 0.0) "%.1f%%".format(cpu) else "\u2014"
            val label = super.getTableCellRendererComponent(
                table, text, isSelected, hasFocus, row, col) as JLabel

            label.horizontalAlignment = SwingConstants.RIGHT
            label.border = JBUI.Borders.empty(0, 4)

            if (!isSelected) {
                label.foreground = when {
                    cpu > 50.0 -> JBColor(Color(195, 50, 0), Color(235, 90, 30))
                    cpu > 10.0 -> JBColor(Color(175, 115, 0), Color(215, 155, 0))
                    cpu > 0.0 -> JBColor(Color(0, 140, 55), Color(70, 190, 110))
                    else -> UIUtil.getLabelDisabledForeground()
                }
            }

            label.toolTipText = "CPU: %.1f%%".format(cpu)
            return label
        }
    }

    private inner class LastMessageRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int
        ): Component {
            val text = (value as? String)?.ifEmpty { "\u2014" } ?: "\u2014"
            val label = super.getTableCellRendererComponent(
                table, text, isSelected, hasFocus, row, col) as JLabel
            if (!isSelected) label.foreground = UIUtil.getLabelDisabledForeground()
            label.border = JBUI.Borders.empty(0, 4)
            label.toolTipText = "<html><pre style='font-size:11px'>${text.take(300)}</pre></html>"
            return label
        }
    }

    private inner class DurationRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int
        ): Component {
            val session = value as? ClaudeSession
            val text = session?.let {
                formatDuration(Duration.between(it.startedAtInstant, Instant.now()))
            } ?: "\u2014"
            val label = super.getTableCellRendererComponent(
                table, text, isSelected, hasFocus, row, col) as JLabel
            label.border = JBUI.Borders.empty(0, 4)
            return label
        }

        private fun formatDuration(d: Duration): String {
            val h = d.toHours()
            val m = d.toMinutesPart()
            val s = d.toSecondsPart()
            return when {
                h > 0  -> "${h}h ${m}m"
                m > 0  -> "${m}m ${s}s"
                else   -> "${s}s"
            }
        }
    }

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    private inner class TimeRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int
        ): Component {
            val time = value as? Instant
            val label = super.getTableCellRendererComponent(
                table, time?.let { timeFormatter.format(it) } ?: "\u2014",
                isSelected, hasFocus, row, col) as JLabel
            label.border = JBUI.Borders.empty(0, 4)
            return label
        }
    }

    private inner class ActionButtonRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int
        ): Component {
            val session = value as? ClaudeSession
            val isFinished = session?.state == SessionState.FINISHED

            val label = super.getTableCellRendererComponent(
                table,
                if (isFinished) "Resume" else "Open",
                isSelected, hasFocus, row, col
            ) as JLabel

            label.icon = if (isFinished) AllIcons.Actions.Restart else AllIcons.Actions.Execute
            label.iconTextGap = JBUI.scale(3)
            label.horizontalAlignment = SwingConstants.CENTER
            label.border = JBUI.Borders.empty(0, 4)
            label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            label.font = label.font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())

            if (!isSelected) {
                label.foreground = JBColor(Color(0x4285F4), Color(0x5C9DF5))
            }

            label.toolTipText = if (isFinished)
                "Resume this finished session"
            else
                "Open / focus this session"

            return label
        }
    }

    companion object {
        private const val COL_PROJECT = 0
        private const val COL_ENV = 1
        private const val COL_STATUS = 2
        private const val COL_CONTEXT = 3
        private const val COL_CPU = 4
        private const val COL_MESSAGE = 5
        private const val COL_DURATION = 6
        private const val COL_STARTED = 7
        private const val COL_ACTION = 8

        private fun stateOrder(state: SessionState) = when (state) {
            SessionState.WAITING_FOR_ACCEPT -> 0
            SessionState.WAITING_FOR_INPUT  -> 1
            SessionState.RUNNING            -> 2
            SessionState.FINISHED           -> 3
            SessionState.UNKNOWN            -> 4
        }
    }
}
