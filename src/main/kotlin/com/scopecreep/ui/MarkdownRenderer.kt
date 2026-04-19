package com.scopecreep.ui

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

object MarkdownRenderer {
    private val parser: Parser = Parser.builder().build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder().build()

    private val cssPrelude = """
        <style>
        body { font-family: -apple-system, Segoe UI, sans-serif;
               font-size: 13px; line-height: 1.5; color: #ddd;
               background: #2b2b2b; padding: 12px; }
        h1, h2, h3 { color: #fff; border-bottom: 1px solid #444;
                     padding-bottom: 4px; }
        code { background: #1e1e1e; padding: 2px 4px;
               border-radius: 3px; font-family: Menlo, monospace;
               font-size: 12px; }
        pre { background: #1e1e1e; padding: 10px; border-radius: 4px;
              overflow-x: auto; }
        pre code { background: none; padding: 0; }
        table { border-collapse: collapse; margin: 8px 0; }
        th, td { border: 1px solid #555; padding: 4px 8px; text-align: left; }
        th { background: #333; }
        a { color: #58a6ff; }
        </style>
    """.trimIndent()

    fun toHtml(markdown: String): String {
        val body = renderer.render(parser.parse(markdown))
        return "<html><head>$cssPrelude</head><body>$body</body></html>"
    }
}
