package com.nexus.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusJsonTest {
    @Test
    fun parsesCommandEnvelopeWithExactGestureArguments() {
        val data = JSONObject(
            """
            {
              "id": "command-1",
              "action": "swipe",
              "arguments": {
                "start_x": 500,
                "start_y": 1400,
                "end_x": 500,
                "end_y": 400,
                "coordinate_space": "pixels",
                "duration_ms": 450
              }
            }
            """.trimIndent(),
        )

        val command = NexusJson.parseCommand(MobileApiResponse(200, "", data = data))

        assertEquals("command-1", command?.id)
        assertEquals("swipe", command?.action)
        assertEquals(500.0, (command?.arguments?.get("start_x") as Number).toDouble(), 0.0001)
        assertEquals("pixels", command.arguments["coordinate_space"])
        assertEquals(450, (command.arguments["duration_ms"] as Number).toInt())
    }

    @Test
    fun handlesNoCommandAndProducesStructuredResults() {
        assertNull(
            NexusJson.parseCommand(
                MobileApiResponse(200, "", data = JSONObject().put("command", JSONObject.NULL)),
            ),
        )
        val succeeded = JSONObject(
            NexusJson.encodeResult(CommandExecutionResult(true, mapOf("matched" to true))),
        )
        assertEquals("succeeded", succeeded.getString("status"))
        assertTrue(succeeded.getJSONObject("result").getBoolean("matched"))

        val failed = JSONObject(
            NexusJson.encodeResult(
                CommandExecutionResult(
                    false,
                    errorCode = "STATE_TIMEOUT",
                    errorMessage = "Requested state did not appear.",
                ),
            ),
        )
        assertEquals("failed", failed.getString("status"))
        assertFalse(failed.getString("error").contains("token"))
    }

    @Test
    fun heartbeatReportsOnDemandScreenshotsForAndroidElevenAndNewer() {
        val heartbeat = JSONObject(
            NexusJson.encodeHeartbeat(
                MobileObservation(packageName = "com.android.settings"),
                accessibilityReady = true,
                appVersion = "0.1.0",
                sdkVersion = 34,
                deviceModel = "Android Emulator",
            ),
        )

        assertEquals("online", heartbeat.getString("online_status"))
        assertTrue(heartbeat.getJSONObject("capabilities").getBoolean("accessibility"))
        assertTrue(heartbeat.getJSONObject("capabilities").getBoolean("screenshot"))
        assertEquals("com.android.settings", heartbeat.getString("current_package"))

        val legacyHeartbeat = JSONObject(
            NexusJson.encodeHeartbeat(
                MobileObservation(),
                accessibilityReady = true,
                appVersion = "0.1.0",
                sdkVersion = 29,
                deviceModel = "Legacy Android",
            ),
        )
        assertFalse(legacyHeartbeat.getJSONObject("capabilities").getBoolean("screenshot"))
    }

    @Test
    fun disconnectDoesNotOverwriteKnownPermissions() {
        val disconnect = JSONObject(
            NexusJson.encodeDisconnect(MobileObservation(packageName = "com.nexus.mobile")),
        )

        assertEquals("offline", disconnect.getString("online_status"))
        assertEquals("com.nexus.mobile", disconnect.getString("current_package"))
        assertFalse(disconnect.has("capabilities"))
    }
}
