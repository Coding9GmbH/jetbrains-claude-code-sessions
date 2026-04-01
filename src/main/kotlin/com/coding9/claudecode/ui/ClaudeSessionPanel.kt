package com.coding9.claudecode.ui

import com.coding9.claudecode.actions.OpenSessionAction
import com.coding9.claudecode.actions.RefreshAction
import com.coding9.claudecode.actions.StartSessionAction
import com.coding9.claudecode.model.ClaudeSession
import com.coding9.claudecode.model.SessionState
import com.coding9.claudecode.services.ClaudeSessionMonitorService
import com.coding9.claudecode.services.SessionsListener
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.StringSelection
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

class ClaudeSessionPanel(private val project: Project, parentDisposable: Disposable) :
    JPanel(BorderLayout()), Disposable {

    private val tableModel = SessionTableModel()
    private val table = JBTable(tableModel)
    private val statusLabel = JBLabel("")

    // Keep an explicit reference so we can remove the exact same listener instance
    private val sessionsListener = SessionsListener { sessions -> updateSessions(sessions) }

    init {
        border = JBUI.Borders.empty()
        setupTable()
        add(buildToolbar(), BorderLayout.NORTH)
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
            intercellSpacing = java.awt.Dimension(0, 0)
            rowHeight = JBUI.scale(34)
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            emptyText.text = "No active Claude Code sessions"

            // Column widths
            columnModel.getColumn(0).apply { minWidth = JBUI.scale(180); preferredWidth = JBUI.scale(200) } // project + badge
            columnModel.getColumn(1).preferredWidth = JBUI.scale(130) // state
            columnModel.getColumn(2).preferredWidth = JBUI.scale(210) // last message
            columnModel.getColumn(3).preferredWidth = JBUI.scale(75)  // duration
            columnModel.getColumn(4).preferredWidth = JBUI.scale(60)  // started

            // Custom renderers
            columnModel.getColumn(0).cellRenderer = ProjectBadgeRenderer()
            columnModel.getColumn(1).cellRenderer = StateTextRenderer()
            columnModel.getColumn(2).cellRenderer = LastMessageRenderer()
            columnModel.getColumn(3).cellRenderer = DurationRenderer()
            columnModel.getColumn(4).cellRenderer = TimeRenderer()

            // Double-click: open session
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) {
                        val row = rowAtPoint(e.point)
                        if (row >= 0) OpenSessionAction.openSession(project, tableModel.getSession(row))
                    }
                }

                // Right-click: context menu
                override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
                override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)

                private fun maybeShowPopup(e: MouseEvent) {
                    if (!e.isPopupTrigger) return
                    val row = rowAtPoint(e.point)
                    if (row < 0) return
                    table.selectionModel.setSelectionInterval(row, row)
                    buildContextMenu(tableModel.getSession(row)).show(table, e.x, e.y)
                }

                override fun mouseMoved(e: MouseEvent) {
                    cursor = if (rowAtPoint(e.point) >= 0) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                             else Cursor.getDefaultCursor()
                }
            })
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

        // Open / focus
        val openItem = JMenuItem("Open Session", AllIcons.Actions.Execute)
        openItem.isEnabled = session.state != SessionState.FINISHED
        openItem.addActionListener { OpenSessionAction.openSession(project, session) }
        menu.add(openItem)

        // Reveal in Finder
        val finderItem = JMenuItem("Reveal in Finder", AllIcons.Actions.ShowAsTree)
        finderItem.addActionListener {
            try { Desktop.getDesktop().open(File(session.cwd)) }
            catch (e: Exception) { /* ignore */ }
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

        // Kill session
        if (session.state != SessionState.FINISHED) {
            menu.addSeparator()
            val killItem = JMenuItem("Kill Session", AllIcons.Process.Stop)
            killItem.addActionListener {
                val confirm = JOptionPane.showConfirmDialog(
                    table,
                    "Kill Claude session in '${session.projectName}'?\nPID: ${session.pid}",
                    "Kill Session",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                )
                if (confirm == JOptionPane.YES_OPTION) {
                    ProcessHandle.of(session.pid).ifPresent { it.destroy() }
                }
            }
            menu.add(killItem)
        }

        return menu
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
        statusLabel.text = when {
            sessions.isEmpty() -> "No active sessions"
            else -> "${sessions.size} session(s)  \u2022  $running running  \u2022  $waiting waiting"
        }
    }

    // -------------------------------------------------------------------------
    // Table model
    // -------------------------------------------------------------------------

    private inner class SessionTableModel : AbstractTableModel() {
        private var data: List<ClaudeSession> = emptyList()
        private val columns = arrayOf("Project", "Status", "Last message", "Duration", "Started")

        fun updateData(sessions: List<ClaudeSession>) {
            data = sessions.sortedWith(compareBy({ stateOrder(it.state) }, { it.projectName }))
            fireTableDataChanged()
        }

        fun getSession(row: Int): ClaudeSession = data[row]

        private fun stateOrder(state: SessionState) = when (state) {
            SessionState.WAITING_FOR_ACCEPT -> 0
            SessionState.WAITING_FOR_INPUT  -> 1
            SessionState.RUNNING            -> 2
            SessionState.FINISHED           -> 3
            SessionState.UNKNOWN            -> 4
        }

        override fun getRowCount() = data.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(col: Int) = columns[col]
        override fun isCellEditable(row: Int, col: Int) = false

        override fun getValueAt(row: Int, col: Int): Any {
            val s = data[row]
            return when (col) {
                0 -> s
                1 -> s.state
                2 -> s.lastAssistantMessage
                3 -> s
                4 -> s.startedAtInstant
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
            label.toolTipText = "<html><b>${session.projectName}</b><br>${session.cwd}<br>PID: ${session.pid}</html>"

            // Dim finished sessions
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
}
