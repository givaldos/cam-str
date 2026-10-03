package com.camsrt.obs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service que segura a transmissão longa (2h+).
 *
 * Segura PARTIAL_WAKE_LOCK (CPU não dorme) e um Wi-Fi lock (o Wi-Fi
 * não economiza energia no meio do stream). Sem isso o Android pode
 * colocar o app em Doze, reduzir o Wi-Fi ou matar o processo.
 *
 * O modo do Wi-Fi lock vem no start: HIGH_PERF para bitrate alto
 * (menor latência, mais bateria) ou FULL para bitrate baixo
 * (economia de bateria, suficiente até ~4 Mbps).
 */
class StreamKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiHighPerf = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopKeepAlive()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                wifiHighPerf = intent?.getBooleanExtra(EXTRA_HIGH_PERF, true) ?: true
                startKeepAlive()
            }
        }
        // Se o sistema matar o serviço, recria para não derrubar live longa.
        return START_STICKY
    }

    private fun startKeepAlive() {
        acquireLocks()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                @Suppress("DEPRECATION")
                serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            }
            try {
                startForeground(NOTIF_ID, notification, serviceType)
            } catch (_: SecurityException) {
                // Sem permissão de câmera/mic em 2º plano: sobe sem tipo.
                startForeground(NOTIF_ID, notification)
            }
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopKeepAlive() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        releaseLocks()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "CamSRT:StreamLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(3 * 60 * 60 * 1000L) // teto de 3h, solto no stop
                }
            }
        } catch (_: Throwable) {
        }
        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (wifiHighPerf) {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                } else {
                    WifiManager.WIFI_MODE_FULL
                }
                wifiLock = wm.createWifiLock(mode, "CamSRT:WifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        wakeLock = null
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        wifiLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Transmissão CamSRT",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("CamSRT transmitindo")
            .setContentText("Toque para voltar ao app. Não feche durante a live.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.camsrt.obs.KEEPALIVE_START"
        const val ACTION_STOP = "com.camsrt.obs.KEEPALIVE_STOP"
        private const val EXTRA_HIGH_PERF = "highPerf"
        private const val CHANNEL_ID = "camsrt_stream"
        private const val NOTIF_ID = 1001

        fun start(context: Context, highPerf: Boolean = true) {
            val intent = Intent(context, StreamKeepAliveService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_HIGH_PERF, highPerf)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, StreamKeepAliveService::class.java)
                        .setAction(ACTION_STOP)
                )
            } catch (_: Throwable) {
            }
        }
    }
}
