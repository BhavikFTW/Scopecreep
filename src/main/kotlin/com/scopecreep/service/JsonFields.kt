package com.scopecreep.service

/**
 * Minimal JSON-field extractor. Covers the small, well-shaped responses the
 * agent backend returns (single-level string/number/object lookups). Avoids
 * pulling a full JSON library; when payload shape grows, swap in Jackson
 * (already on the IntelliJ platform classpath).
 *
 * Not a real parser — tolerates nested objects for stringField on top-level
 * only. Callers pass pre-scoped substrings (e.g. the inner "current_probe"
 * object) when they need to read nested fields.
 */
object JsonFields {

    private val STRING_RE = Regex("\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"")

    fun stringField(json: String, key: String): String? {
        val m = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"")
            .find(json) ?: return null
        return unescape(m.groupValues[1])
    }

    fun intField(json: String, key: String): Int? {
        val m = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)").find(json) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /**
     * Return the raw substring of the value for [key] — usable as a scoped
     * input for further extraction. Supports object `{...}` and array `[...]`
     * values only; returns null for primitives.
     */
    fun objectField(json: String, key: String): String? {
        val needle = "\"${Regex.escape(key)}\""
        val keyMatch = Regex("$needle\\s*:\\s*").find(json) ?: return null
        val start = keyMatch.range.last + 1
        if (start >= json.length) return null
        val open = json[start]
        val (opener, closer) = when (open) {
            '{' -> '{' to '}'
            '[' -> '[' to ']'
            else -> return null
        }
        var depth = 0
        var inStr = false
        var esc = false
        var i = start
        while (i < json.length) {
            val c = json[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    opener -> depth++
                    closer -> {
                        depth--
                        if (depth == 0) return json.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    private fun unescape(s: String): String = s
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    /**
     * Very basic JSON string quoter. Mirrors the helper inline in RunnerClient.
     */
    fun quote(s: String): String = "\"" + s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\""
}
