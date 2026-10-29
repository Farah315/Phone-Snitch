package com.farah.phonesnitch.agent

/** A model the agent can run on. Same API, same tools, so results can be compared fairly. */
data class AgentModel(val id: String, val label: String)

object AgentModels {
    private val known = listOf(
        AgentModel("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite"),
        AgentModel("gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite"),
        AgentModel("gemini-3.6-flash", "Gemini 3.6 Flash"),
        AgentModel("gemini-3.8-flash", "Gemini 3.8 Flash"),
    )

    /** The model from local.properties comes first (it is the default), then the other known ones. */
    fun options(defaultId: String): List<AgentModel> {
        val default = known.firstOrNull { it.id == defaultId } ?: AgentModel(defaultId, defaultId)
        return listOf(default) + known.filter { it.id != default.id }
    }
}

/** One tool call the model made, and what our code answered. */
data class AgentStep(
    val tool: String,
    val args: String,
    val result: String,
    val ok: Boolean,
)

data class AgentResult(
    val model: AgentModel,
    val answer: String,
    val steps: List<AgentStep>,
    val latencyMs: Long,
    val error: String? = null,
)

data class AgentUiState(
    val selected: AgentModel,
    val loading: Boolean = false,
    val result: AgentResult? = null,
    /** Filled by "Compare models": one result per model, tools were NOT executed (dry run). */
    val comparison: List<AgentResult> = emptyList(),
)
