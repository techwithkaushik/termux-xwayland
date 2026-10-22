package org.techwithkaushik.termuxxwayland

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat

/**
 * Native AndroidX preference hierarchy modelled on Termux:X11.
 *
 * Display controls are kept consistent with the native renderer: scale is
 * meaningful only for Scaled mode, and the stretch override only for Fit mode.
 * Preferences are persisted through the shared PreferenceDataStore consumed by
 * the renderer/input handlers.
 */
class PreferencesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, PreferencesFragment())
            .commit()
    }

    class PreferencesFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore =
                TermuxXWaylandPreferenceDataStore(requireContext())
            setPreferencesFromResource(R.xml.preferences, rootKey)

            val resolutionMode = findPreference<ListPreference>("resolution_mode")
            resolutionMode?.onPreferenceChangeListener =
                androidx.preference.Preference.OnPreferenceChangeListener { _, newValue ->
                    applyDisplayDependencies(newValue?.toString() ?: DEFAULT_RESOLUTION_MODE)
                    true
                }

            applyDisplayDependencies(
                resolutionMode?.value ?: DEFAULT_RESOLUTION_MODE
            )
        }

        private fun applyDisplayDependencies(mode: String) {
            // Values are the stable numeric modes consumed by native_egl_renderer.cpp:
            // 0 = fit, 1 = scaled, 2 = stretch, 3 = pixel-perfect.
            findPreference<ListPreference>("display_scale")?.isEnabled =
                mode == SCALED_MODE
            findPreference<androidx.preference.SwitchPreferenceCompat>("display_stretch")?.apply {
                isEnabled = mode == FIT_MODE
                summary = if (isEnabled) {
                    "Override aspect ratio and fill the display while using Fit mode."
                } else {
                    "Available only in Fit mode; Stretch and Pixel-perfect define their own geometry."
                }
            }
        }

        private companion object {
            const val DEFAULT_RESOLUTION_MODE = "0"
            const val FIT_MODE = "0"
            const val SCALED_MODE = "1"
        }
    }
}
