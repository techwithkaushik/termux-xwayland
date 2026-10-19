package org.techwithkaushik.termuxxwayland.companion

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.EOFException
import java.io.IOException
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Minimal capability probe only. This is not yet a Termux bridge or frame transport.
 *
 * The manifest's signature-level permission gates binding. The transaction also checks
 * that the caller and service are signed with the same certificate and bounds payloads
 * before allocating a byte array.
 */
class IpcProbeService : Service() {
    private val probeBinder = object : Binder() {
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int
        ): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(DESCRIPTOR)
                return true
            }
            if (code != TRANSACTION_ECHO && code != TRANSACTION_FD_ECHO && code != TRANSACTION_PING && code != TRANSACTION_STREAM) return super.onTransact(code, data, reply, flags)
            if (reply == null) return false

            return try {
                data.enforceInterface(DESCRIPTOR)
                val callerUid = Binder.getCallingUid()
                if (packageManager.checkSignatures(callerUid, Process.myUid()) !=
                    PackageManager.SIGNATURE_MATCH
                ) {
                    throw SecurityException("Binder probe caller signature is not trusted")
                }

                if (code == TRANSACTION_STREAM) {
                    val inputFd = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                    val outputFd = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                    Thread({ runStreamEcho(inputFd, outputFd) }, "binder-stream-echo").start()
                    reply.writeNoException()
                    reply.writeInt(Process.myUid())
                    reply.writeInt(callerUid)
                    reply.writeInt(Build.VERSION.SDK_INT)
                    true
                } else if (code == TRANSACTION_PING) {
                    val nonce = data.readLong()
                    reply.writeNoException()
                    reply.writeInt(Process.myUid())
                    reply.writeInt(callerUid)
                    reply.writeInt(Build.VERSION.SDK_INT)
                    reply.writeLong(nonce)
                    reply.writeLong(android.os.SystemClock.elapsedRealtime())
                    true
                } else if (code == TRANSACTION_ECHO) {
                    val payloadSize = data.readInt()
                    if (payloadSize !in 0..MAX_PAYLOAD_BYTES) {
                        throw IllegalArgumentException(
                            "Payload must be between 0 and $MAX_PAYLOAD_BYTES bytes"
                        )
                    }
                    val payload = ByteArray(payloadSize)
                    data.readByteArray(payload)

                    reply.writeNoException()
                    reply.writeInt(Process.myUid())
                    reply.writeInt(callerUid)
                    reply.writeInt(Build.VERSION.SDK_INT)
                    reply.writeInt(payload.size)
                    reply.writeByteArray(payload)
                    payload.fill(0)
                    true
                } else {
                    val payloadSize = data.readInt()
                    if (payloadSize !in 0..MAX_PAYLOAD_BYTES) {
                        throw IllegalArgumentException(
                            "FD payload must be between 0 and $MAX_PAYLOAD_BYTES bytes"
                        )
                    }
                    val inputFd = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                    val outputFd = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                    val payload = ByteArray(payloadSize)
                    try {
                        ParcelFileDescriptor.AutoCloseInputStream(inputFd).use { input ->
                            readFully(input, payload)
                        }
                        ParcelFileDescriptor.AutoCloseOutputStream(outputFd).use { output ->
                            output.write(payload)
                            output.flush()
                        }
                        reply.writeNoException()
                        reply.writeInt(Process.myUid())
                        reply.writeInt(callerUid)
                        reply.writeInt(Build.VERSION.SDK_INT)
                        reply.writeInt(payload.size)
                        true
                    } finally {
                        payload.fill(0)
                    }
                }
            } catch (error: Exception) {
                reply.writeException(error)
                true
            }
        }
    }

    private fun runStreamEcho(inputFd: ParcelFileDescriptor, outputFd: ParcelFileDescriptor) {
        try {
            DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(inputFd)).use { input ->
                DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(outputFd)).use { output ->
                    while (true) {
                        val size = try { input.readInt() } catch (_: EOFException) { break }
                        if (size !in 0..MAX_STREAM_FRAME_BYTES) throw IOException("Invalid stream frame size: $size")
                        val payload = ByteArray(size)
                        try {
                            input.readFully(payload)
                            output.writeInt(payload.size)
                            output.write(payload)
                            output.flush()
                        } finally {
                            payload.fill(0)
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // Closing either pipe ends this diagnostic stream; never keep a dead session alive.
        } finally {
            try { inputFd.close() } catch (_: Exception) { }
            try { outputFd.close() } catch (_: Exception) { }
        }
    }

    private fun readFully(input: java.io.InputStream, payload: ByteArray) {
        var offset = 0
        while (offset < payload.size) {
            val count = input.read(payload, offset, payload.size - offset)
            if (count < 0) throw EOFException("FD input closed after $offset of ${payload.size} bytes")
            if (count == 0) continue
            offset += count
        }
        if (input.read() != -1) throw IOException("FD input contains trailing bytes")
    }

    override fun onBind(intent: Intent?): IBinder = probeBinder

    companion object {
        private const val DESCRIPTOR = "org.techwithkaushik.termuxxwayland.ipc.PROBE"
        private const val TRANSACTION_ECHO = IBinder.FIRST_CALL_TRANSACTION + 17
        private const val TRANSACTION_FD_ECHO = IBinder.FIRST_CALL_TRANSACTION + 18
        private const val TRANSACTION_PING = IBinder.FIRST_CALL_TRANSACTION + 19
        private const val TRANSACTION_STREAM = IBinder.FIRST_CALL_TRANSACTION + 20
        private const val MAX_STREAM_FRAME_BYTES = 64 * 1024
        private const val MAX_PAYLOAD_BYTES = 64 * 1024
    }
}
