package su.xash.diffusion.model

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import su.xash.diffusion.MainActivity
import su.xash.diffusion.R
import java.io.File

class DownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null
    private val downloader by lazy { GameDataDownloader(this) }
    private var lastProgress = 0
    private var downloadTargetDir: File? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(0, "Starting..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val targetPath = intent.getStringExtra(EXTRA_TARGET) ?: return START_NOT_STICKY
                startDownload(File(targetPath))
            }
            ACTION_CANCEL -> cancelDownload()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startDownload(targetDir: File) {
        if (downloadJob?.isActive == true) return
        downloadTargetDir = targetDir
        _state.value = DownloadState.Downloading(0, 0, "Starting...")

        downloadJob = scope.launch {
            try {
                val result = downloader.downloadGame(
                    targetDir,
                    onProgress = { current, total ->
                        val pct = if (total > 0) ((current * 100L) / total).toInt() else 0
                        lastProgress = pct
                        _state.update { state ->
                            if (state is DownloadState.Downloading) {
                                state.copy(current = current, total = total)
                            } else {
                                DownloadState.Downloading(current, total, "$pct%")
                            }
                        }
                    },
                    onStatus = { status ->
                        if (downloadJob?.isActive == true) {
                            _state.update { state ->
                                if (state is DownloadState.Downloading) {
                                    state.copy(status = status)
                                } else {
                                    DownloadState.Downloading(0, 0, status)
                                }
                            }
                            updateNotification(lastProgress, status)
                        }
                    }
                )

                if (result.isSuccess) {
                    hasCompleted = true
                    _state.value = DownloadState.Success
                    showCompletionNotification(true, null)
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Unknown error"
                    _state.value = DownloadState.Error(err)
                    showCompletionNotification(false, err)
                }
            } catch (e: CancellationException) {
                _state.value = DownloadState.Cancelled
            } catch (e: Exception) {
                val err = e.message ?: "Unknown error"
                _state.value = DownloadState.Error(err)
                showCompletionNotification(false, err)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = DownloadState.Cancelled
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm().cancel(NOTIFICATION_ID)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Game Data Download",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }

            val channelDone = NotificationChannel(
                CHANNEL_ID_COMPLETION,
                "Download Complete",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { setShowBadge(true) }

            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
            nm.createNotificationChannel(channelDone)
        }
    }

    private fun buildNotification(progress: Int, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val openPend = PendingIntent.getActivity(this, 0, openIntent, flags)
        val cancelIntent = Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL }
        val cancelPend = PendingIntent.getService(this, 1, cancelIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading Diffusion")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openPend)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel), cancelPend)
            .setOngoing(true)
            .apply {
                if (progress > 0) {
                    setProgress(100, progress, false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun updateNotification(progress: Int, text: String) {
        nm().notify(NOTIFICATION_ID, buildNotification(progress, text))
    }

    private fun showCompletionNotification(success: Boolean, error: String?) {
        val title = if (success) "Diffusion Installed!" else "Download Failed"
        val text = if (success) "Game data downloaded successfully" else error ?: "Unknown error"
        val icon = if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val openPend = PendingIntent.getActivity(this, 0, openIntent, flags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_COMPLETION)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(openPend)
            .setAutoCancel(true)
            .build()

        nm().notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "DownloadService"
        const val CHANNEL_ID = "diffusion_game_download"
        const val CHANNEL_ID_COMPLETION = "diffusion_game_download_complete"
        const val NOTIFICATION_ID = 1001
        const val COMPLETION_NOTIFICATION_ID = 1002
        const val ACTION_START = "su.xash.diffusion.action.START_DOWNLOAD"
        const val ACTION_CANCEL = "su.xash.diffusion.action.CANCEL_DOWNLOAD"
        const val ACTION_STOP = "su.xash.diffusion.action.STOP_DOWNLOAD"
        const val EXTRA_TARGET = "target"

        private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val state: StateFlow<DownloadState> = _state.asStateFlow()

        var hasCompleted: Boolean = false
            private set

        fun consumeCompletion(): Boolean {
            val v = hasCompleted
            hasCompleted = false
            return v
        }

        fun resetState() {
            val current = _state.value
            if (current is DownloadState.Downloading) return
            if (!_state.compareAndSet(current, DownloadState.Idle)) return
            hasCompleted = false
        }

        fun start(context: Context, targetDir: File) {
            val current = _state.value
            if (current is DownloadState.Downloading) return
            val starting = DownloadState.Downloading(0, 0, "Starting...")
            if (!_state.compareAndSet(current, starting)) return

            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TARGET, targetDir.absolutePath)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start DownloadService", e)
                _state.compareAndSet(starting, DownloadState.Error(e.message ?: "Could not start download"))
                throw e
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply { action = ACTION_CANCEL }
            context.startService(intent)
        }
    }
}

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val current: Long, val total: Long, val status: String) : DownloadState()
    object Success : DownloadState()
    data class Error(val message: String) : DownloadState()
    object Cancelled : DownloadState()
}
