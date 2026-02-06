package com.yunji.yunaudio.video

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 前台服务：保持应用在后台运行
 * 确保 WebSocket 连接和视频处理不被系统杀死
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "KeepAliveService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "video_stream_channel"
        private const val CHANNEL_NAME = "视频流传输"

        /**
         * 启动服务
         */
        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止服务
         */
        fun stop(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "服务创建")

        // 创建通知渠道
        createNotificationChannel()

        // 获取 WakeLock 防止 CPU 休眠
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "服务启动")

        // 启动前台服务
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // 返回 START_STICKY 确保服务被杀死后会重启
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "服务销毁")

        // 释放 WakeLock
        releaseWakeLock()
    }

    /**
     * 创建通知渠道（Android 8.0+）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW  // 低重要性，不会发出声音
            ).apply {
                description = "保持视频流传输在后台运行"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            Log.d(TAG, "通知渠道已创建")
        }
    }

    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        // 点击通知时打开应用
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("视频流传输中")
            .setContentText("WebSocket 连接保持中...")
            .setSmallIcon(android.R.drawable.ic_media_play)  // 使用系统图标
            .setOngoing(true)  // 不可滑动删除
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 获取 WakeLock 防止 CPU 休眠
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "VideoStream::KeepAlive"
            ).apply {
                acquire(10 * 60 * 60 * 1000L)  // 10 小时超时
            }
            Log.d(TAG, "✅ WakeLock 已获取")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 获取 WakeLock 失败", e)
        }
    }

    /**
     * 释放 WakeLock
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "✅ WakeLock 已释放")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "❌ 释放 WakeLock 失败", e)
        }
    }
}