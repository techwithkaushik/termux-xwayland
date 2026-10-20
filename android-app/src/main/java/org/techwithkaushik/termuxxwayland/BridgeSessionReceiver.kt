package org.techwithkaushik.termuxxwayland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Receives the Termux:X11-style callback Binder sent by the Termux-side
 * app_process. Android 10 does not expose the sender UID through the public
 * receiver API, so this validates the interface descriptor as a sanity check;
 * the Termux-side endpoint independently validates the host UID on callback.
 */
class BridgeSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_OPEN_SESSION) return
        val client = intent.extras?.getBundle(EXTRA_BUNDLE)?.getBinder(EXTRA_CLIENT_BINDER)
        if (client == null || !client.isBinderAlive) {
            Log.w(TAG, "Rejected bridge broadcast without a live client Binder")
            return
        }
        val descriptor = runCatching { client.interfaceDescriptor }.getOrNull()
        if (descriptor != BridgeSessionService.DESCRIPTOR) {
            Log.w(TAG, "Rejected bridge broadcast with an unexpected Binder descriptor")
            return
        }

        val remoteBundle = Bundle().apply { putBinder(EXTRA_REMOTE_CLIENT, client) }
        val serviceIntent = Intent(context, BridgeSessionService::class.java)
            .putExtra(EXTRA_REMOTE_BUNDLE, remoteBundle)
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
        const val EXTRA_REMOTE_BUNDLE = "termuxxwayland.session.remote_bundle"
        const val EXTRA_REMOTE_CLIENT = "termuxxwayland.session.remote_client"
        private const val TAG = "TermuxXWayland"
    }
}
