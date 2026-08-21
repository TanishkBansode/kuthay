package com.kuthay.p2p

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class P2pService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: P2pSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            session?.close()
            session = null
            P2pBus.session = null
            P2pBus.connected.value = false
            P2pBus.setStatus("STOPPED")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        intent ?: return START_NOT_STICKY
        startTest(intent)
        return START_STICKY
    }

    private fun startTest(intent: Intent) {
        val server = intent.getStringExtra(EXTRA_SERVER) ?: return
        val label = intent.getStringExtra(EXTRA_LABEL) ?: ""
        val mode = intent.getStringExtra(EXTRA_MODE) ?: "host"
        val joinCode = intent.getStringExtra(EXTRA_CODE) ?: ""
        notify(mode)
        P2pBus.connected.value = false
        val s = P2pSession(scope, server, label)
        session = s
        P2pBus.session = s
        s.start(mode, joinCode)
    }

    private fun notify(mode: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "p2p test", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Kuthay P2P")
            .setContentText("test active ($mode)")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SERVER = "server"
        const val EXTRA_LABEL = "label"
        const val EXTRA_MODE = "mode"
        const val EXTRA_CODE = "code"
        const val ACTION_STOP = "stop"
        private const val CHANNEL_ID = "p2p"
        private const val NOTIF_ID = 1
    }
}
