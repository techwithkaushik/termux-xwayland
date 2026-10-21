package org.techwithkaushik.termuxxwayland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Stops the Android bridge when Exit is pressed in its persistent notification. */
class ExitSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_EXIT) return
        Log.i("TermuxXWayland", "event=notification_exit_requested")
        context.stopService(Intent(context, BridgeSessionService::class.java))
    }

    companion object {
        const val ACTION_EXIT = "org.techwithkaushik.termuxxwayland.action.EXIT_SESSION"
    }
}
