package org.techwithkaushik.termuxxwayland

import android.app.Service
import android.content.Intent
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.IBinder
import android.os.Process
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Started host-side abstract Unix socket server.
 *
 * No Binder interface is exposed. Termux connects directly using LocalSocket.
 * Cross-UID socket access is device-policy dependent and must be validated on
 * the target Android build.
 */
class BridgeSessionService : Service() {
    private val stopping = AtomicBoolean(false)
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var activeClient: LocalSocket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server == null && !stopping.get()) {
            Thread(::listen, "txw-abstract-socket-listener").apply {
                isDaemon = true
                start()
            }
        }
        return START_STICKY
    }

    private fun listen() {
        try {
            val socketServer = LocalServerSocket(SOCKET_NAME)
            server = socketServer
            Log.i(TAG, "Abstract socket server listening: @$SOCKET_NAME")
            while (!stopping.get()) {
                val client = socketServer.accept()
                if (stopping.get()) {
                    closeQuietly(client)
                    break
                }
                synchronized(this) {
                    closeQuietly(activeClient)
                    activeClient = client
                }
                Thread({ serve(client) }, "txw-abstract-session").apply {
                    isDaemon = true
                    start()
                }
            }
        } catch (error: IOException) {
            if (!stopping.get()) Log.e(TAG, "Abstract socket listener failed", error)
        } finally {
            closeQuietly(server)
            server = null
        }
    }

    private fun serve(client: LocalSocket) {
        try {
            val input = client.inputStream
            val output = client.outputStream
            val uid = Process.myUid()
            BridgeProtocol.write(output, BridgeMessageKind.WELCOME, byteArrayOf(
                (uid ushr 24).toByte(), (uid ushr 16).toByte(),
                (uid ushr 8).toByte(), uid.toByte()
            ))
            while (!stopping.get() && client.isConnected) {
                val message = BridgeProtocol.read(input)
                when (message.kind) {
                    BridgeMessageKind.HELLO -> {
                        // The current raw-socket migration is a transport prototype.
                        // Token authentication must be restored before production use.
                        Log.i(TAG, "Received TXWB HELLO")
                    }
                    BridgeMessageKind.PING -> {
                        if (message.payload.isNotEmpty()) throw IOException("PING payload must be empty")
                        BridgeProtocol.write(output, BridgeMessageKind.PONG)
                    }
                    BridgeMessageKind.FRAME -> {
                        // Frame reception is accepted; UI rendering is wired separately.
                        Log.d(TAG, "Received frame payload bytes=${message.payload.size}")
                    }
                    BridgeMessageKind.CLOSE -> {
                        if (message.payload.isNotEmpty()) throw IOException("CLOSE payload must be empty")
                        BridgeProtocol.write(output, BridgeMessageKind.CLOSE)
                        break
                    }
                    else -> throw IOException("Unexpected TXWB message kind ${message.kind}")
                }
            }
        } catch (error: IOException) {
            if (!stopping.get()) Log.w(TAG, "Socket session ended: ${error.message}")
        } finally {
            closeQuietly(client)
            synchronized(this) {
                if (activeClient === client) activeClient = null
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping.set(true)
        closeQuietly(activeClient)
        activeClient = null
        closeQuietly(server)
        server = null
        super.onDestroy()
    }

    private fun closeQuietly(closeable: Closeable?) {
        try { closeable?.close() } catch (_: IOException) {}
    }

    companion object {
        private const val TAG = "TermuxXWayland"
        private const val SOCKET_NAME = "termux-xwayland-control.sock"
    }
}
