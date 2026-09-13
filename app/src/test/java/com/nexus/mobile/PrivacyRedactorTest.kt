package com.nexus.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyRedactorTest {
    @Test
    fun redactsPasswordOtpAndPaymentValues() {
        assertEquals("[REDACTED]", PrivacyRedactor.sanitize("super-secret", passwordField = true))
        assertEquals("[REDACTED]", PrivacyRedactor.sanitize("428193", sensitiveContext = true))
        assertEquals(
            "Card [REDACTED]",
            PrivacyRedactor.sanitize("Card 4111 1111 1111 1111"),
        )
    }

    @Test
    fun preservesOrdinaryInterfaceTextWithinBound() {
        assertEquals("Network & internet", PrivacyRedactor.sanitize("Network & internet"))
        assertFalse(PrivacyRedactor.indicatesSensitiveContext("Connected devices"))
        assertTrue(PrivacyRedactor.indicatesSensitiveContext("Verification code"))
        assertEquals(160, PrivacyRedactor.sanitize("a".repeat(300)).length)
    }
}
