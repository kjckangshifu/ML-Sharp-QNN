package com.sharp.qnn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.sharp.qnn.MainActivity
import com.sharp.qnn.R
import com.sharp.qnn.util.LogRecorder

/**
 * 日志记录前台服务: 承载 [LogRecorder] 的会话生命周期。
 * Foreground service hosting the [LogRecorder] session lifecycle.
 *
 * Activity 销毁都不会停止记录;
 * 开关关闭时发送 [ACTION_STOP] 停止并自杀。前台服务能显著降低进程被
 * 回收的概率, 从而避免 logcat 子进程随 App 进程被杀。
 * Recording starts as a foreground service when the toggle is on and survives
 * backgrounding / Activity destruction; turning it off sends [ACTION_STOP] to
 * stop and self-terminate. A foreground service greatly reduces the chance of
 * process reclamation, keeping the logcat child process alive with the app.
 */
class LogRecorderService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LogRecorder.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        LogRecorder.start(this)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LogRecorder.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "log_recorder"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                channelId,
                getString(R.string.log_recording_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_fg)
            .setContentTitle(getString(R.string.log_recording_notification_title))
            .setContentText(getString(R.string.log_recording_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x5A2
        private const val ACTION_START = "com.sharp.qnn.action.LOG_RECORD_START"
        private const val ACTION_STOP = "com.sharp.qnn.action.LOG_RECORD_STOP"

        /** Start the log recording service (foreground, idempotent) */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LogRecorderService::class.java).setAction(ACTION_START)
            )
        }

        /** Stop the log recording service (idempotent) */
        fun stop(context: Context) {
            context.startService(
                Intent(context, LogRecorderService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
