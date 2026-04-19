package com.scopecreep.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class MermaidView(parentDisposable: Disposable) {

    private val browser: JBCefBrowser? =
        if (JBCefApp.isSupported()) JBCefBrowser() else null

    val component: JComponent = JPanel(BorderLayout()).apply {
        if (browser != null) {
            add(browser.component, BorderLayout.CENTER)
            val html = MermaidView::class.java.getResourceAsStream("/mermaid/viewer.html")
                ?.bufferedReader()?.readText() ?: PLACEHOLDER_HTML
            browser.loadHTML(html)
        } else {
            add(JLabel("<html><i>JCEF unavailable — diagram rendering disabled.</i></html>"), BorderLayout.CENTER)
        }
    }

    init {
        if (browser != null) Disposer.register(parentDisposable, browser)
    }

    fun render(mermaidCode: String) {
        val b = browser ?: return
        val js = "window.renderMermaid(${jsLiteral(mermaidCode)});"
        b.cefBrowser.executeJavaScript(js, b.cefBrowser.url, 0)
    }

    private fun jsLiteral(s: String): String = "\"" +
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") +
        "\""

    companion object {
        private const val PLACEHOLDER_HTML =
            "<html><body style='background:#2b2b2b;color:#888;font-family:sans-serif;padding:16px'>" +
                "Failed to load viewer." +
                "</body></html>"
    }
}
