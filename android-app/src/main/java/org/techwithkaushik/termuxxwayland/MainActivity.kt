package org.techwithkaushik.termuxxwayland

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.ClipboardManager
import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.ParcelFileDescriptor
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Android endpoint for the authenticated abstract Unix socket smoke test. */
class MainActivity : Activity() {
    private val ink = Color.rgb(22, 32, 48)
    private val muted = Color.rgb(99, 113, 132)
    private val blue = Color.rgb(37, 99, 235)
    private val pale = Color.rgb(239, 246, 255)
    private val page = Color.rgb(246, 248, 252)
    private val SOCKET_PREFIX = "termux_xwayland_"
    private companion object {
        const val BINDER_PROBE_DESCRIPTOR = "org.techwithkaushik.termuxxwayland.ipc.PROBE"
        const val BINDER_PROBE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 17
        const val BINDER_PROBE_PAYLOAD_BYTES = 16 * 1024
        const val BINDER_PROBE_MAX_PAYLOAD_BYTES = 64 * 1024
        const val BINDER_FD_PROBE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 18
    }

    private lateinit var tokenInput: EditText
    private lateinit var statusTitle: TextView
    private lateinit var statusDescription: TextView
    private lateinit var previewImage: ImageView
    private lateinit var connectButton: Button
    @Volatile private var activeSocket: LocalSocket? = null
    @Volatile private var connecting = false
    private lateinit var binderProbeTitle: TextView
    private lateinit var binderProbeDescription: TextView
    private lateinit var binderProbeButton: Button
    private var binderProbeConnection: ServiceConnection? = null
    @Volatile private var binderProbeRunning = false
    private lateinit var binderFdProbeTitle: TextView
    private lateinit var binderFdProbeDescription: TextView
    private lateinit var binderFdProbeButton: Button
    @Volatile private var binderFdProbeRunning = false
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
            "Connect this Android display host to a Termux bridge running on the same device.",
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
            "Cross-UID IPC passed means the token was accepted, the Termux process UID was reported, a synthetic frame arrived, and Android PING received a Termux PONG. Real desktop frames, Wayland compositor integration, input forwarding and XWayland are still pending."
        ), margins(top = 8, bottom = 16))
        content.addView(label("Binder IPC capability probe", 20, ink, true).apply {
            setPadding(0, dp(12), 0, dp(8))
        })
        content.addView(infoCard(
            "Separate companion APK",
            "Tests Android-authorized Binder communication with a separately installed companion app. Both APKs must be signed with the same certificate. This proves host-to-companion IPC only; it does not yet connect Termux or stream desktop frames."
        ), margins(bottom = 10))
        binderProbeTitle = label("Not tested", 17, ink, true).apply {
            setPadding(0, dp(4), 0, dp(4))
        }
        binderProbeDescription = label(
            "Sends a bounded 16 KiB test payload and verifies the exact echo, caller identity, service UID, and Android API level.",
            13, muted, false
        )
        content.addView(binderProbeTitle)
        content.addView(binderProbeDescription, margins(bottom = 8))
        binderProbeButton = actionButton("Run Binder capability probe") { runBinderProbe() }
        content.addView(binderProbeButton, margins(bottom = 16))
        binderFdProbeTitle = label("Not tested", 17, ink, true).apply {
            setPadding(0, dp(4), 0, dp(4))
        }
        binderFdProbeDescription = label(
            "Tests a bounded 16 KiB bidirectional payload over two Binder-transferred file descriptors. This is a companion capability test, not Termux integration.",
            13, muted, false
        )
        content.addView(binderFdProbeTitle)
        content.addView(binderFdProbeDescription, margins(bottom = 8))
        binderFdProbeButton = actionButton("Test Binder file descriptor handoff") { runBinderFdProbe() }
        content.addView(binderFdProbeButton, margins(bottom = 16))

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

    private fun runBinderProbe() {
        if (binderProbeRunning) return
        binderProbeRunning = true
        binderProbeButton.isEnabled = false
        binderProbeTitle.text = "Binding companion…"
        binderProbeDescription.text = "Requesting the signature-protected Binder service."

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                Thread({
                    val result = try {
                        performBinderProbe(service)
                    } catch (error: Exception) {
                        "FAIL • ${error.message ?: error.javaClass.simpleName}"
                    }
                    runOnUiThread {
                        if (binderProbeConnection === this) {
                            try { unbindService(this) } catch (_: Exception) { }
                            binderProbeConnection = null
                        }
                        binderProbeRunning = false
                        binderProbeButton.isEnabled = true
                        binderProbeTitle.text = if (result.startsWith("PASS")) {
                            "Binder probe passed"
                        } else {
                            "Binder probe failed"
                        }
                        binderProbeDescription.text = result
                    }
                }, "binder-ipc-probe").start()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                runOnUiThread {
                    if (binderProbeRunning) {
                        binderProbeTitle.text = "Companion disconnected"
                        binderProbeDescription.text =
                            "The Binder service disconnected before the probe completed."
                    }
                }
            }
        }
        binderProbeConnection = connection
        val intent = Intent().setComponent(
            ComponentName(
                "org.techwithkaushik.termuxxwayland.companion",
                "org.techwithkaushik.termuxxwayland.companion.IpcProbeService"
            )
        )
        val bound = try {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
        if (!bound) {
            binderProbeConnection = null
            binderProbeRunning = false
            binderProbeButton.isEnabled = true
            binderProbeTitle.text = "Companion unavailable"
            binderProbeDescription.text =
                "Install the companion APK built from :ipc-companion and ensure both APKs use the same signing certificate. No socket or network fallback was attempted."
        }
    }

    private fun runBinderFdProbe() {
        if (binderFdProbeRunning) return
        binderFdProbeRunning = true
        binderFdProbeButton.isEnabled = false
        binderFdProbeTitle.text = "Binding companion…"
        binderFdProbeDescription.text = "Preparing two one-shot pipes and a bounded echo payload."
        Thread({
            val result = try {
                performBinderFdProbe()
            } catch (error: Exception) {
                "FAIL • ${error.message ?: error.javaClass.simpleName}"
            }
            runOnUiThread {
                binderFdProbeRunning = false
                binderFdProbeButton.isEnabled = true
                binderFdProbeTitle.text = if (result.startsWith("PASS")) {
                    "Binder FD handoff passed"
                } else {
                    "Binder FD handoff failed"
                }
                binderFdProbeDescription.text = result
            }
        }, "binder-fd-probe").start()
    }

    private fun performBinderFdProbe(): String {
        val payload = ByteArray(16 * 1024) { index -> ((index * 17 + 11) and 0xff).toByte() }
        val inputPipe = ParcelFileDescriptor.createPipe()
        val outputPipe = ParcelFileDescriptor.createPipe()
        val echoed = java.io.ByteArrayOutputStream(payload.size)
        var writerFailure: Throwable? = null
        var readerFailure: Throwable? = null
        val writer = Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(inputPipe[1]).use { it.write(payload) }
            } catch (error: Throwable) {
                writerFailure = error
            }
        }, "binder-fd-writer")
        val reader = Thread({
            try {
                ParcelFileDescriptor.AutoCloseInputStream(outputPipe[0]).use { input ->
                    val buffer = ByteArray(2048)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (echoed.size() + count > payload.size) {
                            throw IOException("FD echo exceeded expected payload size")
                        }
                        echoed.write(buffer, 0, count)
                    }
                }
            } catch (error: Throwable) {
                readerFailure = error
            }
        }, "binder-fd-reader")
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        var parcelsRecycled = false
        try {
            request.writeInterfaceToken(BINDER_PROBE_DESCRIPTOR)
            request.writeInt(payload.size)
            inputPipe[0].writeToParcel(request, 0)
            outputPipe[1].writeToParcel(request, 0)
            writer.start()
            reader.start()
            var apiLevel = -1
            val binding = bindProbeForFd()
            val binder = binding.first
            try {
                if (!binder.transact(BINDER_FD_PROBE_TRANSACTION, request, response, 0)) {
                    throw IOException("Companion does not implement FD handoff transaction")
                }
                response.readException()
                val serviceUid = response.readInt()
                val callerUid = response.readInt()
                apiLevel = response.readInt()
                val echoedSize = response.readInt()
                if (serviceUid == callerUid || callerUid != Process.myUid()) {
                    throw IOException("Unexpected Binder caller/service UID")
                }
                if (echoedSize != payload.size) throw IOException("FD echo size mismatch: $echoedSize")
            } finally {
                unbindProbeForFd(binding.second)
            }
            // Parcel retains duplicated FD references. Recycle it before waiting for EOF,
            // otherwise the output pipe can stay open and the reader blocks indefinitely.
            request.recycle()
            response.recycle()
            parcelsRecycled = true
            try { inputPipe[0].close() } catch (_: Exception) { }
            try { outputPipe[1].close() } catch (_: Exception) { }
            writer.join(5000)
            reader.join(5000)
            if (writer.isAlive || reader.isAlive) throw IOException("FD pipe worker timed out")
            writerFailure?.let { throw IOException("FD input pipe failed", it) }
            readerFailure?.let { throw IOException("FD output pipe failed", it) }
            if (!echoed.toByteArray().contentEquals(payload)) {
                throw IOException("FD echo bytes did not match the original payload")
            }
            return "PASS • Android API $apiLevel • exact ${payload.size}-byte round trip through two Binder-transferred pipe FDs. One-shot companion test only; Termux integration and sustained streaming remain unverified."
        } finally {
            try { inputPipe[0].close() } catch (_: Exception) { }
            try { inputPipe[1].close() } catch (_: Exception) { }
            try { outputPipe[0].close() } catch (_: Exception) { }
            try { outputPipe[1].close() } catch (_: Exception) { }
            if (!parcelsRecycled) {
                request.recycle()
                response.recycle()
            }
            payload.fill(0)
        }
    }

    private fun bindProbeForFd(): Pair<IBinder, ServiceConnection> {
        val latch = CountDownLatch(1)
        var connectedBinder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connectedBinder = service
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                latch.countDown()
            }
        }
        val intent = Intent().setComponent(
            ComponentName(
                "org.techwithkaushik.termuxxwayland.companion",
                "org.techwithkaushik.termuxxwayland.companion.IpcProbeService"
            )
        )
        val bound = try {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (error: Exception) {
            false
        }
        if (!bound) throw IOException("Could not bind the signature-protected Binder companion")
        if (!latch.await(5, TimeUnit.SECONDS)) {
            try { unbindService(connection) } catch (_: Exception) { }
            throw IOException("Timed out waiting for Binder companion")
        }
        val binder = connectedBinder
        if (binder == null) {
            try { unbindService(connection) } catch (_: Exception) { }
            throw IOException("Binder companion disconnected before FD test")
        }
        return Pair(binder, connection)
    }

    private fun unbindProbeForFd(connection: ServiceConnection) {
        try { unbindService(connection) } catch (_: Exception) { }
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

    private fun performBinderProbe(remote: IBinder): String {
        val payload = ByteArray(BINDER_PROBE_PAYLOAD_BYTES) { index ->
            ((index * 31 + 7) and 0xff).toByte()
        }
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            request.writeInterfaceToken(BINDER_PROBE_DESCRIPTOR)
            request.writeInt(payload.size)
            request.writeByteArray(payload)
            if (!remote.transact(BINDER_PROBE_TRANSACTION, request, response, 0)) {
                throw IOException("Companion does not implement the Binder probe transaction")
            }
            response.readException()
            val serviceUid = response.readInt()
            val callerUid = response.readInt()
            val apiLevel = response.readInt()
            val echoedSize = response.readInt()
            if (echoedSize !in 0..BINDER_PROBE_MAX_PAYLOAD_BYTES) {
                throw IOException("Companion returned an invalid payload length")
            }
            val echoed = ByteArray(echoedSize)
            response.readByteArray(echoed)
            if (callerUid != Process.myUid()) {
                throw IOException("Companion reported an unexpected caller UID")
            }
            if (serviceUid == callerUid) {
                throw IOException("Probe service is not running under a separate app UID")
            }
            if (echoedSize != payload.size || !echoed.contentEquals(payload)) {
                throw IOException("Binder echo payload did not match the request")
            }
            return "PASS • host UID $callerUid → companion UID $serviceUid • Android API $apiLevel • exact ${payload.size}-byte echo. This verifies one bounded Binder round trip only; Termux integration and streaming remain unverified."
        } finally {
            request.recycle()
            response.recycle()
            payload.fill(0)
        }
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
        binderProbeConnection?.let { connection ->
            try { unbindService(connection) } catch (_: Exception) { }
        }
        binderProbeConnection = null
        binderProbeRunning = false
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
