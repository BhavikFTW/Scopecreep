package com.scopecreep

import com.scopecreep.service.RunnerClient
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.SwingUtilities

class ScopecreepToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()

        toolWindow.contentManager.addContent(
            factory.createContent(
                com.scopecreep.ui.SchematicTestbenchPanel(project),
                "Schematic Testbench",
                false,
            ),
        )
        toolWindow.contentManager.addContent(
            factory.createContent(
                com.scopecreep.ui.HardwareTestbenchPanel(),
                "Hardware Testbench",
                false,
            ),
        )
        toolWindow.contentManager.addContent(
            factory.createContent(com.scopecreep.ui.ChatPanel(), "Chat", false),
        )
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

class ScopecreepPanel {

    private val statusLabel = JBLabel("Scopecreep — idle")
    private val pingButton = JButton("Ping sidecar")
    private val client = RunnerClient()

    val root: JBPanel<JBPanel<*>> = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
        border = JBUI.Borders.empty(8)
        add(statusLabel)
        add(pingButton)
    }

    init {
        pingButton.addActionListener { ping() }
    }

    private fun ping() {
        pingButton.isEnabled = false
        statusLabel.text = "Pinging…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = client.ping()
            val sidecarErr = com.scopecreep.sidecar.SidecarManager.getInstance().lastStartupError()
            SwingUtilities.invokeLater {
                statusLabel.text = when (result) {
                    is RunnerClient.Result.Ok -> "pong — ${result.body.take(80)}"
                    is RunnerClient.Result.Err -> {
                        if (sidecarErr != null) "sidecar failed to start: $sidecarErr"
                        else "error: ${result.message}"
                    }
                }
                pingButton.isEnabled = true
            }
        }
    }
}
