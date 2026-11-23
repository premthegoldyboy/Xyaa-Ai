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
 * Encrypted, bounded recent conversation history. This is distinct from PersonalMemoryStore:
 * it preserves recent turns for cross-chat recall, while durable personal facts require an
 * explicit remember command. Legacy plaintext turns are migrated on first read.
 */
class ConversationMemory(context: Context) {
    private val prefs = context.getSharedPreferences("xyaa_conversation_memory", Context.MODE_PRIVATE)
    private val lock = Any()
    private val maxTurns = 120
    private val maxCharsPerMessage = 1800
    private val keyAlias = "xyaa_conversation_memory_aes"

    data class Turn(val role: String, val text: String, val timestamp: Long)

    fun remember(role: String, text: String) = synchronized(lock) {
        if (role !in setOf("user", "assistant") || text.isBlank() || containsCredentialLikeData(text)) return@synchronized
        val turns = readUnsafe().toMutableList()
        turns.add(Turn(role, text.take(maxCharsPerMessage), System.currentTimeMillis()))
        while (turns.size > maxTurns) turns.removeAt(0)
        writeUnsafe(turns)
    }

    /** Returns compact recent context; callers must treat it as untrusted user-provided data. */
    fun contextForPrompt(): String = synchronized(lock) {
        val turns = readUnsafe().takeLast(12)
        if (turns.isEmpty()) return@synchronized ""
        turns.joinToString("\n") { "${it.role}: ${it.text}" }.take(9000)
    }

    fun clear() = synchronized(lock) { prefs.edit().remove("turns").remove("payload").apply() }

    fun recentTurns(): List<Turn> = synchronized(lock) { readUnsafe().toList() }

    private fun readUnsafe(): List<Turn> {
        val encrypted = prefs.getString("payload", null)
        if (!encrypted.isNullOrBlank()) {
            return runCatching {
                val packed = Base64.decode(encrypted, Base64.NO_WRAP)
                require(packed.size > 12)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
                parseTurns(String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8))
            }.getOrDefault(emptyList())
        }

        // One-time migration from the previous plaintext SharedPreferences format.
        val legacy = prefs.getString("turns", null) ?: return emptyList()
        val migrated = runCatching { parseTurns(legacy) }.getOrDefault(emptyList()).takeLast(maxTurns)
        if (migrated.isNotEmpty()) writeUnsafe(migrated)
        else prefs.edit().remove("turns").apply()
        return migrated
    }

    private fun parseTurns(raw: String): List<Turn> {
        val json = JSONArray(raw)
        return (0 until json.length()).mapNotNull { index ->
            val item = json.optJSONObject(index) ?: return@mapNotNull null
            val role = item.optString("role")
            val text = item.optString("text")
            if (role !in setOf("user", "assistant") || text.isBlank() || containsCredentialLikeData(text)) null
            else Turn(role, text.take(maxCharsPerMessage), item.optLong("timestamp"))
        }.takeLast(maxTurns)
    }

    private fun writeUnsafe(turns: List<Turn>) {
        val json = JSONArray()
        turns.takeLast(maxTurns).forEach { turn ->
            json.put(JSONObject().put("role", turn.role).put("text", turn.text).put("timestamp", turn.timestamp))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("payload", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP))
            .remove("turns")
            .apply()
    }

    /** Never persist likely credentials in the recent transcript, even though it is encrypted. */
    private fun containsCredentialLikeData(value: String): Boolean {
        val lower = value.lowercase()
        val labels = listOf("password", "passcode", "api key", "access token", "refresh token",
            "bearer token", "one-time password", "secret key", "private key", "recovery code", "client secret")
        if (labels.any(lower::contains)) return true
        if (Regex("\\b(?:token|secret|credential|password|passwd|api[_ -]?key)\\s*[:=]\\s*\\S+", RegexOption.IGNORE_CASE).containsMatchIn(value)) return true
        if (value.contains("-----BEGIN") && value.contains("PRIVATE KEY")) return true
        return Regex("\\b[A-Za-z0-9_-]{32,}\\b").containsMatchIn(value)
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
}
