package com.scopecreep.service

import com.scopecreep.settings.ScopecreepSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Direct-to-Supabase PostgREST client for `firmware_jobs`.
 *
 * The firmware pipeline lives in a sibling repo (~/benchy/pipeline/) and
 * writes progress here; this branch only wires the UI to observe those
 * rows. When the pipeline is absent, inserts still succeed and the row
 * stays at status='queued' forever — that's expected for this MVP.
 *
 * Realtime (WebSocket) support is deferred; we poll the row instead.
 */
class FirmwareClient(
    private val client: OkHttpClient = defaultClient,
    private val settings: ScopecreepSettings = ScopecreepSettings.getInstance(),
) {

    private val base: String get() = settings.state.supabaseUrl.trimEnd('/') + "/rest/v1"
    private val apiKey: String get() = settings.state.supabaseAnonKey

    fun createJob(goal: String, target: String = "esp32-s3"): Result {
        val payload = buildString {
            append('{')
            append("\"goal\":").append(JsonFields.quote(goal))
            append(',')
            append("\"target\":").append(JsonFields.quote(target))
            append('}')
        }
        return request(
            "POST",
            "/firmware_jobs",
            body = payload,
            extraHeaders = mapOf("Prefer" to "return=representation"),
        )
    }

    fun getJob(jobId: String): Result =
        request("GET", "/firmware_jobs?id=eq.$jobId&select=*")

    fun listJobs(limit: Int = 25): Result =
        request("GET", "/firmware_jobs?select=id,goal,target,status,created_at,updated_at&order=created_at.desc&limit=$limit")

    fun requestFlash(jobId: String): Result =
        request(
            "PATCH",
            "/firmware_jobs?id=eq.$jobId",
            body = "{\"status\":\"flash_requested\"}",
        )

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Result {
        if (apiKey.isBlank())
            return Result.Err("Supabase anon key is not configured (Settings → Tools → Scopecreep).")
        val url = base + path
        val rb = body?.toRequestBody(JSON)
        val builder = Request.Builder()
            .url(url)
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(rb ?: EMPTY_BODY)
            "PATCH" -> builder.patch(rb ?: EMPTY_BODY)
            "DELETE" -> builder.delete(rb)
            else -> return Result.Err("Unsupported method $method")
        }
        return try {
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) Result.Ok(text)
                else Result.Err("HTTP ${response.code}: $text")
            }
        } catch (t: Throwable) {
            Result.Err(t.message ?: t.javaClass.simpleName)
        }
    }

    sealed class Result {
        data class Ok(val body: String) : Result()
        data class Err(val message: String) : Result()
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val EMPTY_BODY = "".toRequestBody(JSON)
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
