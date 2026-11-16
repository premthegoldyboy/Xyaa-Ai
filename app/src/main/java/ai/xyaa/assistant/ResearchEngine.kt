package ai.xyaa.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Research workflow adapted from the supplied Nova prototype:
 * Gemini Google Search grounding first, Tavily retrieval as fallback, then a cited report.
 */
class ResearchEngine(
    private val geminiKey: String?,
    private val tavilyKey: String?,
    private val groqKey: String?,
    private val deepSeekKey: String?
) {
    data class Source(val title: String, val url: String, val snippet: String = "")
    data class Report(val topic: String, val spoken: String, val markdown: String, val sources: List<Source>, val provider: String) {
        fun asText(): String = buildString {
            append(markdown.trim())
            if (sources.isNotEmpty()) {
                append("\n\n## Sources\n")
                sources.forEachIndexed { index, source ->
                    append("[${index + 1}] ${source.title} — ${source.url}\n")
                }
            }
        }
    }

    fun research(topic: String, onStatus: (String) -> Unit = {}): Report {
        require(topic.isNotBlank()) { "Enter a research topic." }
        if (!geminiKey.isNullOrBlank()) {
            try {
                onStatus("Researching with Gemini Search…")
                return researchWithGemini(topic)
            } catch (_: Throwable) {
                // A configured Tavily key enables an independent search fallback.
            }
        }
        if (!tavilyKey.isNullOrBlank()) {
            onStatus("Switching to backup web search…")
            return researchWithTavily(topic, onStatus)
        }
        throw IllegalStateException("Research needs a Gemini API key or a Tavily search key.")
    }

    private fun researchWithGemini(topic: String): Report {
        val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", geminiKey!!.trim())
        }
        try {
            val prompt = """Today is ${java.time.LocalDate.now()}. Research this topic using Google Search grounding: "$topic".
Write a balanced, useful report in the user's language. Include a concise two-sentence spoken summary first, on a line beginning SPOKEN:.
Then write Markdown with a title, TL;DR, key findings, details, and caveats. Distinguish verified facts from uncertainty. Do not invent figures, dates, quotes, or sources. Cite source markers like [1] where appropriate."""
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Gemini research HTTP $status")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw IllegalStateException("Gemini returned no research result.")
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val raw = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }
                .filter { it.isNotBlank() }.joinToString("\n")
            if (raw.isBlank()) throw IllegalStateException("Gemini returned an empty report.")
            val chunks = candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks") ?: JSONArray()
            val sources = mutableListOf<Source>()
            val seen = mutableSetOf<String>()
            for (i in 0 until chunks.length()) {
                val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                val uri = web.optString("uri")
                if (uri.startsWith("https://") || uri.startsWith("http://")) {
                    if (seen.add(uri)) sources.add(Source(web.optString("title").ifBlank { uri }, uri))
                }
            }
            val withCitations = applyGroundingCitations(raw, candidate, sources)
            val split = splitSpoken(withCitations)
            return Report(topic, split.first, split.second, sources.take(12), "Gemini Search")
        } finally {
            connection.disconnect()
        }
    }

    private fun applyGroundingCitations(text: String, candidate: JSONObject, sources: List<Source>): String {
        val metadata = candidate.optJSONObject("groundingMetadata") ?: return text
        val supports = metadata.optJSONArray("groundingSupports") ?: return text
        val insertions = mutableListOf<Pair<Int, String>>()
        for (i in 0 until supports.length()) {
            val support = supports.optJSONObject(i) ?: continue
            val segment = support.optJSONObject("segment") ?: continue
            val end = segment.optInt("endIndex", -1)
            val indices = support.optJSONArray("groundingChunkIndices") ?: continue
            val markers = (0 until indices.length()).mapNotNull { indices.optInt(it, -1).takeIf { idx -> idx >= 0 && idx < sources.size } }
                .distinct().joinToString("") { " [${it + 1}]" }
            if (end in 1..text.length && markers.isNotBlank()) insertions.add(end to markers)
        }
        var result = text
        insertions.sortedByDescending { it.first }.forEach { (index, marker) ->
            result = result.substring(0, index) + marker + result.substring(index)
        }
        return result
    }

    private fun researchWithTavily(topic: String, onStatus: (String) -> Unit): Report {
        val connection = (URL("https://api.tavily.com/search").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7000
            readTimeout = 25000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        val sources: List<Source>
        try {
            val body = JSONObject()
                .put("api_key", tavilyKey!!.trim())
                .put("query", topic)
                .put("search_depth", "advanced")
                .put("max_results", 8)
                .put("include_answer", false)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Tavily HTTP $status")
            val results = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("results") ?: JSONArray()
            sources = (0 until results.length()).mapNotNull { index ->
                val item = results.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url")
                if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
                Source(item.optString("title").ifBlank { url }, url, item.optString("content").take(1100))
            }
            if (sources.isEmpty()) throw IllegalStateException("Tavily returned no usable sources.")
        } finally {
            connection.disconnect()
        }
        onStatus("Writing a source-grounded report…")
        val context = sources.mapIndexed { index, source ->
            "[${index + 1}] ${source.title}\nURL: ${source.url}\n${source.snippet}"
        }.joinToString("\n\n")
        val prompt = """Research topic: "$topic"
Use ONLY the source extracts below. Cite factual claims with the matching [number]. If the sources do not establish a claim, say so. Never invent facts or citations.
Write a two-sentence spoken summary on the first line beginning SPOKEN:.
Then provide Markdown sections: title, TL;DR, key findings, details, caveats.

$context"""
        val fallback = ProviderFallbackClient(groqKey, deepSeekKey)
        val report = if (!geminiKey.isNullOrBlank()) {
            runCatching { writeWithGemini(prompt) }.getOrElse {
                fallback.complete(prompt) { onStatus("Writing report with $it…") }
            }
        } else {
            fallback.complete(prompt) { onStatus("Writing report with $it…") }
        }
        val split = splitSpoken(report)
        return Report(topic, split.first, split.second, sources, "Tavily + AI")
    }

    private fun writeWithGemini(prompt: String): String {
        val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7000
            readTimeout = 35000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", geminiKey!!.trim())
        }
        try {
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Gemini report writer HTTP $status")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            return (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }
                .filter { it.isNotBlank() }.joinToString("\n")
                .ifBlank { throw IllegalStateException("Gemini returned an empty report.") }
        } finally {
            connection.disconnect()
        }
    }

    private fun splitSpoken(raw: String): Pair<String, String> {
        val match = Regex("^\\s*SPOKEN:\\s*(.+?)(?:\\r?\\n)+", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(raw)
        return if (match != null) match.groupValues[1].trim() to raw.substring(match.range.last + 1).trim()
        else "I've finished researching $topicPlaceholder.".let { it to raw.trim() }
    }

    companion object {
        private const val topicPlaceholder = "the topic"
    }
}
