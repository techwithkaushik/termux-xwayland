package org.techwithkaushik.termuxxwayland


import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import java.nio.ByteBuffer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Android endpoint for the authenticated abstract Unix socket smoke test. */
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

class MainActivity : Activity() {
    private val ink = Color.rgb(22, 32, 48)
    private val muted = Color.rgb(99, 113, 132)
    private val blue = Color.rgb(37, 99, 235)
    private val pale = Color.rgb(239, 246, 255)
    private val page = Color.rgb(246, 248, 252)
    private val SOCKET_PREFIX = "termux_xwayland_"
    private companion object {
    }

    private lateinit var tokenInput: EditText
    private lateinit var statusTitle: TextView
    private lateinit var statusDescription: TextView
    private lateinit var binderStatusDescription: TextView
    private lateinit var desktopProofDescription: TextView
    private lateinit var previewImage: DesktopSurfaceView
    private lateinit var headingContainer: LinearLayout
    private lateinit var connectionPanel: LinearLayout
    private lateinit var desktopRoot: FrameLayout
    private var binderServiceBound = false
    private var binderFrameCount = 0L
    // Keep at most one pending frame so a slow UI thread cannot accumulate
    // hundreds of full-size pixel arrays and Bitmap allocations.
    private val pendingDesktopFrame = AtomicReference<RgbaFrameCodec.Frame?>(null)
    private val frameRenderPosted = AtomicBoolean(false)
    private var localBridgeBinder: BridgeSessionService.BridgeBinder? = null
    private val binderServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val local = service as? BridgeSessionService.BridgeBinder ?: return
            localBridgeBinder = local
            if (::connectionPanel.isInitialized && binderFrameCount == 0L) connectionPanel.visibility = View.VISIBLE
            Log.i("TermuxXWayland", "event=binder_service_connected component=MainActivity")
            statusTitle.text = "Session connected"
            statusDescription.text = "Connected to the persistent bridge service. Reopening the host reattaches the live-frame listener."
            binderStatusDescription.text = "Bridge service attached; waiting for the latest desktop frame."
            previewImage.visibility = View.VISIBLE
            previewImage.inputEventListener = { payload -> local.sendInput(payload) }
            // Never retain pixels from an earlier Binder session. Until the new
            // server sends a frame, the desktop surface stays empty/black.
            pendingDesktopFrame.set(null)
            previewImage.clearFrame()
            binderFrameCount = 0L
            if (::connectionPanel.isInitialized) connectionPanel.visibility = View.VISIBLE
            local.frameListener = { frame ->
                if (binderFrameCount == 0L) {
                    Log.i("TermuxXWayland", "event=first_frame_received width=${frame.width} height=${frame.height}")
                }
                // Drop stale frames when Android is busy; always render the newest one.
                pendingDesktopFrame.set(frame)
                scheduleDesktopFrameRender()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            localBridgeBinder?.frameListener = null
            pendingDesktopFrame.set(null)
            if (::previewImage.isInitialized) {
                previewImage.inputEventListener = null
                previewImage.clearFrame()
                previewImage.visibility = View.VISIBLE
            }
            if (::desktopProofDescription.isInitialized) {
                desktopProofDescription.text =
                    "NO LIVE DESKTOP FRAME • Binder service disconnected. Previous pixels are cleared and are not treated as live."
                desktopProofDescription.setTextColor(Color.rgb(185, 28, 28))
            }
            localBridgeBinder = null
            binderFrameCount = 0L
        }
    }
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
    private val statusPollHandler = Handler(Looper.getMainLooper())
    private val statusPollInFlight = AtomicBoolean(false)
    private val statusPollRunnable = object : Runnable {
        override fun run() {
            pollTermuxServerStatus()
            refreshTermuxControlStatus()
            statusPollHandler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildScreen())
        applyImmersiveMode()
        Log.i("TermuxXWayland", "event=activity_created package=$packageName sdk=${android.os.Build.VERSION.SDK_INT}")
        handleTermuxIntent(intent)
        refreshTermuxControlStatus()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // isAcceptingText reports editor focus, not whether the IME is currently
        // visible. It therefore caused Back to keep hiding an already-hidden IME.
        // toggleSoftInput tracks the actual IME visibility and works on every press.
        if (!::previewImage.isInitialized) return
        previewImage.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isInPictureInPictureMode) applyImmersiveMode()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTermuxIntent(intent)
        refreshTermuxControlStatus()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (binderFrameCount > 0L) enterDesktopPictureInPicture()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        applyPictureInPictureLayout(isInPictureInPictureMode)
    }

    private fun applyPictureInPictureLayout(inPip: Boolean) {
        if (!::headingContainer.isInitialized) return
        headingContainer.visibility = View.GONE
        statusTitle.visibility = View.GONE
        statusDescription.visibility = View.GONE
        binderStatusDescription.visibility = View.GONE
        desktopProofDescription.visibility = View.GONE
        previewImage.visibility = View.VISIBLE
        previewImage.requestLayout()
    }

    private fun enterDesktopPictureInPicture() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
            isFinishing || isDestroyed) return
        runCatching {
            val bounds = android.graphics.Rect()
            if (::previewImage.isInitialized) previewImage.getGlobalVisibleRect(bounds)
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
            if (!bounds.isEmpty) builder.setSourceRectHint(bounds)
            enterPictureInPictureMode(builder.build())
        }.onFailure { Log.w("TermuxXWayland", "Could not enter picture-in-picture", it) }
    }

    override fun onStart() {
        super.onStart()
        if (!binderServiceBound) {
            binderServiceBound = runCatching {
                bindService(Intent(this, BridgeSessionService::class.java), binderServiceConnection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
        }
        statusPollHandler.removeCallbacks(statusPollRunnable)
        statusPollHandler.post(statusPollRunnable)
    }

    override fun onStop() {
        statusPollHandler.removeCallbacks(statusPollRunnable)
        localBridgeBinder?.frameListener = null
        if (::previewImage.isInitialized) previewImage.inputEventListener = null
        localBridgeBinder = null
        if (binderServiceBound) {
            runCatching { unbindService(binderServiceConnection) }
            binderServiceBound = false
        }
        Log.i("TermuxXWayland", "event=activity_stopped binderFrames=$binderFrameCount")
        super.onStop()
    }

    private fun scheduleDesktopFrameRender() {
        if (!frameRenderPosted.compareAndSet(false, true)) return
        statusPollHandler.post {
            try {
                if (!isFinishing && !isDestroyed && ::previewImage.isInitialized) {
                    val frame = pendingDesktopFrame.getAndSet(null)
                    if (frame != null) {
                        previewImage.setFrame(frame.pixels, frame.width, frame.height)
                        previewImage.visibility = View.VISIBLE
                        if (::connectionPanel.isInitialized) connectionPanel.visibility = View.GONE
                        binderFrameCount++
                        // Avoid forcing extra TextView layout work on every desktop frame.
                        if (binderFrameCount == 1L || binderFrameCount % 60L == 0L) {
                            if (::binderStatusDescription.isInitialized) {
                                binderStatusDescription.text =
                                    "Binder FD: authenticated • rendered frames: $binderFrameCount"
                            }
                            if (::desktopProofDescription.isInitialized) {
                                desktopProofDescription.text =
                                    "FRAME STREAM ACTIVE • received pixel frames over Binder; desktop content is not yet verified. Rendered frames: $binderFrameCount."
                                desktopProofDescription.setTextColor(Color.rgb(180, 83, 9))
                            }
                        }
                    }
                }
            } finally {
                frameRenderPosted.set(false)
                if (pendingDesktopFrame.get() != null) {
                    previewImage.postOnAnimation { scheduleDesktopFrameRender() }
                }
            }
        }
    }

    private fun handleTermuxIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "termuxxwayland" || uri.host != "start") return
        val sessionId = uri.getQueryParameter("session_id").orEmpty()
        val serverState = uri.getQueryParameter("server").orEmpty()
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: -1
        if (!sessionId.matches(Regex("^[a-f0-9]{32}$")) ||
            serverState !in setOf("running", "starting") ||
            port !in 30000..49999) {
            getSharedPreferences("termux_control_session", MODE_PRIVATE).edit()
                .clear()
                .putString("state", "invalid_request").apply()
            return
        }
        getSharedPreferences("termux_control_session", MODE_PRIVATE).edit()
            .putString("state", "request_received")
            .putString("session_id", sessionId)
            .putString("server_state", serverState)
            .putInt("monitor_port", port)
            .putLong("received_at", System.currentTimeMillis())
            .apply()
        pollTermuxServerStatus()
    }

    private fun pollTermuxServerStatus() {
        val prefs = getSharedPreferences("termux_control_session", MODE_PRIVATE)
        val sessionId = prefs.getString("session_id", "").orEmpty()
        val port = prefs.getInt("monitor_port", -1)
        if (!sessionId.matches(Regex("^[a-f0-9]{32}$")) ||
            port !in 30000..49999 ||
            !statusPollInFlight.compareAndSet(false, true)) return

        Thread({
            var response: String? = null
            try {
                Socket().use { socket ->
                    socket.soTimeout = 1500
                    socket.connect(InetSocketAddress("127.0.0.1", port), 1000)
                    val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII)
                    // Display.getRealMetrics reports actual pixels rather than dp; Termux's
                    // shell user cannot reliably query this on Android 10/MIUI.
                    @Suppress("DEPRECATION")
                    val metrics = android.util.DisplayMetrics()
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.getRealMetrics(metrics)
                    writer.write("STATUS $sessionId ${metrics.widthPixels} ${metrics.heightPixels}\n")
                    writer.flush()
                    response = BufferedReader(
                        InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)
                    ).readLine()
                }
            } catch (_: IOException) {
                response = null
            } finally {
                statusPollInFlight.set(false)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val current = getSharedPreferences("termux_control_session", MODE_PRIVATE)
                if (current.getString("session_id", "").orEmpty() != sessionId) return@runOnUiThread
                val nextState = when (response) {
                    "RUNNING $sessionId" -> "server_running"
                    "STOPPED $sessionId" -> "server_stopped"
                    "DENIED" -> "session_mismatch"
                    else -> "server_unreachable"
                }
                if (current.getString("state", "") != nextState) {
                    current.edit().putString("state", nextState)
                        .putLong("last_status_check", System.currentTimeMillis()).apply()
                }
                refreshTermuxControlStatus()
            }
        }, "termux-server-status").start()
    }

    private fun refreshTermuxControlStatus() {
        if (!::statusTitle.isInitialized || !::statusDescription.isInitialized) return
        val prefs = getSharedPreferences("termux_control_session", MODE_PRIVATE)
        when (prefs.getString("state", "disconnected")) {
            "request_received" -> {
                statusTitle.text = "Termux launch request received"
                statusDescription.text = "The launch request arrived. Checking Weston status separately from the Binder session."
            }
            "server_running" -> {
                statusTitle.text = "Termux server confirmed"
                statusDescription.text = "The status endpoint confirms Weston and its Wayland socket are running. This is not proof of an active Binder session or desktop stream."
            }
            "server_stopped" -> {
                statusTitle.text = "Termux server stopped"
                statusDescription.text = "The Termux status endpoint confirms Weston is not running. Run termux-xwayland start to start a new session."
            }
            "server_unreachable" -> {
                statusTitle.text = "Termux server not reachable"
                statusDescription.text = "The local status endpoint did not respond. Termux may be stopped, the monitor may have exited, or the session may have expired."
            }
            "session_mismatch" -> {
                statusTitle.text = "Termux session mismatch"
                statusDescription.text = "The status endpoint rejected a request for a different session. Start a fresh session from Termux."
            }
            "invalid_request" -> {
                statusTitle.text = "Invalid Termux connection request"
                statusDescription.text = "The launch request did not contain valid session details. Start a fresh session from Termux."
            }
            else -> {
                statusTitle.text = "Waiting for Termux"
                statusDescription.text = "Run termux-xwayland start in Termux. The host will open when the Wayland socket is ready."
            }
        }
        if (::binderStatusDescription.isInitialized) {
            val binderState = getSharedPreferences("termux_binder_session", MODE_PRIVATE)
                .getString("state", "disconnected")
            binderStatusDescription.text = when (binderState) {
                "connecting" -> "Binder transport: connecting; waiting for an authorized Termux PING/PONG."
                "connected" -> if (binderFrameCount > 0L) {
                    "Binder transport: active; $binderFrameCount frames rendered. Check Termux session logs for capture-helper and input-routing status."
                } else {
                    "Binder transport: active; Termux UID authorization and PING/PONG are confirmed. Waiting for XWayland frames."
                }
                else -> "Binder transport: disconnected; no active authenticated Binder heartbeat."
            }
        }
    }

    private fun buildScreen(): View {
        // Keep legacy status objects initialized for bridge/session diagnostics,
        // but never attach their text or controls to the desktop host UI.
        statusTitle = TextView(this)
        statusDescription = TextView(this)
        binderStatusDescription = TextView(this)
        desktopProofDescription = TextView(this)
        headingContainer = LinearLayout(this)

        previewImage = DesktopSurfaceView(this).apply {
            visibility = View.VISIBLE
            contentDescription = "Live Linux desktop"
            setBackgroundColor(Color.TRANSPARENT)
            inputEventListener = { payload -> localBridgeBinder?.sendInput(payload) }
        }
        desktopRoot = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            fitsSystemWindows = false
            addView(previewImage, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        connectionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(18, 24, 35))
            addView(TextView(this@MainActivity).apply {
                text = "Not connected"
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(24))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            actions.addView(actionButton("⚙  Settings") {
                startActivity(Intent(this@MainActivity, PreferencesActivity::class.java))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            })
            actions.addView(actionButton("⏻  Exit") {
                finishAndRemoveTask()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(8)
            })
            addView(actions, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        desktopRoot.addView(connectionPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        connectionPanel.visibility = if (binderFrameCount > 0L) View.GONE else View.VISIBLE
        return desktopRoot
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
                // This legacy socket diagnostic receives a synthetic test frame.
                // Never render it in the desktop preview or count it as desktop proof.
                decodeTestFrame(frameMessage.payload)

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
                    previewImage.clearFrame()
                    previewImage.visibility = View.VISIBLE
                    desktopProofDescription.text =
                        "NO LIVE DESKTOP FRAME • Legacy socket test passed, but its synthetic test image is deliberately not rendered as a desktop."
                    desktopProofDescription.setTextColor(Color.rgb(185, 28, 28))
                    statusDescription.text = String.format(
                        java.util.Locale.US,
                        "Termux peer UID: %d (kernel-verified) • Android app UID: %d\nPING/PONG round trip: %.2f ms\nDiagnostic transport passed. This does not prove that Weston desktop pixels are streaming.",
                        kernelPeerUid, androidUid, roundTripMs
                    )
                }
                Thread({
                    try {
                        while (!sessionSocket.isClosed && activeSocket === sessionSocket) {
                            val message = BridgeProtocol.read(sessionSocket.getInputStream())
                            when (message.kind) {
                                BridgeMessageKind.FRAME -> {
                                    // This socket is the legacy synthetic-frame diagnostic, not
                                    // the production Binder FD desktop stream. Consume and validate
                                    // its payload but never display it as if it were the real desktop.
                                    decodeTestFrame(message.payload)
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
        connectButton.text = "Run legacy socket diagnostic"
        previewImage.clearFrame()
        previewImage.visibility = View.VISIBLE
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
        return Bitmap.createBitmap(frame.toArgbPixels(), frame.width, frame.height, Bitmap.Config.ARGB_8888)
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
