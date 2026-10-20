package org.techwithkaushik.termuxxwayland

import android.graphics.Bitmap
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct AF_UNIX abstract-socket endpoint. This deliberately does not use
 * bindService/Binder or require an installable companion application.
 */
internal class BridgeSocketServer(
    private val sessionId: String,
    private val expectedTermuxUid: Int,
    private val onFrame: (Bitmap) -> Unit,
    private val onState: (String) -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var client: LocalSocket? = null
    private val socketName = "termux_xwayland_$sessionId"

    fun start() {
        Thread({
            try {
                val listener = LocalServerSocket(socketName)
                server = listener
                onState("Direct socket ready; waiting for Termux client.")
                while (!closed.get()) {
                    val accepted = listener.accept()
                    client = accepted
                    try {
                        serve(accepted)
                    } catch (error: Exception) {
                        if (!closed.get()) onState("Direct socket session ended: ${error.message ?: error.javaClass.simpleName}")
                    } finally {
                        runCatching { accepted.close() }
                        if (client === accepted) client = null
                    }
                }
            } catch (error: Exception) {
                if (!closed.get()) onState("Direct socket unavailable: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                runCatching { server?.close() }
                server = null
            }
        }, "termux-xwayland-socket-server").apply {
            isDaemon = true
            start()
        }
    }

    private fun serve(socket: LocalSocket) {
        socket.soTimeout = 15000
        val input = socket.inputStream
        val output = socket.outputStream
        val hello = BridgeProtocol.read(input)
        val expected = sessionId.toByteArray(Charsets.US_ASCII)
        if (hello.kind != BridgeMessageKind.HELLO || !hello.payload.contentEquals(expected)) {
            throw SecurityException("Direct socket session authentication failed")
        }
        val peerUid = socket.peerCredentials.uid
        if (peerUid != expectedTermuxUid) {
            throw SecurityException("Rejected socket peer UID $peerUid; expected Termux UID $expectedTermuxUid")
        }
        // WELCOME carries the kernel-verified Termux peer UID.
        BridgeProtocol.write(output, BridgeMessageKind.WELCOME, ByteBuffer.allocate(4).putInt(peerUid).array())
        socket.soTimeout = 0
        onState("Direct AF_UNIX socket connected (Termux UID $peerUid).")
        while (!closed.get() && !socket.isClosed) {
            val message = BridgeProtocol.read(input)
            when (message.kind) {
                BridgeMessageKind.FRAME -> {
                    val frame = RgbaFrameCodec.decode(message.payload)
                    val bitmap = Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
                    onFrame(bitmap)
                }
                BridgeMessageKind.PING -> {
                    if (message.payload.isNotEmpty()) throw IOException("PING payload must be empty")
                    BridgeProtocol.write(output, BridgeMessageKind.PONG)
                }
                BridgeMessageKind.CLOSE -> {
                    BridgeProtocol.write(output, BridgeMessageKind.CLOSE)
                    return
                }
                else -> throw IOException("Unexpected bridge message kind: ${message.kind}")
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { client?.close() }
        runCatching { server?.close() }
    }
}
