package com.blakeriding.upright.watch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val BG = Color(0xFF15131F)
private val AMBER = Color(0xFFFFB547)
private val MUTED = Color(0xFF8A85A0)
private val GREEN = Color(0xFF8BD5B4)

private fun fmt(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** Status: next alarm, whether the phone is connected, last sync. */
class WatchMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        WatchCore.refresh(this)
        setContent { MaterialTheme { Status() } }
    }
}

@Composable
private fun Status() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var phone by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            WatchCore.checkPhone(ctx) { phone = it }
            tick++
            delay(5000)
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    val p = WatchCore.prefs(ctx)
    val next = p.getLong("nextAlarmAt", 0)
    val synced = p.getLong("syncedAt", 0)
    Column(
        Modifier.fillMaxSize().background(BG).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text("Upright", color = AMBER, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(if (next > WatchCore.now()) "Next alarm ${fmt(next)}" else "No alarm set", color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            when (phone) {
                null -> "Checking phone…"
                true -> "Phone connected"
                false -> "Phone not connected"
            },
            color = if (phone == true) GREEN else MUTED, fontSize = 13.sp,
        )
        Text(if (synced > 0) "Synced ${fmt(synced)}" else "Not synced yet. Open Upright on the phone.", color = MUTED, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

/** Shown while the watch buzzes. */
class WatchRingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MaterialTheme { Ringing(onDone = { finish() }) } }
    }
}

@Composable
private fun Ringing(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var phone by remember { mutableStateOf(true) }
    var hold by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            if (!WatchCore.shouldRing(WatchCore.prefs(ctx))) {
                onDone()
                break
            }
            if (tick % 10 == 0) WatchCore.checkPhone(ctx) { phone = it }
            tick++
            delay(1000)
        }
    }
    val p = WatchCore.prefs(ctx)
    val backup = WatchCore.backupActive(p)
    val hushLeft = (WatchCore.hushUntil(p) - WatchCore.now()) / 1000
    Column(
        Modifier.fillMaxSize().background(BG).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text("Time to get up", color = AMBER, fontSize = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                !backup && hushLeft > 0 -> "Hushed for ${hushLeft}s"
                backup && !phone -> "Phone isn't answering"
                backup -> "Phone hasn't started ringing"
                else -> "Finish your steps on the phone"
            },
            color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center,
        )
        // Stopping from the watch is only allowed when the phone can't be reached; otherwise the phone decides.
        if (backup && !phone) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.size(72.dp).background(if (hold > 0f) AMBER else MUTED, CircleShape).pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        val job = scope.launch {
                            val start = System.currentTimeMillis()
                            while (hold < 1f) {
                                hold = ((System.currentTimeMillis() - start) / 3000f).coerceAtMost(1f)
                                delay(50)
                            }
                            WatchCore.stopBackup(ctx)
                        }
                        tryAwaitRelease()
                        job.cancel()
                        if (hold < 1f) hold = 0f
                    })
                },
                contentAlignment = Alignment.Center,
            ) { Text("Hold", color = BG, fontSize = 14.sp) }
            Text("Hold 3s to stop", color = MUTED, fontSize = 11.sp)
        }
    }
}
