package com.nexus.mobile

import java.time.Instant

data class MobileObservation(
    val packageName: String = "",
    val activityName: String = "",
    val nodes: List<MobileNode> = emptyList(),
)

data class MobileNode(
    val text: String = "",
    val description: String = "",
    val className: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val enabled: Boolean = true,
    val sensitive: Boolean = false,
)

data class MobileConfig(
    val baseUrl: String = "",
    val deviceId: String = "",
    val token: String = "",
    val pairingExpiresAt: String = "",
) {
    fun isConfigured(): Boolean =
        baseUrl.isNotBlank() && deviceId.isNotBlank() && token.isNotBlank()

    fun isPairingExpired(now: Instant = Instant.now()): Boolean {
        if (pairingExpiresAt.isBlank()) return false
        return runCatching { !Instant.parse(pairingExpiresAt).isAfter(now) }.getOrDefault(false)
    }

    fun serverLabel(): String = runCatching {
        val uri = java.net.URI(baseUrl)
        buildString {
            append(uri.host ?: baseUrl)
            if (uri.port > 0) append(":${uri.port}")
        }
    }.getOrDefault(baseUrl)
}

data class PairingPayload(
    val config: MobileConfig,
    val warning: String = "",
)

enum class SyncState(val persistedValue: String) {
    NOT_PAIRED("not_paired"),
    SETUP_REQUIRED("setup_required"),
    CONNECTING("connecting"),
    ONLINE("online"),
    OFFLINE("offline"),
    PAIRING_EXPIRED("pairing_expired"),
    AUTH_FAILED("auth_failed"),
    STOPPED("stopped");

    companion object {
        fun from(value: String?): SyncState =
            entries.firstOrNull { it.persistedValue == value } ?: NOT_PAIRED
    }
}

data class MobileRuntimeState(
    val state: SyncState = SyncState.NOT_PAIRED,
    val message: String = "",
    val lastSyncAt: Long = 0L,
)

data class MobileCommandPayload(
    val id: String,
    val action: String,
    val arguments: Map<String, Any?>,
)

data class CommandExecutionResult(
    val succeeded: Boolean,
    val result: Map<String, Any?> = emptyMap(),
    val errorCode: String = "",
    val errorMessage: String = "",
)

data class MobileScreenshotCapture(
    val succeeded: Boolean,
    val bytes: ByteArray = byteArrayOf(),
    val contentType: String = "image/webp",
    val width: Int = 0,
    val height: Int = 0,
    val errorCode: String = "",
    val errorMessage: String = "",
)

data class MobileApiResponse(
    val statusCode: Int,
    val body: String,
    val data: org.json.JSONObject? = null,
    val errorCode: String = "",
    val errorMessage: String = "",
) {
    val isSuccessful: Boolean
        get() = statusCode in 200..299 && errorCode.isBlank()
}
