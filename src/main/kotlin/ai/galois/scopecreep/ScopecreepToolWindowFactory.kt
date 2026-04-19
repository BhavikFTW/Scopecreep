package com.scopecreep

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.scopecreep.service.RunnerClient
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
    private val filesList = JBList(filesModel).apply { visibleRowCount = 6 }

    private val addButton = JButton("Add .SchDoc…")
    private val removeButton = JButton("Remove")
    private val clearButton = JButton("Clear")
    private val generateButton = JButton("Generate").apply { isEnabled = false }

    private val statusLabel = JBLabel("Ready")
    private val runner = RunnerClient()

    val root: JComponent

    init {
        filesList.addListSelectionListener {
            removeButton.isEnabled = !filesList.isSelectionEmpty && generateButton.isEnabled
        }
        filesModel.addListDataListener(object : javax.swing.event.ListDataListener {
            override fun intervalAdded(e: javax.swing.event.ListDataEvent) = refreshEnablement()
            override fun intervalRemoved(e: javax.swing.event.ListDataEvent) = refreshEnablement()
            override fun contentsChanged(e: javax.swing.event.ListDataEvent) = refreshEnablement()
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

        val uploadBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            border = BorderFactory.createTitledBorder("Schematic Upload")
            add(addButton)
            add(removeButton)
            add(clearButton)
        }

        val filesPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Selected .SchDoc files")
            add(
                JBScrollPane(filesList).apply { preferredSize = Dimension(360, 140) },
                BorderLayout.CENTER,
            )
        }

        val top = JBPanel<JBPanel<*>>().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(uploadBar)
            add(javax.swing.Box.createVerticalStrut(6))
            add(filesPanel)
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

    private fun refreshEnablement() {
        val hasFiles = filesModel.size() > 0
        generateButton.isEnabled = hasFiles
        clearButton.isEnabled = hasFiles
        removeButton.isEnabled = hasFiles && !filesList.isSelectionEmpty
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
        val files = filesModel.elements().toList()
        if (files.isEmpty()) return

        val projectBase = project.basePath
        if (projectBase.isNullOrBlank()) {
            statusLabel.text = "Error: project has no base path"
            return
        }

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val outputDir: Path = Paths.get(projectBase, "schematic_md_parse", stamp)

        setBusy(true, "Starting…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val saved = mutableListOf<Path>()
            var failure: String? = null
            try {
                Files.createDirectories(outputDir)
                parseLoop@ for ((i, f) in files.withIndex()) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = "Parsing ${f.name} (${i + 1}/${files.size})…"
                    }
                    when (val r = runner.parseSchematic(f)) {
                        is RunnerClient.Result.Ok -> {
                            val outName = f.nameWithoutExtension + ".schematic_summary.md"
                            val outPath = outputDir.resolve(outName)
                            Files.writeString(outPath, r.body)
                            saved.add(outPath)
                        }
                        is RunnerClient.Result.Err -> {
                            failure = "Parse failed for ${f.name}: ${r.message}"
                            break@parseLoop
                        }
                    }
                }
            } catch (t: Throwable) {
                failure = t.message ?: t.javaClass.simpleName
            }

            SwingUtilities.invokeLater {
                if (failure != null) {
                    setBusy(false, "Error: $failure")
                    return@invokeLater
                }
                LocalFileSystem.getInstance().refreshAndFindFileByPath(outputDir.toString())
                saved.firstOrNull()?.let { first ->
                    LocalFileSystem.getInstance().refreshAndFindFileByPath(first.toString())?.let { vf ->
                        FileEditorManager.getInstance(project).openFile(vf, true)
                    }
                }
                setBusy(false, "Wrote ${saved.size} file(s) to schematic_md_parse/$stamp")
            }
        }
    }

    private fun setBusy(busy: Boolean, status: String) {
        statusLabel.text = status
        val hasFiles = filesModel.size() > 0
        generateButton.isEnabled = !busy && hasFiles
        addButton.isEnabled = !busy
        removeButton.isEnabled = !busy && hasFiles && !filesList.isSelectionEmpty
        clearButton.isEnabled = !busy && hasFiles
    }
}
