package com.scopecreep.service

import com.scopecreep.settings.ScopecreepSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class RunnerClient(
    private val client: OkHttpClient = defaultClient,
    private val settings: ScopecreepSettings = ScopecreepSettings.getInstance(),
) {

    fun ping(): Result {
        val url = settings.runnerUrl.trimEnd('/') + "/health"
        val request = Request.Builder().url(url).get().build()
        return executeAndReturn(request)
    }

    fun recallProfile(slug: String): Result {
        val url = settings.runnerUrl.trimEnd('/') + "/memory/recall/$slug"
        val request = Request.Builder().url(url).get().build()
        return executeAndReturn(request)
    }

    fun searchProfiles(query: String, limit: Int = 10): Result {
        val url = settings.runnerUrl.trimEnd('/') + "/memory/search"
        val body = """{"query":${jsonQuoted(query)},"limit":$limit}"""
            .toRequestBody(JSON)
        val request = Request.Builder().url(url).post(body).build()
        return executeAndReturn(request)
    }

    fun researchProfile(instrumentName: String): Result {
        val url = settings.runnerUrl.trimEnd('/') + "/memory/research"
        val body = """{"instrument_name":${jsonQuoted(instrumentName)}}"""
            .toRequestBody(JSON)
        val request = Request.Builder().url(url).post(body).build()
        return executeAndReturn(request)
    }

    fun publishProfile(profileId: String): Result {
        val url = settings.runnerUrl.trimEnd('/') + "/memory/publish/$profileId"
        val request = Request.Builder().url(url).post("".toRequestBody(JSON)).build()
        return executeAndReturn(request)
    }

    /**
     * Send a chat turn to the sidecar. `messagesJsonArray` is a JSON array of
     * `{"role":..., "content":...}` — caller builds it to avoid pulling a JSON
     * lib into this module. Response body is JSON with `message` + `code_blocks`.
     */
    fun chatTurn(messagesJsonArray: String): Result {
        val url = settings.runnerUrl.trimEnd('/') + "/chat/turn"
        val body = """{"messages":$messagesJsonArray}""".toRequestBody(JSON)
        val request = Request.Builder().url(url).post(body).build()
        return executeAndReturn(request)
    }

    fun execPython(code: String): Result {
        val url = settings.runnerUrl.trimEnd('/') + "/exec/python"
        val body = """{"code":${jsonQuoted(code)}}""".toRequestBody(JSON)
        val request = Request.Builder().url(url).post(body).build()
        return executeAndReturn(request)
    }

    internal fun jsonQuote(s: String): String = jsonQuoted(s)

    private fun executeAndReturn(request: Request): Result =
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.Ok(response.body?.string().orEmpty())
                else Result.Err("HTTP ${response.code}")
            }
        } catch (t: Throwable) {
            Result.Err(t.message ?: t.javaClass.simpleName)
        }

    private fun jsonQuoted(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") +
        "\""

    sealed class Result {
        data class Ok(val body: String) : Result()
        data class Err(val message: String) : Result()
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            // Research calls to Nebius can take 20+ seconds on cold cache.
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
