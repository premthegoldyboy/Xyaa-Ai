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

    // ---- Speech ----------------------------------------------------------------------------------
    // One cleaned transcript feeds Kokoro and Android TTS. It is derived token-by-token from the exact
    // text shown in chat, and every spoken token remembers where it ends in the displayed text, so the
    // progressive reveal can follow the audio without ever showing text the user did not hear.

    private val urlToken = Regex("(?i)^[\\p{Ps}\"'“‘]*(?:https?://|www\\.)\\S*")
    private val trailingPunct = Regex("[.,;:!?…]+$")
    private val noiseChars = Regex("[*~`#<>\\[\\]{}\\\\^|•·▪◦‣●○■□▶►]")
    private val emojiChars = Regex("[\\p{So}\\p{Cs}\\u200D\\uFE0F\\u20E3]")
    private val arrowTokens = Regex("(?:->|=>|→|⇒)")
    private val listMarker = Regex("^\\d{1,3}[.)]$")
    private val brand = Regex("(?i)\\bXYAA\\b")
    private val sentenceEnd = Regex("[.!?…:;,][\\p{Pe}\"'”’]*$")

    /** Cleaned, speakable transcript plus the mapping back to the displayed answer. */
    class SpeechPlan internal constructor(
        val display: String,
        val spoken: String,
        private val spokenEnds: IntArray,
        private val displayEnds: IntArray
    ) {
        val wordCount: Int get() = spokenEnds.size

        /** Displayed text up to and including the word being spoken at [spokenChars] of [spoken]. */
        fun displayPrefix(spokenChars: Int): String {
            if (spokenEnds.isEmpty() || spokenChars <= 0) return ""
            if (spokenChars >= spoken.length) return display
            var lo = 0
            var hi = spokenEnds.size - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (spokenEnds[mid] >= spokenChars) hi = mid else lo = mid + 1
            }
            return display.substring(0, displayEnds[lo].coerceIn(0, display.length))
        }

        /** Displayed text after [words] spoken words (used when an engine reports no ranges). */
        fun displayAfterWords(words: Int): String =
            if (words <= 0) "" else if (words >= spokenEnds.size) display
            else display.substring(0, displayEnds[words - 1].coerceIn(0, display.length))

        /** How many spoken words end at or before [spokenChars] (used to resume after a partial neural reply). */
        fun wordsThrough(spokenChars: Int): Int = spokenEnds.count { it <= spokenChars }

        val isSilent: Boolean get() = spoken.isBlank()
    }

    fun forSpeech(raw: String): SpeechPlan {
        val display = forDisplay(raw)
        if (display.isBlank()) return SpeechPlan(display, "", IntArray(0), IntArray(0))
        val matches = Regex("\\S+").findAll(display).toList()
        val words = ArrayList<String>()
        val displayEnds = ArrayList<Int>()
        var pendingBreak = false

        fun endsSentence(w: String) = sentenceEnd.containsMatchIn(w)
        fun softenPrevious(mark: String) {
            if (words.isNotEmpty() && !endsSentence(words.last())) words[words.size - 1] = words.last() + mark
        }
        fun lineBreakAfter(i: Int): Boolean {
            if (i + 1 >= matches.size) return false
            return display.substring(matches[i].range.last + 1, matches[i + 1].range.first).contains('\n')
        }

        var i = 0
        while (i < matches.size) {
            val raw0 = matches[i].value
            val atLineStart = i == 0 || display.substring(matches[i - 1].range.last + 1, matches[i].range.first).contains('\n')
            var consumed = 1
            var out: String

            // "X Y A A" written with spaces is still the assistant's name.
            val letters = StringBuilder()
            var look = 0
            while (look < 4 && i + look < matches.size) {
                val t = matches[i + look].value.replace(Regex("[^\\p{L}]"), "")
                if (t.length != 1) break
                letters.append(t); look++
            }
            if (look == 4 && letters.toString().equals("XYAA", ignoreCase = true)) {
                out = "Zyaa" + trailingPunct.find(matches[i + 3].value)?.value.orEmpty()
                consumed = 4
            } else if (urlToken.containsMatchIn(raw0)) {
                // Markdown links are displayed as "text (https://…)". The text is spoken, the address never is.
                val opensParen = raw0.startsWith("(")
                out = if (opensParen) "" else "link" + trailingPunct.find(raw0)?.value.orEmpty()
            } else if (atLineStart && listMarker.matches(raw0)) {
                out = ""
            } else {
                out = raw0
                    .replace(emojiChars, "")
                    .replace(arrowTokens, " to ")
                    .replace(Regex("\\[\\d+]"), "")
                    .replace(noiseChars, "")
                    .replace(Regex("(?<=\\p{L})_(?=\\p{L})"), " ")
                    .replace("_", "")
                    .replace("&", " and ")
                    .replace(brand, "Zyaa")
                    .trim()
                if (out == "+") out = "plus"
                if (out == "=") out = "equals"
                if (out in setOf("-", "–", "—", "/", "\\", "~")) { softenPrevious(","); out = "" }
                if (out.none { it.isLetterOrDigit() }) {
                    if (out.isEmpty() || out.all { !it.isLetterOrDigit() }) {
                        trailingPunct.find(raw0)?.value?.take(1)?.let { softenPrevious(it) }
                    }
                    out = ""
                }
            }

            val last = i + consumed - 1
            if (out.isNotEmpty()) {
                if (pendingBreak) { softenPrevious("."); pendingBreak = false }
                words.add(out)
                displayEnds.add(matches[last].range.last + 1)
            }
            if (lineBreakAfter(last)) {
                if (out.isNotEmpty()) { if (!endsSentence(out)) words[words.size - 1] = out + "." }
                else pendingBreak = true
            }
            i += consumed
        }
        if (pendingBreak) softenPrevious(".")

        val ends = IntArray(words.size)
        var cursor = 0
        val spoken = StringBuilder()
        words.forEachIndexed { index, w ->
            if (index > 0) { spoken.append(' '); cursor++ }
            spoken.append(w); cursor += w.length
            ends[index] = cursor
        }
        return SpeechPlan(display, spoken.toString(), ends, displayEnds.toIntArray())
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
