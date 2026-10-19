package org.techwithkaushik.termuxxwayland

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * Minimal control-plane IPC for the existing Termux app.
 *
 * This does not carry frame data. Calls are accepted only from a UID that owns
 * the com.termux package; Android's package manager supplies the UID mapping.
 */
class TermuxControlProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "org.techwithkaushik.termuxxwayland.control"
        private const val PREFS = "termux_control_session"
        private const val KEY_STATE = "state"
        private const val KEY_SESSION = "session_id"
        private const val TERMUX_PACKAGE = "com.termux"
        private val SESSION_ID = Regex("^[a-f0-9]{32}$")
    }

    override fun onCreate(): Boolean = context != null

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: throw IllegalStateException("Provider context unavailable")
        val callerUid = Binder.getCallingUid()
        if (callerUid == Process.myUid() || !isTermuxUid(callerUid)) {
            throw SecurityException("Only the installed com.termux app may call this endpoint")
        }

        val prefs = ctx.getSharedPreferences(PREFS, 0)
        val result = Bundle()
        when (method) {
            "start" -> {
                val session = arg.orEmpty()
                require(SESSION_ID.matches(session)) { "Invalid session ID" }
                prefs.edit().putString(KEY_STATE, "connected")
                    .putString(KEY_SESSION, session).apply()
                result.putString("state", "connected")
                result.putString("session_id", session)
                result.putInt("protocol_version", 1)
            }
            "status" -> {
                result.putString("state", prefs.getString(KEY_STATE, "disconnected"))
                result.putString("session_id", prefs.getString(KEY_SESSION, ""))
                result.putInt("protocol_version", 1)
            }
            "stop" -> {
                prefs.edit().putString(KEY_STATE, "disconnected")
                    .remove(KEY_SESSION).apply()
                result.putString("state", "disconnected")
                result.putInt("protocol_version", 1)
            }
            else -> throw IllegalArgumentException("Unsupported control method")
        }
        return result
    }

    private fun isTermuxUid(uid: Int): Boolean {
        val pm = context?.packageManager ?: return false
        return pm.getPackagesForUid(uid)?.contains(TERMUX_PACKAGE) == true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        throw UnsupportedOperationException("Use the bounded call() control API")

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Use the bounded call() control API")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Use the bounded call() control API")

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Use the bounded call() control API")
}
