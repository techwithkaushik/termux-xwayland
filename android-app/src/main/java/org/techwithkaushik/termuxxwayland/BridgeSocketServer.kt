package org.techwithkaushik.termuxxwayland


private fun RgbaFrameCodec.Frame.toArgbPixels(): IntArray {
    val result = IntArray(width * height)
    var source = 0
    for (index in result.indices) {
        val red = pixels[source++].toInt() and 0xff
        val green = pixels[source++].toInt() and 0xff
        val blue = pixels[source++].toInt() and 0xff
        val alpha = pixels[source++].toInt() and 0xff
        result[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
    return result
}

import android.graphics.Bitmap
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Authenticated loopback TCP endpoint for the Termux client.
 * Bound only to 127.0.0.1; never exposed on LAN interfaces and does not
 * require a separately installed companion app. Session token authenticates HELLO.
 */
internal class BridgeSocketServer(
    private val sessionId: String,
    private val expectedTermuxUid: Int,
    private val onFrame: (Bitmap) -> Unit,
    private val onState: (String) -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null

    companion object {
        const val PORT = 38473
    }

    fun start() {
        Thread({
            try {
                val listener = ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"))
                server = listener
                onState("Authenticated loopback bridge ready at 127.0.0.1:$PORT.")
                while (!closed.get()) {
                    val accepted = listener.accept()
                    client = accepted
                    try {
                        serve(accepted)
                    } catch (error: Exception) {
                        if (!closed.get()) onState("Loopback bridge session ended: ${error.message ?: error.javaClass.simpleName}")
                    } finally {
                        runCatching { accepted.close() }
                        if (client === accepted) client = null
                    }
                }
            } catch (error: Exception) {
                if (!closed.get()) onState("Loopback bridge unavailable: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                runCatching { server?.close() }
                server = null
            }
        }, "termux-xwayland-loopback-server").apply {
            isDaemon = true
            start()
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 15000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val hello = BridgeProtocol.read(input)
        val expected = sessionId.toByteArray(Charsets.US_ASCII)
        if (hello.kind != BridgeMessageKind.HELLO || !hello.payload.contentEquals(expected)) {
            throw SecurityException("Loopback session authentication failed")
        }
        // This is an authenticated loopback TCP session; Android does not expose
        // kernel peer credentials for TCP, so never describe it as UID-verified.
        BridgeProtocol.write(output, BridgeMessageKind.WELCOME, ByteBuffer.allocate(4).putInt(expectedTermuxUid).array())
        socket.soTimeout = 0
        onState("Authenticated loopback TCP connected (127.0.0.1:$PORT).")
        while (!closed.get() && !socket.isClosed) {
            val message = BridgeProtocol.read(input)
            when (message.kind) {
                BridgeMessageKind.FRAME -> {
                    val frame = RgbaFrameCodec.decode(message.payload)
                    val bitmap = Bitmap.createBitmap(frame.toArgbPixels(), frame.width, frame.height, Bitmap.Config.ARGB_8888)
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
