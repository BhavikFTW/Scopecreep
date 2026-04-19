package com.scopecreep.service

import com.scopecreep.settings.ScopecreepSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Client for the bundled jbhack agent backend (python/api + python/agent).
 *
 * Endpoints:
 *   GET  /health
 *   POST /schematic/parse   (multipart upload → Markdown)
 *   POST /agent/sessions    (JSON body)
 *   GET  /agent/sessions/{id}
 *   POST /agent/sessions/{id}/resume
 *   GET  /agent/sessions/{id}/report
 *   DELETE /agent/sessions/{id}
 *
 * Result is the same Ok/Err wrapper as RunnerClient so callers can share UI
 * patterns. The backend has no SSE — UI layers must poll getSession.
 */
class AgentClient(
    private val client: OkHttpClient = defaultClient,
    private val settingsUrl: () -> String = { ScopecreepSettings.getInstance().agentUrl },
) {

    fun health(): Result = get("/health")

    fun parseSchematic(file: File): Result = multipartPost("/schematic/parse", file)

    fun parseSchematicJson(file: File): Result = multipartPost("/schematic/parse.json", file)

    private fun multipartPost(path: String, file: File): Result {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("application/octet-stream".toMediaType()),
            )
            .build()
        return post(path, body)
    }

    fun startSession(schematicJson: String, configJson: String = "{}"): Result {
        val payload = """{"schematic":$schematicJson,"config":$configJson}"""
        return post("/agent/sessions", payload.toRequestBody(JSON))
    }

    fun getSession(sessionId: String): Result = get("/agent/sessions/$sessionId")

    fun resumeSession(sessionId: String): Result =
        post("/agent/sessions/$sessionId/resume", "".toRequestBody(JSON))

    fun getReport(sessionId: String): Result = get("/agent/sessions/$sessionId/report")

    fun cancelSession(sessionId: String): Result = delete("/agent/sessions/$sessionId")

    private fun get(path: String): Result = execute(Request.Builder().url(url(path)).get().build())

    private fun post(path: String, body: okhttp3.RequestBody): Result =
        execute(Request.Builder().url(url(path)).post(body).build())

    private fun delete(path: String): Result =
        execute(Request.Builder().url(url(path)).delete().build())

    private fun url(path: String): String = settingsUrl().trimEnd('/') + path

    private fun execute(request: Request): Result =
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) Result.Ok(body)
                else Result.Err("HTTP ${response.code}${if (body.isNotBlank()) ": $body" else ""}")
            }
        } catch (t: Throwable) {
            Result.Err(t.message ?: t.javaClass.simpleName)
        }

    sealed class Result {
        data class Ok(val body: String) : Result()
        data class Err(val message: String) : Result()
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            // Agent sessions may take many seconds per probe; schematic parse
            // can hit an LLM for "understanding" and block for 20s+.
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
