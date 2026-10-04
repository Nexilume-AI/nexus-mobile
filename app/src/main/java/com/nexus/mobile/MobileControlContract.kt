package com.nexus.mobile

/** Versioned support and geometry guard, with no Android dependencies. */
object MobileControlContract {
    val actions = listOf("observe", "capture_screen", "tap_text", "tap_coordinates", "type_text",
        "swipe", "press_back", "open_app", "wait_for_state", "press_home", "press_recents", "long_press")

    fun supportedActions(sdk: Int): List<String> = actions.filter { it != "capture_screen" || sdk >= 30 }

    fun matchesScreen(expected: Map<*, *>, width: Int, height: Int, rotation: Int): Boolean =
        listOf("screen_width" to width, "screen_height" to height, "rotation" to rotation).all { (key, value) ->
            val number = expected[key] as? Number
            number != null && number.toDouble().isFinite() && number.toDouble() == value.toDouble()
        }
}
