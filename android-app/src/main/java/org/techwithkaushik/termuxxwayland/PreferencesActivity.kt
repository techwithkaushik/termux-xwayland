package org.techwithkaushik.termuxxwayland

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Lightweight, dependency-free Termux:X11-inspired preferences.
 * Values are persisted so later input/rendering changes can consume them.
 */
class PreferencesActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("termux_xwayland_preferences", MODE_PRIVATE) }
    private val ink = Color.rgb(25, 35, 48)
    private val muted = Color.rgb(105, 115, 128)
    private val accent = Color.rgb(0, 137, 123)
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Preferences"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        val toolbar = TextView(this).apply {
            text = "‹     Preferences"
            textSize = 24f
            setTextColor(ink)
            setPadding(dp(20), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.WHITE)
            setOnClickListener { finish() }
        }
        root.addView(toolbar)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        buildPreferences()
    }

    private fun buildPreferences() {
        content.removeAllViews()
        category("Output")
        choice("Display resolution mode", "resolution_mode", listOf("Native", "Scaled", "Exact", "Custom"), 0)
        seek("Display scale, %", "display_scale", 100, 30, 300, 10)
        choice("Display filtering mode", "display_filter", listOf("Bilinear", "Nearest"), 0)
        toggle("Adjust resolution to fit orientation", "adjust_resolution", false)
        toggle("Stretch to fit display", "display_stretch", false)
        toggle("Resize screen while keyboard is open", "resize_for_keyboard", false)
        toggle("Picture-in-picture mode", "pip_mode", false)
        toggle("Fullscreen / immersive mode", "fullscreen", true)
        choice("Screen orientation", "orientation", listOf("Auto", "Portrait", "Landscape", "Reverse portrait", "Reverse landscape"), 0)
        choice("Screen idle timeout", "idle_timeout", listOf("System", "Never", "1 minute", "5 minutes", "10 minutes", "20 minutes", "1 hour"), 0)

        category("Pointer")
        choice("Touchscreen input mode", "touch_mode", listOf("Trackpad", "Simulated touchscreen", "Direct touch"), 0)
        toggle("Apply display scale factor to touchpad", "scale_touchpad", true)
        toggle("Show stylus click options", "stylus_click_override", false)
        toggle("Enable stylus mouse mode", "stylus_mouse", false)
        toggle("Stylus button contact modifier mode", "stylus_button_modifier", false)
        toggle("Show mouse click helper overlay", "mouse_helper", false)
        toggle("Capture external pointer devices when possible", "pointer_capture", false)
        choice("Transform captured pointer movements", "pointer_transform", listOf("No", "Clockwise", "Counter clockwise", "Upside down", "Automatic (for touchpad)"), 4)
        seek("Captured pointer speed factor, %", "pointer_speed", 100, 1, 300, 1)
        toggle("Enable tap-to-move for touchpads", "tap_to_move", true)
        toggle("Ignore gamepad input events", "ignore_gamepad", false)
        toggle("Pan to follow cursor while zoomed in", "zoom_follows_cursor", true)

        category("Keyboard")
        toggle("Show additional keyboard", "additional_keyboard", true)
        toggle("Show software keyboard with external keyboard", "ime_with_external", true)
        toggle("Prefer scancodes when possible", "prefer_scancodes", false)
        toggle("Hardware keyboard scancodes workaround", "scancode_workaround", true)
        toggle("Intercept system shortcuts (DeX)", "intercept_shortcuts", false)
        toggle("Pause key intercepting with Esc", "pause_intercept_esc", false)
        toggle("Filter intercepted Win/Meta key", "filter_meta", false)
        toggle("Enforce character-based input", "character_input", false)

        category("Other")
        toggle("Clipboard sharing", "clipboard_sharing", true)
        toggle("Store preferences for secondary displays separately", "separate_displays", false)
        choice("Three-finger swipe up", "swipe_up", listOf("No action", "Toggle keyboard", "Toggle extra keys", "Open preferences", "Release pointer capture", "Toggle fullscreen", "Restart activity", "Exit"), 0)
        choice("Three-finger swipe down", "swipe_down", listOf("No action", "Toggle keyboard", "Toggle extra keys", "Open preferences", "Release pointer capture", "Toggle fullscreen", "Restart activity", "Exit"), 2)
        choice("Back button", "back_action", listOf("Toggle soft keyboard", "Open preferences", "Release pointer capture", "Exit"), 0)
        choice("Notification tap", "notification_tap", listOf("Open preferences", "Toggle keyboard", "Toggle extra keys", "Exit"), 0)
        content.addView(TextView(this).apply {
            text = "These settings are saved locally. Settings that require compositor-level or native XInput integration will take effect as their input/rendering handlers are connected."
            textSize = 13f
            setTextColor(muted)
            setPadding(dp(12), dp(18), dp(12), dp(12))
        })
    }

    private fun category(name: String) {
        content.addView(TextView(this).apply {
            text = name.uppercase()
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setTextColor(accent)
            setPadding(dp(12), dp(20), dp(12), dp(8))
        })
    }

    private fun row(title: String, summary: String? = null, action: (() -> Unit)? = null): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setBackgroundColor(Color.WHITE)
            if (action != null) setOnClickListener { action() }
            addView(TextView(this@PreferencesActivity).apply {
                text = title
                textSize = 16f
                setTextColor(ink)
            })
            if (!summary.isNullOrBlank()) addView(TextView(this@PreferencesActivity).apply {
                text = summary
                textSize = 13f
                setTextColor(muted)
                setPadding(0, dp(4), 0, 0)
            })
        }.also { view ->
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.bottomMargin = dp(1)
            content.addView(view, lp)
        }
    }

    private fun toggle(title: String, key: String, default: Boolean) {
        val container = row(title)
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(key, default)
            setTextColor(ink)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        }
        container.addView(sw)
        container.setOnClickListener { sw.isChecked = !sw.isChecked }
    }

    private fun choice(title: String, key: String, entries: List<String>, defaultIndex: Int) {
        val index = prefs.getInt(key, defaultIndex).coerceIn(entries.indices)
        row(title, entries[index]) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(entries.toTypedArray(), prefs.getInt(key, defaultIndex).coerceIn(entries.indices)) { dialog, which ->
                    prefs.edit().putInt(key, which).apply()
                    dialog.dismiss()
                    buildPreferences()
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }
    }

    private fun seek(title: String, key: String, default: Int, min: Int, max: Int, step: Int) {
        val container = row(title, "${prefs.getInt(key, default).coerceIn(min, max)}%")
        val seek = SeekBar(this).apply {
            this.max = (max - min) / step
            progress = (prefs.getInt(key, default).coerceIn(min, max) - min) / step
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = min + progress * step
                    prefs.edit().putInt(key, value).apply()
                    (container.getChildAt(1) as? TextView)?.text = "$value%"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        container.addView(seek)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
