package com.nexus.mobile

import android.content.Context
import android.util.Base64

object MobileCommandExecutor {
    fun execute(
        context: Context,
        command: MobileCommandPayload,
    ): CommandExecutionResult {
        val service = NexusAccessibilityServiceHolder.service
            ?: return failure("ACCESSIBILITY_UNAVAILABLE", "Enable Nexus Mobile Control before running actions.")
        return runCatching {
            when (command.action) {
                "observe" -> {
                    val observation = service.observeBlocking()
                    CommandExecutionResult(
                        succeeded = true,
                        result = NexusJson.observationMap(observation),
                    )
                }
                "capture_screen" -> {
                    val capture = service.captureScreenBlocking()
                    if (capture.succeeded) {
                        CommandExecutionResult(
                            succeeded = true,
                            result = mapOf(
                                "screenshot_base64" to Base64.encodeToString(capture.bytes, Base64.NO_WRAP),
                                "content_type" to capture.contentType,
                                "width" to capture.width,
                                "height" to capture.height,
                            ),
                        )
                    } else {
                        failure(capture.errorCode, capture.errorMessage)
                    }
                }
                "tap_text" -> booleanResult(
                    service.tapTextBlocking(command.stringArgument("text")),
                    "TARGET_NOT_FOUND",
                    "No enabled clickable element matched the requested text.",
                )
                "tap_coordinates" -> booleanResult(
                    if (command.coordinateSpace() == "pixels") {
                        service.tapPixelsBlocking(
                            command.nonNegativeFloatArgument("x"),
                            command.nonNegativeFloatArgument("y"),
                        )
                    } else {
                        service.tapNormalizedBlocking(
                            command.normalizedFloatArgument("x"),
                            command.normalizedFloatArgument("y"),
                        )
                    },
                    "GESTURE_FAILED",
                    "Android did not complete the tap gesture.",
                )
                "type_text" -> booleanResult(
                    service.typeTextBlocking(command.stringArgument("text")),
                    "INPUT_UNAVAILABLE",
                    "No enabled editable field is focused.",
                )
                "swipe" -> booleanResult(
                    if (command.coordinateSpace() == "pixels") {
                        service.swipePixelsBlocking(
                            command.nonNegativeFloatArgument("start_x"),
                            command.nonNegativeFloatArgument("start_y"),
                            command.nonNegativeFloatArgument("end_x"),
                            command.nonNegativeFloatArgument("end_y"),
                            command.longArgument("duration_ms", 300L),
                        )
                    } else {
                        service.swipeNormalizedBlocking(
                            command.normalizedFloatArgument("start_x"),
                            command.normalizedFloatArgument("start_y"),
                            command.normalizedFloatArgument("end_x"),
                            command.normalizedFloatArgument("end_y"),
                            command.longArgument("duration_ms", 300L),
                        )
                    },
                    "GESTURE_FAILED",
                    "Android did not complete the swipe gesture.",
                )
                "press_back" -> booleanResult(
                    service.pressBackBlocking(),
                    "GLOBAL_ACTION_FAILED",
                    "Android rejected the Back action.",
                )
                "open_app" -> booleanResult(
                    openApp(context, command.stringArgument("package")),
                    "APP_NOT_FOUND",
                    "The requested Android application is not installed.",
                )
                "wait_for_state" -> waitForState(service, command)
                else -> failure("UNSUPPORTED_ACTION", "This Nexus Mobile version does not support ${command.action}.")
            }
        }.getOrElse { error ->
            failure("COMMAND_INVALID", error.message ?: "The Android command could not be executed.")
        }
    }

    private fun waitForState(
        service: NexusAccessibilityService,
        command: MobileCommandPayload,
    ): CommandExecutionResult {
        val expectedText = command.optionalStringArgument("text")
        val expectedPackage = command.optionalStringArgument("package")
        if (expectedText.isBlank() && expectedPackage.isBlank()) {
            return failure("COMMAND_INVALID", "Wait for state requires text or an Android package.")
        }
        val timeoutMs = command.longArgument("timeout_ms", 10_000L).coerceIn(250L, 30_000L)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() <= deadline) {
            val observation = service.observeBlocking()
            val textMatches = expectedText.isBlank() || observation.nodes.any { node ->
                node.text.contains(expectedText, ignoreCase = true) ||
                    node.description.contains(expectedText, ignoreCase = true)
            }
            val packageMatches =
                expectedPackage.isBlank() || observation.packageName.equals(expectedPackage, ignoreCase = true)
            if (textMatches && packageMatches) {
                return CommandExecutionResult(
                    succeeded = true,
                    result = mapOf(
                        "matched" to true,
                        "packageName" to observation.packageName,
                    ),
                )
            }
            Thread.sleep(250)
        }
        return failure("STATE_TIMEOUT", "The requested Android state did not appear before timeout.")
    }

    private fun openApp(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    private fun booleanResult(
        succeeded: Boolean,
        errorCode: String,
        message: String,
    ): CommandExecutionResult =
        if (succeeded) {
            CommandExecutionResult(true, result = mapOf("ok" to true))
        } else {
            failure(errorCode, message)
        }

    private fun failure(code: String, message: String): CommandExecutionResult =
        CommandExecutionResult(false, errorCode = code, errorMessage = message)

    private fun MobileCommandPayload.stringArgument(key: String): String =
        optionalStringArgument(key).ifBlank { throw IllegalArgumentException("$key is required.") }

    private fun MobileCommandPayload.optionalStringArgument(key: String): String =
        arguments[key]?.toString().orEmpty()

    private fun MobileCommandPayload.nonNegativeFloatArgument(key: String): Float {
        val value = (arguments[key] as? Number)?.toFloat()
            ?: arguments[key]?.toString()?.toFloatOrNull()
            ?: throw IllegalArgumentException("$key must be a number.")
        require(value >= 0f) { "$key must be non-negative." }
        return value
    }

    private fun MobileCommandPayload.normalizedFloatArgument(key: String): Float =
        nonNegativeFloatArgument(key).also { value ->
            require(value <= 1f) { "$key must be between 0 and 1." }
        }

    private fun MobileCommandPayload.coordinateSpace(): String =
        optionalStringArgument("coordinate_space").ifBlank { "normalized" }.lowercase().also { value ->
            require(value == "normalized" || value == "pixels") {
                "coordinate_space must be normalized or pixels."
            }
        }

    private fun MobileCommandPayload.longArgument(key: String, default: Long): Long =
        (arguments[key] as? Number)?.toLong()
            ?: arguments[key]?.toString()?.toLongOrNull()
            ?: default
}
