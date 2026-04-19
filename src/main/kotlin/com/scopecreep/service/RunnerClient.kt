package com.scopecreep.service

import com.scopecreep.settings.ScopecreepSettings
import okhttp3.OkHttpClient
import okhttp3.Request
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

    sealed class Result {
        data class Ok(val body: String) : Result()
        data class Err(val message: String) : Result()
    }

    companion object {
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
