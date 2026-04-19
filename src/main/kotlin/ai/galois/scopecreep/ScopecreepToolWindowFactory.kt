package com.scopecreep

import com.intellij.ide.scratch.ScratchRootType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.scopecreep.service.GenerateOrchestrator
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

class ScopecreepToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ScopecreepPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.root, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

class ScopecreepPanel(private val project: Project) {

    private val filesModel = DefaultListModel<File>()
    private val filesList = JBList(filesModel).apply {
        cellRenderer = javax.swing.DefaultListCellRenderer().apply { /* default */ }
        visibleRowCount = 5
    }
    private val addButton = JButton("Add .SchDoc…")
    private val removeButton = JButton("Remove")
    private val clearButton = JButton("Clear")

    private val mcuField = JBTextField().apply { toolTipText = "e.g. STM32F746ZG" }
    private val purposeArea = JBTextArea(6, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    private val generateButton = JButton("Generate").apply { isEnabled = false }
    private val statusLabel = JBLabel("Ready")

    private val orchestrator = GenerateOrchestrator()

    val root: JComponent

    init {
        filesList.addListSelectionListener { removeButton.isEnabled = !filesList.isSelectionEmpty }
        filesModel.addListDataListener(object : javax.swing.event.ListDataListener {
            override fun intervalAdded(e: javax.swing.event.ListDataEvent) = update()
            override fun intervalRemoved(e: javax.swing.event.ListDataEvent) = update()
            override fun contentsChanged(e: javax.swing.event.ListDataEvent) = update()
            fun update() {
                generateButton.isEnabled = filesModel.size() > 0
                clearButton.isEnabled = filesModel.size() > 0
            }
        })
        removeButton.isEnabled = false
        clearButton.isEnabled = false

        addButton.addActionListener { pickFiles() }
        removeButton.addActionListener {
            filesList.selectedValuesList.forEach { filesModel.removeElement(it) }
        }
        clearButton.addActionListener { filesModel.clear() }
        generateButton.addActionListener { generate() }

        root = buildRoot()
    }

    private fun buildRoot(): JComponent {
        val outer = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
        }

        val filesPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Schematics (.SchDoc)")
            add(JBScrollPane(filesList).apply { preferredSize = Dimension(360, 110) }, BorderLayout.CENTER)
            add(
                JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                    add(addButton); add(removeButton); add(clearButton)
                },
                BorderLayout.SOUTH,
            )
        }

        val contextPanel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createTitledBorder("Project Context")
            val gbc = GridBagConstraints().apply {
                insets = Insets(4, 4, 4, 4)
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
            }
            gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
            add(JBLabel("Microcontroller:"), gbc)
            gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0
            add(mcuField, gbc)

            gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
            gbc.anchor = GridBagConstraints.NORTHWEST
            add(JBLabel("Purpose:"), gbc)
            gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0; gbc.weighty = 1.0
            gbc.fill = GridBagConstraints.BOTH
            add(JBScrollPane(purposeArea), gbc)
        }

        val top = JBPanel<JBPanel<*>>().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(filesPanel)
            add(javax.swing.Box.createVerticalStrut(6))
            add(contextPanel)
        }

        val bottom = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(6)
            add(statusLabel, BorderLayout.WEST)
            add(generateButton, BorderLayout.EAST)
        }

        outer.add(top, BorderLayout.CENTER)
        outer.add(bottom, BorderLayout.SOUTH)
        return outer
    }

    private fun pickFiles() {
        val desc = FileChooserDescriptor(true, false, false, false, false, true)
            .withFileFilter { vf: VirtualFile -> vf.name.endsWith(".SchDoc", ignoreCase = true) }
            .withTitle("Select Schematic Files")
        FileChooser.chooseFiles(desc, project, null).forEach { vf ->
            val f = File(vf.path)
            if (filesModel.elements().toList().none { it.absolutePath == f.absolutePath }) {
                filesModel.addElement(f)
            }
        }
    }

    private fun generate() {
        setBusy(true, "Starting…")
        val inputs = GenerateOrchestrator.Inputs(
            schdocs = filesModel.elements().toList(),
            microcontroller = mcuField.text,
            purpose = purposeArea.text,
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = orchestrator.run(inputs) { msg ->
                SwingUtilities.invokeLater { statusLabel.text = msg }
            }
            SwingUtilities.invokeLater {
                when (result) {
                    is GenerateOrchestrator.Result.Ok -> {
                        openScratch(result.text)
                        setBusy(false, "Done — opened result in editor")
                    }
                    is GenerateOrchestrator.Result.Err -> {
                        setBusy(false, "Error: ${result.message}")
                    }
                }
            }
        }
    }

    private fun setBusy(busy: Boolean, status: String) {
        statusLabel.text = status
        generateButton.isEnabled = !busy && filesModel.size() > 0
        addButton.isEnabled = !busy
        removeButton.isEnabled = !busy && !filesList.isSelectionEmpty
        clearButton.isEnabled = !busy && filesModel.size() > 0
    }

    private fun openScratch(content: String) {
        val mdLang = com.intellij.lang.Language.findLanguageByID("Markdown")
            ?: com.intellij.openapi.fileTypes.PlainTextLanguage.INSTANCE
        val file = ScratchRootType.getInstance().createScratchFile(
            project, "scopecreep_test_plan.md", mdLang, content,
        ) ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
    }
}
