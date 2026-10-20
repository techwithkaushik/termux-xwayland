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
        val closed = AtomicBoolean(false)
        @Volatile var output: FileOutputStream? = null
        val writeLock = Any()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { Os.shutdown(hostEnd.fileDescriptor, OsConstants.SHUT_RDWR) }
            runCatching { hostEnd.close() }
        }
    }

    inner class BridgeBinder : Binder() {
        @Volatile
        internal var frameListener: ((RgbaFrameCodec.Frame) -> Unit)? = null

        internal fun dispatchFrame(payload: ByteArray) {
            val frame = RgbaFrameCodec.decode(payload)
            frameListener?.invoke(frame)
        }

        /** Sends validated, bounded pointer/key events back through the same Binder-transferred FD. */
        internal fun sendInput(payload: ByteArray) {
            require(payload.size in 1..128) { "Input event payload must be 1..128 bytes" }
            val session = synchronized(lock) { activeSession } ?: return
            val output = session.output ?: return
            synchronized(session.writeLock) {
                if (!session.closed.get()) BridgeProtocol.write(output, BridgeMessageKind.INPUT, payload)
            }
        }
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
            updateBinderState("connecting")

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

    private val binder = BridgeBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val remoteClient = intent?.extras?.getBundle(BridgeSessionReceiver.EXTRA_REMOTE_BUNDLE)
            ?.getBinder(BridgeSessionReceiver.EXTRA_REMOTE_CLIENT)
        if (remoteClient != null) {
            Thread({ openRemoteClientSession(remoteClient) }, "txw-open-remote-session").apply {
                isDaemon = true
                start()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Open a session by calling the Binder endpoint that Termux sent in its
     * package-targeted broadcast. This avoids binding a service from an
     * unregistered app_process ActivityThread.
     */
    private fun openRemoteClientSession(remoteClient: IBinder) {
        synchronized(lock) {
            if (activeSession != null) {
                Log.w(TAG, "Ignoring new Binder callback while a session is already active")
                return
            }
        }
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        var hostEnd: ParcelFileDescriptor? = null
        try {
            request.writeInterfaceToken(DESCRIPTOR)
            request.writeInt(PROTOCOL_VERSION)
            if (!remoteClient.transact(TRANSACTION_OPEN_SESSION, request, response, 0)) {
                throw IOException("Termux Binder endpoint rejected OPEN_SESSION")
            }
            response.readException()
            hostEnd = response.readParcelable(ParcelFileDescriptor::class.java.classLoader)
                ?: throw IOException("Termux Binder endpoint returned no session FD")
        } catch (error: Exception) {
            Log.e(TAG, "Could not open Termux-initiated Binder session", error)
            hostEnd?.close()
            return
        } finally {
            request.recycle()
            response.recycle()
        }

        val openedDescriptor = hostEnd ?: return
        val session = Session(openedDescriptor)
        synchronized(lock) {
            activeSession?.close()
            activeSession = session
        }
        updateBinderState("connecting")
        Log.i(TAG, "Termux-initiated Binder FD session opened")
        Thread({ serve(session) }, "txw-binder-session").apply {
            isDaemon = true
            start()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        synchronized(lock) {
            activeSession?.close()
            activeSession = null
        }
        updateBinderState("disconnected")
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
        session.output = output
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
                        synchronized(session.writeLock) { BridgeProtocol.write(output, BridgeMessageKind.PONG) }
                        updateBinderState("connected")
                    }
                    BridgeMessageKind.FRAME -> {
                        binder.dispatchFrame(message.payload)
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
            session.output = null
            session.close()
            synchronized(lock) {
                if (activeSession === session) {
                    activeSession = null
                    updateBinderState("disconnected")
                }
            }
        }
    }

    private fun updateBinderState(state: String) {
        getSharedPreferences("termux_binder_session", MODE_PRIVATE).edit()
            .putString("state", state)
            .putLong("updated_at", System.currentTimeMillis())
            .apply()
    }

    companion object {
        const val DESCRIPTOR = "org.techwithkaushik.termuxxwayland.IBridgeSession"
        const val TRANSACTION_OPEN_SESSION = IBinder.FIRST_CALL_TRANSACTION
        const val PROTOCOL_VERSION = 1
        const val TERMUX_PACKAGE = "com.termux"
        private const val TAG = "TermuxXWayland"
    }
}
