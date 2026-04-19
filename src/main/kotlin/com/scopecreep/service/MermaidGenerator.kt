package com.scopecreep.service

import com.scopecreep.settings.ScopecreepSettings

/**
 * Turns a detailed schematic analysis into a short user-facing summary plus
 * Mermaid diagram code. Takes the analysis as a `String` so it can be chained
 * off the schematic-parser orchestrator on the sibling branch without changes.
 */
class MermaidGenerator(
    private val clientFactory: () -> OpenAiClient? = ::defaultClient,
) {

    fun generate(analysis: String): Result {
        if (analysis.isBlank()) return Result.Err("Empty analysis")
        val openAi = clientFactory()
            ?: return Result.Err("OpenAI API key not set — Settings → Tools → Scopecreep")

        val system = """
            You are a hardware-schematic assistant. Given a PCB analysis (components,
            pins, nets, connectors, test points), produce a short overview plus a
            Mermaid flowchart that captures the full net-level connectivity:

            DIAGRAM RULES — follow all of them:
              1. Start with "flowchart LR".
              2. Each NET is a node shaped as a rounded rectangle, id = sanitized net
                 name (letters, digits, underscores only), label = exact net name.
                 Example: `GND([GND])`, `NET_452([NET_452])`.
              3. Each COMPONENT is a node shaped as a box, id = refdes (e.g. U1, R3),
                 label = "refdes\\nvalue-or-part". Example: `U1[U1\\nTPS62932DRLR]`.
              4. For every component pin that connects to a net, draw an edge from the
                 component to the net labeled with the pin number: `U1 -- "3" --> 3V3`.
                 Include EVERY pin listed in the analysis — do not summarise.
              5. Physical test points (J*.1 etc.) should appear as diamond nodes:
                 `J5{"J5.1 CANPT_H"}` with an edge to their net.
              6. Power nets (3V3, 12V, GND) should be styled with `classDef power`
                 and assigned via `class 3V3,12V,GND power;`.
              7. Do NOT invent pins, parts, or nets that are not in the input.

            Respond with ONLY a single JSON object, no prose, no code fences, with
            exactly two string fields:
              "summary"  — 2-4 sentences a non-expert can read.
              "mermaid"  — the full flowchart obeying the rules above.
            Do not wrap the JSON in markdown.
        """.trimIndent()

        return when (val r = openAi.chat(system, analysis)) {
            is OpenAiClient.Result.Err -> Result.Err(r.message)
            is OpenAiClient.Result.Ok -> parse(r.text)
        }
    }

    private fun parse(content: String): Result {
        val cleaned = stripCodeFence(content).trim()
        val summary = extractField(cleaned, "summary")
            ?: return Result.Err("Model response missing 'summary' field")
        val mermaid = extractField(cleaned, "mermaid")
            ?: return Result.Err("Model response missing 'mermaid' field")
        return Result.Ok(summary, mermaid)
    }

    private fun stripCodeFence(s: String): String {
        val trimmed = s.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstNewline = trimmed.indexOf('\n')
        if (firstNewline < 0) return trimmed
        val afterFence = trimmed.substring(firstNewline + 1)
        val lastFence = afterFence.lastIndexOf("```")
        return if (lastFence < 0) afterFence else afterFence.substring(0, lastFence)
    }

    private fun extractField(json: String, field: String): String? {
        val marker = "\"$field\""
        val idx = json.indexOf(marker)
        if (idx < 0) return null
        val colon = json.indexOf(':', idx + marker.length)
        if (colon < 0) return null
        val firstQuote = json.indexOf('"', colon + 1)
        if (firstQuote < 0) return null
        val sb = StringBuilder()
        var i = firstQuote + 1
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                when (json[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    else -> sb.append(json[i + 1])
                }
                i += 2
            } else if (c == '"') {
                return sb.toString()
            } else {
                sb.append(c)
                i++
            }
        }
        return null
    }

    sealed class Result {
        data class Ok(val summary: String, val mermaid: String) : Result()
        data class Err(val message: String) : Result()
    }

    companion object {
        private fun defaultClient(): OpenAiClient? {
            val s = ScopecreepSettings.getInstance().state
            val key = s.openAiApiKey.takeIf { it.isNotBlank() } ?: return null
            return OpenAiClient(apiKey = key, model = s.openAiModel)
        }
    }
}
