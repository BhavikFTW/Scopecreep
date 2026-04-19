package com.scopecreep.ui

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Key
import com.scopecreep.settings.ScopecreepSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

// TODO(block-j): replace codex-exec body with /chat/turn orchestrator
//   (tool-call loop + Run-on-code-block) per
//   docs/superpowers/specs/2026-04-18-chatbot-hardware-loop-design.md
/**
 * Free-form chat backed by the `codex exec` CLI. The plugin doesn't embed an
 * LLM client — it shells out so the codex config (OpenAI or Nebius via
 * CodexProviderManager) is honored.
 *
 * Out of scope this branch: persistent history, tool use, image upload.
 */
class ChatPanel : JPanel(BorderLayout()) {

    private val transcript = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        append("Codex chat. Type a prompt and press Send.\n\n")
    }
    private val input = JTextArea(3, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val sendButton = JButton("Send")
    private val cancelButton = JButton("Cancel").apply { isEnabled = false }
    private val status = JLabel("Idle.")

    @Volatile
    private var handler: OSProcessHandler? = null

    init {
        val bottom = JPanel(BorderLayout()).apply {
            add(JScrollPane(input), BorderLayout.CENTER)
            val btns = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(status)
                add(sendButton)
                add(cancelButton)
            }
            add(btns, BorderLayout.SOUTH)
            preferredSize = Dimension(400, 120)
            border = BorderFactory.createTitledBorder("Prompt")
        }
        add(JScrollPane(transcript), BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)

        sendButton.addActionListener { send() }
        cancelButton.addActionListener { cancel() }
    }

    private fun send() {
        val prompt = input.text.trim()
        if (prompt.isEmpty()) return
        appendLine("[you] $prompt\n")
        input.text = ""
        sendButton.isEnabled = false
        cancelButton.isEnabled = true
        status.text = "Running codex…"

        val settings = ScopecreepSettings.getInstance().state
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val cmd = GeneralCommandLine("codex", "exec", prompt)
                if (settings.openAiApiKey.isNotBlank())
                    cmd.withEnvironment("OPENAI_API_KEY", settings.openAiApiKey)
                if (settings.nebiusApiKey.isNotBlank())
                    cmd.withEnvironment("NEBIUS_API_KEY", settings.nebiusApiKey)
                val proc = cmd.createProcess()
                val h = OSProcessHandler(proc, cmd.commandLineString, Charsets.UTF_8)
                h.addProcessListener(object : ProcessAdapter() {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        SwingUtilities.invokeLater { appendLine(event.text) }
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        SwingUtilities.invokeLater {
                            appendLine("\n[exit ${event.exitCode}]\n")
                            sendButton.isEnabled = true
                            cancelButton.isEnabled = false
                            status.text = "Idle."
                        }
                        handler = null
                    }
                })
                h.startNotify()
                handler = h
            } catch (t: Throwable) {
                SwingUtilities.invokeLater {
                    appendLine("[error] ${t.message}\n" +
                        "Hint: install the codex CLI or configure Nebius in Settings → Tools → Scopecreep.\n")
                    sendButton.isEnabled = true
                    cancelButton.isEnabled = false
                    status.text = "Error."
                }
            }
        }
    }

    private fun cancel() {
        val h = handler ?: return
        h.process.destroy()
        cancelButton.isEnabled = false
        status.text = "Cancelling…"
    }

    private fun appendLine(s: String) {
        transcript.append(s)
        transcript.caretPosition = transcript.document.length
    }
}
