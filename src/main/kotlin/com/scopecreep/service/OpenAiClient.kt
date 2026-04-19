package com.scopecreep.service

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Thin OpenAI chat-completions client. Kept API-compatible with the sibling
 * schematic-parser branch so either implementation drops in interchangeably:
 * `OpenAiClient(apiKey, model).chat(system, user)` → `Result`.
 */
class OpenAiClient(
    private val apiKey: String,
    private val model: String = "gpt-4o-mini",
    private val client: OkHttpClient = defaultClient,
) {

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Err(val message: String) : Result()
    }

    fun chat(system: String, user: String): Result {
        val body = """
            {
              "model": ${jsonQuoted(model)},
              "messages": [
                {"role": "system", "content": ${jsonQuoted(system)}},
                {"role": "user", "content": ${jsonQuoted(user)}}
              ],
              "temperature": 0.2
            }
        """.trimIndent().toRequestBody(JSON)

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use Result.Err("OpenAI HTTP ${response.code}: ${text.take(300)}")
                }
                val content = extractContent(text)
                    ?: return@use Result.Err("Unexpected response shape")
                Result.Ok(content)
            }
        } catch (t: Throwable) {
            Result.Err(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun extractContent(json: String): String? {
        val marker = "\"content\""
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
                when (val next = json[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 < json.length) {
                            val hex = json.substring(i + 2, i + 6)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                    }
                    else -> sb.append(next)
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

    private fun jsonQuoted(s: String): String = "\"" +
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") +
        "\""

    companion object {
        private val JSON = "application/json".toMediaType()
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }
}
