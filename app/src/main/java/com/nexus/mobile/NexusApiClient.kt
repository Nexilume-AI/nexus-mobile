package com.nexus.mobile

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class NexusApiClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) {
    fun heartbeat(config: MobileConfig, body: String): MobileApiResponse =
        post(
            "${config.baseUrl}/api/v1/mobile-devices/${config.deviceId}/device/heartbeat/",
            config.token,
            body,
        )

    fun nextCommand(config: MobileConfig): MobileApiResponse =
        post(
            "${config.baseUrl}/api/v1/mobile-devices/${config.deviceId}/device/commands/next/",
            config.token,
            "{}",
        )

    fun videoPoll(config: MobileConfig, sessionId: String? = null, after: Int = 0): MobileApiResponse =
        post("${config.baseUrl}/api/v1/mobile-devices/${config.deviceId}/device/video/", config.token,
            JSONObject().put("session_id", sessionId).put("after", after).toString())

    fun videoSignal(config: MobileConfig, sessionId: String, body: JSONObject): MobileApiResponse =
        post("${config.baseUrl}/api/v1/mobile-video/$sessionId/device/", config.token, body.toString())

    fun reportResult(
        config: MobileConfig,
        commandId: String,
        result: CommandExecutionResult,
    ): MobileApiResponse =
        reportResultBody(config, commandId, NexusJson.encodeResult(result))

    fun reportResultBody(config: MobileConfig, commandId: String, body: String): MobileApiResponse =
        post(
            "${config.baseUrl}/api/v1/mobile-commands/$commandId/device/result/",
            config.token,
            body,
        )

    fun disconnect(config: MobileConfig, observation: MobileObservation): MobileApiResponse =
        heartbeat(config, NexusJson.encodeDisconnect(observation))

    private fun post(url: String, token: String, body: String): MobileApiResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Nexus-Mobile-Token", token)
            doOutput = true
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            decode(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(statusCode: Int, body: String): MobileApiResponse {
        val root = runCatching { JSONObject(body) }.getOrNull()
        val error = root?.optJSONObject("error")
        return MobileApiResponse(
            statusCode = statusCode,
            body = body,
            data = root?.optJSONObject("data"),
            errorCode = error?.optString("code").orEmpty(),
            errorMessage = error?.optString("message").orEmpty(),
        )
    }
}
