package me.prince.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

private const val TAG = "Hotword"
private const val CHANNEL = "hotword"
private const val NOTIFICATION_ID = 1
// Google Assistant on Android TV
private const val KATNISS = "com.google.android.katniss"
// A Katniss service other apps may bind; it runs in the process that listens for "Ok Google"
private const val KATNISS_BRIDGE = "com.google.android.apps.tvsearch.voice.search.KVS_SERVICE"

/**
 * Keeps "Ok Google" working after the stock launcher is disabled.
 *
 * Android 11+ only lets a background app use the mic if a foreground client lends it the capability.
 * The stock Google TV launcher does that for the Assistant; disable it and the Assistant is refused the mic
 * (logcat: "Request denied by app op: 27"). This foreground service of type microphone has the capability
 * (it records nothing) and binds the Assistant with BIND_INCLUDE_CAPABILITIES so it inherits it, on any screen.
 *
 * If "Ok Google" had already given up before this ran, restart the Assistant once:
 * adb shell am force-stop com.google.android.katniss
 */
class HotwordService : Service() {
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.i(TAG, "Bound to the Assistant; it can use the mic for \"Ok Google\"")
        }

        override fun onServiceDisconnected(name: ComponentName) {}
    }

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Ok Google", NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Keeping \"Ok Google\" ready")
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val bridge = Intent(KATNISS_BRIDGE).setPackage(KATNISS)
        if (!bindService(bridge, connection, BIND_AUTO_CREATE or BIND_INCLUDE_CAPABILITIES)) {
            // No Google Assistant on this device (or a different build): nothing to keep alive
            Log.w(TAG, "Couldn't bind the Assistant; \"Ok Google\" won't get the mic")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try {
            unbindService(connection)
        } catch (e: IllegalArgumentException) {
            // was never bound
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// Hands-free "Ok Google" keeps the Assistant listening all the time: about a quarter of one CPU core on a
// MediaTek TV. Off here; the remote's mic button works either way.
const val HANDS_FREE_OK_GOOGLE = false

fun startHotwordService(context: Context) {
    if (!HANDS_FREE_OK_GOOGLE) return
    context.startForegroundService(Intent(context, HotwordService::class.java))
}
