package ai.xyaa.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local Code Studio inbox. It safely captures and tracks requested work, but deliberately
 * does not execute code on the Android device. Execution is enabled only when an isolated
 * remote runner is configured and authenticated.
 */
class CodeStudioTaskStore(context: Context) {
    data class Task(
        val id: String,
        val request: String,
        val status: String,
        val createdAt: Long,
        val updatedAt: Long,
        val plan: String = "",
        val approved: Boolean = false
    )

    private val prefs = context.getSharedPreferences("xyaa_code_studio_v1", Context.MODE_PRIVATE)
    private val lock = Any()
    private val alias = "xyaa_code_studio_tasks_aes"
    private val maxTasks = 50

    fun create(request: String): Task? = synchronized(lock) {
        val cleaned = request.trim().take(4000)
        if (cleaned.isBlank()) return@synchronized null
        val tasks = readUnsafe().toMutableList()
        val now = System.currentTimeMillis()
        val task = Task(UUID.randomUUID().toString(), cleaned, "Queued · runner not connected", now, now)
        tasks.add(task)
        writeUnsafe(tasks.takeLast(maxTasks))
        task
    }

    /** Edit a queued task without changing its identity or pretending it has executed. */
    fun update(id: String, request: String): Task? = synchronized(lock) {
        val cleaned = request.trim().take(4000)
        if (cleaned.isBlank()) return@synchronized null
        val tasks = readUnsafe().toMutableList()
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val updated = tasks[index].copy(request = cleaned, plan = "", approved = false,
            status = "Queued · runner not connected", updatedAt = System.currentTimeMillis())
        tasks[index] = updated
        writeUnsafe(tasks)
        updated
    }

    fun setPlanning(id: String): Task? = synchronized(lock) {
        val tasks = readUnsafe().toMutableList()
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val updated = tasks[index].copy(plan = "", approved = false, status = "Planning…", updatedAt = System.currentTimeMillis())
        tasks[index] = updated
        writeUnsafe(tasks)
        updated
    }

    fun setPlanningFailed(id: String, expectedRequest: String? = null): Task? = synchronized(lock) {
        val tasks = readUnsafe().toMutableList()
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0 || (expectedRequest != null && tasks[index].request != expectedRequest)) return@synchronized null
        val updated = tasks[index].copy(status = "Planning failed · check provider settings", updatedAt = System.currentTimeMillis())
        tasks[index] = updated
        writeUnsafe(tasks)
        updated
    }

    /** Persist an AI-generated plan; planning never executes code or changes a repository. */
    fun savePlan(id: String, plan: String, expectedRequest: String? = null): Task? = synchronized(lock) {
        val cleaned = plan.trim().take(12000)
        if (cleaned.isBlank()) return@synchronized null
        val tasks = readUnsafe().toMutableList()
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0 || (expectedRequest != null && tasks[index].request != expectedRequest)) return@synchronized null
        val updated = tasks[index].copy(plan = cleaned, approved = false,
            status = "Plan ready · awaiting approval", updatedAt = System.currentTimeMillis())
        tasks[index] = updated
        writeUnsafe(tasks)
        updated
    }

    fun setApproved(id: String, approved: Boolean): Task? = synchronized(lock) {
        val tasks = readUnsafe().toMutableList()
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0 || tasks[index].plan.isBlank()) return@synchronized null
        val updated = tasks[index].copy(approved = approved,
            status = if (approved) "Plan approved · runner not connected" else "Plan ready · approval withdrawn",
            updatedAt = System.currentTimeMillis())
        tasks[index] = updated
        writeUnsafe(tasks)
        updated
    }

    fun all(): List<Task> = synchronized(lock) { readUnsafe().sortedByDescending { it.createdAt } }

    fun delete(id: String) = synchronized(lock) {
        val tasks = readUnsafe().filterNot { it.id == id }
        writeUnsafe(tasks)
    }

    fun clear() = synchronized(lock) { prefs.edit().remove("payload").apply() }

    private fun readUnsafe(): List<Task> {
        val packed = prefs.getString("payload", null) ?: return emptyList()
        return runCatching {
            val bytes = Base64.decode(packed, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val json = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            (0 until json.length()).mapNotNull { i ->
                val item = json.optJSONObject(i) ?: return@mapNotNull null
                val id = item.optString("id")
                val request = item.optString("request")
                if (id.isBlank() || request.isBlank()) null else Task(
                    id, request.take(4000), item.optString("status").ifBlank { "Queued" },
                    item.optLong("createdAt"), item.optLong("updatedAt"),
                    item.optString("plan").take(12000), item.optBoolean("approved", false)
                )
            }.takeLast(maxTasks)
        }.getOrDefault(emptyList())
    }

    private fun writeUnsafe(tasks: List<Task>) {
        val json = JSONArray()
        tasks.forEach { task ->
            json.put(JSONObject().put("id", task.id).put("request", task.request)
                .put("status", task.status).put("createdAt", task.createdAt).put("updatedAt", task.updatedAt)
                .put("plan", task.plan).put("approved", task.approved))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val bytes = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("payload", Base64.encodeToString(cipher.iv + bytes, Base64.NO_WRAP)).apply()
    }

    private fun getKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}
