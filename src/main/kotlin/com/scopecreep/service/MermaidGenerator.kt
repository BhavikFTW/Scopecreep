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

        // Pass 1: normalize the messy analysis into a clean connectivity table.
        val normalized = when (val r = openAi.chat(NORMALIZE_SYSTEM, analysis)) {
            is OpenAiClient.Result.Err -> return Result.Err("Normalize: ${r.message}")
            is OpenAiClient.Result.Ok -> r.text
        }

        // Pass 2: turn the clean table into summary + Mermaid.
        return when (val r = openAi.chat(MERMAID_SYSTEM, normalized)) {
            is OpenAiClient.Result.Err -> Result.Err("Mermaid: ${r.message}")
            is OpenAiClient.Result.Ok -> parse(r.text)
        }
    }

    companion object Prompts {
        private val NORMALIZE_SYSTEM = """
            You are a PCB netlist normalizer. Given a messy schematic analysis,
            extract every component and every pin-to-net connection and output a
            CLEAN, DETERMINISTIC block in EXACTLY this format (no prose, no
            markdown, no code fences):

            NETS
            - <net_name>
            - <net_name>
            ...

            COMPONENTS
            - <refdes> | <part/value> | <role>
            - <refdes> | <part/value> | <role>
            ...

            CONNECTIONS
            - <refdes>.<pin> -> <net_name>
            - <refdes>.<pin> -> <net_name>
            ...

            TESTPOINTS
            - <connector>.<pin> -> <net_name>
            ...

            Rules:
            - Emit every connection implied by the input. If the input only lists
              "loads" for a net, each load becomes one connection with pin = "?"
              if unknown.
            - Net names: keep exactly as given (e.g. "3V3", "NET_452").
            - Do not invent components or pins. If a pin number is unknown, use "?".
            - No duplicates, no commentary, no trailing blank lines.
        """.trimIndent()

        private val MERMAID_SYSTEM = """
            You are given a CLEAN, DETERMINISTIC PCB netlist (sections: NETS,
            COMPONENTS, CONNECTIONS, TESTPOINTS). Produce a short overview plus a
            Mermaid flowchart of the net-level connectivity. Output MUST be valid
            Mermaid; syntax errors are unacceptable.

            DIAGRAM RULES:
              1. Start with "flowchart LR".
              2. Node IDs: letters, digits, and underscore ONLY. No dots, spaces,
                 dashes, or unicode. For nets whose names start with a digit,
                 prefix with "N_" (so "3V3" → "N_3V3", "12V" → "N_12V").
              3. Node LABELS must ALWAYS be wrapped in double quotes:
                   - NET:        `N_3V3(["3V3"])`
                   - COMPONENT:  `U1["U1 — TPS62932DRLR"]`
                   - TESTPOINT:  `J5_1{"J5.1 CANPT_H"}`
                 NEVER write a bare identifier after `{`, `[`, or `([`.
              4. For every line in CONNECTIONS and TESTPOINTS, draw a directed
                 edge from the component to the net, labeled with the pin number
                 (quoted): `U1 -- "3" --> N_3V3`. Use "?" for unknown pins.
              5. Power nets (GND, 3V3, 12V, etc.) get a class:
                   classDef power fill:#f55,color:#fff;
                   class GND,N_3V3,N_12V power;
                 Only include net ids that actually appear as nodes.
              6. Do NOT use HTML, comments, or `subgraph` blocks.
              7. Do NOT invent pins, parts, or nets.

            Respond with ONLY a single JSON object, no prose, no code fences, with
            exactly two string fields:
              "summary"  — 2-4 sentences a non-expert can read.
              "mermaid"  — the full flowchart obeying the rules above.
        """.trimIndent()

        private fun defaultClient(): OpenAiClient? {
            val s = ScopecreepSettings.getInstance().state
            val key = s.openAiApiKey.takeIf { it.isNotBlank() } ?: return null
            return OpenAiClient(apiKey = key, model = s.openAiModel)
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
}
