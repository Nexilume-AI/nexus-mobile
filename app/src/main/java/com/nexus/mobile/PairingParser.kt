package com.nexus.mobile

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

class PairingValidationException(message: String) : IllegalArgumentException(message)

object PairingParser {
    fun parse(
        rawValue: String,
        allowInsecureLocalhost: Boolean,
        now: Instant = Instant.now(),
    ): PairingPayload {
        val uri = runCatching { URI(rawValue.trim()) }
            .getOrElse { throw PairingValidationException("This is not a valid Nexus pairing QR.") }
        if (uri.scheme != "nexus-mobile" || uri.host != "pair") {
            throw PairingValidationException("This QR was not created by Nexus Mobile pairing.")
        }
        val values = parseQuery(uri.rawQuery.orEmpty())
        val baseUrl = values["base_url"].orEmpty().trimEnd('/')
        val deviceId = values["device_id"].orEmpty()
        val token = values["token"].orEmpty()
        val expiresAt = values["expires_at"].orEmpty()

        val serverUri = runCatching { URI(baseUrl) }
            .getOrElse { throw PairingValidationException("The Nexus server address is invalid.") }
        if (serverUri.userInfo != null || serverUri.host.isNullOrBlank() || serverUri.query != null || serverUri.fragment != null) {
            throw PairingValidationException("The Nexus server address is not trusted.")
        }
        val secure = serverUri.scheme.equals("https", ignoreCase = true)
        val allowedDebugHttp =
            allowInsecureLocalhost &&
                serverUri.scheme.equals("http", ignoreCase = true) &&
                isLocalDevelopmentHost(serverUri.host)
        if (!secure && !allowedDebugHttp) {
            throw PairingValidationException("Pairing requires HTTPS. Local HTTP is allowed only in debug builds.")
        }
        runCatching { UUID.fromString(deviceId) }
            .getOrElse { throw PairingValidationException("The pairing QR has an invalid device identifier.") }
        if (!token.startsWith("mnx-mobile-") || token.length < 32) {
            throw PairingValidationException("The pairing QR has an invalid device token.")
        }
        if (expiresAt.isNotBlank()) {
            val expiry = runCatching { Instant.parse(expiresAt) }
                .getOrElse { throw PairingValidationException("The pairing expiry time is invalid.") }
            if (!expiry.isAfter(now)) {
                throw PairingValidationException("This pairing QR has expired. Generate a new QR in Nexus Console.")
            }
        }
        val warning = if (secure) "" else "Debug connection: traffic to ${serverUri.host} is not encrypted."
        return PairingPayload(
            config = MobileConfig(
                baseUrl = serverUri.toString().trimEnd('/'),
                deviceId = deviceId,
                token = token,
                pairingExpiresAt = expiresAt,
            ),
            warning = warning,
        )
    }

    private fun parseQuery(rawQuery: String): Map<String, String> =
        rawQuery.split("&")
            .filter { it.isNotBlank() }
            .associate { part ->
                val separator = part.indexOf('=')
                val key = if (separator >= 0) part.substring(0, separator) else part
                val value = if (separator >= 0) part.substring(separator + 1) else ""
                decode(key) to decode(value)
            }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun isLocalDevelopmentHost(host: String): Boolean {
        val normalized = host.lowercase()
        if (normalized in setOf("localhost", "127.0.0.1", "10.0.2.2", "::1")) return true
        if (normalized.startsWith("10.") || normalized.startsWith("192.168.")) return true
        val segments = normalized.split(".")
        if (segments.size == 4 && segments[0] == "172") {
            val second = segments[1].toIntOrNull()
            return second != null && second in 16..31
        }
        return false
    }
}
