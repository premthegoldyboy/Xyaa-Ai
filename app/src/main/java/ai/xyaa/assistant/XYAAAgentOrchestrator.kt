package ai.xyaa.assistant

import java.util.Locale

/** Specialist-agent front door. Routing is deterministic and adds no provider latency. */
enum class XYAAAgentKind { DOCUMENT, MANAGEMENT, LINKS, MEMORY, WEB_RESEARCH, DEVICE_SKILL, GENERAL_CHAT }

data class XYAAAgentPlan(
    val kind: XYAAAgentKind,
    val createPdf: Boolean = false,
    val needsResearch: Boolean = false,
    val selectedAgents: List<String> = emptyList()
)

/**
 * Selects only the agents needed for the user's intent. Mixed web-research + PDF requests
 * compose retrieval, source grounding, document writing and PDF export in that order.
 */
object XYAAAgentOrchestrator {
    fun plan(prompt: String, managementAgent: ManagementAgent): XYAAAgentPlan {
        val text = prompt.trim()
        val pdf = PdfAgent.isRequest(text)
        val explicitResearch = isExplicitResearchRequest(text)

        if (pdf) {
            val research = explicitResearch || PdfAgent.needsResearch(text)
            return XYAAAgentPlan(
                XYAAAgentKind.DOCUMENT, createPdf = true, needsResearch = research,
                selectedAgents = if (research)
                    listOf("intent-router", "web-researcher", "source-grounder", "document-writer", "pdf-exporter")
                else listOf("intent-router", "document-writer", "document-quality-check", "pdf-exporter")
            )
        }
        if (managementAgent.isManagementRequest(text))
            return XYAAAgentPlan(XYAAAgentKind.MANAGEMENT, selectedAgents = listOf("intent-router", "file-and-notes-manager"))
        if (LinkAgent.matches(text))
            return XYAAAgentPlan(XYAAAgentKind.LINKS, selectedAgents = listOf("intent-router", "link-builder"))
        if (ConversationMemoryAgent.matches(text))
            return XYAAAgentPlan(XYAAAgentKind.MEMORY, selectedAgents = listOf("intent-router", "conversation-memory"))
        if (explicitResearch)
            return XYAAAgentPlan(
                XYAAAgentKind.WEB_RESEARCH, needsResearch = true,
                selectedAgents = listOf("intent-router", "web-researcher", "source-grounder", "answer-synthesizer")
            )
        if (looksLikeDeviceAction(text.lowercase(Locale.ROOT)))
            return XYAAAgentPlan(XYAAAgentKind.DEVICE_SKILL, selectedAgents = listOf("intent-router", "android-skills"))
        return XYAAAgentPlan(
            XYAAAgentKind.GENERAL_CHAT,
            selectedAgents = listOf("intent-router", "conversation-memory", "response-writer")
        )
    }

    fun isExplicitResearchRequest(prompt: String): Boolean {
        val lower = prompt.trim().lowercase(Locale.ROOT)
        if (lower.isBlank()) return false
        if (Regex("^\\s*(research|investigate|compare|look\\s+up|google)\\b").containsMatchIn(lower)) return true
        if (Regex("\\b(search(?:\\s+the)?\\s+web|web\\s+search|search\\s+for|find\\s+out\\s+about|look\\s+up)\\b")
                .containsMatchIn(lower)) return true
        if (Regex("\\b(latest|current|currently|up[- ]to[- ]date|recent\\s+(?:news|updates)|today'?s\\s+(?:news|updates)|official\\s+sources|with\\s+sources|cite\\s+sources)\\b")
                .containsMatchIn(lower)) return true
        return Regex("^\\s*(?:make|write)\\s+(?:me\\s+)?a\\s+report\\b").containsMatchIn(lower)
    }

    private fun looksLikeDeviceAction(lower: String): Boolean =
        Regex("^\\s*(?:open|launch|start|set|turn\\s+on|turn\\s+off|call|phone|navigate|directions|play|translate|define|calculate|what\\s+time|what\\s+date|remind\\s+me)\\b")
            .containsMatchIn(lower) ||
        lower in setOf("time", "date", "current time", "today's date", "open settings", "open camera", "open calendar")
}

/** Retrieves a real prior turn; it never fabricates conversation history. */
object ConversationMemoryAgent {
    fun matches(prompt: String): Boolean =
        Regex("\\b(what did i (?:say|ask|tell you)|do you remember|remember when|what was (?:the|that|my)|recall (?:our|the|my)|earlier i asked|previous conversation)\\b",
            RegexOption.IGNORE_CASE).containsMatchIn(prompt)

    fun execute(prompt: String, memory: ConversationMemory): String? {
        val turns = memory.recentTurns().dropLast(1)
        if (turns.isEmpty()) return "I don't have an earlier conversation to recall yet."
        val lower = prompt.lowercase(Locale.ROOT)
        val broadRecall = Regex("\\b(what did i (?:say|ask|tell you)|previous conversation|earlier i asked|do you remember)\\b")
            .containsMatchIn(lower)
        val ignored = setOf("what", "when", "where", "which", "that", "this", "with", "from", "about", "have", "were", "your", "tell", "said", "asked", "remember", "earlier", "previous", "conversation", "xyaa")
        val terms = Regex("[a-z0-9]{4,}").findAll(lower).map { it.value }.filterNot { it in ignored }.toSet()
        val relevant = if (terms.isEmpty()) null else turns.asReversed().firstOrNull { turn ->
            terms.any { turn.text.lowercase(Locale.ROOT).contains(it) }
        }
        val selected = relevant ?: if (broadRecall) turns.lastOrNull { it.role == "user" } else null
            ?: return "I couldn't find that detail in the recent conversation I have available."
        val role = if (selected.role == "user") "You previously said" else "XYAA previously replied"
        return "$role: ${selected.text}"
    }
}
