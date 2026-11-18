package com.durai.soundguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GuardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var player: MediaPlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var everConnected = false
    private var lostMuted = false
    private var ignoreUntil = 0L
    private var fails = 0
    private var label = ""
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel("mon", "Monitoring", NotificationManager.IMPORTANCE_LOW))
        val ch = NotificationChannel("call", "Alerts", NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(null, null)
        ch.enableVibration(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, 1, monitorNote(label.ifEmpty { "Looking for SoundGuard..." }),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        when (intent?.action) {
            "ACK" -> {
                if (Guard.callKind.value == 4) lostMuted = true
                ignoreUntil = SystemClock.elapsedRealtime() + 3000
                Guard.send("/ack")
                stopCall()
                return START_STICKY
            }
            "STOP" -> { shutdown(); return START_NOT_STICKY }
        }
        if (loop == null) {
            Guard.bind(this)
            Guard.monitoring.value = true
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "soundguard:poll").apply { acquire() }
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "soundguard:wifi").apply { acquire() }
            loop = scope.launch { poll() }
        }
        return START_STICKY
    }

    private suspend fun poll() {
        while (currentCoroutineContext().isActive) {
            val s = Guard.http("/status")?.let { Guard.parse(it) }
            if (s != null) {
                fails = 0; everConnected = true; lostMuted = false
                Guard.connected.value = true
                Guard.status.value = s
                note("Connected - listening")
                val now = SystemClock.elapsedRealtime()
                if (s.ring && now > ignoreUntil) {
                    if (Guard.callKind.value != s.al) startCall(s.al)
                } else if (!s.ring && Guard.callKind.value != 0) {
                    stopCall()
                }
            } else if (++fails >= 5) {
                Guard.connected.value = false
                note("SoundGuard not reachable")
                if (everConnected && !lostMuted && Guard.callKind.value != 4) startCall(4)
            }
            delay(700)
        }
    }

    private fun startCall(kind: Int) {
        Guard.callKind.value = kind
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val i = Intent(this, AlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, 2, i, flags)
        val ack = PendingIntent.getService(this, 3, Intent(this, GuardService::class.java).setAction("ACK"), flags)
        val n = NotificationCompat.Builder(this, "call")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SoundGuard: " + Guard.kindName(kind))
            .setContentText("Tap to answer")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .addAction(0, "Answer", ack)
            .build()
        nm.notify(2, n)
        startSound()
    }

    private fun startSound() {
        stopSound()
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            mp.setDataSource(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            mp.isLooping = true
            mp.prepare(); mp.start()
            player = mp
        } catch (_: Exception) {}
        getSystemService(Vibrator::class.java)
            .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
    }

    private fun stopSound() {
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
        getSystemService(Vibrator::class.java).cancel()
    }

    private fun stopCall() {
        Guard.callKind.value = 0
        stopSound()
        nm.cancel(2)
    }

    private fun note(t: String) {
        if (t != label) { label = t; nm.notify(1, monitorNote(t)) }
    }

    private fun monitorNote(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "mon")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("SoundGuard")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun shutdown() {
        loop?.cancel(); loop = null
        stopCall()
        Guard.unbind(this)
        Guard.connected.value = false
        Guard.monitoring.value = false
        Guard.status.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        stopSound()
        try { wake?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
