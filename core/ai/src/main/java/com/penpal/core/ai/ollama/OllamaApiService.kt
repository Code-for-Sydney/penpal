package com.penpal.core.ai.ollama

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class OllamaApiService(
    private val baseUrl: String = "http://10.0.2.2:11434",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build(),
    private val gson: Gson = Gson()
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun listModels(): List<OllamaModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/tags")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            val body = response.body?.string() ?: throw IOException("Empty body")
            val tagsResponse = gson.fromJson(body, OllamaTagsResponse::class.java)
            tagsResponse.models
        }
    }

    suspend fun generate(model: String, prompt: String): String = withContext(Dispatchers.IO) {
        val generateRequest = OllamaGenerateRequest(model = model, prompt = prompt, stream = false)
        val body = gson.toJson(generateRequest).toRequestBody(jsonMediaType)
        
        val request = Request.Builder()
            .url("$baseUrl/api/generate")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            val responseBody = response.body?.string() ?: throw IOException("Empty body")
            val genResponse = gson.fromJson(responseBody, OllamaGenerateResponse::class.java)
            genResponse.response
        }
    }

    fun generateStream(model: String, prompt: String): Flow<OllamaGenerateResponse> = flow {
        val generateRequest = OllamaGenerateRequest(model = model, prompt = prompt, stream = true)
        val body = gson.toJson(generateRequest).toRequestBody(jsonMediaType)
        
        val request = Request.Builder()
            .url("$baseUrl/api/generate")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            val source = response.body?.source() ?: throw IOException("Empty body")
            
            while (!source.exhausted()) {
                val line = source.readUtf8Line()
                if (!line.isNullOrBlank()) {
                    val genResponse = gson.fromJson(line, OllamaGenerateResponse::class.java)
                    emit(genResponse)
                    if (genResponse.done) break
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun deleteModel(name: String) = withContext(Dispatchers.IO) {
        val deleteRequest = mapOf("name" to name)
        val body = gson.toJson(deleteRequest).toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/api/delete")
            .delete(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
        }
    }

    fun pullModel(name: String): Flow<OllamaPullResponse> = flow {
        val pullRequest = OllamaPullRequest(name = name, stream = true)
        val body = gson.toJson(pullRequest).toRequestBody(jsonMediaType)
        
        val request = Request.Builder()
            .url("$baseUrl/api/pull")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            val source = response.body?.source() ?: throw IOException("Empty body")
            
            while (!source.exhausted()) {
                val line = source.readUtf8Line()
                if (!line.isNullOrBlank()) {
                    val pullResponse = gson.fromJson(line, OllamaPullResponse::class.java)
                    emit(pullResponse)
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}