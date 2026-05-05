package com.penpal.core.ai

import com.google.gson.annotations.SerializedName

/**
 * Data class representing an Ollama model.
 */
data class OllamaModel(
    @SerializedName("name") val name: String,
    @SerializedName("model") val model: String,
    @SerializedName("modified_at") val modifiedAt: String,
    @SerializedName("size") val size: Long,
    @SerializedName("digest") val digest: String,
    @SerializedName("details") val details: ModelDetails? = null
)

data class ModelDetails(
    @SerializedName("parent_model") val parentModel: String,
    @SerializedName("format") val format: String,
    @SerializedName("family") val family: String,
    @SerializedName("families") val families: List<String>?,
    @SerializedName("parameter_size") val parameterSize: String,
    @SerializedName("quantization_level") val quantizationLevel: String
)

data class OllamaTagsResponse(
    @SerializedName("models") val models: List<OllamaModel>
)

data class OllamaGenerateRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean = false,
    val options: Map<String, Any>? = null
)

data class OllamaGenerateResponse(
    val model: String,
    @SerializedName("created_at") val createdAt: String,
    val response: String,
    val done: Boolean,
    @SerializedName("total_duration") val totalDuration: Long? = null,
    @SerializedName("load_duration") val loadDuration: Long? = null,
    @SerializedName("sample_count") val sampleCount: Int? = null,
    @SerializedName("sample_duration") val sampleDuration: Long? = null,
    @SerializedName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerializedName("prompt_eval_duration") val promptEvalDuration: Long? = null,
    @SerializedName("eval_count") val evalCount: Int? = null,
    @SerializedName("eval_duration") val evalDuration: Long? = null,
    val context: List<Int>? = null
)

data class OllamaPullRequest(
    val name: String,
    val stream: Boolean = true
)

data class OllamaPullResponse(
    val status: String,
    val digest: String? = null,
    val total: Long? = null,
    val completed: Long? = null
)
