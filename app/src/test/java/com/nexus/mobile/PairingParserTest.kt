package com.nexus.mobile

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingParserTest {
    private val deviceId = "11111111-1111-4111-8111-111111111111"
    private val token = "mnx-mobile-abcdefghijklmnopqrstuvwxyz123456"

    @Test
    fun parsesSecureConsolePairingLink() {
        val payload = PairingParser.parse(
            pairingLink("https://nexus.example", "2026-08-01T00:00:00Z"),
            allowInsecureLocalhost = false,
            now = Instant.parse("2026-07-30T00:00:00Z"),
        )

        assertEquals("https://nexus.example", payload.config.baseUrl)
        assertEquals(deviceId, payload.config.deviceId)
        assertEquals(token, payload.config.token)
        assertTrue(payload.warning.isBlank())
    }

    @Test
    fun allowsLocalHttpOnlyForDebugPairing() {
        val link = pairingLink("http://10.0.2.2:8000", "2026-08-01T00:00:00Z")

        assertThrows(PairingValidationException::class.java) {
            PairingParser.parse(link, allowInsecureLocalhost = false)
        }
        val debugPayload = PairingParser.parse(
            link,
            allowInsecureLocalhost = true,
            now = Instant.parse("2026-07-30T00:00:00Z"),
        )
        assertTrue(debugPayload.warning.contains("not encrypted"))
    }

    @Test
    fun rejectsExpiredOrUntrustedPairingLinks() {
        assertThrows(PairingValidationException::class.java) {
            PairingParser.parse(
                pairingLink("https://nexus.example", "2026-07-29T23:59:59Z"),
                allowInsecureLocalhost = false,
                now = Instant.parse("2026-07-30T00:00:00Z"),
            )
        }
        assertThrows(PairingValidationException::class.java) {
            PairingParser.parse(
                pairingLink("http://attacker.example", "2026-08-01T00:00:00Z"),
                allowInsecureLocalhost = true,
            )
        }
    }

    private fun pairingLink(baseUrl: String, expiresAt: String): String {
        fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        return "nexus-mobile://pair" +
            "?base_url=${encode(baseUrl)}" +
            "&device_id=${encode(deviceId)}" +
            "&token=${encode(token)}" +
            "&expires_at=${encode(expiresAt)}"
    }
}
