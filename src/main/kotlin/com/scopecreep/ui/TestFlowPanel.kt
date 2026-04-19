package com.scopecreep.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.scopecreep.settings.ScopecreepSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * Drives the OpenAI test-flow CLI (`python -m cli.run_test <.SchDoc>`).
 *
 * The CLI is interactive: it prints the plan, blocks on
 *   "Approve and run? [y/N]"
 *   "PSU set to XV @ YA on <rail>. Press Enter to energize."
 *   "<probe_instruction>  Press Enter to capture."
 *
 * This panel streams stdout/stderr into a transcript and exposes Approve /
 * Next Step / Abort buttons that push the right bytes into the CLI's stdin.
 * When the CLI exits 0 it writes a JSON report; we show its path.
 */
class TestFlowPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {

    private val pickButton = JButton("Pick .SchDoc…")
    private val runButton = JButton("Run test flow").apply { isEnabled = false }
    private val approveButton = JButton("Approve (y)").apply { isEnabled = false }
    private val nextButton = JButton("Next step (Enter)").apply { isEnabled = false }
    private val abortButton = JButton("Abort").apply { isEnabled = false }

    private val status = JLabel("Idle.")
    private val transcript = JTextArea().apply {
        isEditable = false
        lineWrap = false
    }

    @Volatile private var selected: File? = null
    @Volatile private var process: Process? = null
    @Volatile private var stdin: OutputStreamWriter? = null

    init {
        val top = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(pickButton); add(runButton); add(approveButton); add(nextButton); add(abortButton); add(status)
        }
        add(top, BorderLayout.NORTH)
        add(JScrollPane(transcript), BorderLayout.CENTER)
        preferredSize = Dimension(800, 400)

        pickButton.addActionListener { pick() }
        runButton.addActionListener { runCli() }
        approveButton.addActionListener { sendLine("y") }
        nextButton.addActionListener { sendLine("") }
        abortButton.addActionListener { abort() }
    }

    private fun pick() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Select .SchDoc for test flow")
        val vf = FileChooser.chooseFile(descriptor, project, null) ?: return
        selected = File(vf.path)
        status.text = "Selected: ${selected!!.name}"
        runButton.isEnabled = true
    }

    private fun runCli() {
        val schdoc = selected ?: return
        if (process != null) return
        transcript.text = ""
        runButton.isEnabled = false
        approveButton.isEnabled = true
        nextButton.isEnabled = true
        abortButton.isEnabled = true
        status.text = "Starting CLI…"

        val settings = ScopecreepSettings.getInstance().state
        val home = Paths.get(System.getProperty("user.home"), ".scopecreep")
        val benchy = home.resolve("sidecar").resolve("benchy")
        val venvPython = if (System.getProperty("os.name").lowercase().contains("win"))
            home.resolve("venv").resolve("Scripts").resolve("python.exe")
        else home.resolve("venv").resolve("bin").resolve("python3")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val pb = ProcessBuilder(
                    venvPython.toString(),
                    "-m", "cli.run_test",
                    schdoc.absolutePath,
                ).directory(benchy.toFile()).redirectErrorStream(true)
                val env = pb.environment()
                if (settings.openAiApiKey.isNotBlank()) env["OPENAI_API_KEY"] = settings.openAiApiKey
                if (settings.openAiModel.isNotBlank()) env["OPENAI_MODEL"] = settings.openAiModel
                env["MAX_VOLTAGE"] = settings.maxVoltage
                env["MAX_CURRENT"] = settings.maxCurrent
                if (settings.psuPort.isNotBlank()) env["PSU_PORT"] = settings.psuPort
                // Make reports land somewhere the user can find.
                val reports = home.resolve("reports")
                reports.toFile().mkdirs()
                env["PYTHONUNBUFFERED"] = "1"

                val p = pb.start()
                process = p
                stdin = OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8)

                // Drain stdout on this thread.
                val reader = p.inputStream.bufferedReader(StandardCharsets.UTF_8)
                reader.forEachLine { line ->
                    SwingUtilities.invokeLater { appendLine(line + "\n") }
                }
                val code = p.waitFor()
                SwingUtilities.invokeLater { onExit(code) }
            } catch (t: Throwable) {
                SwingUtilities.invokeLater {
                    appendLine("\n[error] ${t.message}\n")
                    onExit(-1)
                }
            }
        }
    }

    private fun sendLine(text: String) {
        val w = stdin ?: return
        try {
            w.write(text)
            w.write("\n")
            w.flush()
            appendLine("[input] ${if (text.isEmpty()) "<enter>" else text}\n")
        } catch (t: Throwable) {
            appendLine("[input error] ${t.message}\n")
        }
    }

    private fun abort() {
        val p = process ?: return
        p.destroy()
        appendLine("[aborted by user]\n")
    }

    private fun onExit(code: Int) {
        status.text = "Exit $code. ${exitHint(code)}"
        process = null
        stdin = null
        runButton.isEnabled = selected != null
        approveButton.isEnabled = false
        nextButton.isEnabled = false
        abortButton.isEnabled = false
    }

    private fun exitHint(code: Int): String = when (code) {
        0 -> "Report written; look for reports/*.json."
        1 -> "User aborted."
        2 -> "Parse error."
        3 -> "Planner error (check OpenAI key / network)."
        4 -> "Safety abort (voltage/current cap exceeded)."
        else -> "Non-zero exit."
    }

    private fun appendLine(s: String) {
        transcript.append(s)
        transcript.caretPosition = transcript.document.length
    }
}
