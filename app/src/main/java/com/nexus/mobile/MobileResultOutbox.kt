package com.nexus.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One encrypted, atomically published receipt. Never stores a pairing token. */
object MobileResultOutbox {
    private const val KEY_ALIAS = "nexus_mobile_result_outbox_v1"
    private const val MAX_BYTES = 16 * 1024 * 1024
    private val purpose = "nexus-mobile-result-v1".toByteArray(Charsets.UTF_8)

    data class Pending(val scope: String, val commandId: String, val body: String)

    fun scope(config: MobileConfig): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf(config.baseUrl.trimEnd('/'), config.deviceId, config.token)
            .joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun pending(config: MobileConfig, commandId: String, result: CommandExecutionResult): Pending =
        Pending(scope(config), UUID.fromString(commandId).toString(), NexusJson.encodeResult(result))

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "mobile-result-outbox.v1"))

    @Synchronized
    fun load(context: Context, config: MobileConfig): Pending? {
        val store = file(context)
        if (!store.baseFile.exists() && !File(store.baseFile.path + ".bak").exists()) return null
        // Use APIs available at minSdk 26, and cap reads before allocation grows.
        val bytes = store.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "Pending result storage exceeds the bound" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size in 29..MAX_BYTES) { "Pending result storage is invalid" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(purpose)
        val record = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        require(record.getInt("version") == 1)
        val result = Pending(record.getString("scope"), UUID.fromString(record.getString("command_id")).toString(), record.getString("body"))
        if (result.scope != scope(config)) {
            // A deliberate re-pairing changes recipient/authority. Never send an
            // old pairing's screen/action result with a new device credential.
            store.delete()
            return null
        }
        return result
    }

    @Synchronized
    fun save(context: Context, config: MobileConfig, pending: Pending) {
        require(pending.scope == scope(config))
        val previous = load(context, config)
        require(previous == null || previous == pending) { "Unacknowledged result cannot be replaced" }
        val raw = JSONObject().put("version", 1).put("scope", pending.scope)
            .put("command_id", pending.commandId).put("body", pending.body).toString().toByteArray(Charsets.UTF_8)
        require(raw.size <= MAX_BYTES - 28) { "Pending result exceeds the storage bound" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key());cipher.updateAAD(purpose)
        val store = file(context);val output = store.startWrite()
        try {
            output.write(cipher.iv);output.write(cipher.doFinal(raw));store.finishWrite(output)
        } catch (error: Exception) {
            store.failWrite(output);throw error
        }
    }

    @Synchronized
    fun acknowledge(context: Context, config: MobileConfig, pending: Pending) {
        require(load(context, config) == pending) { "Result acknowledgment does not match stored receipt" }
        val store = file(context);store.delete()
        check(!store.baseFile.exists()) { "Acknowledged result could not be removed" }
    }

    @Synchronized
    fun clear(context: Context) { file(context).delete() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
