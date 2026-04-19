package com.scopecreep.ui

import com.intellij.openapi.application.ApplicationManager
import com.scopecreep.service.FirmwareClient
import com.scopecreep.service.JsonFields
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.text.html.HTMLEditorKit

/**
 * Firmware generation dashboard.
 *
 * Flow:
 *   1. User enters a goal and target → Generate inserts a row in firmware_jobs
 *   2. Out-of-process LangGraph pipeline (~/benchy/pipeline) advances the row
 *   3. This panel polls the row and renders stage timeline + files + logs
 *   4. Flash button flips status to flash_requested; the Pi worker picks it up
 *
 * The pipeline is intentionally not embedded in the sidecar this branch.
 * If the pipeline isn't running, rows stay 'queued' and nothing moves —
 * that's the documented state for this integration checkpoint.
 */
class FirmwarePanel(
    private val client: FirmwareClient = FirmwareClient(),
) : JPanel(BorderLayout()) {

    private val goalArea = JTextArea(4, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        text = "e.g. Blink LED on GPIO2 at 1Hz, read ADC on GPIO34 and log over UART."
    }
    private val targetCombo = JComboBox(DefaultComboBoxModel(arrayOf("esp32-s3", "esp32", "rp2040")))
    private val generateButton = JButton("Generate")
    private val refreshButton = JButton("Refresh list")
    private val flashButton = JButton("Flash").apply { isEnabled = false }

    private val jobsList = JComboBox(DefaultComboBoxModel(arrayOf<String>()))
    private val statusLabel = JLabel("No jobs yet.")

    private val detailPane = JEditorPane().apply {
        editorKit = HTMLEditorKit()
        isEditable = false
        contentType = "text/html"
    }

    @Volatile
    private var currentJobId: String? = null
    private val polling = AtomicBoolean(false)

    init {
        val topLeft = JPanel(BorderLayout()).apply {
            add(JScrollPane(goalArea), BorderLayout.CENTER)
            val row = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("Target:"))
                add(targetCombo)
                add(generateButton)
                add(refreshButton)
            }
            add(row, BorderLayout.SOUTH)
            border = BorderFactory.createTitledBorder("Goal")
            preferredSize = Dimension(400, 200)
        }
        val jobsPanel = JPanel(BorderLayout()).apply {
            val row = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("Job:"))
                add(jobsList)
                add(flashButton)
            }
            add(row, BorderLayout.NORTH)
            add(statusLabel, BorderLayout.CENTER)
            border = BorderFactory.createTitledBorder("Jobs")
        }
        val left = JPanel(BorderLayout()).apply {
            add(topLeft, BorderLayout.CENTER)
            add(jobsPanel, BorderLayout.SOUTH)
        }
        val right = JPanel(BorderLayout()).apply {
            add(JScrollPane(detailPane), BorderLayout.CENTER)
            border = BorderFactory.createTitledBorder("Stage / logs")
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply { resizeWeight = 0.4 }
        add(split, BorderLayout.CENTER)

        generateButton.addActionListener { generate() }
        refreshButton.addActionListener { refreshJobs() }
        flashButton.addActionListener { requestFlash() }
        jobsList.addActionListener {
            val sel = jobsList.selectedItem as? String ?: return@addActionListener
            currentJobId = sel.substringBefore(" ")
            startPolling()
        }
    }

    private fun generate() {
        val goal = goalArea.text.trim()
        if (goal.isEmpty()) { statusLabel.text = "Goal is empty."; return }
        val target = (targetCombo.selectedItem as? String) ?: "esp32-s3"
        generateButton.isEnabled = false
        statusLabel.text = "Submitting…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val res = client.createJob(goal, target)
            SwingUtilities.invokeLater {
                when (res) {
                    is FirmwareClient.Result.Ok -> {
                        val id = firstJobId(res.body)
                        if (id != null) {
                            currentJobId = id
                            statusLabel.text = "Created job $id."
                            refreshJobs()
                            startPolling()
                        } else statusLabel.text = "Created, but couldn't parse id: ${res.body.take(200)}"
                    }
                    is FirmwareClient.Result.Err -> statusLabel.text = "Create failed: ${res.message}"
                }
                generateButton.isEnabled = true
            }
        }
    }

    private fun refreshJobs() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val res = client.listJobs()
            SwingUtilities.invokeLater {
                if (res !is FirmwareClient.Result.Ok) {
                    statusLabel.text = "List failed: ${(res as FirmwareClient.Result.Err).message}"
                    return@invokeLater
                }
                val items = parseJobList(res.body)
                val model = DefaultComboBoxModel(items.toTypedArray())
                jobsList.model = model
                currentJobId?.let { cur ->
                    val match = items.firstOrNull { it.startsWith(cur) }
                    if (match != null) jobsList.selectedItem = match
                }
            }
        }
    }

    private fun requestFlash() {
        val id = currentJobId ?: return
        flashButton.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val res = client.requestFlash(id)
            SwingUtilities.invokeLater {
                statusLabel.text = when (res) {
                    is FirmwareClient.Result.Ok -> "Flash requested for $id."
                    is FirmwareClient.Result.Err -> "Flash failed: ${res.message}"
                }
            }
        }
    }

    private fun startPolling() {
        if (!polling.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                while (polling.get()) {
                    val id = currentJobId
                    if (id == null) { Thread.sleep(500); continue }
                    val res = client.getJob(id)
                    SwingUtilities.invokeLater { applyJob(res) }
                    Thread.sleep(2_000)
                }
            } finally { polling.set(false) }
        }
    }

    private fun applyJob(res: FirmwareClient.Result) {
        if (res !is FirmwareClient.Result.Ok) {
            statusLabel.text = "Poll failed: ${(res as FirmwareClient.Result.Err).message}"
            return
        }
        val body = res.body
        val status = firstStringField(body, "status") ?: "unknown"
        statusLabel.text = "status: $status"
        flashButton.isEnabled = status in setOf("done", "compiling")
        detailPane.text = MarkdownRenderer.toHtml(renderDetail(body))
        detailPane.caretPosition = 0
    }

    private fun renderDetail(arrayBody: String): String {
        // Array with a single row.
        val obj = firstObject(arrayBody) ?: return "*No job.*"
        val goal = JsonFields.stringField(obj, "goal").orEmpty()
        val status = JsonFields.stringField(obj, "status").orEmpty()
        val target = JsonFields.stringField(obj, "target").orEmpty()
        val compileOut = JsonFields.stringField(obj, "compile_output").orEmpty()
        val error = JsonFields.stringField(obj, "error").orEmpty()
        val logs = JsonFields.objectField(obj, "logs") ?: "[]"
        val files = JsonFields.objectField(obj, "files") ?: "[]"

        return buildString {
            append("# Firmware job\n\n")
            append("**Target:** $target  \n")
            append("**Status:** $status\n\n")
            append("## Goal\n\n$goal\n\n")
            if (error.isNotBlank()) append("## Error\n\n```\n$error\n```\n\n")
            append("## Stage log\n\n```\n")
            append(truncate(logs, 2000))
            append("\n```\n\n## Files\n\n```\n")
            append(truncate(files, 2000))
            append("\n```\n")
            if (compileOut.isNotBlank()) append("\n## Compile output\n\n```\n${truncate(compileOut, 2000)}\n```\n")
        }
    }

    private fun truncate(s: String, n: Int): String = if (s.length <= n) s else s.take(n) + "…"

    private fun firstJobId(resp: String): String? {
        val obj = firstObject(resp) ?: return null
        return JsonFields.stringField(obj, "id")
    }

    private fun firstStringField(resp: String, key: String): String? {
        val obj = firstObject(resp) ?: return null
        return JsonFields.stringField(obj, key)
    }

    private fun firstObject(arrayBody: String): String? {
        val trimmed = arrayBody.trimStart()
        if (trimmed.isEmpty()) return null
        if (trimmed.first() == '{') return trimmed
        if (trimmed.first() != '[') return null
        // tiny array object extractor
        var depth = 0; var start = -1; var inStr = false; var esc = false
        for (i in trimmed.indices) {
            val c = trimmed[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) return trimmed.substring(start, i + 1) }
            }
        }
        return null
    }

    private fun parseJobList(arrayBody: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0; var start = -1; var inStr = false; var esc = false
        for (i in arrayBody.indices) {
            val c = arrayBody[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) {
                    val obj = arrayBody.substring(start, i + 1)
                    val id = JsonFields.stringField(obj, "id") ?: ""
                    val status = JsonFields.stringField(obj, "status") ?: ""
                    val goal = JsonFields.stringField(obj, "goal")?.take(40).orEmpty()
                    out.add("$id  [$status]  $goal")
                    start = -1
                } }
            }
        }
        return out
    }
}
