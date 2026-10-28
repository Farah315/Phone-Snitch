package com.farah.phonesnitch.data.remote

import com.farah.phonesnitch.domain.model.AppCategory
import com.farah.phonesnitch.domain.model.AppClassification
import com.farah.phonesnitch.domain.model.AppUsageInput
import com.farah.phonesnitch.domain.model.TimeSlot
import com.farah.phonesnitch.domain.repository.AiService
import kotlinx.serialization.json.Json
import retrofit2.HttpException

class GeminiAiService(
    private val api: GeminiApi,
    private val json: Json,
    private val apiKey: String,
    private val model: String,
) : AiService {

    override suspend fun classify(apps: List<AppUsageInput>): List<AppClassification> {
        val request = ClassifyRequest(
            apps = apps.map { input ->
                AppUsageDto(
                    packageName = input.app.packageName,
                    label = input.app.label,
                    // No data yet (day 1) -> empty usage, the model classifies by name only.
                    usage = if (input.usage.isEmpty()) {
                        emptyMap()
                    } else {
                        TimeSlot.entries.associate { it.key to (input.usage[it] ?: 0) }
                    },
                )
            }
        )

        val raw = generate(
            system = CLASSIFY_PROMPT,
            user = json.encodeToString(ClassifyRequest.serializer(), request),
            jsonMode = true,
        )
        val parsed = json.decodeFromString(ClassifyResponse.serializer(), cleanJson(raw))

        // Never trust the model blindly: keep only packages we actually sent, map unknown categories to OTHER.
        val known = apps.map { it.app.packageName }.toSet()
        return parsed.results
            .filter { it.packageName in known }
            .map { dto ->
                AppClassification(
                    packageName = dto.packageName,
                    category = AppCategory.fromKey(dto.category),
                    confidence = dto.confidence.coerceIn(0f, 1f),
                    peakSlots = dto.peakSlots.mapNotNull { TimeSlot.fromKey(it) }.toSet(),
                )
            }
    }

    override suspend fun ask(question: String): String =
        generate(system = ASSISTANT_PROMPT, user = question, jsonMode = false).trim()

    private suspend fun generate(system: String, user: String, jsonMode: Boolean): String {
        check(apiKey.isNotBlank()) { "GEMINI_API_KEY is missing. Add it to local.properties and rebuild." }

        val body = GeminiRequest(
            contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(user)))),
            systemInstruction = GeminiContent(parts = listOf(GeminiPart(system))),
            generationConfig = GenerationConfig(
                // 0 for classification so the same app gets the same category day after day.
                temperature = if (jsonMode) 0f else 0.7f,
                responseMimeType = if (jsonMode) "application/json" else null,
            ),
        )

        val response = try {
            api.generate(model, apiKey, body)
        } catch (e: HttpException) {
            throw IllegalStateException(
                "AI request failed (HTTP ${e.code()}) for model \"$model\". " +
                    "404 = model name is retired or wrong, 400/403 = bad API key. " +
                    "Fix GEMINI_MODEL / GEMINI_API_KEY in local.properties and rebuild.", e
            )
        }

        return response.candidates.firstOrNull()?.content?.parts
            ?.joinToString("") { it.text }
            ?.takeIf { it.isNotBlank() }
            ?: error("The AI returned an empty response.")
    }

    /** Some models still wrap JSON in ```json fences even in JSON mode. */
    private fun cleanJson(raw: String): String =
        raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    private companion object {
        val CLASSIFY_PROMPT = """
            You are an app-categorization engine inside an Android launcher.
            You receive a list of installed apps with usage statistics and must classify each app.

            Rules:
            1. Use ONLY these categories: ${AppCategory.entries.joinToString(", ") { it.key }}.
            2. Use ONLY these time slots: ${TimeSlot.entries.joinToString(", ") { it.key }}.
            3. For each app return exactly one "category" and a "confidence" between 0 and 1.
            4. If usage stats are provided, use them to decide "peak_slots": the 1-2 time slots where the app is most relevant. Use an empty list if there is no usage data.
            5. Base the category on the app's purpose first. Use usage patterns only to break ties (e.g. Slack used mostly in work_hours => work).
            6. If you do not recognize the app, use "other" with low confidence. Never guess wildly.
            7. Return ONLY valid JSON, no explanations, no markdown, in exactly this shape:
               {"results":[{"package":"<package>","category":"<category>","confidence":0.0,"peak_slots":["<slot>"]}]}
            8. Return one result for every app in the input, using the exact "package" value you were given.
        """.trimIndent()

        const val ASSISTANT_PROMPT =
            "You are a quick assistant inside an Android launcher. Answer briefly and clearly, " +
                "in the same language the user wrote in. Prefer short paragraphs over long lists."
    }
}
