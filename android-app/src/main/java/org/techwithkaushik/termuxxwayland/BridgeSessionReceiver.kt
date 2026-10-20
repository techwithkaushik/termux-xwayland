package org.techwithkaushik.termuxxwayland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Receives the Termux:X11-style callback Binder sent by the Termux-side
 * app_process. The sender UID is checked before forwarding the Binder to the
 * in-app session service.
 */
class BridgeSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_OPEN_SESSION) return
        val senderUid = sendingUid
        val senderPackages = context.packageManager.getPackagesForUid(senderUid).orEmpty()
        if (TERMUX_PACKAGE !in senderPackages) {
            Log.w(TAG, "Rejected bridge broadcast uid=$senderUid packages=${senderPackages.joinToString()}")
            return
        }

        val client = intent.extras?.getBinder(EXTRA_CLIENT_BINDER)
        if (client == null || !client.isBinderAlive) {
            Log.w(TAG, "Rejected bridge broadcast without a live client Binder")
            return
        }

        val serviceIntent = Intent(context, BridgeSessionService::class.java)
            .putExtra(EXTRA_REMOTE_CLIENT, client)
        try {
            context.startService(serviceIntent)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to start bridge session service", error)
        }
    }

    companion object {
        const val ACTION_OPEN_SESSION =
            "org.techwithkaushik.termuxxwayland.action.OPEN_TERMUX_SESSION"
        const val EXTRA_BUNDLE = "termuxxwayland.session.bundle"
        const val EXTRA_CLIENT_BINDER = "termuxxwayland.session.client_binder"
        const val EXTRA_REMOTE_CLIENT = "termuxxwayland.session.remote_client"
        const val TERMUX_PACKAGE = "com.termux"
        private const val TAG = "TermuxXWayland"
    }
}
