package ai.xyaa.assistant

import java.net.URLEncoder

/** Task-specific agents used by the chat router. */
object PdfAgent {
    fun isRequest(prompt: String): Boolean =
        Regex("\\b(create|make|generate|export|prepare)\\b.*\\b(pdf|document)\\b|\\b(pdf|document)\\b.*\\b(create|make|generate)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(prompt)

    fun contentPrompt(prompt: String): String = """
        Create polished, complete document content for the user's PDF request:
        "$prompt"
        Produce the actual requested document, not a description of a document or a generic template.
        Never output placeholder headings such as "Document Overview", "Purpose", "Format", "Key Sections",
        "Details", or "Conclusion" unless those sections contain specific, useful information about the
        requested subject. Include concrete facts, explanations, comparisons, practical details, and
        a useful conclusion appropriate to the topic. If the request asks for research, use the research
        workflow and cite verifiable sources rather than guessing.
        Return only the complete document body in clean Markdown, with a specific title and meaningful
        subject-specific sections. Do not ask the user to choose a save location in the document.
    """.trimIndent()
}

object LinkAgent {
    fun matches(prompt: String): Boolean =
        Regex("\\b(generate|create|make|build)\\b.*\\b(link|url|links)\\b|\\b(link generator|make a link)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(prompt)

    fun generate(prompt: String): String {
        val query = prompt.replace(Regex("(?i)\\b(generate|create|make|build|a|an|me|some|link|links|url|urls|for|to)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim().ifBlank { prompt.trim() }
        val encoded = URLEncoder.encode(query, "UTF-8")
        return "## Links ready\n\n" +
            "**Google Search**\nhttps://www.google.com/search?q=" + encoded + "\n\n" +
            "**YouTube Search**\nhttps://www.youtube.com/results?search_query=" + encoded + "\n\n" +
            "**Google Maps**\nhttps://www.google.com/maps/search/?api=1&query=" + encoded + "\n\n" +
            "These are destination links, not hosted short links. XYAA has not published or shortened them."
    }
}
