package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.terminal.DiagnosticLog

class TermService : Service() {
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onCreate() {
    super.onCreate()
    createChannel()
    acquireWakeLock()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP_SESSIONS) {
      DiagnosticLog.log("TermService", "Stop action tapped in notification")
      onStopRequested?.invoke()
      releaseWakeLock()
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    val count = intent?.getIntExtra(EXTRA_SESSION_COUNT, 1) ?: 1
    val notification = buildNotification(count)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
    return START_STICKY
  }

  override fun onDestroy() {
    releaseWakeLock()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun acquireWakeLock() {
    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
    if (wakeLock == null) {
      wakeLock =
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TerXtreme::TerminalWakeLock").apply {
          setReferenceCounted(false)
          acquire(6L * 60L * 60L * 1000L) // 6h safety timeout
        }
    }
  }

  private fun releaseWakeLock() {
    wakeLock?.let {
      if (it.isHeld) it.release()
    }
    wakeLock = null
  }

  private fun createChannel() {
    val mgr = getSystemService(NotificationManager::class.java) ?: return
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        getString(R.string.notification_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = getString(R.string.notification_channel_desc)
      }
    mgr.createNotificationChannel(channel)
  }

  private fun buildNotification(sessionCount: Int): Notification {
    val openIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val stopIntent =
      PendingIntent.getService(
        this,
        1,
        Intent(this, TermService::class.java).setAction(ACTION_STOP_SESSIONS),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val text =
      if (sessionCount == 1) {
        "1 active Linux proot session"
      } else {
        "$sessionCount active Linux proot sessions"
      }

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle(getString(R.string.app_name))
      .setContentText(text)
      .setContentIntent(openIntent)
      .setOngoing(true)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_stop), stopIntent)
      .build()
  }

  companion object {
    const val CHANNEL_ID = "ter_xtreme_sessions"
    const val NOTIFICATION_ID = 1001
    const val ACTION_STOP_SESSIONS = "com.example.action.STOP_SESSIONS"
    const val EXTRA_SESSION_COUNT = "session_count"

    @Volatile var onStopRequested: (() -> Unit)? = null

    fun sync(context: Context, sessionCount: Int) {
      if (sessionCount > 0) {
        val intent =
          Intent(context, TermService::class.java).putExtra(EXTRA_SESSION_COUNT, sessionCount)
        context.startForegroundService(intent)
      } else {
        context.stopService(Intent(context, TermService::class.java))
      }
    }
  }
}
