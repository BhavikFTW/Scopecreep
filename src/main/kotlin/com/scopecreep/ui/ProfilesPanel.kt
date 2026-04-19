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
    private val preview = JEditorPane("text/html", "").apply { isEditable = false }
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

    // ---- JSON parsing helpers (intentionally tiny — avoids another dep) ----

    data class ProfileRow(val slug: String, val title: String) {
        override fun toString() = title
    }

    @VisibleForTesting
    internal fun parseProfileList(body: String): List<ProfileRow> {
        val rows = mutableListOf<ProfileRow>()
        val titleRegex = Regex("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val slugRegex = Regex("\"slug\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val titles = titleRegex.findAll(body).map { it.groupValues[1] }.toList()
        val slugs = slugRegex.findAll(body).map { it.groupValues[1] }.toList()
        val n = minOf(titles.size, slugs.size)
        for (i in 0 until n) {
            rows += ProfileRow(slug = slugs[i], title = titles[i])
        }
        return rows
    }

    @VisibleForTesting
    internal fun extractContent(body: String): String? {
        val match = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
            ?: return null
        return match.groupValues[1]
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
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
