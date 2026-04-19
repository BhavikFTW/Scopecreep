package com.scopecreep.ui

import com.intellij.ide.scratch.ScratchRootType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.scopecreep.service.MermaidGenerator
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Vertical panel: paste analysis → Generate → summary + Mermaid diagram.
 * Accepts analysis as a plain String so the schematic-parser orchestrator
 * (sibling branch) can call [setAnalysis] directly once merged.
 */
class MermaidPanel(
    private val project: Project,
    parentDisposable: Disposable,
) {

    private val statusLabel = JBLabel("idle")
    private val analysisArea = JBTextArea(6, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val generateButton = JButton("Generate diagram")
    private val summaryArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
    }
    private val mermaidView = MermaidView(parentDisposable)
    private val viewAnalysisButton = JButton("View full analysis").apply { isVisible = false }

    private val generator = MermaidGenerator()
    private var currentAnalysis: String? = null

    val root: JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = JBUI.Borders.empty(8)
        add(buildCenter(), BorderLayout.CENTER)
        add(buildBottom(), BorderLayout.SOUTH)
    }

    init {
        generateButton.addActionListener { generate(analysisArea.text) }
        viewAnalysisButton.addActionListener { openFullAnalysis() }
    }

    /** Hook point for the schematic-parser orchestrator to pipe analysis in. */
    fun setAnalysis(text: String, autoGenerate: Boolean = true) {
        analysisArea.text = text
        if (autoGenerate) generate(text)
    }

    private fun buildCenter(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        add(sectionLabel("Analysis (paste detailed schematic analysis)"))
        add(JBScrollPane(analysisArea).apply {
            alignmentX = 0f
            preferredSize = Dimension(300, 120)
            maximumSize = Dimension(Int.MAX_VALUE, 160)
        })

        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply {
            alignmentX = 0f
            add(generateButton)
            add(statusLabel)
        })

        add(sectionLabel("Summary"))
        add(JBScrollPane(summaryArea).apply {
            alignmentX = 0f
            preferredSize = Dimension(300, 80)
            maximumSize = Dimension(Int.MAX_VALUE, 120)
        })

        add(sectionLabel("Diagram"))
        add(mermaidView.component.apply {
            alignmentX = 0f
            preferredSize = Dimension(300, 320)
        })
    }

    private fun buildBottom(): JComponent = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(viewAnalysisButton)
    }

    private fun sectionLabel(text: String) = JBLabel(text).apply {
        border = BorderFactory.createEmptyBorder(8, 0, 4, 0)
        alignmentX = 0f
    }

    private fun generate(analysis: String) {
        if (analysis.isBlank()) {
            statusLabel.text = "paste an analysis first"
            return
        }
        generateButton.isEnabled = false
        statusLabel.text = "generating…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = generator.generate(analysis)
            SwingUtilities.invokeLater {
                when (result) {
                    is MermaidGenerator.Result.Ok -> {
                        currentAnalysis = analysis
                        summaryArea.text = result.summary
                        mermaidView.render(result.mermaid)
                        viewAnalysisButton.isVisible = true
                        statusLabel.text = "ready"
                    }
                    is MermaidGenerator.Result.Err -> {
                        statusLabel.text = "error: ${result.message}"
                    }
                }
                generateButton.isEnabled = true
            }
        }
    }

    private fun openFullAnalysis() {
        val text = currentAnalysis ?: return
        val md = FileTypeManager.getInstance().getFileTypeByExtension("md")
        val file = ScratchRootType.getInstance().createScratchFile(
            project,
            "scopecreep-analysis.md",
            md,
            text,
        ) ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
    }
}
