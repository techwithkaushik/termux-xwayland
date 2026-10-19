package org.techwithkaushik.termuxxwayland.companion

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process

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
            if (code != TRANSACTION_ECHO) return super.onTransact(code, data, reply, flags)
            if (reply == null) return false

            return try {
                data.enforceInterface(DESCRIPTOR)
                val callerUid = Binder.getCallingUid()
                if (packageManager.checkSignatures(callerUid, Process.myUid()) !=
                    PackageManager.SIGNATURE_MATCH
                ) {
                    throw SecurityException("Binder probe caller signature is not trusted")
                }

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
                true
            } catch (error: Exception) {
                reply.writeException(error)
                true
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = probeBinder

    companion object {
        private const val DESCRIPTOR = "org.techwithkaushik.termuxxwayland.ipc.PROBE"
        private const val TRANSACTION_ECHO = IBinder.FIRST_CALL_TRANSACTION + 17
        private const val MAX_PAYLOAD_BYTES = 64 * 1024
    }
}
