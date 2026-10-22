package org.techwithkaushik.termuxxwayland

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

/**
 * Native AndroidX preference hierarchy modelled on Termux:X11's preferences.
 * The PreferenceDataStore adapter keeps the existing renderer/input keys stable.
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
        }
    }
}
