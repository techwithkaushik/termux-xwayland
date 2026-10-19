package org.techwithkaushik.termuxxwayland

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
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
import java.net.InetSocketAddress
import java.net.Socket

/** Android endpoint for the authenticated bridge smoke test; no frame renderer yet. */
class MainActivity : Activity() {
    private val ink = Color.rgb(22, 32, 48)
    private val muted = Color.rgb(99, 113, 132)
    private val blue = Color.rgb(37, 99, 235)
    private val pale = Color.rgb(239, 246, 255)
    private val page = Color.rgb(246, 248, 252)

    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var statusTitle: TextView
    private lateinit var statusDescription: TextView
    private lateinit var previewImage: ImageView
    private lateinit var connectButton: Button
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var connecting = false

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
        hostInput = field("Host (same device)", "127.0.0.1")
        portInput = field("Port", "38765", numeric = true)
        tokenInput = field("Session token (64 hex characters)", "", secret = true)
        content.addView(hostInput, margins(bottom = 8))
        content.addView(portInput, margins(bottom = 8))
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
            "Connected does not mean desktop rendered",
            "A successful connection confirms the session token was accepted and one synthetic test frame was rendered. Real desktop frames, Wayland compositor integration, input forwarding and XWayland are still pending."
        ), margins(top = 8, bottom = 16))
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

        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull()
        val token = try {
            BridgeProtocol.parseToken(tokenInput.text.toString().trim())
        } catch (error: IllegalArgumentException) {
            showError(error.message ?: "Invalid session token")
            return
        }
        if (host.isBlank() || port == null || port !in 1..65535) {
            showError("Enter a valid host and port")
            return
        }

        connecting = true
        connectButton.isEnabled = false
        statusTitle.text = "Connecting…"
        statusDescription.text = "Connecting to loopback bridge and checking the session token."
        Thread({
            var candidate: Socket? = null
            try {
                candidate = Socket()
                candidate.connect(InetSocketAddress(host, port), 3000)
                candidate.soTimeout = 15000
                BridgeProtocol.write(candidate.getOutputStream(), BridgeMessageKind.HELLO, token)
                token.fill(0)
                val response = BridgeProtocol.read(candidate.getInputStream())
                if (response.kind != BridgeMessageKind.WELCOME || response.payload.isNotEmpty()) {
                    throw IllegalStateException("Bridge rejected the handshake")
                }
                val frameMessage = BridgeProtocol.read(candidate.getInputStream())
                if (frameMessage.kind != BridgeMessageKind.FRAME) {
                    throw IllegalStateException("Expected a test frame after authentication")
                }
                val testFrame = decodeTestFrame(frameMessage.payload)
                activeSocket = candidate
                candidate = null
                runOnUiThread {
                    connecting = false
                    connectButton.isEnabled = true
                    connectButton.text = "Disconnect"
                    tokenInput.text.clear()
                    statusTitle.text = "Test frame received"
                    previewImage.setImageBitmap(testFrame)
                    previewImage.visibility = View.VISIBLE
                    statusDescription.text =
                        "Authenticated successfully. The image below is a synthetic test pattern—not a Linux desktop or Wayland output."
                }
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

    private fun decodeTestFrame(payload: ByteArray): Bitmap {
        if (payload.size < 8) throw IllegalStateException("Truncated frame metadata")
        val width = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        val height = ((payload[2].toInt() and 0xff) shl 8) or (payload[3].toInt() and 0xff)
        val format = payload[4].toInt() and 0xff
        if (width !in 1..640 || height !in 1..360 || format != 1 ||
            payload[5] != 0.toByte() || payload[6] != 0.toByte() || payload[7] != 0.toByte()
        ) {
            throw IllegalStateException("Unsupported or invalid test frame metadata")
        }
        val expectedBytes = 8L + width.toLong() * height.toLong() * 4L
        if (expectedBytes != payload.size.toLong()) {
            throw IllegalStateException("Frame pixel length does not match its dimensions")
        }
        val pixels = IntArray(width * height)
        var offset = 8
        for (index in pixels.indices) {
            val red = payload[offset++].toInt() and 0xff
            val green = payload[offset++].toInt() and 0xff
            val blue = payload[offset++].toInt() and 0xff
            val alpha = payload[offset++].toInt() and 0xff
            pixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
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
