package com.scopecreep.service

import com.scopecreep.settings.ScopecreepSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class RunnerClient(
    private val client: OkHttpClient = defaultClient,
    private val settings: ScopecreepSettings = ScopecreepSettings.getInstance(),
) {

    fun ping(): Result {
        val url = settings.runnerUrl.trimEnd('/') + "/health"
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.Ok(response.body?.string().orEmpty())
                } else {
                    Result.Err("HTTP ${response.code}")
                }
            }
        } catch (t: Throwable) {
            Result.Err(t.message ?: t.javaClass.simpleName)
        }
    }

    fun parseSchematic(schdoc: File): Result {
        val url = settings.runnerUrl.trimEnd('/') + "/schematic/parse"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                schdoc.name,
                schdoc.asRequestBody("application/octet-stream".toMediaType()),
            )
            .build()
        val request = Request.Builder().url(url).post(body).build()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) Result.Ok(text)
                else Result.Err("HTTP ${response.code}: ${text.take(200)}")
            }
        } catch (t: Throwable) {
            Result.Err(t.message ?: t.javaClass.simpleName)
        }
    }

    fun uploadFiles(schematic: File, pcb: File): Result {
        val url = settings.runnerUrl.trimEnd('/') + "/upload"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("schematic", schematic.name, schematic.asRequestBody("application/octet-stream".toMediaType()))
            .addFormDataPart("pcb", pcb.name, pcb.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = Request.Builder().url(url).post(body).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.Ok(response.body?.string().orEmpty())
                } else {
                    Result.Err("HTTP ${response.code}")
                }
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
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
