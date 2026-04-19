package com.scopecreep.ui

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

object MarkdownRenderer {
    private val parser: Parser = Parser.builder().build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder().build()

    // NOTE: Swing's HTMLEditorKit has an ancient CSS parser (effectively CSS 1 subset)
    // that NullPointerExceptions on modern properties like `-apple-system`,
    // `overflow-x`, unquoted multi-font stacks, etc. Keep this CSS boring.
    private val cssPrelude = """
        <style>
        body { font-family: SansSerif; font-size: 12pt; color: #dddddd; background-color: #2b2b2b; }
        h1 { font-size: 18pt; color: #ffffff; }
        h2 { font-size: 15pt; color: #ffffff; }
        h3 { font-size: 13pt; color: #ffffff; }
        code { font-family: Monospaced; font-size: 11pt; color: #d0d0d0; background-color: #1e1e1e; }
        pre { font-family: Monospaced; font-size: 11pt; color: #d0d0d0; background-color: #1e1e1e; }
        table { border-color: #555555; }
        th { background-color: #333333; color: #ffffff; }
        td { color: #dddddd; }
        a { color: #58a6ff; }
        </style>
    """.trimIndent()

    fun toHtml(markdown: String): String {
        val body = renderer.render(parser.parse(markdown))
        return "<html><head>$cssPrelude</head><body>$body</body></html>"
    }
}
