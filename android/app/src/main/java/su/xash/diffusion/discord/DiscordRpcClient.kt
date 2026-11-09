package su.xash.diffusion.discord

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Collections
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class DiscordRpcClient(
    private val token: String,
    private val listener: Listener
) {

    interface Listener {
        fun onConnecting()
        fun onReady(userId: String, username: String, discriminator: String, avatar: String?)
        fun onPresenceUpdated(state: String, details: String?)
        fun onDisconnected(reason: String?)
        fun onError(error: String)
    }

    companion object {
        private const val TAG = "DiscordRpcClient"
        const val DISCORD_APP_ID = "1224041387654385695"
        const val LARGE_IMAGE_KEY = "gameicon"
        const val LARGE_IMAGE_TEXT = "https://moddb.com/mods/diffusion"
        const val SMALL_IMAGE_KEY = "gameicon"
        const val SMALL_IMAGE_TEXT = ""
        private const val GATEWAY_HOST = "gateway.discord.gg"
        private const val GATEWAY_PORT = 443
    }

    data class PresenceData(
        val state: String = "In Menus",
        val details: String? = null,
        val partySize: Int = 0,
        val partyMax: Int = 0,
        val startTimeMillis: Long = System.currentTimeMillis()
    )

    @Volatile
    private var isRunning = false

    @Volatile
    private var isConnected = false

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var readerThread: Thread? = null
    private var heartbeatThread: Thread? = null

    private var lastSequence: Int = -1
    private var heartbeatIntervalMs: Long = 41250
    private var lastAckReceived = true

    private val random = SecureRandom()
    private val sendLock = Any()

    @Volatile
    var currentPresence: PresenceData = PresenceData()
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        Thread({
            connectionLoop()
        }, "DiscordRpcConnectionThread").start()
    }

    fun stop() {
        isRunning = false
        disconnectSocket()
        listener.onDisconnected("Stopped by user")
    }

    fun updatePresence(
        state: String,
        details: String? = null,
        partySize: Int = 0,
        partyMax: Int = 0,
        startTimeSec: Long = 0
    ) {
        val startMillis = if (startTimeSec > 0) startTimeSec * 1000L else currentPresence.startTimeMillis
        val p = PresenceData(
            state = if (state.isBlank()) "In Menus" else state,
            details = if (details.isNullOrBlank()) null else details,
            partySize = partySize,
            partyMax = partyMax,
            startTimeMillis = startMillis
        )
        currentPresence = p
        if (isConnected) {
            sendPresencePayload(p)
        }
    }

    private fun connectionLoop() {
        var backoffMs = 2000L
        while (isRunning) {
            try {
                listener.onConnecting()
                connectAndRun()
                backoffMs = 2000L // Reset backoff on clean finish
            } catch (e: Throwable) {
                val msg = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "Connection error: $msg")
                listener.onError(msg)
            } finally {
                disconnectSocket()
            }

            if (!isRunning) break

            try {
                Thread.sleep(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30000L)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun connectAndRun() {
        Log.i(TAG, "Connecting to $GATEWAY_HOST:$GATEWAY_PORT...")
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val sslSocket = factory.createSocket(GATEWAY_HOST, GATEWAY_PORT) as SSLSocket

        try {
            val params = sslSocket.sslParameters
            val sniHostNameClass = Class.forName("javax.net.ssl.SNIHostName")
            val constructor = sniHostNameClass.getConstructor(String::class.java)
            val sniHostName = constructor.newInstance(GATEWAY_HOST)
            val setServerNamesMethod = params.javaClass.getMethod("setServerNames", java.util.List::class.java)
            setServerNamesMethod.invoke(params, Collections.singletonList(sniHostName))
            sslSocket.sslParameters = params
        } catch (_: Throwable) {
            // Conscrypt / Android OpenSSL sets SNI automatically when createSocket(host, port) is used
        }
        sslSocket.soTimeout = 0 // Heartbeat handles timeout
        sslSocket.startHandshake()

        socket = sslSocket
        val out = sslSocket.outputStream
        val inStream = sslSocket.inputStream
        outputStream = out

        // RFC 6455 Handshake
        val keyBytes = ByteArray(16)
        random.nextBytes(keyBytes)
        val secKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP)

        val req = "GET /?v=10&encoding=json HTTP/1.1\r\n" +
                "Host: $GATEWAY_HOST\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $secKey\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "User-Agent: Diffusion-Android\r\n\r\n"

        out.write(req.toByteArray(StandardCharsets.UTF_8))
        out.flush()

        // Read HTTP 101 Switching Protocols response headers byte by byte
        readHttpResponseHeaders(inStream)
        Log.i(TAG, "WebSocket handshake completed successfully")

        val din = DataInputStream(inStream)

        // Read frames loop
        while (isRunning && !sslSocket.isClosed) {
            val b1 = din.readUnsignedByte()
            val b2 = din.readUnsignedByte()

            val fin = (b1 and 0x80) != 0
            val opcode = b1 and 0x0F
            val isMasked = (b2 and 0x80) != 0
            var payloadLen = (b2 and 0x7F).toLong()

            if (payloadLen == 126L) {
                payloadLen = din.readUnsignedShort().toLong()
            } else if (payloadLen == 127L) {
                payloadLen = din.readLong()
            }

            val maskKey = if (isMasked) {
                val mask = ByteArray(4)
                din.readFully(mask)
                mask
            } else null

            if (payloadLen > 10 * 1024 * 1024) { // sanity check 10 MB
                throw IllegalStateException("Frame payload too large: $payloadLen")
            }

            val payload = ByteArray(payloadLen.toInt())
            din.readFully(payload)

            if (isMasked && maskKey != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }
            }

            when (opcode) {
                0x8 -> { // CLOSE
                    Log.i(TAG, "Server sent WebSocket Close frame")
                    sendCloseFrame(1000, "Normal closure")
                    return
                }
                0x9 -> { // PING
                    sendFrame(0xA, payload) // Reply with PONG
                }
                0xA -> { // PONG
                    // Heartbeat ack or pong
                }
                0x1 -> { // TEXT
                    val text = String(payload, StandardCharsets.UTF_8)
                    handleGatewayMessage(text)
                }
            }
        }
    }

    private fun readHttpResponseHeaders(inStream: InputStream) {
        val headerBuf = ByteArrayOutputStream()
        var c: Int
        var state = 0
        while (inStream.read().also { c = it } != -1) {
            headerBuf.write(c)
            if (state == 0 && c == '\r'.code) state = 1
            else if (state == 1 && c == '\n'.code) state = 2
            else if (state == 2 && c == '\r'.code) state = 3
            else if (state == 3 && c == '\n'.code) break
            else state = 0
        }
        val headers = headerBuf.toString(StandardCharsets.UTF_8.name())
        if (!headers.contains("101")) {
            throw IllegalStateException("Handshake failed, response:\n$headers")
        }
    }

    private fun handleGatewayMessage(text: String) {
        try {
            val json = JSONObject(text)
            val op = json.getInt("op")

            if (json.has("s") && !json.isNull("s")) {
                lastSequence = json.getInt("s")
            }

            when (op) {
                10 -> { // HELLO
                    val d = json.getJSONObject("d")
                    heartbeatIntervalMs = d.getLong("heartbeat_interval")
                    Log.i(TAG, "Gateway HELLO received, heartbeat interval: $heartbeatIntervalMs ms")
                    startHeartbeat()
                    sendIdentify()
                }
                11 -> { // HEARTBEAT ACK
                    lastAckReceived = true
                }
                1 -> { // HEARTBEAT REQUEST
                    sendHeartbeat()
                }
                7 -> { // RECONNECT
                    Log.i(TAG, "Gateway requested RECONNECT")
                    disconnectSocket()
                }
                9 -> { // INVALID SESSION
                    Log.w(TAG, "Gateway sent INVALID SESSION")
                    lastSequence = -1
                    Thread.sleep(2000)
                    sendIdentify()
                }
                0 -> { // DISPATCH
                    val eventType = json.optString("t", "")
                    if (eventType == "READY") {
                        val d = json.getJSONObject("d")
                        val user = d.getJSONObject("user")
                        val uId = user.getString("id")
                        val uName = user.getString("username")
                        val uDisc = user.optString("discriminator", "0")
                        val uAvatar = if (user.isNull("avatar")) null else user.optString("avatar", null)
                        isConnected = true
                        Log.i(TAG, "Gateway READY! Logged in as $uName ($uId)")
                        listener.onReady(uId, uName, uDisc, uAvatar)
                        sendPresencePayload(currentPresence)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling gateway message", e)
        }
    }

    private fun startHeartbeat() {
        heartbeatThread?.interrupt()
        lastAckReceived = true
        heartbeatThread = Thread({
            // Discord docs: First heartbeat after interval * jitter (jitter between 0.0 and 1.0)
            val initialDelay = (heartbeatIntervalMs * Math.random()).toLong()
            try {
                Thread.sleep(initialDelay)
                while (isRunning && isConnectedOrConnecting()) {
                    if (!lastAckReceived) {
                        Log.w(TAG, "Heartbeat ACK was not received in time, reconnecting...")
                        disconnectSocket()
                        break
                    }
                    lastAckReceived = false
                    sendHeartbeat()
                    Thread.sleep(heartbeatIntervalMs)
                }
            } catch (_: InterruptedException) {}
        }, "DiscordHeartbeatThread").apply {
            isDaemon = true
            start()
        }
    }

    private fun isConnectedOrConnecting(): Boolean = socket?.isConnected == true && socket?.isClosed == false

    private fun sendHeartbeat() {
        try {
            val payload = JSONObject().apply {
                put("op", 1)
                if (lastSequence >= 0) {
                    put("d", lastSequence)
                } else {
                    put("d", JSONObject.NULL)
                }
            }
            sendText(payload.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send heartbeat", e)
        }
    }

    private fun sendIdentify() {
        Log.i(TAG, "Sending Gateway IDENTIFY...")
        val props = JSONObject().apply {
            put("os", "linux")
            put("browser", "unknown")
            put("device", "unknown")
        }
        val d = JSONObject().apply {
            put("token", token)
            put("intents", 0)
            put("properties", props)
            put("presence", buildPresenceData(currentPresence))
        }
        val payload = JSONObject().apply {
            put("op", 2)
            put("d", d)
        }
        sendText(payload.toString())
    }

    private fun buildPresenceData(presence: PresenceData): JSONObject {
        val now = System.currentTimeMillis()
        val activity = JSONObject().apply {
            put("name", "Diffusion")
            put("type", 0) // Playing
            put("application_id", DISCORD_APP_ID)
            put("state", if (presence.state.isBlank()) "In Menus" else presence.state)

            if (!presence.details.isNullOrBlank()) {
                put("details", presence.details)
            }

            val timestamps = JSONObject().apply {
                put("start", presence.startTimeMillis)
            }
            put("timestamps", timestamps)

            val assets = JSONObject().apply {
                put("large_image", LARGE_IMAGE_KEY)
                put("large_text", LARGE_IMAGE_TEXT)
                put("small_image", SMALL_IMAGE_KEY)
                put("small_text", SMALL_IMAGE_TEXT)
            }
            put("assets", assets)

            if (presence.partySize > 0 && presence.partyMax > 0) {
                val party = JSONObject().apply {
                    put("id", "diffusion_party")
                    val sizeArr = JSONArray().apply {
                        put(presence.partySize)
                        put(presence.partyMax)
                    }
                    put("size", sizeArr)
                }
                put("party", party)
            }

            put("instance", true)
            put("created_at", now)
        }

        val activities = JSONArray().apply {
            put(activity)
        }

        return JSONObject().apply {
            put("since", now)
            put("activities", activities)
            put("status", "online")
            put("afk", false)
        }
    }

    private fun sendPresencePayload(presence: PresenceData) {
        try {
            val d = buildPresenceData(presence)
            val payload = JSONObject().apply {
                put("op", 3)
                put("d", d)
            }

            sendText(payload.toString())
            listener.onPresenceUpdated(presence.state, presence.details)
            Log.i(TAG, "Presence updated: ${presence.state} | ${presence.details}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send presence update", e)
        }
    }

    private fun sendText(text: String) {
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        sendFrame(0x1, payload)
    }

    private fun sendCloseFrame(code: Int, reason: String) {
        val reasonBytes = reason.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(2 + reasonBytes.size)
        payload[0] = ((code shr 8) and 0xFF).toByte()
        payload[1] = (code and 0xFF).toByte()
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.size)
        try {
            sendFrame(0x8, payload)
        } catch (_: Exception) {}
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        synchronized(sendLock) {
            val out = outputStream ?: return
            val b1 = 0x80 or (opcode and 0x0F) // FIN = 1
            out.write(b1)

            val len = payload.size
            if (len <= 125) {
                out.write(0x80 or len) // MASK = 1
            } else if (len <= 65535) {
                out.write(0x80 or 126)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            } else {
                out.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) {
                    out.write(((len.toLong() shr shift) and 0xFFL).toInt())
                }
            }

            // Client-to-server frames MUST be masked per RFC 6455
            val mask = ByteArray(4)
            random.nextBytes(mask)
            out.write(mask)

            val maskedPayload = ByteArray(len)
            for (i in 0 until len) {
                maskedPayload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            out.write(maskedPayload)
            out.flush()
        }
    }

    private fun disconnectSocket() {
        isConnected = false
        heartbeatThread?.interrupt()
        heartbeatThread = null
        try {
            socket?.close()
        } catch (_: Throwable) {}
        socket = null
        outputStream = null
    }
}
