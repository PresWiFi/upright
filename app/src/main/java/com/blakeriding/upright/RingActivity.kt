package com.blakeriding.upright

import android.app.KeyguardManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The screen you see while the alarm rings, during the still-up wait, and for the still-up check. */
class RingActivity : ComponentActivity() {
    private val nfc = MutableSharedFlow<String>(extraBufferCapacity = 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // No backing out of the alarm.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() {} })
        setContent { UprightTheme { RingScreen(nfc, onClose = { finish() }, onUnlock = ::askUnlock) } }
    }

    override fun onResume() {
        super.onResume()
        startCardReader { nfc.tryEmit(it) }
    }

    override fun onPause() {
        stopCardReader()
        super.onPause()
    }

    /** Scanning steps need the phone unlocked (NFC and the camera scanner don't work on the lock screen). */
    private fun askUnlock() {
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) km.requestDismissKeyguard(this, null)
    }
}

private fun working() {
    RingService.workingUntil = nowMs() + 30_000
}

@Composable
private fun RingScreen(nfc: SharedFlow<String>, onClose: () -> Unit, onUnlock: () -> Unit) {
    val state by Store.state.collectAsStateWithLifecycle()
    val session = state.session
    LaunchedEffect(session == null) { if (session == null) onClose() }
    if (session == null) return
    val alarm = state.alarms.find { it.id == session.alarmId }
    var showEmergency by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                Instant.ofEpochMilli(session.firedAt).atZone(zone()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
                fontSize = 56.sp, fontWeight = FontWeight.Light,
            )
            Text(alarm?.label?.ifBlank { null } ?: "Good morning", style = MaterialTheme.typography.titleMedium)
            if (session.test) Text("Test run", color = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(32.dp))
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when (session.phase) {
                    Phase.RINGING -> {
                        if (session.rerings > 0) {
                            Text("You didn't answer the still-up check, so it's ringing again.", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                        }
                        HushButton(session, state.settings)
                        Spacer(Modifier.height(12.dp))
                        Text("Step ${session.stepIndex + 1} of ${session.steps.size}", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(16.dp))
                        val step = session.currentStep()
                        // Keyed so each step starts with fresh state.
                        androidx.compose.runtime.key(session.stepIndex, step) {
                            if (step != null) StepView(step, state, nfc, onUnlock)
                        }
                    }
                    Phase.WAITING -> WaitingView(session, state, onClose)
                    Phase.PROMPT -> PromptView(session)
                    Phase.DONE -> {}
                }
            }
            if (session.phase == Phase.RINGING) {
                val ctx = LocalContext.current
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { Wake.swapStep() }) { Text("Can't do this step") }
                    TextButton(onClick = { showEmergency = true }) { Text("Emergency") }
                }
                if (showEmergency) EmergencyDialog(state.settings, onDismiss = { showEmergency = false }, onOk = {
                    showEmergency = false
                    Wake.emergency(ctx)
                })
            }
        }
    }
}

@Composable
private fun StepView(step: Step, state: AppState, nfc: SharedFlow<String>, onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    val done = { Wake.completeStep(ctx) }
    when (step) {
        is Step.Scan -> {
            val tag = state.tags.find { it.id == step.tagId }
            if (tag == null) MissingView("The tag for this step was deleted.") else ScanView(tag, "Scan ${tag.name}", nfc, onUnlock, done)
        }
        is Step.Lights -> if (hasLightSensor(ctx)) LightsView(step.seconds, state.settings.lightsLux, done) else MissingView("This phone has no light sensor.")
        is Step.Type -> TypeView(step.phrase, done)
        is Step.Med -> {
            val med = state.meds.find { it.id == step.medId }
            if (med == null) MissingView("This med was deleted.") else MedView(med, state, nfc, onUnlock, done)
        }
    }
}

@Composable
private fun MissingView(why: String) {
    Text("$why Tap \"Can't do this step\" below to type a code instead.", textAlign = TextAlign.Center)
}

@Composable
private fun ScanView(tag: Tag, title: String, nfc: SharedFlow<String>, onUnlock: () -> Unit, onMatch: () -> Unit) {
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { onUnlock() }
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    when (tag.kind) {
        TagKind.NFC -> {
            Text("Unlock your phone, then hold it to the ${tag.name} card.", textAlign = TextAlign.Center)
            LaunchedEffect(tag) {
                nfc.collect { uid ->
                    working()
                    if (uid == tag.value) onMatch() else error = "That's a different card."
                }
            }
        }
        TagKind.BARCODE -> {
            Button(onClick = {
                working()
                GmsBarcodeScanning.getClient(ctx).startScan()
                    .addOnSuccessListener { code ->
                        if (code.rawValue == tag.value) onMatch() else error = "That's a different code."
                    }
                    .addOnFailureListener { error = "Scanner error: ${it.message}" }
            }, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("Open scanner") }
        }
    }
    error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun LightsView(seconds: Int, threshold: Float, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var lux by remember { mutableFloatStateOf(0f) }
    var brightSince by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(nowMs()) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                lux = e.values[0]
                if (lux >= threshold) {
                    if (brightSince == 0L) brightSince = nowMs()
                    working()
                } else {
                    brightSince = 0L
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sm.getDefaultSensor(Sensor.TYPE_LIGHT), SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sm.unregisterListener(listener) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = nowMs()
            if (brightSince != 0L && now - brightSince >= seconds * 1000L) {
                onDone()
                break
            }
            delay(250)
        }
    }
    val progress = if (brightSince == 0L) 0f else ((now - brightSince) / (seconds * 1000f)).coerceIn(0f, 1f)
    Text("Turn the lights on", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text("Keep the phone in bright light for $seconds seconds. Screen facing up, not covered.", textAlign = TextAlign.Center)
    Spacer(Modifier.height(24.dp))
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(12.dp))
    Spacer(Modifier.height(8.dp))
    Text("Light: ${lux.toInt()} lux (need ${threshold.toInt()})", color = if (lux >= threshold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
}

@Composable
private fun TypeView(phrase: String, onDone: () -> Unit) {
    var input by remember { mutableStateOf("") }
    Text("Type this code", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(phrase.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 26.sp, textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = input,
        onValueChange = {
            input = it
            working()
            if (normalizeCode(it) == phrase) onDone()
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MedView(med: Med, state: AppState, nfc: SharedFlow<String>, onUnlock: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val tag = med.bottleTagId?.let { id -> state.tags.find { it.id == id } }
    var scanned by remember { mutableStateOf(tag == null) }
    var skipping by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    val name = if (med.dose.isBlank()) med.name else "${med.name} ${med.dose}"

    if (!scanned) {
        ScanView(tag!!, "Grab your ${med.name}", nfc, onUnlock) { scanned = true }
        return
    }
    val slot = remember { nearestOpenSlot(med, state.doses, nowMs(), zone()) }
    val already = state.doses.eventFor(slot) != null || med.slotsOn(dateOf(nowMs(), zone()), zone()).let { s -> s.isNotEmpty() && s.all { state.doses.eventFor(it) != null } }
    fun finish(status: DoseStatus?) {
        if (status != null) {
            Store.update { it.logDose(slot, status, nowMs(), if (status == DoseStatus.SKIPPED) reason.trim() else "") }
            if (status == DoseStatus.TAKEN) Notifs.maybeRefillWarning(ctx, med.id)
            Notifs.cancelMed(ctx, slot)
        }
        Store.update { s -> s.copy(session = s.session?.copy(medsDone = s.session.medsDone + med.id)) }
        onDone()
    }

    Text("Take $name", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(24.dp))
    when {
        already -> {
            Text("Already logged today.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { finish(null) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Continue") }
        }
        skipping -> {
            OutlinedTextField(reason, { reason = it; working() }, label = { Text("Why skip?") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(onClick = { finish(DoseStatus.SKIPPED) }, enabled = reason.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Skip this dose") }
            TextButton(onClick = { skipping = false }) { Text("Back") }
        }
        else -> {
            Button(onClick = { finish(DoseStatus.TAKEN) }, Modifier.fillMaxWidth().height(64.dp)) { Text("Taken", fontSize = 20.sp) }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { skipping = true }, Modifier.fillMaxWidth()) { Text("Skip") }
        }
    }
}

/** Quick way to silence it for a bad moment: a short pause, a couple of times per alarm. */
@Composable
private fun HushButton(session: Session, settings: Settings) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(Unit) { while (true) { now = nowMs(); delay(500) } }
    val left = settings.hushMax - session.hushesUsed
    when {
        session.isHushed(now) -> Text(
            "Hushed · rings again in ${(session.hushUntil - now) / 1000 + 1}s",
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary,
        )
        left > 0 -> OutlinedButton(onClick = { Wake.hush(ctx) }) { Text("Hush ${settings.hushSec}s ($left left)") }
        else -> Text("No hushes left", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WaitingView(session: Session, state: AppState, onClose: () -> Unit) {
    val settings = state.settings
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(Unit) { while (true) { now = nowMs(); delay(1000) } }
    val left = ((session.promptAt - now) / 1000).coerceAtLeast(0)
    Text("You're up!", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    Text(
        "Stay up. In ${left / 60}:${"%02d".format(left % 60)} you'll get a \"still up?\" check. " +
            "Answer it within ${settings.stillUpWindowSec} seconds or the alarm starts again.",
        textAlign = TextAlign.Center,
    )
    state.routineRun?.let {
        Spacer(Modifier.height(16.dp))
        com.blakeriding.upright.ui.RoutineCard(it)
    }
    Spacer(Modifier.height(24.dp))
    OutlinedButton(onClick = onClose) { Text("Close") }
}

@Composable
private fun PromptView(session: Session) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(Unit) { while (true) { now = nowMs(); delay(250) } }
    val left = ((session.deadline - now) / 1000).coerceAtLeast(0)
    Text("Still up?", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text("$left seconds", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(24.dp))
    Button(onClick = { Wake.confirmUp(ctx) }, Modifier.size(200.dp)) { Text("I'm up", fontSize = 28.sp) }
}

@Composable
private fun EmergencyDialog(settings: Settings, onDismiss: () -> Unit, onOk: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Emergency stop") },
        text = {
            Column {
                if (settings.codeHash == null) {
                    Text("No emergency code is set up. Use \"Can't do this step\" to type a code instead.")
                } else {
                    Text("Enter the emergency code you printed. This stops the alarm and breaks your streak.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        code, { code = it; wrong = false; working() }, singleLine = true, isError = wrong,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    )
                    if (wrong) Text("Wrong code", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (settings.codeHash != null) TextButton(onClick = {
                if (settings.checkCode(code)) onOk() else {
                    wrong = true
                    Store.update { it.logTamper(TamperKind.WRONG_CODE, nowMs(), "emergency stop") }
                }
            }) { Text("Stop alarm") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
