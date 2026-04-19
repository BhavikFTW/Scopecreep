package com.scopecreep.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.scopecreep.service.AgentClient
import java.awt.BorderLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.text.html.HTMLEditorKit

/**
 * Upload a .SchDoc and render the Markdown summary produced by the backend
 * `/schematic/parse` endpoint. Picking a file auto-parses; the parsed JSON is
 * also forwarded to the sibling Agent panel via [onSchdocReady] so the user
 * only has to click "Pick .SchDoc" then "Start session".
 *
 * Mermaid blocks in the output are rendered as `<pre>` — Swing HTMLEditorKit
 * can't render SVG diagrams.
 */
class SchematicSummaryPanel(
    private val project: Project,
    private val client: AgentClient = AgentClient(),
    /** Fires with the picked `.SchDoc` after the Markdown parse succeeds. */
    private val onSchdocReady: ((File) -> Unit)? = null,
) : JPanel(BorderLayout()) {

    private val pickButton = JButton("Pick .SchDoc…")
    private val statusLabel = JLabel("No file selected.")
    private val preview = JEditorPane().apply {
        editorKit = HTMLEditorKit()
        isEditable = false
        contentType = "text/html"
    }

    init {
        val top = JPanel().apply {
            layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT)
            add(pickButton)
            add(statusLabel)
        }
        add(top, BorderLayout.NORTH)
        add(JScrollPane(preview), BorderLayout.CENTER)

        pickButton.addActionListener { pickFile() }
    }

    private fun pickFile() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Select Altium .SchDoc file")
            .withDescription("The backend will parse it into a Markdown summary + structured JSON.")
        val vf = FileChooser.chooseFile(descriptor, project, null) ?: return
        val f = File(vf.path)
        if (!f.name.endsWith(".SchDoc", ignoreCase = true)) {
            Messages.showErrorDialog(
                project,
                "\"${f.name}\" is not a .SchDoc file.\nOnly Altium schematic documents are supported.",
                "Invalid file type",
            )
            return
        }
        statusLabel.text = "Parsing ${f.name}…"
        pickButton.isEnabled = false
        preview.text = ""
        parseMarkdown(f)
    }

    private fun parseMarkdown(f: File) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = client.parseSchematic(f)
            SwingUtilities.invokeLater {
                when (result) {
                    is AgentClient.Result.Ok -> {
                        preview.text = MarkdownRenderer.toHtml(result.body)
                        preview.caretPosition = 0
                        statusLabel.text = "Parsed ${f.name} (${result.body.length} chars)"
                        onSchdocReady?.invoke(f)
                    }
                    is AgentClient.Result.Err -> {
                        statusLabel.text = "Error: ${result.message}"
                    }
                }
                pickButton.isEnabled = true
            }
        }
    }
}
