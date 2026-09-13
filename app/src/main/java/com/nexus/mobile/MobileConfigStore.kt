package com.nexus.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object MobileConfigStore {
    private const val CONFIG_PREFS = "nexus_mobile_secure"
    private const val RUNTIME_PREFS = "nexus_mobile_runtime"
    private const val LEGACY_PREFS = "nexus_mobile"
    private const val KEY_ALIAS = "nexus_mobile_pairing_v1"
    private const val ENCRYPTED_PREFIX = "v1:"

    fun load(context: Context): MobileConfig {
        val prefs = context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
        val config = MobileConfig(
            baseUrl = decrypt(prefs.getString("base_url", "").orEmpty(), "base_url").trimEnd('/'),
            deviceId = decrypt(prefs.getString("device_id", "").orEmpty(), "device_id"),
            token = decrypt(prefs.getString("token", "").orEmpty(), "token"),
            pairingExpiresAt = decrypt(prefs.getString("pairing_expires_at", "").orEmpty(), "pairing_expires_at"),
        )
        if (config.isConfigured()) return config
        return migrateLegacy(context)
    }

    fun save(context: Context, config: MobileConfig) {
        require(config.isConfigured()) { "A complete pairing configuration is required." }
        context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("base_url", encrypt(config.baseUrl.trimEnd('/'), "base_url"))
            .putString("device_id", encrypt(config.deviceId, "device_id"))
            .putString("token", encrypt(config.token, "token"))
            .putString("pairing_expires_at", encrypt(config.pairingExpiresAt, "pairing_expires_at"))
            .apply()
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        updateRuntime(context, MobileRuntimeState(SyncState.NOT_PAIRED, "Pairing removed."))
    }

    fun loadRuntime(context: Context): MobileRuntimeState {
        val prefs = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
        return MobileRuntimeState(
            state = SyncState.from(prefs.getString("state", null)),
            message = prefs.getString("message", "").orEmpty(),
            lastSyncAt = prefs.getLong("last_sync_at", 0L),
        )
    }

    fun updateRuntime(context: Context, state: MobileRuntimeState) {
        context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("state", state.state.persistedValue)
            .putString("message", state.message)
            .putLong("last_sync_at", state.lastSyncAt)
            .apply()
    }

    private fun migrateLegacy(context: Context): MobileConfig {
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val legacy = MobileConfig(
            baseUrl = prefs.getString("base_url", "").orEmpty().trimEnd('/'),
            deviceId = prefs.getString("device_id", "").orEmpty(),
            token = prefs.getString("token", "").orEmpty(),
        )
        if (legacy.isConfigured()) save(context, legacy)
        return legacy
    }

    private fun encrypt(value: String, purpose: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        return ENCRYPTED_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String, purpose: String): String {
        if (value.isEmpty()) return ""
        if (!value.startsWith(ENCRYPTED_PREFIX)) return ""
        return runCatching {
            val payload = Base64.decode(value.removePrefix(ENCRYPTED_PREFIX), Base64.NO_WRAP)
            require(payload.size > 12) { "Encrypted pairing value is invalid." }
            val iv = payload.copyOfRange(0, 12)
            val encrypted = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
