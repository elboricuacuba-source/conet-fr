package com.gns.phonebridge

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gns.phonebridge.ftp.MiniFtpServer
import com.gns.phonebridge.http.MiniHttpServer
import com.gns.phonebridge.util.Credentials
import com.gns.phonebridge.util.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class FtpServerService : Service() {

    companion object {
        const val ACTION_START = "com.gns.phonebridge.START"
        const val ACTION_STOP = "com.gns.phonebridge.STOP"
        const val PORT = 2121
        const val HTTP_PORT = 8080
        const val USERNAME = "gns"
        private const val NOTIF_ID = 1

        private val _state = MutableStateFlow(BridgeState())
        val state = _state.asStateFlow()
    }

    private var server: MiniFtpServer? = null
    private var httpServer: MiniHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServerInternal()
                stopSelf()
            }
            else -> startServerInternal()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep serving files even if the user swipes the app away from Recents -
        // only the "Detener" notification action or the in-app button should stop it.
        super.onTaskRemoved(rootIntent)
    }

    private fun startServerInternal() {
        if (server?.isRunning == true) return

        val root = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            Environment.getExternalStorageDirectory()
        } else {
            getExternalFilesDir(null) ?: filesDir
        }
        val ip = NetworkUtils.getLocalIpAddress()
        val password = Credentials.randomPassword()

        startForeground(NOTIF_ID, buildNotification())

        val instance = MiniFtpServer(
            rootDir = root as File,
            port = PORT,
            username = USERNAME,
            password = password,
        )
        server = instance
        instance.start()

        val httpInstance = MiniHttpServer(rootDir = root, port = HTTP_PORT)
        httpServer = httpInstance
        httpInstance.start()

        _state.value = BridgeState(
            running = true,
            ip = ip,
            port = PORT,
            httpPort = HTTP_PORT,
            username = USERNAME,
            password = password,
        )
    }

    private fun stopServerInternal() {
        server?.stop()
        server = null
        httpServer?.stop()
        httpServer = null
        _state.value = BridgeState(running = false)
    }

    override fun onDestroy() {
        stopServerInternal()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FtpServerService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, PhoneBridgeApp.NOTIF_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_running))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openPending)
            .addAction(0, "Detener", stopPending)
            .setOngoing(true)
            .build()
    }
}
