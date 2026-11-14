package com.jarvis.hope.agent

class AgentWorker(
    private val store: SupabaseAgentStore,
    private val planner: AgentPlanner,
    private val codingProvider: GeminiCodingProvider,
    private val batchSize: Int,
) {
    fun runOnce() {
        store.claimTasks(batchSize).forEach { task ->
            try {
                if (CodingIntentRouter.route(task.objective) == AgentRequestRoute.CODING) {
                    store.publishCodingPlan(task, codingProvider.generate(task.objective))
                } else {
                    store.publishPlan(
                        task,
                        planner.createPlan(task.userId, task.sessionId, task.objective),
                    )
                }
            } catch (error: Exception) {
                val failureCode = (error as? GeminiCodingProviderException)?.failureCode
                    ?: "planner_or_publish_failed"
                store.markFailed(task, failureCode)
                System.err.println("Agent task processing failed for task ${task.taskId}.")
            }
        }
    }
}
