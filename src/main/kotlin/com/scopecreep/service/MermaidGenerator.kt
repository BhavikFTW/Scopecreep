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
            You are a hardware-schematic assistant. Given a detailed circuit analysis,
            produce a concise overview and a Mermaid diagram of the circuit's high-level
            architecture. Respond with ONLY a single JSON object, no prose, no code
            fences, with exactly two string fields:
              "summary"  — 2-4 sentences a non-expert can read.
              "mermaid"  — valid Mermaid flowchart code (start with "flowchart LR" or
                           "flowchart TD"). Use node labels that match real parts.
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
