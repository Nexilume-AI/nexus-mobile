package com.nexus.mobile

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import org.json.JSONObject

/** Deterministic Accessibility surface included only in debug builds. */
class MobileE2EActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("nexus-mobile-e2e", MODE_PRIVATE)
        val savedMarker = preferences.getString("saved_marker", "").orEmpty()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(80))
            setBackgroundColor(Color.rgb(247, 248, 245))
        }
        content.addView(label("Nexus Mobile E2E", 26f, Typeface.BOLD).apply {
            contentDescription = "Nexus Mobile E2E"
        })
        content.addView(label("Private Display caller-device validation", 14f, Typeface.NORMAL).apply {
            setTextColor(Color.DKGRAY)
        }, margin(top = 8))

        input = EditText(this).apply {
            hint = "Enter unique Run marker"
            contentDescription = "E2E message"
            isSingleLine = false
            minHeight = dp(56)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setText(intent.getStringExtra("paper_marker") ?: savedMarker)
        }
        content.addView(input, margin(top = 28))

        content.addView(action("Apply") {
            val value = input.text.toString()
            // Debug-only effect receipt: independent of Cloud result reporting.
            // Retain every click, including repeats of the same marker.
            val sequence = preferences.getLong("apply_sequence", 0L) + 1L
            check(preferences.edit().putLong("apply_sequence", sequence).commit())
            File(filesDir, "paper-mobile-effects.jsonl").appendText(
                JSONObject().put("sequence", sequence).put("marker", value)
                    .put("at_unix_ms", System.currentTimeMillis()).toString() + "\n",
                Charsets.UTF_8,
            )
            preferences.edit().putString("saved_marker", value).apply()
            status.text = if (value.isBlank()) "Saved: empty" else "Saved: $value"
            status.contentDescription = status.text
        }.apply {
            setOnLongClickListener {
                status.text = "Long press received"
                status.contentDescription = status.text
                true
            }
        }, margin(top = 14))
        content.addView(action("Reset") {
            input.setText("")
            preferences.edit().remove("saved_marker").apply()
            status.text = "Not saved"
            status.contentDescription = "Not saved"
        }, margin(top = 10))

        val initialStatus = if (savedMarker.isBlank()) "Not saved" else "Saved: $savedMarker"
        status = label(initialStatus, 16f, Typeface.BOLD).apply {
            contentDescription = initialStatus
            setTextColor(Color.rgb(44, 88, 38))
            setPadding(dp(12), dp(18), dp(12), dp(18))
        }
        content.addView(status, margin(top = 20))

        repeat(12) { index ->
            content.addView(label("Scrollable target ${index + 1}", 15f, Typeface.NORMAL).apply {
                contentDescription = "Scrollable target ${index + 1}"
                setPadding(dp(12), dp(18), dp(12), dp(18))
            }, margin(top = 6))
        }
        content.addView(label("End of Mobile E2E Surface", 16f, Typeface.BOLD).apply {
            contentDescription = "End of Mobile E2E Surface"
            gravity = Gravity.CENTER
        }, margin(top = 18))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        })
    }

    private fun label(value: String, size: Float, style: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(28, 29, 27))
        setTypeface(typeface, style)
    }

    private fun action(value: String, onClick: () -> Unit) = Button(this).apply {
        text = value
        contentDescription = value
        minHeight = dp(48)
        setOnClickListener { onClick() }
    }

    private fun margin(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
