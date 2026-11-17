package ai.xyaa.assistant

/**
 * Keeps provider protocol and Markdown syntax out of the user-facing transcript.
 * This is deliberately a presentation formatter, not a content generator.
 */
internal object AssistantTextFormatter {
    private val spokenLine = Regex("(?im)^\\s*\\*{0,2}SPOKEN:\\*{0,2}\\s*(.+)$")
    private val hiddenBlocks = Regex("(?is)<(think|analysis|reasoning|tool_call|function_call)>.*?</\\1>")
    private val markdownLink = Regex("\\[([^\\]]+)]\\((https?://[^)]+)\\)")

    fun forDisplay(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(hiddenBlocks, "")
            .replace(Regex("(?i)\\*{0,2}SPOKEN:\\*{0,2}\\s*"), "")
            .replace(Regex("(?im)^\\s*(?:internal|background|debug|tool)\\s*(?:status|note)?\\s*:\\s*.*$"), "")
            .replace(Regex("(?im)^\\s*(?:assistant|system|developer)\\s*:\\s*"), "")
            .replace(Regex("(?m)^\\s*\\x60{3,}[^\\n]*$"), "")
            .replace(Regex("(?m)^\\s*---+\\s*$"), "")
            .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
            .replace(markdownLink, "$1 ($2)")
            .replace(Regex("\\*{1,3}([^*\\n]+)\\*{1,3}"), "$1")
            .replace(Regex("\\x60([^\\x60]+)\\x60"), "$1")
            .replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
            .replace(Regex("(?m)^\\s*(\\d+)\\.\\s+"), "$1. ")
            .replace(Regex("(?m)^\\s*\\|\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$"), "")
            .replace(Regex("\\s*\\|\\s*"), " · ")
            .replace(Regex("(?m)^\\s*\\|\\s*"), "")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** A compact, faithful memory entry; spoken summaries take priority for long reports. */
    fun forMemory(raw: String): String {
        if (raw.isBlank()) return ""
        val spoken = spokenLine.find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val source = if (spoken.isNotBlank()) spoken else forDisplay(raw).substringBefore("\n\n")
        return compact(source, 520)
    }

    /** Short card preview; the full cleaned entry remains available by expanding the card. */
    fun memoryPreview(raw: String): String = compact(forDisplay(raw), 260)

    private fun compact(value: String, limit: Int): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        if (normalized.length <= limit) return normalized
        val boundary = normalized.lastIndexOf(' ', limit - 1).takeIf { it >= limit / 2 } ?: limit - 1
        return normalized.substring(0, boundary).trimEnd() + "…"
    }
}
