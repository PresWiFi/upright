package com.blakeriding.upright

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

class App : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifs.createChannels(this)
        // Any change to alarms, meds or the wake-up session re-plans what Android should wake us for.
        scope.launch {
            Store.state
                .map { listOf(it.alarms, it.meds, it.doses, it.snoozes, it.session, it.settings, it.routineRun) }
                .distinctUntilChanged()
                .collect { Scheduler.rescheduleAll(this@App) }
        }
        // Keep the watch in step with the alarm.
        scope.launch {
            Store.state.map { WatchLink.snapshot(it) }.distinctUntilChanged().collect { WatchLink.push(this@App, it) }
        }
        // Keep the home-screen widget current.
        scope.launch {
            Store.state.map { listOf(it.meds, it.doses, it.armed) }.distinctUntilChanged().collect { MedWidget.refresh(this@App) }
        }
        scope.launch(Dispatchers.Default) { checkExits(this@App) }
    }
}

/**
 * Android remembers why the app last stopped. A force-stop from Settings, or the system killing it
 * mid-alarm, goes in the tamper log.
 */
private fun checkExits(context: Context) {
    if (Build.VERSION.SDK_INT < 30) return
    val since = Store.state.value.exitsCheckedAt
    val exits = try {
        context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 20)
    } catch (_: Exception) {
        return
    }.filter { it.timestamp > since }
    if (exits.isEmpty()) return
    // First run: start counting from now rather than logging old history.
    if (since == 0L) {
        Store.update { it.copy(exitsCheckedAt = exits.maxOf { e -> e.timestamp }) }
        return
    }
    Store.update { st ->
        var s = st
        for (e in exits.sortedBy { it.timestamp }) {
            val inUse = e.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
            s = when {
                // Updating the app also stops it; that's not tampering.
                e.reason == ApplicationExitInfo.REASON_USER_REQUESTED && UPDATE_WORDS.none { it in e.description.orEmpty().lowercase() } ->
                    s.logTamper(TamperKind.FORCE_STOPPED, e.timestamp, e.description.orEmpty().take(80))
                inUse && e.reason in KILL_REASONS -> s.logTamper(TamperKind.KILLED, e.timestamp, e.description.orEmpty().take(80))
                else -> s
            }
        }
        s.copy(exitsCheckedAt = exits.maxOf { it.timestamp })
    }
}

private val UPDATE_WORDS = listOf("update", "replace", "install")

@android.annotation.SuppressLint("InlinedApi") // Only read on Android 11+, see checkExits.
private val KILL_REASONS = setOf(
    ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_OTHER,
    ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
)

/** While active, Android refuses to uninstall the app. Turning it off is guarded by [GuardService]. */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Store.init(context)
        Store.update { it.logTamper(TamperKind.ADMIN_OFF_ATTEMPT, nowMs()) }
        return context.getString(R.string.admin_disable_warning)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Store.init(context)
        Store.update { it.logTamper(TamperKind.ADMIN_OFF, nowMs()) }
    }
}

fun zone(): ZoneId = ZoneId.systemDefault()
fun nowMs(): Long = System.currentTimeMillis()
fun nowZ(): ZonedDateTime = ZonedDateTime.now(zone())
fun newId(): String = UUID.randomUUID().toString()

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

// ---------- Setup checks ----------

fun adminComponent(context: Context) = ComponentName(context, AdminReceiver::class.java)

fun isAdminActive(context: Context): Boolean =
    context.getSystemService(DevicePolicyManager::class.java).isAdminActive(adminComponent(context))

fun adminIntent(context: Context): Intent =
    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.admin_description))

fun isAccessibilityEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val me = ComponentName(context, GuardService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
}

fun canNotify(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

fun canFullScreen(context: Context): Boolean =
    Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

@android.annotation.SuppressLint("InlinedApi") // Only offered on Android 14+, see canFullScreen.
fun fullScreenSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri())

fun canExactAlarm(context: Context): Boolean =
    Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

fun ignoresBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

@android.annotation.SuppressLint("BatteryLife") // Sideloaded alarm app: this is exactly the intended use.
fun batteryIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

fun hasDndAccess(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

fun hasNfc(context: Context): Boolean = NfcAdapter.getDefaultAdapter(context) != null
fun nfcOn(context: Context): Boolean = NfcAdapter.getDefaultAdapter(context)?.isEnabled == true

fun hasLightSensor(context: Context): Boolean =
    context.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_LIGHT) != null

// ---------- NFC ----------

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

/** 4-byte IDs starting 0x08 are random on every tap (ISO 14443-3), so they can't identify a card. */
fun isRandomUid(uid: String) = uid.length == 8 && uid.startsWith("08")

private const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
    NfcAdapter.FLAG_READER_NFC_B or
    NfcAdapter.FLAG_READER_NFC_F or
    NfcAdapter.FLAG_READER_NFC_V or
    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

/** Reads the UID of any card tapped while this activity is in the foreground. Call from onResume. */
fun Activity.startCardReader(onUid: (String) -> Unit) {
    NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(this, { tag -> runOnUiThread { onUid(tag.id.toHex()) } }, READER_FLAGS, null)
}

fun Activity.stopCardReader() {
    NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
}
