package org.techwithkaushik.termuxxwayland

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.LocalSocket
import android.net.LocalServerSocket
import android.net.LocalSocketAddress
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** Android endpoint for the authenticated abstract Unix socket smoke test. */
class MainActivity : Activity() {
    private val ink = Color.rgb(22, 32, 48)
    private val muted = Color.rgb(99, 113, 132)
    private val blue = Color.rgb(37, 99, 235)
    private val pale = Color.rgb(239, 246, 255)
    private val page = Color.rgb(246, 248, 252)
    private val SOCKET_PREFIX = "termux_xwayland_"
    private lateinit var tokenInput: EditText
    private lateinit var statusTitle: TextView
    private lateinit var statusDescription: TextView
    private lateinit var previewImage: ImageView
    private lateinit var connectButton: Button
    @Volatile private var activeSocket: LocalSocket? = null
    @Volatile private var connecting = false
    private lateinit var tcpProbeTitle: TextView
    private lateinit var tcpProbeDescription: TextView
    private lateinit var tcpProbeButton: Button
    @Volatile private var tcpProbeRunning = false
    private lateinit var framedProbeTitle: TextView
    private lateinit var framedProbeDescription: TextView
    private lateinit var framedProbeButton: Button
    @Volatile private var framedProbeRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ink
        window.navigationBarColor = ink
        setContentView(buildScreen())
        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "termuxxwayland" || data.host != "start") return

        statusTitle.text = "Termux launch request received"
        statusDescription.text =
            "The Termux CLI opened this host successfully. This confirms the launch request only; authorized Termux-to-host IPC and live desktop streaming are not connected yet."
        Log.i("TermuxXWayland", "Termux host launch intent received")
    }

    private fun buildScreen(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(page) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        scroll.addView(content)
        content.addView(label("TERMUX • WAYLAND", 12, blue, true))
        content.addView(label("Your Linux desktop,\non Android", 29, ink, true).apply {
            setPadding(0, dp(8), 0, dp(8))
        })
        content.addView(label(
            "Android display host for a Termux-installed bridge. Live IPC and desktop streaming are not connected yet.",
            15, muted, false
        ))

        val status = card()
        status.addView(label("BRIDGE STATUS", 11, muted, true))
        statusTitle = label("Not connected", 21, ink, true).apply {
            setPadding(0, dp(6), 0, dp(4))
        }
        statusDescription = label(
            "No bridge session is active. The app can render a synthetic test pattern; desktop compositor integration is not implemented yet.",
            13, muted, false
        )
        status.addView(statusTitle)
        status.addView(statusDescription)
        previewImage = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Synthetic bridge test frame"
            setBackgroundColor(Color.rgb(20, 29, 43))
        }
        status.addView(previewImage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(180)
        ).apply { topMargin = dp(12) })
        content.addView(status, margins(top = 22, bottom = 16))

        content.addView(label("Connect to Termux", 20, ink, true).apply {
            setPadding(0, dp(6), 0, dp(8))
        })
        content.addView(infoCard(
            "Native local socket",
            "Uses an Android abstract AF_UNIX socket. No localhost address, port, Wi-Fi or internet connection is required."
        ), margins(bottom = 10))
        tokenInput = field("Session token (64 hex characters)", "", secret = true)
        content.addView(tokenInput, margins(bottom = 8))
        connectButton = actionButton("Connect securely") { connectOrDisconnect() }
        content.addView(connectButton, margins(top = 4, bottom = 8))

        val commands = "pkg update\npkg install rust clang pkg-config"
        content.addView(label("Prepare Termux", 18, ink, true).apply {
            setPadding(0, dp(12), 0, dp(8))
        })
        content.addView(label(
            "Build the native bridge binary in the repository first. The current server is a handshake smoke test, not a compositor.",
            14, muted, false
        ))
        content.addView(codeCard(commands), margins(top = 12, bottom = 8))
        content.addView(actionButton("Copy environment commands") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Termux commands", commands))
            Toast.makeText(this, "Commands copied", Toast.LENGTH_SHORT).show()
        }, margins(bottom = 8))
        content.addView(infoCard(
            "What this test proves",
            "No active Termux bridge session is connected. A successful diagnostic handshake is not a desktop stream; real Wayland frames, compositor integration, input forwarding and XWayland are still pending."
        ), margins(top = 8, bottom = 16))
        content.addView(infoCard(
            "IPC status",
            "The old separate-companion Binder probes have been retired. The supported design is a Termux-installed bridge package; no live Termux-to-host session or desktop frame stream is connected yet."
        ), margins(top = 8, bottom = 16))

        content.addView(label("Temporary TCP loopback probe", 20, ink, true).apply {
            setPadding(0, dp(8), 0, dp(8))
        })
        content.addView(infoCard(
            "Diagnostic only • 127.0.0.1:38471",
            "Checks whether this Android app can reach a one-shot TCP echo server running inside Termux on the same phone. This is not enabled as a bridge fallback and does not send desktop frames."
        ), margins(bottom = 10))
        tcpProbeTitle = label("Not tested", 17, ink, true).apply {
            setPadding(0, dp(4), 0, dp(4))
        }
        tcpProbeDescription = label(
            "Start the repository's TCP probe server in Termux, then tap the button below.",
            13, muted, false
        )
        content.addView(tcpProbeTitle)
        content.addView(tcpProbeDescription, margins(bottom = 8))
        tcpProbeButton = actionButton("Test Termux TCP connection") { runTcpProbe() }
        content.addView(tcpProbeButton, margins(bottom = 10))
        content.addView(infoCard(
            "TXWB v1 framing diagnostic • 127.0.0.1:38472",
            "Uses the existing 12-byte TXWB header and checks magic, version, reserved bits, message type, payload length and exact PING/PONG payload. One-shot test only; not a production transport."
        ), margins(bottom = 10))
        framedProbeTitle = label("Not tested", 17, ink, true).apply {
            setPadding(0, dp(4), 0, dp(4))
        }
        framedProbeDescription = label(
            "Checks the existing TXWB v1 12-byte frame header, PING/PONG message types, payload length, and exact payload echo. Diagnostic only.",
            13, muted, false
        )
        content.addView(framedProbeTitle)
        content.addView(framedProbeDescription, margins(bottom = 8))
        framedProbeButton = actionButton("Test framed TXWB protocol") { runFramedProbe() }
        content.addView(framedProbeButton, margins(bottom = 16))

        content.addView(label("Rust JNI socket reader", 20, ink, true).apply {
            setPadding(0, dp(8), 0, dp(8))
        })
        content.addView(infoCard(
            "Native-library smoke test • app-local only",
            "Creates a temporary local Unix socket, passes its connected file descriptor to Rust, and verifies native reading/logging. This validates JNI packaging and FD ownership only; it does not connect to Termux or parse Wayland."
        ), margins(bottom = 10))
        content.addView(actionButton("Run Rust JNI socket test") { runNativeJniProbe() },
            margins(bottom = 16))

        content.addView(label("Termux filesystem socket access", 20, ink, true).apply {
            setPadding(0, dp(8), 0, dp(8))
        })
        content.addView(infoCard(
            "Cross-app access diagnostic",
            "Tries one connection to the Termux control-socket path without sending data. This reports path/permission errors only; it does not change permissions or bypass Android sandboxing."
        ), margins(bottom = 10))
        content.addView(actionButton("Probe Termux control socket") {
            runTermuxFilesystemSocketProbe()
        }, margins(bottom = 16))

        content.addView(label("Planned capabilities", 18, ink, true).apply {
            setPadding(0, dp(8), 0, dp(8))
        })
        content.addView(label(
            "• Termux-managed sessions\n• Native Wayland applications\n• XFCE through XWayland\n• LXQt session option\n• ARM32 and ARM64 builds\n• Android 10+ compatibility target",
            14, muted, false
        ))
        content.addView(actionButton("Termux:X11 source reference") {
            openUrl("https://github.com/termux/termux-x11")
        }, margins(top = 18, bottom = 8))
        content.addView(actionButton("Wayland prototype reference") {
            openUrl("https://github.com/J-x-Z/termux-wayland")
        }, margins(bottom = 8))
        content.addView(label("Development build • 0.1.0-alpha02", 12, muted, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })
        return scroll
    }

    /**
     * App-local smoke test for the Rust JNI FD handoff. It deliberately uses
     * a separate socket so it cannot consume frames from the active bridge.
     */
    private fun runTermuxFilesystemSocketProbe() {
        val socketPath = "/data/data/com.termux/files/usr/tmp/termux-xwayland-control.sock"
        Log.i("TermuxXWayland", "Filesystem socket probe requested")
        Toast.makeText(this, "Checking Termux socket path…", Toast.LENGTH_SHORT).show()
        Thread({
            val result = try {
                NativeBridge.nativeProbeUnixSocket(socketPath)
            } catch (error: Throwable) {
                Log.e("TermuxXWayland", "Filesystem socket probe crashed", error)
                Int.MIN_VALUE
            }
            val message = when (result) {
                0 -> "Connected: Termux control socket is reachable"
                -2 -> "ENOENT: socket path does not exist yet"
                -13 -> "EACCES: Android denied cross-app socket access"
                -1 -> "EPERM: operation not permitted"
                -111 -> "ECONNREFUSED: no listener at that socket"
                Int.MIN_VALUE -> "JNI probe failed; see logcat"
                else -> "Socket probe returned $result (negative errno); see logcat"
            }
            Log.i("TermuxXWayland", "Filesystem socket probe result=$result; $message")
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }, "termux-filesystem-socket-probe").start()
    }

    private fun runNativeJniProbe() {
        Log.i("TermuxXWayland", "JNI probe button tapped")
        Toast.makeText(this, "Starting Rust JNI socket test…", Toast.LENGTH_SHORT).show()
        Thread({
            Log.i("TermuxXWayland", "JNI probe worker started")
            var server: LocalServerSocket? = null
            var client: LocalSocket? = null
            var accepted: LocalSocket? = null
            try {
                val name = "txw_jni_${Process.myPid()}_${System.nanoTime()}"
                Log.i("TermuxXWayland", "Creating app-local server socket")
                server = LocalServerSocket(name)
                Log.i("TermuxXWayland", "Server ready; connecting client socket")
                client = LocalSocket()
                client.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                Log.i("TermuxXWayland", "Client connected; accepting server socket")
                accepted = server.accept()
                Log.i("TermuxXWayland", "Server accepted client; writing probe bytes")

                val writer = Thread({
                    try {
                        Log.i("TermuxXWayland", "Writing six probe bytes")
                        client?.outputStream?.write(byteArrayOf(0x54, 0x58, 0x57, 0x42, 0x01, 0x02))
                        client?.outputStream?.flush()
                        Log.i("TermuxXWayland", "Probe bytes written; shutting down client output")
                        client?.shutdownOutput()
                        Log.i("TermuxXWayland", "Client output shutdown complete")
                    } catch (error: Throwable) {
                        Log.e("TermuxXWayland", "Probe writer failed", error)
                    }
                }, "rust-jni-probe-writer")
                writer.start()

                val fd = accepted.fileDescriptor
                    ?: throw IOException("Connected diagnostic socket has no file descriptor")
                Log.i("TermuxXWayland", "Local socket connected; invoking native reader")
                val result = NativeBridge.nativeReadSocket(fd)
                Log.i("TermuxXWayland", "Native reader returned $result")
                writer.join(2000)
                if (writer.isAlive) Log.w("TermuxXWayland", "Probe writer did not finish within 2 seconds")
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (result == 0) {
                            "PASS: Rust JNI read the test socket; see logcat tag TermuxXWayland"
                        } else {
                            "FAIL: Rust JNI socket reader returned $result"
                        },
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (error: Throwable) {
                Log.e("TermuxXWayland", "JNI probe failed", error)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Rust JNI test failed: ${error.message ?: error.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                try { accepted?.close() } catch (_: Exception) { }
                try { client?.close() } catch (_: Exception) { }
                try { server?.close() } catch (_: Exception) { }
            }
        }, "rust-jni-socket-probe").start()
    }

    private fun connectOrDisconnect() {
        if (activeSocket != null) {
            disconnectSession()
            return
        }
        if (connecting) return

        val token = try {
            BridgeProtocol.parseToken(tokenInput.text.toString().trim())
        } catch (error: IllegalArgumentException) {
            showError(error.message ?: "Invalid session token")
            return
        }
        val socketName = socketNameForToken(token)
        connecting = true
        connectButton.isEnabled = false
        statusTitle.text = "Connecting…"
        statusDescription.text = "Connecting to the local abstract Unix socket and checking the session token."
        Thread({
            var candidate: LocalSocket? = null
            try {
                candidate = LocalSocket()
                try {
                    candidate.connect(LocalSocketAddress(
                        socketName, LocalSocketAddress.Namespace.ABSTRACT
                    ))
                } catch (error: IOException) {
                    if (error.message?.contains("Permission denied", ignoreCase = true) == true) {
                        throw IllegalStateException(
                            "Android blocked the cross-app AF_UNIX connection (EACCES). " +
                                "On Android 10 this is commonly SELinux per-app isolation; " +
                                "chmod cannot fix an abstract socket. A shared-UID build " +
                                "signed with the same key as Termux, or a Termux-mediated IPC bridge, is required.",
                            error
                        )
                    }
                    throw error
                }
                candidate.setSoTimeout(15000)
                BridgeProtocol.write(candidate.getOutputStream(), BridgeMessageKind.HELLO, token)
                token.fill(0)
                val response = BridgeProtocol.read(candidate.getInputStream())
                if (response.kind != BridgeMessageKind.WELCOME || response.payload.size != 4) {
                    throw IllegalStateException("Bridge handshake did not include a valid Termux UID")
                }
                val termuxUid = ByteBuffer.wrap(response.payload).int.toLong() and 0xffffffffL
                val peerCredentials = candidate.peerCredentials
                val kernelPeerUid = peerCredentials.uid.toLong()
                if (kernelPeerUid < 0 || kernelPeerUid != termuxUid) {
                    throw IllegalStateException(
                        "Socket peer UID mismatch: kernel=$kernelPeerUid, bridge=$termuxUid"
                    )
                }
                val androidUid = Process.myUid().toLong()
                val frameMessage = BridgeProtocol.read(candidate.getInputStream())
                if (frameMessage.kind != BridgeMessageKind.FRAME) {
                    throw IllegalStateException("Expected a test frame after authentication")
                }
                val testFrame = decodeTestFrame(frameMessage.payload)

                // A successful authenticated handshake proves Android -> Termux;
                // PING/PONG verifies traffic in both directions over this socket.
                val pingStartedAt = System.nanoTime()
                BridgeProtocol.write(candidate.getOutputStream(), BridgeMessageKind.PING)
                val pong = BridgeProtocol.read(candidate.getInputStream())
                if (pong.kind != BridgeMessageKind.PONG || pong.payload.isNotEmpty()) {
                    throw IllegalStateException("Termux bridge did not answer the PING with PONG")
                }
                val roundTripMs = (System.nanoTime() - pingStartedAt) / 1_000_000.0
                // Keep the authenticated connection open for later FRAME messages.
                candidate.setSoTimeout(0)
                val sessionSocket = candidate
                activeSocket = sessionSocket
                candidate = null
                runOnUiThread {
                    connecting = false
                    connectButton.isEnabled = true
                    connectButton.text = "Disconnect"
                    tokenInput.text.clear()
                    statusTitle.text = if (termuxUid != androidUid) {
                        "Connected • waiting for frames"
                    } else {
                        "Connected (same UID)"
                    }
                    previewImage.setImageBitmap(testFrame)
                    previewImage.visibility = View.VISIBLE
                    statusDescription.text = String.format(
                        java.util.Locale.US,
                        "Termux peer UID: %d (kernel-verified) • Android app UID: %d\nPING/PONG round trip: %.2f ms\nInitial frame received. Waiting for subsequent FRAME messages; the current bridge still sends only a synthetic frame.",
                        kernelPeerUid, androidUid, roundTripMs
                    )
                }
                Thread({
                    try {
                        while (!sessionSocket.isClosed && activeSocket === sessionSocket) {
                            val message = BridgeProtocol.read(sessionSocket.getInputStream())
                            when (message.kind) {
                                BridgeMessageKind.FRAME -> {
                                    val bitmap = decodeTestFrame(message.payload)
                                    runOnUiThread {
                                        if (activeSocket === sessionSocket) {
                                            previewImage.setImageBitmap(bitmap)
                                            statusTitle.text = "Frame received"
                                            statusDescription.text =
                                                "Receiving bridge frames. Desktop capture is not yet wired into the Termux server."
                                        } else {
                                            bitmap.recycle()
                                        }
                                    }
                                }
                                BridgeMessageKind.PONG -> {
                                    if (message.payload.isNotEmpty()) {
                                        throw IOException("Invalid PONG payload")
                                    }
                                }
                                BridgeMessageKind.CLOSE -> break
                                else -> throw IOException("Unexpected message while receiving frames: ${message.kind}")
                            }
                        }
                    } catch (error: Exception) {
                        if (activeSocket === sessionSocket && !sessionSocket.isClosed) {
                            runOnUiThread {
                                if (activeSocket === sessionSocket) {
                                    statusTitle.text = "Stream stopped"
                                    statusDescription.text = error.message ?: "Bridge frame stream ended"
                                }
                            }
                        }
                    }
                }, "bridge-frame-reader").start()
            } catch (error: Exception) {
                token.fill(0)
                candidate?.close()
                runOnUiThread {
                    connecting = false
                    connectButton.isEnabled = true
                    statusTitle.text = "Connection failed"
                    statusDescription.text = error.message ?: "Could not connect to the bridge"
                }
            }
        }, "bridge-connect").start()
    }

    private fun disconnectSession() {
        val socket = activeSocket
        activeSocket = null
        try {
            if (socket != null && !socket.isClosed) {
                BridgeProtocol.write(socket.getOutputStream(), BridgeMessageKind.CLOSE)
                socket.close()
            }
        } catch (_: Exception) {
            try { socket?.close() } catch (_: Exception) { }
        }
        connectButton.text = "Connect securely"
        previewImage.setImageDrawable(null)
        previewImage.visibility = View.GONE
        statusTitle.text = "Not connected"
        statusDescription.text = "Bridge session closed."
    }






    private fun runTcpProbe() {
        if (tcpProbeRunning) return
        tcpProbeRunning = true
        tcpProbeButton.isEnabled = false
        tcpProbeTitle.text = "Connecting to 127.0.0.1:38471…"
        tcpProbeDescription.text = "Testing a loopback TCP echo from Android host to Termux."
        Thread({
            val result = try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", 38471), 3000)
                    socket.soTimeout = 3000
                    val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    writer.write("TERMUX_XWAYLAND_TCP_PROBE\n")
                    writer.flush()
                    val reply = reader.readLine()
                    if (reply != "PASS TERMUX_XWAYLAND_TCP_PROBE") {
                        throw IOException("Unexpected TCP echo response: ${reply ?: "connection closed"}")
                    }
                    "PASS • Android host connected to Termux on 127.0.0.1:38471 and received the exact echo. This is a one-shot connectivity test only."
                }
            } catch (error: Exception) {
                "FAIL • ${error.message ?: error.javaClass.simpleName}. Ensure the Termux probe server is running on 127.0.0.1:38471."
            }
            runOnUiThread {
                tcpProbeRunning = false
                tcpProbeButton.isEnabled = true
                tcpProbeTitle.text = if (result.startsWith("PASS")) "TCP loopback passed" else "TCP loopback failed"
                tcpProbeDescription.text = result
            }
        }, "tcp-loopback-probe").start()
    }

    private fun runFramedProbe() {
        if (framedProbeRunning) return
        framedProbeRunning = true
        framedProbeButton.isEnabled = false
        framedProbeTitle.text = "Testing TXWB v1 framing…"
        framedProbeDescription.text = "Sending one bounded PING frame to the loopback diagnostic server."
        Thread({
            val result = try {
                val payload = "TERMUX_XWAYLAND_FRAME_PROBE".toByteArray(Charsets.UTF_8)
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", 38472), 3000)
                    socket.soTimeout = 3000
                    BridgeProtocol.write(socket.getOutputStream(), BridgeMessageKind.PING, payload)
                    val response = BridgeProtocol.read(socket.getInputStream())
                    if (response.kind != BridgeMessageKind.PONG) {
                        throw IOException("Expected PONG frame, received ${response.kind}")
                    }
                    if (!response.payload.contentEquals(payload)) {
                        throw IOException("PONG payload did not exactly match PING payload")
                    }
                    "PASS • TXWB v1 header, PING/PONG types, bounded payload length and exact echo verified on 127.0.0.1:38472. Diagnostic only; no desktop frames or production bridge."
                }
            } catch (error: Exception) {
                "FAIL • ${error.message ?: error.javaClass.simpleName}. Start tools/tcp-framed-protocol-probe.py in Termux."
            }
            runOnUiThread {
                framedProbeRunning = false
                framedProbeButton.isEnabled = true
                framedProbeTitle.text = if (result.startsWith("PASS")) {
                    "TXWB framed protocol passed"
                } else {
                    "TXWB framed protocol failed"
                }
                framedProbeDescription.text = result
            }
        }, "tcp-framed-protocol-probe").start()
    }


    private fun socketNameForToken(token: ByteArray): String {
        val fingerprint = token.take(8).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return "$SOCKET_PREFIX$fingerprint"
    }

    private fun decodeTestFrame(payload: ByteArray): Bitmap {
        val frame = RgbaFrameCodec.decode(payload)
        return Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        try { activeSocket?.close() } catch (_: Exception) { }
        activeSocket = null
        super.onDestroy()
    }

    private fun field(hintText: String, initial: String, numeric: Boolean = false, secret: Boolean = false) =
        EditText(this).apply {
            hint = hintText
            setText(initial)
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.WHITE)
            inputType = when {
                numeric -> InputType.TYPE_CLASS_NUMBER
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            if (secret) {
                isSingleLine = true
                typeface = Typeface.MONOSPACE
                isSaveEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
        }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(Color.WHITE)
        elevation = dp(2).toFloat()
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean) =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
        }

    private fun codeCard(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setTextColor(Color.rgb(226, 232, 240))
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setBackgroundColor(Color.rgb(20, 29, 43))
    }

    private fun infoCard(title: String, body: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setBackgroundColor(pale)
        addView(label(title, 15, ink, true))
        addView(label(body, 13, muted, false).apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(blue)
        setBackgroundColor(Color.WHITE)
        minHeight = dp(48)
        setOnClickListener { action() }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun margins(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
