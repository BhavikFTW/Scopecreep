package com.scopecreep.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.scopecreep.service.RunnerClient
import org.jetbrains.annotations.VisibleForTesting
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

class ProfilesPanel(private val client: RunnerClient = RunnerClient()) {

    private val listModel = DefaultListModel<ProfileRow>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ProfileCellRenderer()
    }
    private val preview = JEditorPane("text/html", "").apply {
        isEditable = false
        // On JDK 21 some read-only JEditorPane instances lose mouse selection.
        // Explicitly mark focusable + use a DefaultCaret with visible update
        // policy so drag-selection works and Cmd/Ctrl-C copies the selection.
        isFocusable = true
        caret = javax.swing.text.DefaultCaret().apply {
            updatePolicy = javax.swing.text.DefaultCaret.NEVER_UPDATE
        }
    }
    private val researchButton = JButton("Research new instrument…")
    private val refreshButton = JButton("Refresh")
    private val statusLabel = JLabel(" ")

    val root: JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4)
        val toolbar = JPanel().apply {
            add(refreshButton); add(researchButton); add(statusLabel)
        }
        val split = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JBScrollPane(list),
            JBScrollPane(preview),
        ).apply {
            dividerLocation = 240
            preferredSize = Dimension(700, 400)
        }
        add(toolbar, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)
    }

    init {
        list.addListSelectionListener {
            val selected = list.selectedValue ?: return@addListSelectionListener
            loadPreview(selected)
        }
        refreshButton.addActionListener { refresh() }
        researchButton.addActionListener { openResearchDialog() }
        refresh()
        // Pick up profiles researched elsewhere (e.g. a scratch block from
        // the Chat tab posting to /memory/research) without a manual click.
        javax.swing.Timer(20_000) { refresh() }.apply { isRepeats = true }.start()
    }

    fun refresh() {
        statusLabel.text = "Loading…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = client.searchProfiles("", limit = 50)
            SwingUtilities.invokeLater {
                when (result) {
                    is RunnerClient.Result.Ok -> {
                        listModel.clear()
                        parseProfileList(result.body).forEach(listModel::addElement)
                        statusLabel.text = "${listModel.size()} profiles"
                    }
                    is RunnerClient.Result.Err -> {
                        statusLabel.text = "error: ${result.message}"
                    }
                }
            }
        }
    }

    private fun loadPreview(row: ProfileRow) {
        preview.text = "<html><body>Loading…</body></html>"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = client.recallProfile(row.slug)
            SwingUtilities.invokeLater {
                preview.text = when (result) {
                    is RunnerClient.Result.Ok -> {
                        val md = extractContent(result.body) ?: result.body
                        MarkdownRenderer.toHtml(md)
                    }
                    is RunnerClient.Result.Err -> "<pre>error: ${result.message}</pre>"
                }
                preview.caretPosition = 0
            }
        }
    }

    private fun openResearchDialog() {
        val name = Messages.showInputDialog(
            "Instrument name (e.g. 'Keithley 2400 SMU')",
            "Research new instrument",
            Messages.getQuestionIcon(),
        ) ?: return
        if (name.isBlank()) return
        statusLabel.text = "Researching '$name'…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = client.researchProfile(name)
            SwingUtilities.invokeLater {
                when (result) {
                    is RunnerClient.Result.Ok -> {
                        val md = extractContent(result.body) ?: result.body
                        preview.text = MarkdownRenderer.toHtml(md)
                        preview.caretPosition = 0
                        statusLabel.text = "Draft ready — review and press Publish"
                        // TODO(publish button): wire publishProfile(draft_id)
                    }
                    is RunnerClient.Result.Err -> {
                        statusLabel.text = "error: ${result.message}"
                    }
                }
            }
        }
    }

    // ---- JSON parsing helpers (linear scanner — no regex backtracking) ----

    data class ProfileRow(val slug: String, val title: String) {
        override fun toString() = title
    }

    @VisibleForTesting
    internal fun parseProfileList(body: String): List<ProfileRow> {
        val rows = mutableListOf<ProfileRow>()
        var cursor = 0
        while (cursor < body.length) {
            val slugPair = extractJsonString(body, "slug", cursor) ?: break
            val titlePair = extractJsonString(body, "title", slugPair.second)
                ?: extractJsonString(body, "title", cursor) ?: break
            rows += ProfileRow(slug = slugPair.first, title = titlePair.first)
            cursor = maxOf(slugPair.second, titlePair.second)
        }
        return rows
    }

    @VisibleForTesting
    internal fun extractContent(body: String): String? =
        extractJsonString(body, "content", 0)?.first

    /**
     * Extract the next JSON string value for [key], starting at [from].
     * Linear — reads character-by-character tracking backslash escapes.
     * Returns (decodedValue, indexAfterClosingQuote) or null if not found.
     */
    private fun extractJsonString(body: String, key: String, from: Int): Pair<String, Int>? {
        val marker = "\"$key\""
        var keyIdx = body.indexOf(marker, from)
        if (keyIdx < 0) return null
        // Skip whitespace + colon + optional whitespace + opening quote.
        var i = keyIdx + marker.length
        while (i < body.length && body[i].isWhitespace()) i++
        if (i >= body.length || body[i] != ':') return null
        i++
        while (i < body.length && body[i].isWhitespace()) i++
        if (i >= body.length || body[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> {
                    when (val next = body[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'u' -> {
                            if (i + 5 < body.length) {
                                val hex = body.substring(i + 2, i + 6)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            } else sb.append(next)
                        }
                        else -> sb.append(next)
                    }
                    i += 2
                }
                c == '"' -> return sb.toString() to (i + 1)
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    private class ProfileCellRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val base = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            border = JBUI.Borders.empty(4, 8)
            return base
        }
    }
}
