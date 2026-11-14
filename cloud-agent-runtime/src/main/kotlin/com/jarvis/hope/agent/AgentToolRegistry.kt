package com.jarvis.hope.agent

import com.fasterxml.jackson.databind.JsonNode

internal data class AgentToolDefinition(
    val name: String,
    val permissionScope: String,
    val riskCategory: String,
    val confirmationRequired: Boolean,
    val plannerContract: String,
    private val decodeParameters: (JsonNode) -> Map<String, Any>,
) {
    fun toAction(parameters: JsonNode, requestedRisk: String): PlannedAction {
        require(requestedRisk == riskCategory) { "Planner tool risk category is invalid." }
        require(parameters.isObject) { "Planner tool parameters must be an object." }
        return PlannedAction(
            toolName = name,
            parameters = decodeParameters(parameters),
            riskCategory = riskCategory,
            permissionScope = permissionScope,
        )
    }
}

internal object AgentToolRegistry {
    val definitions: List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = "get_device_context",
            permissionScope = "device.context.read",
            riskCategory = "low",
            confirmationRequired = true,
            plannerContract = """{"tool":"get_device_context","parameters":{},"risk":"low"}""",
            decodeParameters = { parameters ->
                require(parameters.size() == 0) { "Device context parameters are invalid." }
                emptyMap()
            },
        ),
        AgentToolDefinition(
            name = "set_screen_brightness",
            permissionScope = "system_settings.write",
            riskCategory = "moderate",
            confirmationRequired = true,
            plannerContract = """{"tool":"set_screen_brightness","parameters":{"percent":N},"risk":"moderate"}""",
            decodeParameters = { parameters ->
                val percent = parameters.path("percent")
                require(
                    parameters.size() == 1 && percent.isIntegralNumber &&
                        percent.intValue() in 5..100,
                ) { "Brightness parameters are invalid." }
                mapOf("percent" to percent.intValue())
            },
        ),
    )

    private val definitionsByName = definitions.associateBy(AgentToolDefinition::name)

    fun decodeAction(toolName: String, parameters: JsonNode, riskCategory: String): PlannedAction {
        val definition = definitionsByName[toolName]
            ?: throw IllegalArgumentException("Planner requested an unsupported action.")
        return definition.toAction(parameters, riskCategory)
    }

    fun plannerContracts(): String =
        definitions.joinToString(separator = " or ") { it.plannerContract }
}
