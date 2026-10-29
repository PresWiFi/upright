package com.blakeriding.upright.ui

import android.Manifest
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.blakeriding.upright.AppState
import com.blakeriding.upright.HomeWifi
import com.blakeriding.upright.TamperEvent
import com.blakeriding.upright.TamperKind
import com.blakeriding.upright.WatchLink
import com.blakeriding.upright.logTamper
import com.blakeriding.upright.nowMs
import com.blakeriding.upright.Step
import com.blakeriding.upright.Store
import com.blakeriding.upright.Tag
import com.blakeriding.upright.TagKind
import com.blakeriding.upright.adminIntent
import com.blakeriding.upright.alarmLocked
import com.blakeriding.upright.batteryIntent
import com.blakeriding.upright.canFullScreen
import com.blakeriding.upright.canNotify
import com.blakeriding.upright.fullScreenSettingsIntent
import com.blakeriding.upright.generateCode
import com.blakeriding.upright.guardActive
import com.blakeriding.upright.hasDndAccess
import com.blakeriding.upright.hasLightSensor
import com.blakeriding.upright.hasNfc
import com.blakeriding.upright.hashEmergencyCode
import com.blakeriding.upright.ignoresBatteryOptimizations
import com.blakeriding.upright.isAccessibilityEnabled
import com.blakeriding.upright.isAdminActive
import com.blakeriding.upright.isRandomUid
import com.blakeriding.upright.newId
import com.blakeriding.upright.nfcOn
import com.blakeriding.upright.nowZ
import com.blakeriding.upright.randomPhrase
import com.blakeriding.upright.toast
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.flow.SharedFlow
import java.security.SecureRandom
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
fun SettingsScreen(state: AppState, nfc: SharedFlow<String>) {
    val ctx = LocalContext.current
    val s = state.settings
    // Re-check permissions every time the screen comes back (after returning from system settings).
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    var newCode by remember { mutableStateOf<String?>(null) }
    var needCode by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var addingTag by remember { mutableStateOf(false) }
    val guarding = guardActive(state, nowZ())

    fun update(change: (com.blakeriding.upright.Settings) -> com.blakeriding.upright.Settings) =
        Store.update { it.copy(settings = change(it.settings)) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 16.dp))

        @Suppress("UNUSED_EXPRESSION") tick // read so the checks below recompose on resume
        SectionTitle("Setup")
        Check("Notifications", canNotify(ctx), "Needed for the alarm screen and med reminders") {
            if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Check("Full-screen alarm", canFullScreen(ctx), "Lets the alarm take over the lock screen") {
            ctx.startActivity(fullScreenSettingsIntent(ctx))
        }
        Check("Battery: unrestricted", ignoresBatteryOptimizations(ctx), "OnePlus kills apps that aren't. Also set Battery → Unrestricted in app info.") {
            ctx.startActivity(batteryIntent(ctx))
        }
        Check("Uninstall lock", isAdminActive(ctx), "Device admin: Android won't uninstall Upright while this is on") {
            ctx.startActivity(adminIntent(ctx))
        }
        Check("Settings guard", isAccessibilityEnabled(ctx), "Accessibility: blocks the screens that turn the lock off, during alarm hours") {
            ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        Check("Emergency code", s.codeHash != null, "Print it and keep it far from your bed") { newCode = generateCode(SecureRandom()) }
        Check("Ring through Do Not Disturb", hasDndAccess(ctx), "Optional. Turns DND off while ringing, then back on") {
            ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        if (hasNfc(ctx)) Check("NFC on", nfcOn(ctx), "Needed for card steps") { ctx.startActivity(Intent(AndroidSettings.ACTION_NFC_SETTINGS)) }

        SectionTitle("Protection")
        val ready = isAdminActive(ctx) && isAccessibilityEnabled(ctx) && s.codeHash != null
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Protection on")
                Text(
                    when {
                        !ready -> "Finish uninstall lock, settings guard and emergency code first."
                        guarding -> "Active now: an alarm is within ${s.guardLeadMin / 60}h or in progress."
                        else -> "Kicks in ${s.guardLeadMin / 60}h before each alarm and lasts until you're up."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(s.protectionOn, enabled = ready || s.protectionOn, onCheckedChange = { on ->
                if (!on && guarding) needCode = "Protection is active right now. Enter your emergency code to turn it off." to {
                    Store.update { it.copy(settings = it.settings.copy(protectionOn = false)).logTamper(TamperKind.PROTECTION_OFF, nowMs(), "during alarm hours") }
                    Unit
                }
                else if (!on) Store.update { it.copy(settings = it.settings.copy(protectionOn = false)).logTamper(TamperKind.PROTECTION_OFF, nowMs()) }
                else update { it.copy(protectionOn = true) }
            })
        }
        if (s.codeHash != null) {
            TextButton(onClick = {
                if (guarding) ctx.toast("Can't change the code during alarm hours") else newCode = generateCode(SecureRandom())
            }) { Text("Make a new emergency code") }
        }
        Text("Turn protection on before you rely on it. It covers: uninstalling, force-stopping, clearing data, and turning off the uninstall lock or settings guard.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionTitle("Tags")
        Text("NFC cards/stickers and barcodes you scan to stop the alarm. Put them far from bed.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.tags.forEach { tag ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${tag.name}  ", Modifier.weight(1f))
                Text(if (tag.kind == TagKind.NFC) "NFC" else "Barcode", color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = {
                    val lockedUse = state.alarms.any { a -> alarmLocked(state, a, nowZ()) && a.steps.any { it is Step.Scan && it.tagId == tag.id } }
                    val remove: () -> Unit = {
                        Store.update { st ->
                            st.copy(
                                tags = st.tags.filterNot { it.id == tag.id },
                                alarms = st.alarms.map { a -> a.copy(steps = a.steps.filterNot { it is Step.Scan && it.tagId == tag.id }) },
                                meds = st.meds.map { if (it.bottleTagId == tag.id) it.copy(bottleTagId = null) else it },
                            )
                        }
                    }
                    if (lockedUse) needCode = "An upcoming alarm uses this tag. Enter your emergency code to delete it." to remove else remove()
                }) { Icon(Icons.Default.Close, "Delete tag") }
            }
        }
        OutlinedButton(onClick = { addingTag = true }) { Text("Add tag") }

        SectionTitle("Still-up check")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ask \"still up?\" after the alarm", Modifier.weight(1f))
            Switch(s.stillUpEnabled, enabled = !guarding || !s.stillUpEnabled, onCheckedChange = { on -> update { it.copy(stillUpEnabled = on) } })
        }
        if (s.stillUpEnabled) {
            Text("Ask after ${s.stillUpDelayMin} min")
            Slider(s.stillUpDelayMin.toFloat(), { v -> update { it.copy(stillUpDelayMin = v.roundToInt()) } }, valueRange = 5f..30f, steps = 24)
            Text("Answer within ${s.stillUpWindowSec} s")
            Slider(s.stillUpWindowSec.toFloat(), { v -> update { it.copy(stillUpWindowSec = (v / 15).roundToInt() * 15) } }, valueRange = 30f..180f, steps = 9)
        }

        if (hasLightSensor(ctx)) {
            SectionTitle("Lights step")
            LuxCalibration(s.lightsLux) { v -> update { it.copy(lightsLux = v) } }
        }

        SectionTitle("Med reminders")
        Text("Repeat every ${s.nagMin} min until answered (for up to 3 hours)")
        Slider(s.nagMin.toFloat(), { v -> update { it.copy(nagMin = v.roundToInt()) } }, valueRange = 5f..30f, steps = 4)

        SectionTitle("Home Wi-Fi")
        HomeWifiSection(state, tick)

        SectionTitle("Galaxy Watch")
        WatchSection(tick)

        SectionTitle("Weekly report")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notification on Sunday evening", Modifier.weight(1f))
            Switch(s.weeklyReport, onCheckedChange = { on -> update { it.copy(weeklyReport = on) } })
        }

        SectionTitle("Tamper log")
        TamperSection(state)
        Spacer(Modifier.height(48.dp))
    }

    newCode?.let { code -> NewCodeDialog(code, onClose = { newCode = null }) {
        val salt = randomPhrase(Random.Default, 16)
        update { it.copy(codeHash = hashEmergencyCode(code, salt), codeSalt = salt) }
        newCode = null
    } }
    needCode?.let { (why, action) -> CodeDialog(s, why, onDismiss = { needCode = null }, onOk = { needCode = null; action() }) }
    if (addingTag) AddTagDialog(nfc, defaultName = "", onClose = { addingTag = false }) { addingTag = false }
}

@Composable
private fun Check(title: String, ok: Boolean, detail: String, fix: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ok) Icons.Default.CheckCircle else Icons.Default.Warning, null,
                tint = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!ok) TextButton(onClick = fix) { Text("Fix") }
        }
    }
}

@Composable
private fun NewCodeDialog(code: String, onClose: () -> Unit, onSaved: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Your emergency code") },
        text = {
            Column {
                Text(code, fontFamily = FontFamily.Monospace, fontSize = 28.sp)
                Spacer(Modifier.height(12.dp))
                Text("Write it down or print it, and keep it somewhere you'd have to get fully up to reach (car, garage, a friend). " +
                    "It's only shown now. It stops a ringing alarm and unlocks protected changes.")
            }
        },
        confirmButton = { TextButton(onClick = onSaved) { Text("I've written it down") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun LuxCalibration(threshold: Float, onChange: (Float) -> Unit) {
    val ctx = LocalContext.current
    var lux by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { lux = e.values[0] }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(l, sm.getDefaultSensor(Sensor.TYPE_LIGHT), SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sm.unregisterListener(l) }
    }
    Text("Counts as \"lights on\" at ${threshold.toInt()} lux. Right now: ${lux.toInt()} lux.")
    Text("Check it in your dark bedroom and with the bathroom light on, and set it between the two.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Slider(threshold, onChange, valueRange = 10f..400f)
}

/** Register a new NFC card (by tapping it) or barcode (by scanning it). */
@Composable
fun AddTagDialog(nfc: SharedFlow<String>, defaultName: String, onClose: () -> Unit, onAdded: (Tag) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(defaultName) }
    var kind by remember { mutableStateOf<TagKind?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun add(k: TagKind, value: String) {
        val existing = Store.state.value.tags.find { it.kind == k && it.value == value }
        if (existing != null) {
            onAdded(existing)
            ctx.toast("Already saved as \"${existing.name}\"")
            return
        }
        val tag = Tag(newId(), name.trim().ifBlank { if (k == TagKind.NFC) "Card" else "Barcode" }, k, value)
        Store.update { it.copy(tags = it.tags + tag) }
        onAdded(tag)
    }

    if (kind == TagKind.NFC) {
        LaunchedEffect(Unit) {
            nfc.collect { uid ->
                if (isRandomUid(uid)) error = "This card gives a different ID every tap, so it can't be used. Try another." else add(TagKind.NFC, uid)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add tag") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. Bathroom)") }, singleLine = true)
                Spacer(Modifier.height(12.dp))
                when (kind) {
                    TagKind.NFC -> Text("Hold the card to the back of your phone…")
                    else -> {
                        if (hasNfc(ctx)) OutlinedButton(onClick = { kind = TagKind.NFC; error = null }, Modifier.fillMaxWidth()) { Text("Tap an NFC card") }
                        OutlinedButton(onClick = {
                            error = null
                            GmsBarcodeScanning.getClient(ctx).startScan()
                                .addOnSuccessListener { b -> b.rawValue?.let { add(TagKind.BARCODE, it) } ?: run { error = "Couldn't read that code" } }
                                .addOnFailureListener { error = "Scanner error: ${it.message}" }
                        }, Modifier.fillMaxWidth()) { Text("Scan a barcode or QR code") }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun HomeWifiSection(state: AppState, tick: Int) {
    val ctx = LocalContext.current
    val home = state.homeNet
    @Suppress("UNUSED_EXPRESSION") tick
    val atHome = home != null && HomeWifi.atHome(ctx)
    Text(
        when {
            home == null -> "Save your home Wi-Fi to get an \"At home\" option on med reminders: it reminds you again as soon as you get home."
            atHome -> "Saved. You're on it now."
            else -> "Saved. You're not on it right now."
        },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Recognised by your router's network details, not the Wi-Fi name, so Upright doesn't need location access. " +
            "If home isn't detected, the reminder comes back after a few hours anyway.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row {
        OutlinedButton(onClick = {
            val now = HomeWifi.current(ctx)
            if (now == null) ctx.toast("Connect to your home Wi-Fi first") else {
                Store.update { it.copy(homeNet = now) }
                ctx.toast("Saved as home")
            }
        }) { Text(if (home == null) "I'm home: save this Wi-Fi" else "Re-save (I'm home now)") }
        if (home != null) TextButton(onClick = { Store.update { it.copy(homeNet = null, homeSnoozes = emptySet()) } }) { Text("Forget") }
    }
}

@Composable
private fun WatchSection(tick: Int) {
    val ctx = LocalContext.current
    var found by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(tick) { WatchLink.checkWatch(ctx) { found = it } }
    Text(
        when (found) {
            null -> "Looking for the watch…"
            true -> "Connected. The watch buzzes while the alarm rings, and rings by itself if the phone can't."
            false -> "No watch with Upright found. Install the watch app (see the README) and keep Bluetooth on."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (found == true) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun tamperLabel(e: TamperEvent): String = when (e.kind) {
    TamperKind.BLOCKED_SCREEN -> "Blocked a settings screen" + if (e.detail.isNotBlank()) " (${e.detail})" else ""
    TamperKind.EMERGENCY_STOP -> "Emergency stop used"
    TamperKind.HUSH -> "Hushed the alarm"
    TamperKind.STEP_SWAPPED -> "Swapped a ${e.detail} step for typing"
    TamperKind.CODE_UNLOCK -> "Unlocked with the emergency code"
    TamperKind.WRONG_CODE -> "Wrong emergency code"
    TamperKind.PROTECTION_OFF -> "Protection turned off" + if (e.detail.isNotBlank()) " ${e.detail}" else ""
    TamperKind.ADMIN_OFF_ATTEMPT -> "Tried to turn off the uninstall lock"
    TamperKind.ADMIN_OFF -> "Uninstall lock turned off"
    TamperKind.GUARD_OFF -> "Settings guard switched off"
    TamperKind.FORCE_STOPPED -> "Upright was force-stopped or closed" + if (e.detail.isNotBlank()) " (${e.detail})" else ""
    TamperKind.KILLED -> "Android stopped Upright while it was busy"
}

@Composable
private fun TamperSection(state: AppState) {
    var showAll by remember { mutableStateOf(false) }
    Text(
        "Every attempt to get around the alarm, and every way out that was used.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val recent = state.tamper.asReversed()
    if (recent.isEmpty()) Text("Nothing yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    recent.take(5).forEach { TamperRow(it) }
    if (recent.size > 5) TextButton(onClick = { showAll = true }) { Text("See all ${recent.size}") }
    if (showAll) AlertDialog(
        onDismissRequest = { showAll = false },
        title = { Text("Tamper log") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { recent.forEach { TamperRow(it) } } },
        confirmButton = { TextButton(onClick = { showAll = false }) { Text("Close") } },
    )
}

private val tamperTime = java.time.format.DateTimeFormatter.ofPattern("EEE MMM d, h:mm a")

@Composable
private fun TamperRow(e: TamperEvent) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(tamperLabel(e))
        Text(
            java.time.Instant.ofEpochMilli(e.at).atZone(com.blakeriding.upright.zone()).format(tamperTime),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
