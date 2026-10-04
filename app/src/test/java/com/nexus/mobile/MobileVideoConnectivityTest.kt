package com.nexus.mobile

import org.junit.Assert.*
import org.junit.Test

class MobileVideoConnectivityTest {
    @Test fun negotiationIsBounded() {
        val gate = MobileVideoConnectivity(100)
        assertFalse(gate.expired(30_099)); assertTrue(gate.expired(30_100))
    }
    @Test fun transientDisconnectCanRecover() {
        val gate = MobileVideoConnectivity(0)
        gate.update("CONNECTED", 100)
        gate.update("DISCONNECTED", 200)
        assertFalse(gate.expired(5_199))
        gate.update("COMPLETED", 5_000)
        assertFalse(gate.expired(100_000))
    }
    @Test fun repeatingDisconnectDoesNotExtendGrace() {
        val gate = MobileVideoConnectivity(0)
        gate.update("CONNECTED", 1); gate.update("DISCONNECTED", 100)
        gate.update("DISCONNECTED", 4_000)
        assertTrue(gate.expired(5_100))
    }
    @Test fun failureIsImmediate() {
        val gate = MobileVideoConnectivity(0)
        gate.update("FAILED", 100); assertTrue(gate.expired(100))
    }
}
