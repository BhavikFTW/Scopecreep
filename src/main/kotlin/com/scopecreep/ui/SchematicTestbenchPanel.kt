package com.scopecreep.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.scopecreep.service.RunnerClient
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingUtilities

/**
 * Pillar 1 — Schematic Testbench.
 *
 * Composes the schematic upload, agent session, and result panels into a
 * single tool-window tab. Wires the existing callbacks (onUseInAgent,
 * onReport) inline so the factory stays thin.
 */
class SchematicTestbenchPanel(
    project: Project,
    runnerClient: RunnerClient = RunnerClient(),
) : JPanel(BorderLayout()) {

    private val healthLabel = JLabel("sidecar: —")
    private val pingButton = JButton("Ping")
    private val runner = runnerClient

    init {
        val waveform = WaveformPanel()
        val testFlow = TestFlowPanel(project)
        val agent = AgentSessionPanel(
            project = project,
            onReport = { json -> waveform.loadReport(json) },
        )
        val schematic = SchematicSummaryPanel(
            project = project,
            onSchdocReady = { file -> agent.loadSchdocJson(file) },
        )

        val results = JBTabbedPane().apply {
            addTab("Waveform", waveform)
            addTab("Test flow", testFlow)
        }

        val upperSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, schematic, agent).apply {
            resizeWeight = 0.35
            isContinuousLayout = true
        }
        val mainSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, upperSplit, results).apply {
            resizeWeight = 0.65
            isContinuousLayout = true
        }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            border = JBUI.Borders.emptyBottom(2)
            add(healthLabel)
            add(pingButton)
        }
        pingButton.addActionListener { ping() }

        add(toolbar, BorderLayout.NORTH)
        add(mainSplit, BorderLayout.CENTER)
        preferredSize = Dimension(720, 900)

        ping()
    }

    private fun ping() {
        pingButton.isEnabled = false
        healthLabel.text = "sidecar: …"
        healthLabel.foreground = null
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runner.ping()
            SwingUtilities.invokeLater {
                when (result) {
                    is RunnerClient.Result.Ok -> {
                        healthLabel.text = "sidecar: ok"
                        healthLabel.foreground = Color(0x2f, 0x9e, 0x44)
                    }
                    is RunnerClient.Result.Err -> {
                        healthLabel.text = "sidecar: down (${result.message.take(60)})"
                        healthLabel.foreground = Color(0xc9, 0x2a, 0x2a)
                    }
                }
                pingButton.isEnabled = true
            }
        }
    }
}
