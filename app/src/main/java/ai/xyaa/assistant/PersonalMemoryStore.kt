package ai.xyaa.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Durable, consent-based personal memory.
 *
 * This store is separate from ConversationMemory: only explicit "remember ..." requests
 * are written here. The encrypted payload is kept in app-private preferences and the
 * AES key is non-exportable in Android Keystore.
 */
class PersonalMemoryStore(context: Context) {
    data class Item(
        val id: String,
        val category: String,
        val content: String,
        val createdAt: Long,
        val updatedAt: Long
    )

    private val prefs = context.getSharedPreferences("xyaa_personal_memory_v1", Context.MODE_PRIVATE)
    private val lock = Any()
    private val keyAlias = "xyaa_personal_memory_v1_aes"
    private val maxItems = 200
    private val maxContentChars = 500

    fun isPaused(): Boolean = prefs.getBoolean("paused", false)

    fun setPaused(paused: Boolean) {
        prefs.edit().putBoolean("paused", paused).apply()
    }

    fun add(content: String, category: String = classify(content)): Item? = synchronized(lock) {
        val cleaned = content.trim().take(maxContentChars)
        if (cleaned.isBlank() || isPaused() || containsCredentialLikeData(cleaned)) return@synchronized null
        val items = readUnsafe().toMutableList()
        val duplicate = items.indexOfFirst { it.content.equals(cleaned, ignoreCase = true) }
        val now = System.currentTimeMillis()
        val item = if (duplicate >= 0) {
            items[duplicate].copy(category = category, updatedAt = now).also { items[duplicate] = it }
        } else {
            Item(java.util.UUID.randomUUID().toString(), category, cleaned, now, now).also { items.add(it) }
        }
        while (items.size > maxItems) items.removeAt(0)
        writeUnsafe(items)
        item
    }

    fun all(): List<Item> = synchronized(lock) { readUnsafe().sortedByDescending { it.updatedAt } }

    fun deleteMatching(query: String): Int = synchronized(lock) {
        val needle = query.trim()
        if (needle.isBlank()) return@synchronized 0
        val items = readUnsafe().toMutableList()
        val before = items.size
        items.removeAll { it.content.contains(needle, ignoreCase = true) }
        val removed = before - items.size
        if (removed > 0) writeUnsafe(items)
        removed
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val items = readUnsafe().toMutableList()
        val changed = items.removeAll { it.id == id }
        if (changed) writeUnsafe(items)
        changed
    }

    fun clear() = synchronized(lock) {
        prefs.edit().remove("payload").apply()
    }

    /** Relevant personal facts only; never includes the recent transcript. */
    fun contextForPrompt(query: String, limit: Int = 8): String = synchronized(lock) {
        if (isPaused()) return@synchronized ""
        val items = readUnsafe()
        if (items.isEmpty()) return@synchronized ""
        val terms = Regex("[a-z0-9]{3,}").findAll(query.lowercase()).map { it.value }.toSet()
        if (terms.isEmpty()) return@synchronized ""
        val ranked = items.map { item ->
            val haystack = (item.category + " " + item.content).lowercase()
            item to terms.count { haystack.contains(it) }
        }.sortedWith(compareByDescending<Pair<Item, Int>> { it.second }.thenByDescending { it.first.updatedAt })
        val selected = ranked.filter { it.second > 0 }.take(limit)
        selected.joinToString("\n") { "- [${it.first.category}] ${it.first.content}" }.take(2400)
    }

    private fun readUnsafe(): List<Item> {
        val packed = prefs.getString("payload", null) ?: return emptyList()
        return runCatching {
            val bytes = Base64.decode(packed, Base64.NO_WRAP)
            require(bytes.size > 12)
            val iv = bytes.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
            val json = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            (0 until json.length()).mapNotNull { index ->
                val obj = json.optJSONObject(index) ?: return@mapNotNull null
                val id = obj.optString("id")
                val category = obj.optString("category")
                val content = obj.optString("content")
                if (id.isBlank() || content.isBlank()) null else Item(
                    id, category.ifBlank { "general" }, content.take(maxContentChars),
                    obj.optLong("createdAt"), obj.optLong("updatedAt")
                )
            }.takeLast(maxItems)
        }.getOrDefault(emptyList())
    }

    private fun writeUnsafe(items: List<Item>) {
        val json = JSONArray()
        items.takeLast(maxItems).forEach { item ->
            json.put(JSONObject()
                .put("id", item.id)
                .put("category", item.category)
                .put("content", item.content)
                .put("createdAt", item.createdAt)
                .put("updatedAt", item.updatedAt))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("payload", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }

    private fun getKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun containsCredentialLikeData(value: String): Boolean {
        val lower = value.lowercase()
        return listOf("password is", "my password", "api key", "access token", "refresh token",
            "one-time password", "otp is", "secret key", "private key").any(lower::contains)
    }

    private fun classify(value: String): String {
        val lower = value.lowercase()
        return when {
            listOf("favourite", "favorite", "i love", "i like").any(lower::contains) -> "favourites"
            listOf("interested in", "interest", "hobby", "i enjoy").any(lower::contains) -> "interests"
            listOf("plan", "goal", "want to", "aim to", "deadline").any(lower::contains) -> "plans"
            listOf("prefer", "i'd rather", "i would rather").any(lower::contains) -> "preferences"
            else -> "general"
        }
    }
}
