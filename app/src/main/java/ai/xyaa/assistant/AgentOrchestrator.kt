package ai.xyaa.assistant

/**
 * Deterministic multi-specialist coordinator.
 *
 * Specialists are intentionally injected by the app: this class does not pretend that
 * local agents can access network services or execute Android actions without permission.
 * A specialist returns null when it cannot handle the task.
 */
class AgentOrchestrator(
    private val specialists: List<Specialist>
) {
    data class Task(val input: String, val context: String = "")
    data class Result(val specialist: String, val output: String)

    interface Specialist {
        val name: String
        fun handle(task: Task): String?
    }

    fun execute(task: Task): Result? {
        if (task.input.isBlank()) return null
        for (specialist in specialists) {
            val output = runCatching { specialist.handle(task) }.getOrNull()
            if (!output.isNullOrBlank()) return Result(specialist.name, output.trim())
        }
        return null
    }

    companion object {
        /** Safe starter routing categories; execution remains in the existing app skill/API layer. */
        fun categoryFor(input: String): String {
            val text = input.lowercase()
            return when {
                listOf("remember", "note", "recall", "previously").any(text::contains) -> "memory"
                listOf("calculate", "plus", "minus", "multiply", "divide").any(text::contains) -> "utility"
                listOf("open", "launch", "settings", "timer", "alarm").any(text::contains) -> "device"
                listOf("search", "research", "latest", "news", "weather").any(text::contains) -> "research"
                else -> "general"
            }
        }
    }
}
