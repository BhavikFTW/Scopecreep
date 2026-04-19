package com.scopecreep.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.scopecreep.service.AgentClient
import com.scopecreep.service.JsonFields
import com.scopecreep.service.SessionPoller
import com.scopecreep.service.SessionSnapshot
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.text.html.HTMLEditorKit

/**
 * Orchestrates an agent test session:
 *   1. Load/paste a schematic JSON (structure matching POST /agent/sessions body)
 *   2. Start a session, display status + progress
 *   3. Surface PROBE_REQUIRED prompts with a Resume button
 *   4. On COMPLETE, fetch and render the report
 *
 * Waveform details (per-probe scope captures) are shown in [WaveformPanel],
 * which subscribes to the same listener registry via [onMeasurement].
 */
class AgentSessionPanel(
    private val project: Project,
    private val client: AgentClient = AgentClient(),
    /** Optional hook: called with per-snapshot status lines. */
    private val onResult: ((String) -> Unit)? = null,
    /** Optional hook: raw report JSON when the session reaches a terminal state. */
    private val onReport: ((String) -> Unit)? = null,
) : JPanel(BorderLayout()) {

    private val schematicJson = JTextArea().apply {
        lineWrap = false
        text = DEFAULT_SCHEMATIC_JSON
        rows = 10
    }
    private val pickButton = JButton("Pick .SchDoc…")
    private val startButton = JButton("Start session").apply { isEnabled = false }
    private val cancelButton = JButton("Cancel").apply { isEnabled = false }
    private val resumeButton = JButton("Resume (probe placed)").apply { isEnabled = false }

    private val statusLabel = JLabel("Idle.")
    private val sessionLabel = JLabel("session: —")
    private val progressBar = JProgressBar(0, 1).apply {
        isStringPainted = true
        string = "0 / 0"
    }

    private val probePane = JEditorPane().apply {
        editorKit = HTMLEditorKit()
        isEditable = false
        contentType = "text/html"
        background = Color(0x40, 0x38, 0x20)
        text = "<html><body><i>No probe pending.</i></body></html>"
    }
    private val reportPane = JEditorPane().apply {
        editorKit = HTMLEditorKit()
        isEditable = false
        contentType = "text/html"
    }

    @Volatile
    private var poller: SessionPoller? = null

    @Volatile
    private var currentSessionId: String? = null

    init {
        val header = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(pickButton)
            add(startButton)
            add(cancelButton)
            add(resumeButton)
        }
        val status = JPanel(GridLayout(1, 3)).apply {
            add(statusLabel)
            add(sessionLabel)
            add(progressBar)
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        }
        val top = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(status, BorderLayout.SOUTH)
        }
        val schematicArea = JScrollPane(schematicJson).apply {
            preferredSize = Dimension(400, 200)
            border = BorderFactory.createTitledBorder("Schematic JSON")
        }
        val probeArea = JPanel(BorderLayout()).apply {
            add(JScrollPane(probePane), BorderLayout.CENTER)
            border = BorderFactory.createTitledBorder("Probe prompt")
        }
        val reportArea = JPanel(BorderLayout()).apply {
            add(JScrollPane(reportPane), BorderLayout.CENTER)
            border = BorderFactory.createTitledBorder("Report")
        }
        val leftSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, schematicArea, probeArea).apply {
            resizeWeight = 0.5
        }
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplit, reportArea).apply {
            resizeWeight = 0.45
        }

        add(top, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)

        pickButton.addActionListener { pickSchdoc() }
        startButton.addActionListener { startSession() }
        cancelButton.addActionListener { cancelSession() }
        resumeButton.addActionListener { resumeSession() }
    }

    private fun pickSchdoc() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Select Altium .SchDoc file")
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
        startButton.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val res = client.parseSchematicJson(f)
            SwingUtilities.invokeLater {
                when (res) {
                    is AgentClient.Result.Ok -> {
                        schematicJson.text = res.body
                        statusLabel.text = "Loaded ${f.name}."
                        startButton.isEnabled = true
                    }
                    is AgentClient.Result.Err ->
                        statusLabel.text = "Parse error: ${res.message}"
                }
                pickButton.isEnabled = true
            }
        }
    }

    private fun startSession() {
        val schematic = schematicJson.text.trim()
        if (schematic.isEmpty()) {
            statusLabel.text = "Schematic JSON is empty."
            return
        }
        startButton.isEnabled = false
        reportPane.text = ""
        probePane.text = "<html><body><i>Session starting…</i></body></html>"
        statusLabel.text = "Starting session…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val res = client.startSession(schematic)
            SwingUtilities.invokeLater {
                when (res) {
                    is AgentClient.Result.Ok -> {
                        val sid = JsonFields.stringField(res.body, "session_id")
                        if (sid == null) {
                            statusLabel.text = "Bad response: ${res.body}"
                            startButton.isEnabled = true
                            return@invokeLater
                        }
                        currentSessionId = sid
                        sessionLabel.text = "session: $sid"
                        cancelButton.isEnabled = true
                        beginPolling(sid)
                    }
                    is AgentClient.Result.Err -> {
                        statusLabel.text = "Start failed: ${res.message}"
                        startButton.isEnabled = true
                    }
                }
            }
        }
    }

    private fun beginPolling(sessionId: String) {
        poller?.stop()
        val p = SessionPoller(
            client = client,
            sessionId = sessionId,
            onUpdate = ::handleSnapshot,
            onTerminal = ::handleTerminal,
        )
        poller = p
        p.start()
    }

    private fun handleSnapshot(snap: SessionSnapshot) {
        statusLabel.text = "Status: ${snap.status}"
        progressBar.maximum = snap.total.coerceAtLeast(1)
        progressBar.value = snap.completed.coerceAtMost(progressBar.maximum)
        progressBar.string = "${snap.completed} / ${snap.total}"

        val probe = snap.currentProbe
        if (snap.status == "PROBE_REQUIRED" && probe != null) {
            probePane.text = probePromptHtml(probe.label, probe.net, probe.locationHint, probe.probeType, probe.instructions)
            resumeButton.isEnabled = true
        } else {
            resumeButton.isEnabled = false
            if (snap.status in setOf("PLANNING", "CAPTURING", "EVALUATING")) {
                probePane.text = "<html><body><i>Running: ${snap.status}…</i></body></html>"
            }
        }

        onResult?.invoke(
            "status=${snap.status} completed=${snap.completed}/${snap.total}" +
                (snap.error?.let { " error=$it" } ?: ""),
        )
    }

    private fun handleTerminal(snap: SessionSnapshot) {
        cancelButton.isEnabled = false
        resumeButton.isEnabled = false
        startButton.isEnabled = true
        val sid = snap.sessionId.ifEmpty { currentSessionId ?: return }
        if (snap.status == "COMPLETE" || snap.status == "FAILED") {
            ApplicationManager.getApplication().executeOnPooledThread {
                val res = client.getReport(sid)
                SwingUtilities.invokeLater {
                    when (res) {
                        is AgentClient.Result.Ok -> {
                            reportPane.text = MarkdownRenderer.toHtml(renderReport(res.body))
                            reportPane.caretPosition = 0
                            onReport?.invoke(res.body)
                        }
                        is AgentClient.Result.Err ->
                            reportPane.text = "<html><body>Report error: ${res.message}</body></html>"
                    }
                }
            }
        }
        if (snap.error != null) statusLabel.text = "Ended: ${snap.status} (${snap.error})"
    }

    private fun resumeSession() {
        val sid = currentSessionId ?: return
        resumeButton.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val res = client.resumeSession(sid)
            SwingUtilities.invokeLater {
                if (res is AgentClient.Result.Err) {
                    statusLabel.text = "Resume failed: ${res.message}"
                    resumeButton.isEnabled = true
                }
            }
        }
    }

    private fun cancelSession() {
        val sid = currentSessionId ?: return
        cancelButton.isEnabled = false
        poller?.stop()
        ApplicationManager.getApplication().executeOnPooledThread {
            client.cancelSession(sid)
            SwingUtilities.invokeLater {
                statusLabel.text = "Cancelled."
                startButton.isEnabled = true
            }
        }
    }

    private fun probePromptHtml(label: String, net: String, hint: String, type: String, instructions: String): String =
        """
        <html><body>
          <h3>Probe required: $label</h3>
          <p><b>Net:</b> $net &nbsp; <b>Type:</b> $type</p>
          <p><b>Location:</b> $hint</p>
          <p>${instructions.replace("\n", "<br>")}</p>
          <p><i>Place the probe, then click "Resume (probe placed)".</i></p>
        </body></html>
        """.trimIndent()

    private fun renderReport(json: String): String {
        val overall = JsonFields.stringField(json, "overall").orEmpty()
        val board = JsonFields.stringField(json, "board_name").orEmpty()
        val results = JsonFields.objectField(json, "results").orEmpty()
        // Results is a JSON array; split top-level objects by depth-tracking.
        val rows = splitTopLevelObjects(results).map { obj ->
            val probe = JsonFields.stringField(obj, "probe_point").orEmpty()
            val net = JsonFields.stringField(obj, "net").orEmpty()
            val verdict = JsonFields.stringField(obj, "verdict").orEmpty()
            val range = JsonFields.stringField(obj, "expected_range").orEmpty()
            val reason = JsonFields.stringField(obj, "reasoning").orEmpty()
            "| $probe | $net | $verdict | $range | ${reason.take(120)} |"
        }
        val header = "| Probe | Net | Verdict | Expected | Reasoning |"
        val sep = "|---|---|---|---|---|"
        return buildString {
            append("# Report: $board\n\n")
            append("**Overall:** $overall\n\n")
            append(header).append('\n').append(sep).append('\n')
            rows.forEach { append(it).append('\n') }
        }
    }

    private fun splitTopLevelObjects(arr: String): List<String> {
        if (arr.isBlank() || arr.first() != '[') return emptyList()
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inStr = false
        var esc = false
        for (i in arr.indices) {
            val c = arr[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) { out.add(arr.substring(start, i + 1)); start = -1 } }
            }
        }
        return out
    }

    companion object {
        private val DEFAULT_SCHEMATIC_JSON = """
            {
              "board_name": "Demo Board",
              "understanding": "",
              "probe_points": [
                {
                  "label": "TP1",
                  "net": "VCC_3V3",
                  "expected_range": "3.3V +/- 5%",
                  "probe_type": "power_rail",
                  "designator": "TP1",
                  "pin_name": "1",
                  "pin_number": "1"
                }
              ]
            }
        """.trimIndent()
    }
}
