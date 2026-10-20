package org.techwithkaushik.termuxxwayland

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Termux-authorized Binder endpoint for a persistent TXWB framed session.
 *
 * Binder transfers a connected socket FD to the real Termux UID. This avoids
 * cross-UID AF_UNIX connect permissions and never opens a TCP listener.
 * Rendering real Wayland frames is intentionally left to the compositor layer.
 */
class BridgeSessionService : Service() {
    private val lock = Any()
    private var activeSession: Session? = null

    private data class Session(val hostEnd: ParcelFileDescriptor) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { Os.shutdown(hostEnd.fileDescriptor, OsConstants.SHUT_RDWR) }
            runCatching { hostEnd.close() }
        }
    }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TRANSACTION_OPEN_SESSION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            enforceTermuxCaller()
            if (data.readInt() != PROTOCOL_VERSION) {
                reply?.writeException(IllegalArgumentException("Unsupported bridge protocol version"))
                return true
            }

            val pair = try {
                ParcelFileDescriptor.createSocketPair()
            } catch (error: IOException) {
                reply?.writeException(error)
                return true
            }
            val next = Session(pair[0])
            synchronized(lock) {
                activeSession?.close()
                activeSession = next
            }

            try {
                reply?.writeNoException()
                reply?.writeParcelable(pair[1], 0)
            } catch (error: RuntimeException) {
                synchronized(lock) {
                    if (activeSession === next) activeSession = null
                    next.close()
                }
                throw error
            } finally {
                // Binder Parcel owns a transferred FD reference after marshalling.
                runCatching { pair[1].close() }
            }
            Thread({ serve(next) }, "txw-binder-session").apply {
                isDaemon = true
                start()
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

    private fun serve(session: Session) {
        val input = FileInputStream(session.hostEnd.fileDescriptor)
        val output = FileOutputStream(session.hostEnd.fileDescriptor)
        try {
            val hostUid = Process.myUid()
            BridgeProtocol.write(
                output,
                BridgeMessageKind.WELCOME,
                byteArrayOf(
                    (hostUid ushr 24).toByte(),
                    (hostUid ushr 16).toByte(),
                    (hostUid ushr 8).toByte(),
                    hostUid.toByte()
                )
            )
            while (!session.closed.get()) {
                val message = BridgeProtocol.read(input)
                when (message.kind) {
                    BridgeMessageKind.PING -> {
                        if (message.payload.isNotEmpty()) throw IOException("PING payload must be empty")
                        BridgeProtocol.write(output, BridgeMessageKind.PONG)
                    }
                    BridgeMessageKind.CLOSE -> {
                        if (message.payload.isNotEmpty()) throw IOException("CLOSE payload must be empty")
                        BridgeProtocol.write(output, BridgeMessageKind.CLOSE)
                        break
                    }
                    BridgeMessageKind.INPUT,
                    BridgeMessageKind.RESIZE,
                    BridgeMessageKind.CLIPBOARD -> {
                        // Framing is accepted; routing to Android input/clipboard is not wired yet.
                        Log.d(TAG, "Received framed control message kind=${message.kind}")
                    }
                    else -> throw IOException("Unexpected message kind ${message.kind}")
                }
            }
        } catch (error: IOException) {
            if (!session.closed.get()) Log.i(TAG, "Bridge session ended: ${error.message}")
        } finally {
            session.close()
            synchronized(lock) {
                if (activeSession === session) activeSession = null
            }
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
