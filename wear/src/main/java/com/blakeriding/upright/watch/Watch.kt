package com.blakeriding.upright.watch

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What the watch knows. The phone sends its state whenever it changes; the watch:
 *  - buzzes while the phone rings (and pauses while the phone is hushed),
 *  - keeps its own alarm for the next alarm time + a short grace period. If the phone never said it
 *    started ringing by then (dead battery, out of range), the watch rings by itself.
 */
object WatchCore {
    const val PATH = "/upright/state"
    /** How long after the alarm time the watch waits for the phone before ringing on its own. */
    private const val BACKUP_GRACE_MS = 90_000L
    private const val SAME_ALARM_MS = 5 * 60_000L

    fun prefs(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences("watch", Context.MODE_PRIVATE)

    fun now() = System.currentTimeMillis()

    fun phoneRinging(p: SharedPreferences) = p.getBoolean("ringing", false)
    fun hushUntil(p: SharedPreferences) = p.getLong("hushUntil", 0)
    fun backupActive(p: SharedPreferences) = p.getBoolean("backupActive", false)

    fun shouldRing(p: SharedPreferences) = phoneRinging(p) || backupActive(p)
    fun shouldBuzz(p: SharedPreferences) = backupActive(p) || (phoneRinging(p) && now() >= hushUntil(p))

    /** New state from the phone. */
    fun onPhoneData(context: Context, map: DataMap) {
        val p = prefs(context)
        p.edit()
            .putBoolean("ringing", map.getBoolean("ringing"))
            .putLong("hushUntil", map.getLong("hushUntil"))
            .putLong("firedAt", map.getLong("firedAt"))
            .putLong("nextAlarmAt", map.getLong("nextAlarmAt"))
            .putString("label", map.getString("label", ""))
            .putLong("lastDoneAt", map.getLong("lastDoneAt"))
            .putLong("syncedAt", now())
            .apply()
        // The phone is ringing for (or already finished) the alarm we were backing up: it takes over.
        val backupFor = p.getLong("backupFor", 0)
        if (backupActive(p) && (map.getLong("firedAt") >= backupFor - SAME_ALARM_MS || map.getLong("lastDoneAt") >= backupFor - SAME_ALARM_MS)) {
            p.edit().putBoolean("backupActive", false).apply()
        }
        armBackup(context)
        if (shouldRing(p)) WatchRingService.start(context)
    }

    private fun backupIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent(context, BackupReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun armBackup(context: Context) {
        val p = prefs(context)
        val next = p.getLong("nextAlarmAt", 0)
        val am = context.getSystemService(AlarmManager::class.java)
        if (next <= now()) {
            am.cancel(backupIntent(context))
            return
        }
        p.edit().putLong("armedFor", next).apply()
        val show = PendingIntent.getActivity(context, 0, Intent(context, WatchMainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(next + BACKUP_GRACE_MS, show), backupIntent(context))
        } catch (e: SecurityException) {
            Log.w("Upright", "Exact alarm not allowed on watch", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next + BACKUP_GRACE_MS, backupIntent(context))
        }
    }

    /** The backup alarm went off: ring unless the phone already reported this alarm. */
    fun onBackupFire(context: Context) {
        val p = prefs(context)
        val target = p.getLong("armedFor", 0)
        val handled = p.getLong("firedAt", 0) >= target - SAME_ALARM_MS || p.getLong("lastDoneAt", 0) >= target - SAME_ALARM_MS
        if (target > 0 && !handled) {
            p.edit().putBoolean("backupActive", true).putLong("backupFor", target).apply()
            WatchRingService.start(context)
        }
    }

    /** Hold-to-stop on the watch, only offered when the phone can't be reached. */
    fun stopBackup(context: Context) {
        prefs(context).edit().putBoolean("backupActive", false).putBoolean("ringing", false).apply()
    }

    /** Pull the phone's latest state (on app open, in case an update was missed). */
    fun refresh(context: Context) {
        Wearable.getDataClient(context).dataItems.addOnSuccessListener { items ->
            items.firstOrNull { it.uri.path == PATH }?.let { onPhoneData(context, DataMapItem.fromDataItem(it).dataMap) }
            items.release()
        }
    }

    fun checkPhone(context: Context, onResult: (Boolean) -> Unit) {
        Wearable.getNodeClient(context).connectedNodes
            .addOnSuccessListener { onResult(it.isNotEmpty()) }
            .addOnFailureListener { onResult(false) }
    }
}

class PhoneListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == WatchCore.PATH) {
                WatchCore.onPhoneData(this, DataMapItem.fromDataItem(e.dataItem).dataMap)
            }
        }
    }
}

class BackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = WatchCore.onBackupFire(context)
}

class WatchBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WatchCore.armBackup(context)
        if (WatchCore.shouldRing(WatchCore.prefs(context))) WatchRingService.start(context)
    }
}

/** Buzzes the watch while the alarm is on. Stops by itself when the phone says you're up. */
class WatchRingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var buzzing = false
    private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, 1, notification(), if (android.os.Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0)
        if (running) return START_STICKY
        running = true
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Upright:watch").apply { setReferenceCounted(false) }
        try {
            startActivity(Intent(this, WatchRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // The full-screen notification opens it instead.
        }
        scope.launch {
            val p = WatchCore.prefs(this@WatchRingService)
            while (isActive) {
                if (!WatchCore.shouldRing(p)) {
                    stopSelf()
                    break
                }
                setBuzz(WatchCore.shouldBuzz(p))
                wakeLock?.acquire(60_000)
                delay(1000)
            }
        }
        return START_STICKY
    }

    private fun setBuzz(on: Boolean) {
        if (on == buzzing) return
        buzzing = on
        val v = ContextCompat.getSystemService(this, Vibrator::class.java) ?: return
        if (on) {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            @Suppress("DEPRECATION")
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 300, 700, 900), 0), attrs)
        } else {
            v.cancel()
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ring", "Alarm", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(false) })
        val open = PendingIntent.getActivity(this, 1, Intent(this, WatchRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "ring")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Time to get up")
            .setContentText("Finish your steps on the phone")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        ContextCompat.getSystemService(this, Vibrator::class.java)?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, WatchRingService::class.java))
    }
}
