package com.nexus.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/** ADB-driven command bridge included only in debug APKs. */
class MobileE2ECommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "nexus-mobile-e2e") {
            val response = runCatching { execute(context, intent) }.getOrElse { error ->
                JSONObject()
                    .put("id", intent.getStringExtra("command_id").orEmpty())
                    .put("succeeded", false)
                    .put("error_code", "E2E_BRIDGE_FAILED")
                    .put("error_message", error.message ?: "The debug command bridge failed.")
            }
            File(context.filesDir, RESULT_FILE).writeText(response.toString(), Charsets.UTF_8)
            pending.resultData = response.toString()
            pending.resultCode = if (response.optBoolean("succeeded")) 0 else 1
            pending.finish()
        }
    }

    private fun execute(context: Context, intent: Intent): JSONObject {
        val id = intent.getStringExtra("command_id").orEmpty()
        val action = intent.getStringExtra("command").orEmpty()
        require(id.isNotBlank()) { "command_id is required." }
        require(action.isNotBlank()) { "command is required." }
        val arguments = mutableMapOf<String, Any?>()
        intent.extras?.keySet()?.filter { it.startsWith(ARG_PREFIX) }?.forEach { key ->
            arguments[key.removePrefix(ARG_PREFIX)] = intent.extras?.get(key)
        }
        intent.getStringExtra("text_base64")?.takeIf { it.isNotBlank() }?.let { encoded ->
            arguments["text"] = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        }
        val result = MobileCommandExecutor.execute(
            context,
            MobileCommandPayload(id = id, action = action, arguments = arguments),
        )
        val safeResult = result.result.toMutableMap()
        (safeResult.remove("screenshot_base64") as? String)?.let { screenshot ->
            safeResult["screenshot_bytes"] = Base64.decode(screenshot, Base64.DEFAULT).size
        }
        return JSONObject()
            .put("id", id)
            .put("succeeded", result.succeeded)
            .put("error_code", result.errorCode)
            .put("error_message", result.errorMessage)
            .put("result", jsonValue(safeResult))
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { target ->
            value.forEach { (key, item) -> target.put(key.toString(), jsonValue(item)) }
        }
        is Iterable<*> -> JSONArray().also { target -> value.forEach { target.put(jsonValue(it)) } }
        is Array<*> -> JSONArray().also { target -> value.forEach { target.put(jsonValue(it)) } }
        else -> value
    }

    companion object {
        const val RESULT_FILE = "mobile-e2e-result.json"
        private const val ARG_PREFIX = "arg_"
    }
}
