package com.nexus.mobile

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MobileControlContractTest {
    @Test fun advertisesNewActionsAndExcludesUnsupportedCapture() {
        assertTrue(MobileControlContract.supportedActions(34).containsAll(listOf("press_home", "press_recents", "long_press")))
        assertFalse(MobileControlContract.supportedActions(29).contains("capture_screen"))
        val heartbeat = JSONObject(NexusJson.encodeHeartbeat(MobileObservation(), true, "test", 34, "test"))
        val capabilities = heartbeat.getJSONObject("capabilities")
        assertEquals(1, capabilities.getInt("screen_control"))
        assertEquals(12, capabilities.getJSONArray("supported_actions").length())
    }
    @Test fun geometryMustMatchExactPhysicalDimensionsAndRotation() {
        val expected = mapOf("screen_width" to 1080, "screen_height" to 2400, "rotation" to 0)
        assertTrue(MobileControlContract.matchesScreen(expected, 1080, 2400, 0))
        assertFalse(MobileControlContract.matchesScreen(expected, 2400, 1080, 1))
        assertFalse(MobileControlContract.matchesScreen(expected, 1080, 2400, 2))
        assertFalse(MobileControlContract.matchesScreen(expected + ("rotation" to 0.5), 1080, 2400, 0))
        assertFalse(MobileControlContract.matchesScreen(emptyMap<String, Int>(), 1080, 2400, 0))
    }
}
