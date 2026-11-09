package su.xash.diffusion.discord

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import su.xash.diffusion.MainActivity
import su.xash.diffusion.R
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets

class DiscordRpcService : Service(), DiscordRpcClient.Listener {

    enum class Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    data class ActivityInfo(
        val state: String = "In Menus",
        val details: String? = null,
        val partySize: Int = 0,
        val partyMax: Int = 0,
        val startTimeMillis: Long = System.currentTimeMillis()
    )

    companion object {
        private const val TAG = "DiscordRpcService"
        private const val NOTIFICATION_ID = 1042
        private const val CHANNEL_ID = "diffusion_discord_rpc"
        const val DISCORD_UDP_PORT = 27421

        const val ACTION_START = "su.xash.diffusion.discord.action.START"
        const val ACTION_STOP = "su.xash.diffusion.discord.action.STOP"

        private val _statusFlow = MutableStateFlow(Status.DISCONNECTED)
        val statusFlow: StateFlow<Status> = _statusFlow.asStateFlow()

        private val _activityFlow = MutableStateFlow(ActivityInfo())
        val activityFlow: StateFlow<ActivityInfo> = _activityFlow.asStateFlow()

        private val _errorMessageFlow = MutableStateFlow<String?>(null)
        val errorMessageFlow: StateFlow<String?> = _errorMessageFlow.asStateFlow()

        @Volatile
        private var isServiceRunning = false

        fun isRunning(): Boolean = isServiceRunning

        fun start(context: Context) {
            val intent = Intent(context, DiscordRpcService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DiscordRpcService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var client: DiscordRpcClient? = null
    private var currentToken: String? = null
    private var udpSocket: DatagramSocket? = null
    private var udpThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("In Menus", null))
        startUdpListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val authManager = DiscordAuthManager.get(this)
        val token = authManager.token
        if (token.isNullOrBlank()) {
            Log.w(TAG, "No Discord token available, stopping service")
            _statusFlow.value = Status.DISCONNECTED
            stopSelf()
            return START_NOT_STICKY
        }

        if (client == null || currentToken != token) {
            client?.stop()
            currentToken = token
            client = DiscordRpcClient(token, this).also {
                it.start()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        _statusFlow.value = Status.DISCONNECTED

        client?.stop()
        client = null

        stopUdpListener()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // DiscordRpcClient.Listener implementation
    override fun onConnecting() {
        _statusFlow.value = Status.CONNECTING
        _errorMessageFlow.value = null
        updateNotification("Connecting to Discord…", null)
    }

    override fun onReady(userId: String, username: String, discriminator: String, avatar: String?) {
        _statusFlow.value = Status.CONNECTED
        _errorMessageFlow.value = null
        DiscordAuthManager.get(this).saveUser(userId, username, discriminator, avatar)
        val cur = _activityFlow.value
        updateNotification(cur.state, cur.details)
    }

    override fun onPresenceUpdated(state: String, details: String?) {
        updateNotification(state, details)
    }

    override fun onDisconnected(reason: String?) {
        _statusFlow.value = Status.DISCONNECTED
        Log.i(TAG, "Disconnected: $reason")
    }

    override fun onError(error: String) {
        _statusFlow.value = Status.ERROR
        _errorMessageFlow.value = error
        Log.w(TAG, "Discord RPC error: $error")
    }

    private fun startUdpListener() {
        stopUdpListener()
        udpThread = Thread({
            try {
                val socket = DatagramSocket(DISCORD_UDP_PORT, InetAddress.getByName("127.0.0.1"))
                udpSocket = socket
                val buffer = ByteArray(2048)

                while (isServiceRunning && !socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)

                    val text = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                    handleUdpPacket(text)
                }
            } catch (e: Throwable) {
                if (isServiceRunning) {
                    Log.w(TAG, "UDP listener encountered error: ${e.message}")
                }
            }
        }, "DiscordUdpListenerThread").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopUdpListener() {
        try {
            udpSocket?.close()
        } catch (_: Throwable) {}
        udpSocket = null
        udpThread?.interrupt()
        udpThread = null
    }

    private fun handleUdpPacket(text: String) {
        try {
            val json = JSONObject(text)
            val state = json.optString("state", "In Menus")
            val details = if (json.has("details") && !json.isNull("details")) json.optString("details") else null
            val partySize = json.optInt("partySize", 0)
            val partyMax = json.optInt("partyMax", 0)
            val startTimeSec = json.optLong("startTime", 0)

            val currentStart = if (startTimeSec > 0) startTimeSec * 1000L else _activityFlow.value.startTimeMillis
            val act = ActivityInfo(
                state = state,
                details = details,
                partySize = partySize,
                partyMax = partyMax,
                startTimeMillis = currentStart
            )
            _activityFlow.value = act
            client?.updatePresence(state, details, partySize, partyMax, startTimeSec)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse UDP packet: $text", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.discord_service_channel_name)
            val descriptionText = getString(R.string.discord_service_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(state: String, details: String?) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state, details))
    }

    private fun buildNotification(state: String, details: String?): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val contentText = if (!details.isNullOrBlank()) {
            "$details - $state"
        } else {
            state
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_discord_24)
            .setContentTitle(getString(R.string.discord_game_name))
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
