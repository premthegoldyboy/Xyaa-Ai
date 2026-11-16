package ai.xyaa.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.DocumentsContract.Document
import java.util.ArrayDeque
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * XYAA's local file-and-notes management agent.
 *
 * It translates natural-language management requests into explicit, local operations.
 * Destructive actions are limited to an exact matched item; ambiguous requests are rejected.
 * PDF contents remain in Android's document provider, while a small index stores granted URIs.
 */
class ManagementAgent(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("xyaa_management", Context.MODE_PRIVATE)

    fun execute(raw: String): String? {
        val text = raw.trim()
        if (text.isBlank()) return null
        val lower = text.lowercase(Locale.ROOT)

        if (isPdfIntent(lower)) return handlePdf(text, lower)
        if (isNoteIntent(lower)) return handleNotes(text, lower)
        return null
    }

    /** Called only after the user has chosen a destination and PDF bytes were written successfully. */
    fun registerPdf(uri: Uri, fallbackName: String = "XYAA-Document.pdf") {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val name = queryName(uri) ?: fallbackName
        val records = readPdfs()
        upsertPdf(uri, name)
    }

    private fun upsertPdf(uri: Uri, name: String) {
        val records = readPdfs()
        var existing = -1
        for (i in 0 until records.length()) {
            if (records.getJSONObject(i).optString("uri") == uri.toString()) { existing = i; break }
        }
        val item = JSONObject().put("uri", uri.toString()).put("name", name)
            .put("savedAt", System.currentTimeMillis())
        if (existing >= 0) records.put(existing, item) else records.put(item)
        writePdfs(records)
    }

    /** Index PDFs in a user-selected folder tree using Android's scoped document access. */
    fun importPdfFolder(treeUri: Uri): Int {
        app.contentResolver.takePersistableUriPermission(
            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.add(rootId to 0)
        val visited = HashSet<String>()
        var imported = 0
        while (pending.isNotEmpty() && visited.size < 1500) {
            val (parentId, depth) = pending.removeFirst()
            if (!visited.add(parentId) || depth > 8) continue
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
            app.contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol).orEmpty()
                    val mime = cursor.getString(mimeCol).orEmpty()
                    if (mime == Document.MIME_TYPE_DIR) {
                        if (depth < 8) pending.add(id to depth + 1)
                    } else if (mime.equals("application/pdf", true) || name.endsWith(".pdf", true)) {
                        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        upsertPdf(documentUri, name.ifBlank { "Document.pdf" })
                        imported++
                    }
                }
            }
        }
        return imported
    }

    private fun handleNotes(text: String, lower: String): String? {
        val notes = readNotes()
        val save = Regex("(?i)^(?:please\\s+)?(?:remember|save|add|create|take)\\s+(?:a\\s+)?note(?:\\s+(?:that|saying))?\\s*[:,-]?\\s*(.+)$").find(text)
        if (save != null) {
            val body = save.groupValues[1].trim().take(2000)
            if (body.isBlank()) return "What should I put in the note?"
            val item = JSONObject().put("id", System.currentTimeMillis().toString())
                .put("text", body).put("updatedAt", System.currentTimeMillis())
            notes.put(item)
            while (notes.length() > 200) notes.remove(0)
            writeNotes(notes)
            return "Saved note #${notes.length()}: $body"
        }

        if (Regex("(?i)^(?:show|list|view|read)(?:\\s+all)?\\s+(?:my\\s+)?notes$|^(?:my\\s+notes|notes)$").matches(text)) {
            if (notes.length() == 0) return "Your notes are empty."
            return "Your saved notes ( ${notes.length()} ):\\n" + (0 until notes.length()).joinToString("\\n") { i ->
                "${i + 1}. ${notes.getJSONObject(i).optString("text")}"
            }
        }

        val search = Regex("(?i)^(?:search|find|look for)\\s+(?:in\\s+)?(?:my\\s+)?notes(?:\\s+for)?\\s+(.+)$").find(text)
        if (search != null) {
            val query = search.groupValues[1].trim()
            val found = (0 until notes.length()).map { notes.getJSONObject(it).optString("text") }
                .filter { it.contains(query, true) }
            return if (found.isEmpty()) "I couldn't find a note matching “$query”." else
                "Notes matching “$query”:\\n" + found.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\\n")
        }

        val deleteIndex = Regex("(?i)^(?:delete|remove)\\s+note\\s+#?(\\d+)$").find(text)
        if (deleteIndex != null) {
            val index = deleteIndex.groupValues[1].toIntOrNull()?.minus(1) ?: -1
            if (index !in 0 until notes.length()) return "I couldn't find that note number."
            val removed = notes.getJSONObject(index).optString("text")
            notes.remove(index); writeNotes(notes)
            return "Deleted note: $removed"
        }

        val deleteMatch = Regex("(?i)^(?:delete|remove)\\s+(?:the\\s+)?note(?:\\s+about)?\\s+(.+)$").find(text)
        if (deleteMatch != null) {
            val query = deleteMatch.groupValues[1].trim()
            val matches = (0 until notes.length()).filter { notes.getJSONObject(it).optString("text").contains(query, true) }
            if (matches.isEmpty()) return "I couldn't find a note matching “$query”."
            if (matches.size > 1) return "I found ${matches.size} matching notes. Please specify the note number so I don't delete the wrong one."
            val i = matches.single(); val removed = notes.getJSONObject(i).optString("text")
            notes.remove(i); writeNotes(notes)
            return "Deleted note: $removed"
        }

        val edit = Regex("(?i)^(?:edit|update|replace)\\s+note\\s+#?(\\d+)\\s+(?:to|with)\\s+(.+)$").find(text)
        if (edit != null) {
            val i = edit.groupValues[1].toIntOrNull()?.minus(1) ?: -1
            if (i !in 0 until notes.length()) return "I couldn't find that note number."
            val replacement = edit.groupValues[2].trim().take(2000)
            if (replacement.isBlank()) return "The replacement note can't be empty."
            val item = notes.getJSONObject(i).put("text", replacement).put("updatedAt", System.currentTimeMillis())
            notes.put(i, item); writeNotes(notes)
            return "Updated note #${i + 1}: $replacement"
        }

        if (Regex("(?i)^(?:clear|delete|remove)\\s+(?:all\\s+)?(?:my\\s+)?notes$").matches(text)) {
            prefs.edit().remove(KEY_NOTES).apply()
            return "All saved notes have been cleared."
        }
        return null
    }

    private fun handlePdf(text: String, lower: String): String? {
        val records = readPdfs()
        if (Regex("(?i)^(?:show|list|view)(?:\\s+all)?\\s+(?:my\\s+)?pdfs$|^(?:my\\s+pdfs|pdf library|show my documents)$").matches(text)) {
            if (records.length() == 0) return "Your XYAA PDF library is empty. PDFs you create and save through XYAA will appear here."
            return "Your saved PDFs:\\n" + (0 until records.length()).joinToString("\\n") { i ->
                "${i + 1}. ${records.getJSONObject(i).optString("name")}"
            }
        }

        val search = Regex("(?i)^(?:search|find|look for)\\s+(?:in\\s+)?(?:my\\s+)?(?:pdfs|pdf files|documents)(?:\\s+for)?\\s+(.+)$").find(text)
        if (search != null) {
            val query = search.groupValues[1].trim()
            val matches = (0 until records.length()).map { records.getJSONObject(it) }
                .filter { it.optString("name").contains(query, true) }
            return if (matches.isEmpty()) "I couldn't find a saved PDF matching “$query”." else
                "Matching PDFs:\\n" + matches.mapIndexed { i, item -> "${i + 1}. ${item.optString("name")}" }.joinToString("\\n")
        }

        val delete = Regex("(?i)^(?:delete|remove)\\s+(?:the\\s+)?pdf\\s+(.+)$").find(text)
        if (delete != null) return deletePdf(delete.groupValues[1].trim(), records)

        val open = Regex("(?i)^(?:open|read|view)\\s+(?:the\\s+)?pdf\\s+(.+)$").find(text)
        if (open != null) return openPdf(open.groupValues[1].trim(), records)

        val rename = Regex("(?i)^rename\\s+(?:the\\s+)?pdf\\s+(.+?)\\s+to\\s+(.+)$").find(text)
        if (rename != null) return renamePdf(rename.groupValues[1].trim(), rename.groupValues[2].trim(), records)

        return null
    }

    private fun deletePdf(query: String, records: JSONArray): String {
        val matches = matchingPdfIndexes(query, records)
        if (matches.isEmpty()) return "I couldn't find a saved PDF matching “$query”."
        if (matches.size > 1) return "I found multiple matching PDFs. Please give the exact filename."
        val index = matches.single()
        val item = records.getJSONObject(index)
        val uri = Uri.parse(item.optString("uri"))
        return runCatching {
            val deleted = runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
                .getOrElse { app.contentResolver.delete(uri, null, null) > 0 }
            if (!deleted) error("The document provider refused the delete request.")
            val name = item.optString("name")
            records.remove(index); writePdfs(records)
            "Deleted $name."
        }.getOrElse { "I couldn't delete that PDF: ${it.message ?: "document provider error"}" }
    }

    private fun openPdf(query: String, records: JSONArray): String {
        val matches = matchingPdfIndexes(query, records)
        if (matches.isEmpty()) return "I couldn't find a saved PDF matching “$query”."
        if (matches.size > 1) return "I found multiple matching PDFs. Please give the exact filename."
        val item = records.getJSONObject(matches.single())
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.optString("uri")), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            if (intent.resolveActivity(app.packageManager) == null) "No PDF viewer is available on this device."
            else { app.startActivity(intent); "Opening ${item.optString("name")}." }
        }.getOrElse { "I couldn't open that PDF: ${it.message ?: "viewer unavailable"}" }
    }

    private fun renamePdf(oldName: String, newNameRaw: String, records: JSONArray): String {
        val matches = matchingPdfIndexes(oldName, records)
        if (matches.isEmpty()) return "I couldn't find a saved PDF matching “$oldName”."
        if (matches.size > 1) return "I found multiple matching PDFs. Please give the exact filename."
        val index = matches.single()
        val newName = newNameRaw.trim().take(120).let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        if (newName == ".pdf" || newName.contains('/') || newName.contains('\\')) return "That filename isn't valid."
        val item = records.getJSONObject(index)
        return runCatching {
            val renamed = DocumentsContract.renameDocument(app.contentResolver, Uri.parse(item.optString("uri")), newName)
                ?: error("The document provider doesn't support renaming this file.")
            item.put("uri", renamed.toString()).put("name", newName)
            records.put(index, item); writePdfs(records)
            "Renamed PDF to $newName."
        }.getOrElse { "I couldn't rename that PDF: ${it.message ?: "document provider error"}" }
    }

    private fun matchingPdfIndexes(query: String, records: JSONArray): List<Int> =
        (0 until records.length()).filter { i -> records.getJSONObject(i).optString("name").equals(query, true) ||
            records.getJSONObject(i).optString("name").contains(query, true) }

    private fun isPdfIntent(lower: String): Boolean =
        Regex("\\b(pdf|pdfs|document library|documents)\\b").containsMatchIn(lower) &&
            Regex("\\b(list|show|find|search|open|read|view|delete|remove|rename|manage|my)\\b").containsMatchIn(lower)

    private fun isNoteIntent(lower: String): Boolean =
        Regex("\\b(note|notes)\\b").containsMatchIn(lower) &&
            Regex("\\b(save|remember|add|create|take|show|list|view|read|search|find|look|delete|remove|clear|edit|update|replace|manage|my)\\b").containsMatchIn(lower)

    fun isManagementRequest(prompt: String): Boolean {
        val lower = prompt.lowercase(Locale.ROOT)
        val target = Regex("\\b(notes?|pdfs?|documents?)\\b").containsMatchIn(lower)
        val action = Regex("\\b(save|remember|add|create|take|show|list|view|read|search|find|look|delete|remove|clear|edit|update|replace|rename|open|manage|organize|summari[sz]e)\\b")
            .containsMatchIn(lower)
        return target && action
    }

    fun plannerPrompt(userRequest: String): String = """
        You are XYAA's file-and-notes management planner. Convert the user's request into exactly one JSON object.
        User request: "$userRequest"
        Allowed actions:
        save_note {text}; list_notes {}; search_notes {query}; edit_note {number, text};
        delete_note {number or query}; clear_notes {};
        list_pdfs {}; search_pdfs {query}; open_pdf {query}; rename_pdf {query, new_name};
        delete_pdf {query}; clarify {question}.
        Never invent file names, note numbers, or contents. If the request is ambiguous, use clarify.
        Do not execute anything. Output only JSON with keys action, text, query, number, new_name, question as needed.
    """.trimIndent()

    fun executePlan(rawPlan: String, originalRequest: String): String? {
        val jsonText = rawPlan.substringAfter('{', "").let { "{" + it.substringBeforeLast('}', "") + "}" }
        val plan = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val action = plan.optString("action").lowercase(Locale.ROOT)
        val query = plan.optString("query").trim()
        val text = plan.optString("text").trim()
        val number = plan.optInt("number", -1)
        val original = originalRequest.lowercase(Locale.ROOT)
        return when (action) {
            "save_note" -> if (text.isNotBlank()) execute("save note: $text") else "What should I write in the note?"
            "list_notes" -> execute("show my notes")
            "search_notes" -> if (query.isNotBlank()) execute("search notes for $query") else "What should I search for?"
            "edit_note" -> if (number > 0 && text.isNotBlank()) execute("edit note #$number to $text") else "Tell me which note number to edit and its replacement."
            "delete_note" -> {
                if (!Regex("\\b(delete|remove)\\b").containsMatchIn(original)) "I won't delete a note unless you explicitly ask me to."
                else if (number > 0) execute("delete note #$number")
                else if (query.isNotBlank()) execute("delete note about $query")
                else "Which note should I delete?"
            }
            "clear_notes" -> if (Regex("\\b(clear|delete|remove)\\s+(all|every)\\b").containsMatchIn(original)) execute("clear my notes")
                else "Please explicitly say “clear all my notes” before I remove every note."
            "list_pdfs" -> execute("list my PDFs")
            "search_pdfs" -> if (query.isNotBlank()) execute("search PDFs for $query") else "What should I search for?"
            "open_pdf" -> if (query.isNotBlank()) execute("open PDF $query") else "Which PDF should I open?"
            "rename_pdf" -> if (query.isNotBlank() && plan.optString("new_name").isNotBlank()) execute("rename PDF $query to ${plan.optString("new_name")}") else "Tell me the PDF and its new filename."
            "delete_pdf" -> if (Regex("\\b(delete|remove)\\b").containsMatchIn(original) && query.isNotBlank()) execute("delete PDF $query")
                else "I need an explicit delete request and an identifiable PDF filename."
            "clarify" -> plan.optString("question").ifBlank { "Which note or PDF do you mean?" }
            else -> null
        }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun readNotes(): JSONArray = runCatching { JSONArray(prefs.getString(KEY_NOTES, "[]")) }.getOrDefault(JSONArray())
    private fun writeNotes(items: JSONArray) { prefs.edit().putString(KEY_NOTES, items.toString()).apply() }
    private fun readPdfs(): JSONArray = runCatching { JSONArray(prefs.getString(KEY_PDFS, "[]")) }.getOrDefault(JSONArray())
    private fun writePdfs(items: JSONArray) { prefs.edit().putString(KEY_PDFS, items.toString()).apply() }

    companion object {
        private const val KEY_NOTES = "notes_v2"
        private const val KEY_PDFS = "pdf_library_v1"
    }
}
