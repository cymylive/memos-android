package com.usememos.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import mobile.Mobile

/**
 * Foreground service that keeps the embedded memos server alive while the
 * app is backgrounded, so LAN devices can keep accessing it.
 */
class MemosService : Service() {

    companion object {
        private const val CHANNEL_ID = "memos_service"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        startServerIfNeeded()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Mobile.stopServer()
        super.onDestroy()
    }

    private fun startServerIfNeeded() {
        if (Mobile.isRunning()) return
        // Service may be restarted by the system without MainActivity; re-read the
        // saved port so the notification and server always agree with the app.
        MainActivity.serverPort = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
            .getInt(MainActivity.PREF_SERVER_PORT, MainActivity.DEFAULT_SERVER_PORT)
        val error = runCatching {
            Mobile.startServer(filesDir.absolutePath, MainActivity.serverPort.toLong())
        }.getOrDefault("服务启动异常")
        if (error.isNotEmpty()) {
            updateNotification("服务异常：$error")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OwnDiary 共享服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持 OwnDiary 服务器运行，供局域网设备访问"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("OwnDiary 共享服务运行中")
            .setContentText("局域网地址：http://${NetworkUtils.lanIpAddress() ?: "未知"}:${MainActivity.serverPort}")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("OwnDiary 共享服务")
                .setContentText(message)
                .setOngoing(true)
                .build()
        )
    }
}