package com.ossm.remote.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ossm.remote.MainActivity
import com.ossm.remote.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class RemoteSessionService : Service() {
    @Inject lateinit var client: RemoteSessionClient
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.remote_session_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, RemoteSessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(getString(R.string.remote_session_notification_title)).setContentText(getString(R.string.remote_session_notification_text)).setContentIntent(open).setOngoing(true).addAction(0, getString(R.string.remote_stop_session), stop).build(), if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { client.stop(); stopSelf() }
            ACTION_REGENERATE -> client.regenerate()
            null -> client.start()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { client.onServiceDestroyed(); super.onDestroy() }
    companion object {
        private const val CHANNEL_ID = "remote_session"; private const val NOTIFICATION_ID = 1203; private const val ACTION_STOP = "com.ossm.remote.remote.STOP_SESSION"; private const val ACTION_REGENERATE = "com.ossm.remote.remote.REGENERATE_SESSION"
        fun start(context: Context) = context.startForegroundService(Intent(context, RemoteSessionService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, RemoteSessionService::class.java).setAction(ACTION_STOP))
        fun regenerate(context: Context) = context.startForegroundService(Intent(context, RemoteSessionService::class.java).setAction(ACTION_REGENERATE))
    }
}
