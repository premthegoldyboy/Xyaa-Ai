package ai.xyaa.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
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
        val existing = records.indexOfFirst { it.optString("uri") == uri.toString() }
        val item = JSONObject()
            .put("uri", uri.toString())
            .put("name", name)
            .put("savedAt", System.currentTimeMillis())
        if (existing >= 0) records.put(existing, item) else records.put(item)
        prefs.edit().putString(KEY_PDFS, records.toString()).apply()
    }

    private fun handleNotes(text: String, lower: String): String {
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
        return "I can save, list, search, edit, and delete notes. Try “save a note: …” or “find in my notes …”."
    }

    private fun handlePdf(text: String, lower: String): String {
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

        return "I can list, search, open, rename, and delete PDFs saved through XYAA. Try “list my PDFs”."
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
