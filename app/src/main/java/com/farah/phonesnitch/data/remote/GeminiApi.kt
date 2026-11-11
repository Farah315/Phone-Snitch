package com.farah.phonesnitch.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface GeminiApi {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generate(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body body: GeminiRequest,
    ): GeminiResponse

    /**
     * Same endpoint, but with raw JSON in and out. The agent needs this because function calling
     * sends back extra fields (like thoughtSignature) that must be echoed to the model untouched.
     */
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateRaw(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body body: JsonObject,
    ): JsonObject
}

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val generationConfig: GenerationConfig? = null,
)

@Serializable
data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart> = emptyList(),
)

@Serializable
data class GeminiPart(val text: String = "")

@Serializable
data class GenerationConfig(
    val temperature: Float = 0f,
    val responseMimeType: String? = null,
)

@Serializable
data class GeminiResponse(val candidates: List<GeminiCandidate> = emptyList())

@Serializable
data class GeminiCandidate(val content: GeminiContent? = null)

// ---- DTOs for the classification prompt/response (the JSON contract with the model) ----

@Serializable
data class ClassifyRequest(val apps: List<AppUsageDto>)

@Serializable
data class AppUsageDto(
    @SerialName("package") val packageName: String,
    val label: String,
    val usage: Map<String, Int> = emptyMap(),
)

@Serializable
data class ClassifyResponse(val results: List<AppCategoryDto> = emptyList())

@Serializable
data class AppCategoryDto(
    @SerialName("package") val packageName: String,
    val category: String,
    val confidence: Float = 0f,
    @SerialName("peak_slots") val peakSlots: List<String> = emptyList(),
)
