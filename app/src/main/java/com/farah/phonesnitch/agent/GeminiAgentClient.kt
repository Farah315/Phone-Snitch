package com.farah.phonesnitch.agent

import com.farah.phonesnitch.data.remote.GeminiApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import retrofit2.HttpException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException

/**
 * The agent loop with Gemini function calling:
 *
 *   user message -> model
 *   model answers with text            -> done
 *   model answers with function call(s) -> we run our own code (AgentTools), send the result back -> model again
 *
 * The conversation is kept as raw JSON on purpose: the model's own turns are sent back exactly as received,
 * which keeps fields like `thoughtSignature` (required by newer Gemini models for multi-step tool use).
 */
class GeminiAgentClient(
    private val api: GeminiApi,
    private val apiKey: String,
    private val tools: AgentTools,
) {

    /** @param dryRun when true, tools that change something only report what they would do. */
    suspend fun run(model: AgentModel, userMessage: String, dryRun: Boolean): AgentResult =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val steps = mutableListOf<AgentStep>()
            fun elapsed() = System.currentTimeMillis() - started

            try {
                check(apiKey.isNotBlank()) { "GEMINI_API_KEY is missing. Add it to local.properties and rebuild." }

                val contents = mutableListOf<JsonElement>(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", userMessage) }) }
                    }
                )

                for (step in 1..MAX_STEPS) {
                    val response = api.generateRaw(model.id, apiKey, requestBody(contents))
                    val parts = modelParts(response)
                        ?: return@withContext AgentResult(model, "", steps, elapsed(), "The model returned no answer.")

                    // Echo the model turn back exactly as it came.
                    contents += buildJsonObject {
                        put("role", "model")
                        put("parts", parts)
                    }

                    val calls = parts.mapNotNull { (it as? JsonObject)?.get("functionCall") as? JsonObject }
                    if (calls.isEmpty()) {
                        val text = parts
                            .mapNotNull { it as? JsonObject }
                            .filter { (it["thought"] as? JsonPrimitive)?.booleanOrNull != true }
                            .mapNotNull { (it["text"] as? JsonPrimitive)?.contentOrNull }
                            .joinToString("")
                            .trim()
                        return@withContext AgentResult(model, text.ifBlank { "Done." }, steps, elapsed())
                    }

                    // Run every requested function, then send all results back in ONE turn.
                    val outcomes = calls.map { call ->
                        val name = (call["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val args = call["args"] as? JsonObject ?: JsonObject(emptyMap())
                        val outcome = tools.execute(name, args, dryRun)
                        steps += AgentStep(name, args.toString(), outcome.json.toString(), outcome.ok)
                        Triple(call, name, outcome)
                    }

                    contents += buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            outcomes.forEach { (call, name, outcome) ->
                                add(buildJsonObject {
                                    putJsonObject("functionResponse") {
                                        (call["id"] as? JsonPrimitive)?.contentOrNull?.let { put("id", it) }
                                        put("name", name)
                                        putJsonObject("response") { put("result", outcome.json) }
                                    }
                                })
                            }
                        }
                    }

                    // After a successful "open app" there is nothing left to say: skip the extra model round trip.
                    if (outcomes.all { it.third.finalMessage != null }) {
                        val message = outcomes.joinToString("\n") { it.third.finalMessage.orEmpty() }
                        return@withContext AgentResult(model, message, steps, elapsed())
                    }
                }

                AgentResult(model, "", steps, elapsed(), "Stopped after $MAX_STEPS steps without a final answer.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty().take(300)
                AgentResult(
                    model, "", steps, elapsed(),
                    "HTTP ${e.code()} from model \"${model.id}\". 404 = model name retired or wrong, " +
                        "400/403 = bad request or API key. $body",
                )
            } catch (e: Exception) {
                AgentResult(model, "", steps, elapsed(), e.message ?: e.javaClass.simpleName)
            }
        }

    /**
     * Runs the same message on every model at the same time, as a dry run
     * (nothing is opened or changed), so the tool choices can be compared.
     */
    suspend fun compare(models: List<AgentModel>, userMessage: String): List<AgentResult> = coroutineScope {
        models.map { model -> async { run(model, userMessage, dryRun = true) } }.awaitAll()
    }

    // ------------------------------------------------------------------ request / response helpers

    private fun requestBody(contents: List<JsonElement>): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { add(buildJsonObject { put("text", systemPrompt()) }) }
        }
        // Copy the list: the loop keeps appending to `contents` after this request is built.
        put("contents", JsonArray(contents.toList()))
        putJsonArray("tools") {
            add(buildJsonObject { put("functionDeclarations", tools.declarations) })
        }
        putJsonObject("toolConfig") {
            putJsonObject("functionCallingConfig") { put("mode", "AUTO") }
        }
        // No temperature on purpose: Gemini 3 models are tuned for their default and can loop when it is lowered.
    }

    private fun modelParts(response: JsonObject): JsonArray? =
        ((response["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.let { it["content"] as? JsonObject }
            ?.let { it["parts"] as? JsonArray }
            ?.takeIf { it.isNotEmpty() }

    private fun systemPrompt(): String = """
        You are the assistant inside an Android launcher called Phone Snitch.
        You can read the phone's usage data and control the launcher ONLY through the tools you are given.

        Rules:
        1. When the user asks you to do something (open an app, set a daily limit, turn the detox on or off,
           pin an app, change a category), call the matching tool. Never say you did something unless a tool confirmed it.
        2. App names on this phone are usually in English. If the user writes in another language, translate the name
           (for example "واتساب" becomes "WhatsApp"). If you are not sure of the exact name, call find_apps first.
        3. For any question about usage numbers (opens, minutes, screen time) call get_usage_stats or get_app_usage.
           Never guess numbers.
        4. If a tool returns an error, explain the reason to the user in one short sentence.
        5. If no tool is needed, just answer.
        6. Reply in the language the user used. Keep it short and friendly.

        Current local time: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm"))}
    """.trimIndent()

    private companion object {
        const val MAX_STEPS = 6
    }
}
