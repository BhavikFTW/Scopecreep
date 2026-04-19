package com.scopecreep.service

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OpenAiClient(
    private val apiKey: String,
    private val model: String = "gpt-4o",
    private val client: OkHttpClient = defaultClient,
) {

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Err(val message: String) : Result()
    }

    fun chat(system: String, user: String): Result {
        val payload = JsonObject().apply {
            addProperty("model", model)
            add("messages", Gson().toJsonTree(listOf(
                mapOf("role" to "system", "content" to system),
                mapOf("role" to "user", "content" to user),
            )))
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return Result.Err("OpenAI HTTP ${response.code}: ${text.take(300)}")
                }
                val root = JsonParser.parseString(text).asJsonObject
                val content = root
                    .getAsJsonArray("choices")
                    .get(0).asJsonObject
                    .getAsJsonObject("message")
                    .get("content").asString
                Result.Ok(content)
            }
        } catch (t: Throwable) {
            Result.Err(t.message ?: t.javaClass.simpleName)
        }
    }

    companion object {
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }
}
