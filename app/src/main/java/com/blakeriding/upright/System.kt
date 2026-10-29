package com.blakeriding.upright

import android.app.Activity
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
                .map { listOf(it.alarms, it.meds, it.doses, it.snoozes, it.session, it.settings) }
                .distinctUntilChanged()
                .collect { Scheduler.rescheduleAll(this@App) }
        }
    }
}

/** While active, Android refuses to uninstall the app. Turning it off is guarded by [GuardService]. */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)
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
