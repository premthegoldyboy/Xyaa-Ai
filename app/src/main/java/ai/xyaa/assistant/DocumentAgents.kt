package ai.xyaa.assistant

import java.net.URLEncoder

/** Task-specific agents used by the chat router. */
object PdfAgent {
    fun isRequest(prompt: String): Boolean =
        Regex("\\b(create|make|generate|export|save|convert|turn|prepare|want|need|give|provide|request|would\\s+like)\\b.*\\b(pdf|document)\\b|\\b(pdf|document)\\b.*\\b(create|make|generate|want|need|give|provide|request|save|convert)\\b|\\b(pdf|document)\\s+(?:of|about|on)\\b|\\b(?:as|into)\\s+(?:a\\s+)?pdf(?:\\s+format)?\\b|\\bpdf\\s+(?:please|format|version)\\b",
            RegexOption.IGNORE_CASE).containsMatchIn(prompt)

    fun needsResearch(prompt: String): Boolean =
        Regex("\\b(complete|full|detailed|comprehensive)\\s+(?:technical\\s+)?spe.{1,4}fic\\w*|\\bspe.{1,4}fic\\w*\\s+(?:of|for)\\b|\\b(price|availability|release date|dimensions|battery capacity|processor|camera specifications)\\b",
            RegexOption.IGNORE_CASE).containsMatchIn(prompt)

    fun contentPrompt(prompt: String): String = """
        Create polished, complete document content for the user's PDF request:
        "$prompt"
        Produce the actual requested document, not a description of a document or a generic template.
        Never output placeholder headings such as "Document Overview", "Purpose", "Format", "Key Sections",
        "Details", or "Conclusion" unless those sections contain specific, useful information about the
        requested subject. Include concrete facts, explanations, comparisons, practical details, and
        a useful conclusion appropriate to the topic. Never invent specifications, measurements,
        model numbers, compatibility claims, dates, prices, or source references. If the request asks
        for factual product specifications or current information, use the research workflow and cite
        verifiable sources rather than guessing. If a fact cannot be verified, label it clearly as unknown.
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
