package com.scopecreep.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.scopecreep.service.RunnerClient
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/**
 * Conversational chat for the hardware agent. Posts turns to the sidecar's
 * `/chat/turn` endpoint (OpenAI-backed on the python side). Any ```python
 * fenced block in the assistant response becomes its own bordered card with
 * a Run button wired to `/exec/python`.
 *
 * Messages = JSON array maintained client-side. Sidecar is stateless per
 * turn; the full history is sent every time.
 */
class ChatPanel(private val project: Project? = null) : JPanel(BorderLayout()) {

    private val messages = mutableListOf<Pair<String, String>>() // role, content
    private val client = RunnerClient()

    private val messageStack = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = Color(30, 30, 30)
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    }
    private val scroll = JScrollPane(
        messageStack,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
    ).apply {
        verticalScrollBar.unitIncrement = 16
        border = BorderFactory.createEmptyBorder()
    }

    private val input = JTextArea(3, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val sendButton = JButton("Send")
    private val status = JLabel("Ready.")

    init {
        add(scroll, BorderLayout.CENTER)
        val inputScroll = JScrollPane(input).apply {
            preferredSize = Dimension(400, 80)
        }
        val bottom = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Prompt")
            add(inputScroll, BorderLayout.CENTER)
            val btns = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(status)
                add(sendButton)
            }
            add(btns, BorderLayout.SOUTH)
        }
        add(bottom, BorderLayout.SOUTH)

        sendButton.addActionListener { send() }
        // Cmd/Ctrl+Enter → send
        val sendKey = KeyStroke.getKeyStroke("ctrl ENTER")
        input.inputMap.put(sendKey, "send")
        input.actionMap.put("send", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = send()
        })
        val metaSend = KeyStroke.getKeyStroke("meta ENTER")
        input.inputMap.put(metaSend, "send")

        appendSystemNote(
            "Type a request below. The agent will respond with prose and " +
                "runnable Python. Click Run on any code block to execute it."
        )
    }

    private fun send() {
        val text = input.text.trim()
        if (text.isEmpty()) return
        input.text = ""
        sendButton.isEnabled = false
        status.text = "Thinking…"

        messages += ("user" to text)
        addMessageCard("You", text, Color(40, 70, 110))

        val messagesJson = buildMessagesJson(messages)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = client.chatTurn(messagesJson)
            SwingUtilities.invokeLater {
                sendButton.isEnabled = true
                when (result) {
                    is RunnerClient.Result.Ok -> handleTurnBody(result.body)
                    is RunnerClient.Result.Err -> {
                        status.text = "Error."
                        addMessageCard("Error", result.message, Color(120, 40, 40))
                    }
                }
            }
        }
    }

    private fun handleTurnBody(body: String) {
        val content = extractStringField(body, "\"content\":") ?: body
        status.text = "Ready."
        messages += ("assistant" to content)
        addAssistantResponse(content)
    }

    private fun addAssistantResponse(content: String) {
        val card = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Color(45, 45, 45)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(70, 70, 70)),
                BorderFactory.createEmptyBorder(8, 10, 8, 10),
            )
            alignmentX = Component.LEFT_ALIGNMENT
            // Let BoxLayout stretch the card horizontally with the parent.
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            add(labelFor("Scopecreep", Color(100, 180, 120)))
            add(Box.createVerticalStrut(4))
        }

        // Split content around ```python [path=foo/bar.py] ... ``` blocks.
        val regex = Regex(
            "```(?:python|py)(?:[ \\t]+path=([^\\s`]+))?\\s*\\n(.*?)```",
            RegexOption.DOT_MATCHES_ALL,
        )
        var last = 0
        for (m in regex.findAll(content)) {
            val before = content.substring(last, m.range.first)
            if (before.isNotBlank()) card.add(proseArea(before.trim()))
            card.add(Box.createVerticalStrut(6))
            val path = m.groupValues[1].ifBlank { null }
            val code = m.groupValues[2].trim()
            // Auto-write path= blocks to the project as soon as they arrive.
            // The Save button stays as a manual re-save after edits.
            val autoSaveResult = path?.let { writeToProject(it, code) }
            card.add(codeBlockCard(code, path, autoSaveResult))
            card.add(Box.createVerticalStrut(6))
            last = m.range.last + 1
        }
        val tail = content.substring(last)
        if (tail.isNotBlank()) card.add(proseArea(tail.trim()))

        messageStack.add(Box.createVerticalStrut(8))
        messageStack.add(card)
        messageStack.revalidate()
        messageStack.repaint()
        scrollToBottom()
    }

    private fun codeBlockCard(code: String, suggestedPath: String?, autoSaveStatus: String? = null): JPanel {
        val codeArea = JTextArea(code).apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            background = Color(25, 25, 25)
            foreground = Color(220, 220, 220)
            isEditable = true
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
        }
        val output = JTextArea().apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            background = Color(20, 20, 20)
            foreground = Color(200, 200, 200)
            isEditable = false
            lineWrap = true
            wrapStyleWord = false
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            isVisible = false
        }
        val runButton = JButton("Run")
        val copyButton = JButton("Copy")
        val pathField = JTextField(suggestedPath ?: "", 28).apply {
            toolTipText = "Project-relative path. Save writes here."
        }
        val saveButton = JButton(if (suggestedPath != null) "Re-save" else "Save as…")
        if (autoSaveStatus != null) {
            output.isVisible = true
            output.text = autoSaveStatus
        }
        copyButton.addActionListener {
            val sel = java.awt.datatransfer.StringSelection(codeArea.text)
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
        }
        saveButton.addActionListener {
            val p = pathField.text.trim()
            if (p.isEmpty()) {
                output.isVisible = true
                output.text = "[save] enter a project-relative path first"
                return@addActionListener
            }
            val result = writeToProject(p, codeArea.text)
            output.isVisible = true
            output.text = result
        }
        runButton.addActionListener {
            runButton.isEnabled = false
            output.isVisible = true
            output.text = "running…\n"
            ApplicationManager.getApplication().executeOnPooledThread {
                val res = client.execPython(codeArea.text)
                SwingUtilities.invokeLater {
                    runButton.isEnabled = true
                    when (res) {
                        is RunnerClient.Result.Ok -> {
                            val body = res.body
                            val stdout = extractStringField(body, "\"stdout\":").orEmpty()
                            val stderr = extractStringField(body, "\"stderr\":").orEmpty()
                            val ok = body.contains("\"ok\":true")
                            val sb = StringBuilder()
                            if (stdout.isNotEmpty()) sb.append(stdout)
                            if (stderr.isNotEmpty()) sb.append("\n[stderr]\n").append(stderr)
                            if (sb.isEmpty()) sb.append(if (ok) "(no output)" else "(no output, failed)")
                            output.text = sb.toString()
                        }
                        is RunnerClient.Result.Err -> output.text = "[error] ${res.message}"
                    }
                    scrollToBottom()
                }
            }
        }
        return JPanel(BorderLayout()).apply {
            background = Color(30, 30, 30)
            border = BorderFactory.createLineBorder(Color(80, 80, 80))
            alignmentX = Component.LEFT_ALIGNMENT
            add(codeArea, BorderLayout.CENTER)
            val toolbar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                background = Color(35, 35, 35)
                add(runButton); add(saveButton); add(pathField); add(copyButton)
            }
            add(toolbar, BorderLayout.NORTH)
            add(output, BorderLayout.SOUTH)
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    private fun addMessageCard(speaker: String, content: String, accent: Color) {
        val card = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Color(45, 45, 45)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(accent),
                BorderFactory.createEmptyBorder(8, 10, 8, 10),
            )
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            add(labelFor(speaker, accent))
            add(Box.createVerticalStrut(4))
            add(proseArea(content))
        }
        messageStack.add(Box.createVerticalStrut(8))
        messageStack.add(card)
        messageStack.revalidate()
        messageStack.repaint()
        scrollToBottom()
    }

    private fun appendSystemNote(s: String) {
        val lbl = JLabel("<html><i style='color:#888'>${htmlEscape(s)}</i></html>").apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        messageStack.add(lbl)
    }

    private fun proseArea(text: String): JTextArea = JTextArea(text).apply {
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
        background = Color(45, 45, 45)
        foreground = Color(220, 220, 220)
        border = null
        alignmentX = Component.LEFT_ALIGNMENT
        // Allow the BoxLayout parent to stretch us horizontally so wrapping
        // follows the window width instead of the initial preferred width.
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }

    private fun labelFor(name: String, color: Color): JLabel = JLabel(name).apply {
        foreground = color
        font = font.deriveFont(Font.BOLD)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun writeToProject(relPath: String, content: String): String {
        val proj = project ?: return "[save] no project context — open this tab inside a project"
        val base = proj.basePath ?: return "[save] project has no base path"
        // Reject absolute paths + parent escapes — keep writes scoped to the project.
        if (relPath.startsWith("/") || relPath.startsWith("\\") || relPath.contains("..")) {
            return "[save] refusing unsafe path: $relPath"
        }
        val target = File(base, relPath)
        val result = StringBuilder()
        val err = arrayOf<Throwable?>(null)
        // VFS mutations must run on the EDT inside a WriteCommandAction so PSI
        // listeners fire on the right thread. Caller may be on EDT or pooled.
        val body = Runnable {
            try {
                WriteCommandAction.runWriteCommandAction(proj) {
                    target.parentFile?.mkdirs()
                    val baseVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(base))
                        ?: throw IllegalStateException("cannot resolve project root in VFS")
                    val parent = VfsUtil.createDirectoryIfMissing(baseVf, relPath.substringBeforeLast('/', ""))
                        ?: baseVf
                    val existing = parent.findChild(target.name)
                    val vf = existing ?: parent.createChildData(this, target.name)
                    vf.setBinaryContent(content.toByteArray(Charsets.UTF_8))
                    result.append("[saved] ").append(relPath)
                    FileEditorManager.getInstance(proj).openFile(vf, true)
                }
            } catch (t: Throwable) {
                err[0] = t
            }
        }
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) body.run() else app.invokeAndWait(body)
        return err[0]?.let { "[save failed] ${it.message ?: it.javaClass.simpleName}" }
            ?: result.toString()
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val bar = scroll.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private fun htmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        /**
         * Build a JSON array like `[{"role":"user","content":"..."},...]`.
         * We hand-roll because the plugin stays dependency-light.
         */
        fun buildMessagesJson(msgs: List<Pair<String, String>>): String {
            val sb = StringBuilder("[")
            msgs.forEachIndexed { i, (role, content) ->
                if (i > 0) sb.append(",")
                sb.append("{\"role\":")
                sb.append(jsonQuote(role))
                sb.append(",\"content\":")
                sb.append(jsonQuote(content))
                sb.append("}")
            }
            sb.append("]")
            return sb.toString()
        }

        private fun jsonQuote(s: String): String {
            val b = StringBuilder("\"")
            for (c in s) when (c) {
                '\\' -> b.append("\\\\")
                '"' -> b.append("\\\"")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c.code < 0x20) b.append(String.format("\\u%04x", c.code)) else b.append(c)
            }
            b.append("\"")
            return b.toString()
        }

        /**
         * Pull the first JSON-string value after `key` out of `body`. Linear
         * scan, honours backslash escapes. Returns null if not found.
         * Used instead of pulling in a JSON dep.
         */
        fun extractStringField(body: String, key: String): String? {
            val start = body.indexOf(key)
            if (start < 0) return null
            var i = start + key.length
            while (i < body.length && body[i] != '"') i++
            if (i >= body.length) return null
            i++
            val out = StringBuilder()
            while (i < body.length) {
                val c = body[i]
                if (c == '\\' && i + 1 < body.length) {
                    when (val esc = body[i + 1]) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'u' -> if (i + 5 < body.length) {
                            out.append(body.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 4
                        }
                        else -> out.append(esc)
                    }
                    i += 2
                } else if (c == '"') {
                    return out.toString()
                } else {
                    out.append(c); i++
                }
            }
            return null
        }
    }
}
