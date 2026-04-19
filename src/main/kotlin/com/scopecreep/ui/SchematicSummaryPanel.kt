package com.scopecreep.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
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
 * `/schematic/parse` endpoint.
 *
 * Mermaid blocks in the output are rendered as `<pre>` — Swing HTMLEditorKit
 * can't render SVG diagrams. A JCEF-based viewer is a later-branch upgrade.
 */
class SchematicSummaryPanel(
    private val project: Project,
    private val client: AgentClient = AgentClient(),
    /** Receives the parsed schematic markdown when the user clicks "Use in Agent". */
    private val onUseInAgent: ((String) -> Unit)? = null,
) : JPanel(BorderLayout()) {

    private val pickButton = JButton("Pick .SchDoc…")
    private val parseButton = JButton("Parse").apply { isEnabled = false }
    private val useButton = JButton("Use in Agent").apply { isEnabled = false }
    private val statusLabel = JLabel("No file selected.")
    private val preview = JEditorPane().apply {
        editorKit = HTMLEditorKit()
        isEditable = false
        contentType = "text/html"
    }

    @Volatile
    private var selected: File? = null

    @Volatile
    private var lastMarkdown: String? = null

    init {
        val top = JPanel().apply {
            layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT)
            add(pickButton)
            add(parseButton)
            add(useButton)
            add(statusLabel)
        }
        add(top, BorderLayout.NORTH)
        add(JScrollPane(preview), BorderLayout.CENTER)

        pickButton.addActionListener { pickFile() }
        parseButton.addActionListener { parseSelected() }
        useButton.addActionListener {
            lastMarkdown?.let { onUseInAgent?.invoke(it) }
        }
    }

    private fun pickFile() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Select Altium .SchDoc file")
            .withDescription("The backend will parse it into a Markdown summary.")
        val vf = FileChooser.chooseFile(descriptor, project, null) ?: return
        val f = File(vf.path)
        selected = f
        statusLabel.text = "Selected: ${f.name}"
        parseButton.isEnabled = true
        useButton.isEnabled = false
    }

    private fun parseSelected() {
        val f = selected ?: return
        statusLabel.text = "Parsing ${f.name}…"
        parseButton.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = client.parseSchematic(f)
            SwingUtilities.invokeLater {
                when (result) {
                    is AgentClient.Result.Ok -> {
                        lastMarkdown = result.body
                        preview.text = MarkdownRenderer.toHtml(result.body)
                        preview.caretPosition = 0
                        statusLabel.text = "Parsed ${f.name} (${result.body.length} chars)"
                        useButton.isEnabled = onUseInAgent != null
                    }
                    is AgentClient.Result.Err -> {
                        statusLabel.text = "Error: ${result.message}"
                    }
                }
                parseButton.isEnabled = true
            }
        }
    }
}
