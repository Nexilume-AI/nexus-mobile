package com.nexus.mobile

import org.json.JSONArray
import org.json.JSONObject

object NexusJson {
    fun encodeHeartbeat(
        observation: MobileObservation,
        accessibilityReady: Boolean,
        appVersion: String,
        sdkVersion: Int,
        deviceModel: String,
    ): String = JSONObject()
        .put("online_status", if (accessibilityReady) "online" else "offline")
        .put("current_package", observation.packageName)
        .put("current_activity", observation.activityName)
        .put(
            "capabilities",
            JSONObject()
                .put("accessibility", accessibilityReady)
                .put("screen_observation", accessibilityReady)
                .put("screenshot", accessibilityReady && sdkVersion >= 30)
                .put("gestures", accessibilityReady),
        )
        .put(
            "metadata",
            JSONObject()
                .put("app_version", appVersion)
                .put("android_sdk", sdkVersion)
                .put("device_model", deviceModel.take(120)),
        )
        .put("observation", observationToJson(observation))
        .toString()

    fun encodeDisconnect(observation: MobileObservation): String = JSONObject()
        .put("online_status", "offline")
        .put("current_package", observation.packageName)
        .put("current_activity", observation.activityName)
        .toString()

    fun parseCommand(response: MobileApiResponse): MobileCommandPayload? {
        val data = response.data ?: return null
        if (data.has("command") && data.isNull("command")) return null
        val id = data.optString("id")
        val action = data.optString("action")
        if (id.isBlank() || action.isBlank()) return null
        val argumentsObject = data.optJSONObject("arguments") ?: JSONObject()
        return MobileCommandPayload(
            id = id,
            action = action,
            arguments = argumentsObject.toMap(),
        )
    }

    fun encodeResult(result: CommandExecutionResult): String {
        val payload = JSONObject()
        if (result.succeeded) {
            payload.put("status", "succeeded")
            payload.put("result", mapToJson(result.result))
        } else {
            payload.put("status", "failed")
            payload.put(
                "error",
                listOf(result.errorCode, result.errorMessage)
                    .filter { it.isNotBlank() }
                    .joinToString(": ")
                    .take(1024),
            )
        }
        return payload.toString()
    }

    fun observationMap(observation: MobileObservation): Map<String, Any?> =
        observationToJson(observation).toMap()

    private fun observationToJson(observation: MobileObservation): JSONObject {
        val nodes = JSONArray()
        observation.nodes.take(80).forEach { node ->
            nodes.put(
                JSONObject()
                    .put("text", node.text)
                    .put("description", node.description)
                    .put("className", node.className)
                    .put("clickable", node.clickable)
                    .put("editable", node.editable)
                    .put("enabled", node.enabled)
                    .put("sensitive", node.sensitive),
            )
        }
        return JSONObject()
            .put("packageName", observation.packageName)
            .put("activityName", observation.activityName)
            .put("nodes", nodes)
    }

    private fun JSONObject.toMap(): Map<String, Any?> =
        keys().asSequence().associateWith { key ->
            when (val value = opt(key)) {
                JSONObject.NULL -> null
                is JSONObject -> value.toMap()
                is JSONArray -> value.toList()
                else -> value
            }
        }

    private fun JSONArray.toList(): List<Any?> =
        (0 until length()).map { index ->
            when (val value = opt(index)) {
                JSONObject.NULL -> null
                is JSONObject -> value.toMap()
                is JSONArray -> value.toList()
                else -> value
            }
        }

    private fun mapToJson(value: Map<String, Any?>): JSONObject {
        val output = JSONObject()
        value.forEach { (key, item) ->
            output.put(
                key,
                when (item) {
                    is Map<*, *> -> mapToJson(item.entries.associate { it.key.toString() to it.value })
                    is Iterable<*> -> JSONArray(item.toList())
                    null -> JSONObject.NULL
                    else -> item
                },
            )
        }
        return output
    }
}
