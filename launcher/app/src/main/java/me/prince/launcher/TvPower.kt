package me.prince.launcher

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

private const val TAG = "TvPower"
private const val PREFS = "launcher"
private const val KEY_RESTORE_SCREENSAVER = "restore_dream_on_sleep"
private const val SCREENSAVER_ON_SLEEP = "screensaver_activate_on_sleep"

// Xiaomi's panel state. Unlike PowerManager.isInteractive it also reads "false" during the OLED
// refresh that runs after the TV is switched off, which no broadcast reports, hence the poll.
// One property read every 2 s; it costs nothing measurable.
private const val PANEL_PROPERTY = "sys.screen.turn_on"
private const val PANEL_POLL_MS = 2_000L
// Let the panel and the HDMI link settle before switching input
private const val SCREEN_ON_SETTLE_MS = 3_000L
// A CEC wake request this recent means the device woke the TV, so it is already the active source
private const val WAKE_REQUEST_WINDOW_MS = 20_000L
private const val SLEEP_DELAY_MS = 2_000L
private const val PENDING_WAKE_TIMEOUT_MS = 10 * 60_000L
private const val HOME_BEFORE_INPUT_MS = 800L
private const val LAST_APP_WINDOW_MS = 6 * 60 * 60_000L

// CEC messages as the TV logs them under the HDMI tag. [R] = received; the source digit 1-E is
// another device (0 is the TV itself, F broadcast).
private val STANDBY = Regex("""\[R]:<Standby> [1-9A-E]""")
private val WAKE_REQUEST = Regex("""\[R]:<(Image View On|Text View On|Active Source)> [1-9A-E]""")
// Power status: 0 on, 1 standby, 2 turning on, 3 turning off
private val POWER_STATUS = Regex("""\[R]:<Report Power Status> [1-9A-E]0:90:0([0-3])""")

/** What the HDMI device (the Apple TV) last said about its power over CEC; null until it says anything. */
object HdmiDevice {
    val asleep = MutableStateFlow<Boolean?>(null)
}

/**
 * Makes the TV follow the Apple TV, which the TV doesn't do on its own:
 * - Apple TV goes to sleep (CEC <Standby>) while its input is on screen: the TV goes to sleep too, instead of
 *   showing the screensaver.
 * - TV switched on: it opens the Apple TV input (waking the Apple TV over CEC) unless that input is already up.
 * - Apple TV asks to wake the TV during the OLED refresh, when the TV ignores power-on: the request is held
 *   and replayed when the refresh ends.
 *
 * An accessibility service because that is the only way an app can put the TV to sleep
 * (GLOBAL_ACTION_LOCK_SCREEN). Needs READ_LOGS (CEC traffic), WRITE_SECURE_SETTINGS (screensaver toggle)
 * and SYSTEM_ALERT_WINDOW (opening the input from the background), all granted over adb.
 */
class TvPowerService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private var panelOn = true
    private var goingToSleep = false
    private var lastWakeRequest = 0L
    private var pendingWake: PowerManager.WakeLock? = null
    private var cecLog: Process? = null

    private val pollPanel = object : Runnable {
        override fun run() {
            val on = readPanelState()
            if (on && !panelOn) {
                goingToSleep = false
                releasePendingWake()
                restoreScreensaver()
                handler.postDelayed(::afterScreenOn, SCREEN_ON_SETTLE_MS)
            }
            panelOn = on
            if (pendingWake != null && !power.isInteractive) {
                Log.i(TAG, "OLED refresh over; waking the TV for the earlier wake request")
                @Suppress("DEPRECATION") // the only wake lock that turns the screen on
                power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "launcher:wake")
                    .acquire(1_000L)
                releasePendingWake()
            }
            handler.postDelayed(this, PANEL_POLL_MS)
        }
    }

    override fun onServiceConnected() {
        // A crash or reinstall mid-sleep would otherwise leave the screensaver off for good
        restoreScreensaver()
        startHotwordService(this)
        handler.post(pollPanel)
        thread(isDaemon = true, name = "cec-log") { readCecLog() }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        cecLog?.destroy()
        releasePendingWake()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun readCecLog() {
        try {
            // Only lines from now on; older ones would replay standbys that are already handled
            val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val process = ProcessBuilder("logcat", "-v", "raw", "-T", since, "-s", "HDMI")
                .redirectErrorStream(true)
                .start()
            cecLog = process
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach(::onCecLine) }
        } catch (e: IOException) {
            Log.w(TAG, "Can't read the CEC log (READ_LOGS not granted?)", e)
        }
    }

    // On the cec-log thread
    private fun onCecLine(line: String) {
        when {
            STANDBY.containsMatchIn(line) -> handler.post(::onDeviceStandby)
            WAKE_REQUEST.containsMatchIn(line) -> handler.post(::onWakeRequest)
            else -> POWER_STATUS.find(line)?.let { match ->
                val status = match.groupValues[1].toInt()
                handler.post { HdmiDevice.asleep.value = status == 1 || status == 3 }
            }
        }
    }

    private fun onDeviceStandby() {
        HdmiDevice.asleep.value = true
        // Only follow the device to sleep while its input is what's on screen
        val inputApp = inputAppPackage(this) ?: return
        if (goingToSleep || !panelOn || lastForegroundPackage(this) != inputApp) return
        goingToSleep = true
        // Otherwise "sleep" starts the screensaver and the TV stays on
        setScreensaverOnSleep(false)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) }, SLEEP_DELAY_MS)
    }

    private fun onWakeRequest() {
        lastWakeRequest = SystemClock.elapsedRealtime()
        HdmiDevice.asleep.value = false
        // Panel off but the system still awake = OLED refresh; the TV ignores power-on until it ends
        if (!panelOn && power.isInteractive && pendingWake == null) {
            Log.i(TAG, "Wake request during OLED refresh; waking the TV when it ends")
            pendingWake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "launcher:pending-wake").apply {
                acquire(PENDING_WAKE_TIMEOUT_MS)
            }
        }
    }

    private fun afterScreenOn() {
        val deviceWokeTheTv = SystemClock.elapsedRealtime() - lastWakeRequest < WAKE_REQUEST_WINDOW_MS
        if (deviceWokeTheTv || HdmiDevice.asleep.value != true) {
            switchToHdmiDevice(this)
        } else {
            // The TV remote turned the TV on and the Apple TV is asleep. If the Live TV app is still in front from
            // before, it can't be restarted to send the CEC routing that wakes the Apple TV; go Home first so it can.
            launch(this, Intent(this, MainActivity::class.java))
            handler.postDelayed({ switchToHdmiDevice(this) }, HOME_BEFORE_INPUT_MS)
        }
    }

    private fun readPanelState(): Boolean = try {
        val value = Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, PANEL_PROPERTY) as String
        value != "false"
    } catch (e: ReflectiveOperationException) {
        Log.w(TAG, "Can't read $PANEL_PROPERTY", e)
        true
    }

    private fun setScreensaverOnSleep(enabled: Boolean) {
        try {
            val wasEnabled = Settings.Secure.getInt(contentResolver, SCREENSAVER_ON_SLEEP, 1) == 1
            Settings.Secure.putInt(contentResolver, SCREENSAVER_ON_SLEEP, if (enabled) 1 else 0)
            if (!enabled && wasEnabled) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RESTORE_SCREENSAVER, true).apply()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No WRITE_SECURE_SETTINGS; the TV will show the screensaver instead of turning off", e)
        }
    }

    private fun restoreScreensaver() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_RESTORE_SCREENSAVER, false)) {
            setScreensaverOnSleep(true)
            prefs.edit().remove(KEY_RESTORE_SCREENSAVER).apply()
        }
    }

    private fun releasePendingWake() {
        pendingWake?.takeIf { it.isHeld }?.release()
        pendingWake = null
    }
}

/**
 * Opens the input of the HDMI device that announces itself over CEC (the Apple TV), or the first connected input,
 * unless an input is already on screen. Opening it also routes CEC to it, which wakes the device.
 */
fun switchToHdmiDevice(context: Context) {
    val inputs = loadInputs(context)
    val target = inputs.firstOrNull { it.detail != null } ?: inputs.firstOrNull() ?: return
    val inputApp = inputAppPackage(context)
    if (inputApp != null && lastForegroundPackage(context) == inputApp) return
    launchFresh(context, target.launchIntent)
}

// The app that shows HDMI inputs (Xiaomi: Live TV)
private fun inputAppPackage(context: Context): String? {
    val input = loadInputs(context).firstOrNull() ?: return null
    return context.packageManager.resolveActivity(input.launchIntent, 0)?.activityInfo?.packageName
}

// The app most recently brought to the front. Needs GET_USAGE_STATS (granted over adb).
private fun lastForegroundPackage(context: Context): String? {
    val now = System.currentTimeMillis()
    val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(now - LAST_APP_WINDOW_MS, now)
    val event = UsageEvents.Event()
    var last: String? = null
    while (events.getNextEvent(event)) {
        @Suppress("DEPRECATION") // MOVE_TO_FOREGROUND == ACTIVITY_RESUMED
        if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = event.packageName
    }
    return last
}

fun launch(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No activity for $intent", e)
    }
}

// Live TV apps (HDMI inputs) and Stremio (title links) can ignore the link if they're already running,
// so close the target's background process first.
fun launchFresh(context: Context, intent: Intent) {
    context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName?.let {
        context.getSystemService(ActivityManager::class.java).killBackgroundProcesses(it)
    }
    launch(context, intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK))
}
