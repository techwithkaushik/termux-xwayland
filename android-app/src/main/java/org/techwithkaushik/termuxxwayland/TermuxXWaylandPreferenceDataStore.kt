package org.techwithkaushik.termuxxwayland

import android.content.Context
import androidx.preference.PreferenceDataStore

/**
 * Adapts AndroidX Preference widgets to the shared preference file already read
 * by DesktopSurfaceView and the input handlers. Numeric list values remain ints
 * for compatibility with the renderer.
 */
internal class TermuxXWaylandPreferenceDataStore(context: Context) : PreferenceDataStore() {
    private val values = context.getSharedPreferences("termux_xwayland_preferences", Context.MODE_PRIVATE)

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values.getBoolean(key, defValue)

    override fun putBoolean(key: String, value: Boolean) {
        values.edit().putBoolean(key, value).apply()
    }

    override fun getInt(key: String, defValue: Int): Int =
        values.getInt(key, defValue)

    override fun putInt(key: String, value: Int) {
        values.edit().putInt(key, value).apply()
    }

    override fun getString(key: String, defValue: String?): String? {
        val default = defValue ?: return values.getInt(key, 0).toString()
        return when (key) {
            "resolution_mode", "display_filter", "display_scale", "pointer_speed", "orientation", "idle_timeout",
            "touch_mode", "pointer_transform", "swipe_up", "swipe_down",
            "back_action", "notification_tap" -> values.getInt(key, default.toIntOrNull() ?: 0).toString()
            else -> values.getString(key, default)
        }
    }

    override fun putString(key: String, value: String?) {
        if (value == null) {
            values.edit().remove(key).apply()
            return
        }
        val numericKeys = setOf(
            "resolution_mode", "display_filter", "orientation", "idle_timeout",
            "touch_mode", "pointer_transform", "swipe_up", "swipe_down",
            "back_action", "notification_tap"
        )
        if (key in numericKeys) {
            value.toIntOrNull()?.let { values.edit().putInt(key, it).apply() }
        } else {
            values.edit().putString(key, value).apply()
        }
    }
}
