package com.nexus.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileVideoPrivacyGateTest {
    @Test fun transitionDropsFramesWithoutEndingConsent() {
        val gate = MobileVideoPrivacyGate()
        assertEquals(MobileVideoPrivacyGate.Decision.DROP, gate.evaluate(MobileScreenPrivacy.UNKNOWN, 100))
        assertEquals(MobileVideoPrivacyGate.Decision.DROP, gate.evaluate(MobileScreenPrivacy.UNKNOWN, 4_999))
        assertEquals(MobileVideoPrivacyGate.Decision.SEND, gate.evaluate(MobileScreenPrivacy.CLEAR, 5_000))
    }
    @Test fun sensitiveWindowStopsPermanently() {
        val gate = MobileVideoPrivacyGate()
        assertEquals(MobileVideoPrivacyGate.Decision.SEND, gate.evaluate(MobileScreenPrivacy.CLEAR, 0))
        assertEquals(MobileVideoPrivacyGate.Decision.STOP, gate.evaluate(MobileScreenPrivacy.SENSITIVE, 1))
        assertEquals(MobileVideoPrivacyGate.Decision.STOP, gate.evaluate(MobileScreenPrivacy.CLEAR, 2))
    }
    @Test fun unknownTimeoutCannotResumeWithoutNewConsent() {
        val gate = MobileVideoPrivacyGate()
        assertEquals(MobileVideoPrivacyGate.Decision.DROP, gate.evaluate(MobileScreenPrivacy.UNKNOWN, 0))
        assertEquals(MobileVideoPrivacyGate.Decision.STOP, gate.evaluate(MobileScreenPrivacy.UNKNOWN, 5_000))
        assertEquals(MobileVideoPrivacyGate.Decision.STOP, gate.evaluate(MobileScreenPrivacy.CLEAR, 5_001))
    }
    @Test fun separateTransitionsDoNotAccumulateUnknownTime() {
        val gate = MobileVideoPrivacyGate()
        gate.evaluate(MobileScreenPrivacy.UNKNOWN, 0)
        gate.evaluate(MobileScreenPrivacy.CLEAR, 4_000)
        assertEquals(MobileVideoPrivacyGate.Decision.DROP, gate.evaluate(MobileScreenPrivacy.UNKNOWN, 10_000))
        assertEquals(MobileVideoPrivacyGate.Decision.SEND, gate.evaluate(MobileScreenPrivacy.CLEAR, 14_000))
    }
}
