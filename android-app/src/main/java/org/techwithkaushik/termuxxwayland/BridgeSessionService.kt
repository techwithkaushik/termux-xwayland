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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Termux-authorized Binder endpoint for the persistent framed session.
 *
 * Binder transfers a connected socket FD to the real Termux UID. This avoids
 * cross-UID AF_UNIX connect permissions and never opens a TCP listener.
 * TXWB framing is bounded; rendering real Wayland frames is a separate layer.
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
            val hostEnd = pair[0]
            val clientEnd = pair[1]
            val next = Session(hostEnd)
            synchronized(lock) {
                activeSession?.close()
                activeSession = next
            }

            try {
                reply?.writeNoException()
                reply?.writeParcelable(clientEnd, 0)
            } catch (error: RuntimeException) {
                synchronized(lock) {
                    if (activeSession === next) activeSession = null
                    next.close()
                }
                throw error
            } finally {
                // Binder Parcel owns a transferred FD reference after marshalling.
                runCatching { clientEnd.close() }
            }
            Thread({ serve(next) }, "txw-binder-session").apply { isDaemon = true; start() }
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
        val input = DataInputStream(FileInputStream(session.hostEnd.fileDescriptor))
        val output = DataOutputStream(FileOutputStream(session.hostEnd.fileDescriptor))
        try {
            writeMessage(output, KIND_WELCOME, Process.myUid().toUIntBytes())
            while (!session.closed.get()) {
                val message = readMessage(input)
                when (message.kind) {
                    KIND_PING -> {
                        if (message.payload.isNotEmpty()) throw IOException("PING payload must be empty")
                        writeMessage(output, KIND_PONG, byteArrayOf())
                    }
                    KIND_CLOSE -> {
                        if (message.payload.isNotEmpty()) throw IOException("CLOSE payload must be empty")
                        writeMessage(output, KIND_CLOSE, byteArrayOf())
                        break
                    }
                    KIND_INPUT, KIND_RESIZE, KIND_CLIPBOARD -> {
                        // Accepted framing; routing to Android input/clipboard is a later layer.
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

    private data class Frame(val kind: Int, val payload: ByteArray)

    private fun readMessage(input: DataInputStream): Frame {
        val header = ByteArray(HEADER_SIZE)
        input.readFully(header)
        if (!header.copyOfRange(0, 4).contentEquals(MAGIC)) throw IOException("Invalid TXWB magic")
        if (header[4].toInt() and 0xff != PROTOCOL_VERSION) throw IOException("Unsupported TXWB version")
        val kind = header[5].toInt() and 0xff
        if (kind !in VALID_KINDS) throw IOException("Unknown TXWB message kind")
        if (header[6].toInt() != 0 || header[7].toInt() != 0) throw IOException("Reserved TXWB bits must be zero")
        val length = ((header[8].toLong() and 0xff) shl 24) or
            ((header[9].toLong() and 0xff) shl 16) or
            ((header[10].toLong() and 0xff) shl 8) or
            (header[11].toLong() and 0xff)
        if (length > MAX_PAYLOAD_BYTES) throw IOException("TXWB payload exceeds limit")
        val payload = ByteArray(length.toInt())
        input.readFully(payload)
        return Frame(kind, payload)
    }

    private fun writeMessage(output: DataOutputStream, kind: Int, payload: ByteArray) {
        require(payload.size <= MAX_PAYLOAD_BYTES) { "TXWB payload exceeds limit" }
        output.write(MAGIC)
        output.writeByte(PROTOCOL_VERSION)
        output.writeByte(kind)
        output.writeShort(0)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    private fun Int.toUIntBytes(): ByteArray = byteArrayOf(
        (this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), this.toByte()
    )

    companion object {
        const val DESCRIPTOR = "org.techwithkaushik.termuxxwayland.IBridgeSession"
        const val TRANSACTION_OPEN_SESSION = IBinder.FIRST_CALL_TRANSACTION
        const val PROTOCOL_VERSION = 1
        const val TERMUX_PACKAGE = "com.termux"
        private const val TAG = "TermuxXWayland"
        private const val HEADER_SIZE = 12
        private const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
        private val MAGIC = byteArrayOf(0x54, 0x58, 0x57, 0x42)
        private const val KIND_WELCOME = 2
        private const val KIND_INPUT = 4
        private const val KIND_RESIZE = 5
        private const val KIND_CLIPBOARD = 6
        private const val KIND_PING = 7
        private const val KIND_PONG = 8
        private const val KIND_CLOSE = 9
        private val VALID_KINDS = setOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
    }
}
