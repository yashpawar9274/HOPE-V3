package com.jarvis.hope.agent

class AgentWorker(
    private val store: SupabaseAgentStore,
    private val planner: AgentPlanner,
    private val batchSize: Int,
) {
    fun runOnce() {
        store.claimTasks(batchSize).forEach { task ->
            try {
                val plan = planner.createPlan(task.userId, task.sessionId, task.objective)
                store.publishPlan(task, plan)
            } catch (_: Exception) {
                store.markFailed(task, "planner_or_publish_failed")
                System.err.println("Agent task processing failed for task ${task.taskId}.")
            }
        }
    }
}
