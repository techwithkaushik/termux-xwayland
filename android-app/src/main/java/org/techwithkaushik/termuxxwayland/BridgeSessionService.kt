package org.techwithkaushik.termuxxwayland

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exported Binder entry point for the installed Termux process.
 *
 * Android's service manager authorizes access to this service; we additionally
 * verify that the calling UID owns the real com.termux package. The service
 * hands one connected socket FD to the client through Binder, avoiding a
 * cross-UID AF_UNIX connect and avoiding a TCP listener.
 *
 * This is the transport/session primitive only. TXWB frame parsing, Wayland
 * frame production and input forwarding are separate layers.
 */
class BridgeSessionService : Service() {
    private val lock = Any()
    private var activeSession: Session? = null

    private data class Session(val hostEnd: ParcelFileDescriptor) : Closeable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) runCatching { hostEnd.close() }
        }
    }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TRANSACTION_OPEN_SESSION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            enforceTermuxCaller()
            val version = data.readInt()
            if (version != PROTOCOL_VERSION) {
                reply?.writeException(IllegalArgumentException("Unsupported bridge protocol version"))
                return true
            }

            val pair = try {
                ParcelFileDescriptor.createSocketPair()
            } catch (error: IOException) {
                reply?.writeException(error)
                return true
            }
            val hostEnd = pair[0]
            val clientEnd = pair[1]
            synchronized(lock) {
                activeSession?.close()
                activeSession = Session(hostEnd)
            }

            try {
                reply?.writeNoException()
                reply?.writeParcelable(clientEnd, 0)
            } catch (error: RuntimeException) {
                synchronized(lock) {
                    activeSession?.close()
                    activeSession = null
                }
                throw error
            } finally {
                // Binder Parcel has taken its own FD reference during writeParcelable.
                runCatching { clientEnd.close() }
            }
            Log.i(TAG, "Authorized Termux Binder session opened")
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        synchronized(lock) {
            activeSession?.close()
            activeSession = null
        }
        super.onDestroy()
    }

    private fun enforceTermuxCaller() {
        val callingUid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(callingUid).orEmpty()
        if (callingUid == Process.myUid() || TERMUX_PACKAGE !in packages) {
            Log.w(TAG, "Rejected Binder caller uid=$callingUid packages=${packages.joinToString()}")
            throw SecurityException("Only the installed Termux package may open a bridge session")
        }
    }

    companion object {
        const val DESCRIPTOR = "org.techwithkaushik.termuxxwayland.IBridgeSession"
        const val TRANSACTION_OPEN_SESSION = IBinder.FIRST_CALL_TRANSACTION
        const val PROTOCOL_VERSION = 1
        const val TERMUX_PACKAGE = "com.termux"
        private const val TAG = "TermuxXWayland"
    }
}
