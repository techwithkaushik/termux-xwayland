package org.techwithkaushik.termuxxwayland

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Binder entry point for the Termux-side session client.
 *
 * The caller must be the installed Termux package. The service returns one end
 * of a local socket pair; the other end remains in this process. The socket is
 * deliberately transferred over Binder rather than relying on cross-UID
 * abstract/filesystem AF_UNIX connect permissions.
 *
 * The stream is not a general-purpose unauthenticated endpoint: a caller must
 * identify as com.termux, and every opened session is single-client and bounded
 * by the TXWB framing implementation.
 */
class BridgeSessionService : Service() {
    private val lock = Any()
    private var activeSession: Session? = null

    private data class Session(
        val hostEnd: ParcelFileDescriptor,
        val clientEnd: ParcelFileDescriptor,
        val closed: AtomicBoolean = AtomicBoolean(false)
    ) : Closeable {
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { hostEnd.close() }
            runCatching { clientEnd.close() }
        }
    }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TRANSACTION_OPEN_SESSION) {
                return super.onTransact(code, data, reply, flags)
            }
            data.enforceInterface(DESCRIPTOR)
            enforceTermuxCaller()
            val protocolVersion = data.readInt()
            if (protocolVersion != PROTOCOL_VERSION) {
                reply?.writeException(IllegalArgumentException("Unsupported bridge protocol version"))
                return true
            }
            val token = data.createByteArray()
            if (token == null || token.size != TOKEN_BYTES) {
                reply?.writeException(IllegalArgumentException("Session token must be 32 bytes"))
                return true
            }

            val pair = try {
                ParcelFileDescriptor.createSocketPair()
            } catch (error: IOException) {
                reply?.writeException(error)
                return true
            }
            val next = Session(hostEnd = pair[0], clientEnd = pair[1])
            synchronized(lock) {
                activeSession?.close()
                activeSession = next
            }

            reply?.writeNoException()
            reply?.writeParcelable(next.clientEnd, 0)
            Log.i(TAG, "Binder session opened by Termux uid=${Binder.getCallingUid()}")
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
        if (callingUid == Process.myUid() || packages.none { it == TERMUX_PACKAGE }) {
            Log.w(TAG, "Rejected Binder caller uid=$callingUid packages=${packages.joinToString()}")
            throw SecurityException("Only the installed Termux package may open a bridge session")
        }
        try {
            packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            throw SecurityException("Termux is not installed")
        }
    }

    companion object {
        const val DESCRIPTOR = "org.techwithkaushik.termuxxwayland.IBridgeSession"
        const val TRANSACTION_OPEN_SESSION = IBinder.FIRST_CALL_TRANSACTION
        const val PROTOCOL_VERSION = 1
        const val TOKEN_BYTES = 32
        const val TERMUX_PACKAGE = "com.termux"
        private const val TAG = "TermuxXWayland"
    }
}
