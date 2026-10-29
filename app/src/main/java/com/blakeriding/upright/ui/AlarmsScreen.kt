package com.blakeriding.upright.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.blakeriding.upright.Alarm
import com.blakeriding.upright.AppState
import com.blakeriding.upright.Step
import com.blakeriding.upright.Store
import com.blakeriding.upright.Wake
import com.blakeriding.upright.alarmLocked
import com.blakeriding.upright.hasLightSensor
import com.blakeriding.upright.newId
import com.blakeriding.upright.nextTrigger
import com.blakeriding.upright.nowZ
import com.blakeriding.upright.toast
import java.time.Duration

@Composable
fun AlarmsScreen(state: AppState) {
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var pendingToggle by remember { mutableStateOf<Alarm?>(null) }
    var editingRoutine by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item { NextAlarmHeader(state) }
            state.routineRun?.let { run -> item { RoutineCard(run, Modifier.padding(bottom = 8.dp)) } }
            items(state.alarms.sortedBy { it.minuteOfDay }, key = { it.id }) { alarm ->
                val locked = alarmLocked(state, alarm, nowZ())
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { editing = alarm }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(fmtMinute(alarm.minuteOfDay), fontSize = 36.sp)
                                if (locked) Icon(Icons.Default.Lock, "Locked", Modifier.padding(start = 8.dp))
                            }
                            Text(listOf(alarm.label, daysSummary(alarm.days)).filter { it.isNotBlank() }.joinToString(" · "))
                            Text(stepsSummary(alarm, state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(alarm.enabled, onCheckedChange = { on ->
                            if (!on && locked) pendingToggle = alarm else setEnabled(alarm.id, on)
                        })
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { editingRoutine = true }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Morning routine", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (state.routine.isEmpty()) "Add a checklist that starts once you're up (shower, dress, out the door)."
                            else state.routine.joinToString(" → ") { it.name } + " · ${state.routine.sumOf { it.minutes }} min",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
        FloatingActionButton(
            onClick = { editing = Alarm(newId(), 7 * 60, steps = emptyList()) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, "Add alarm") }
    }
    editing?.let { AlarmEditor(it, state, onClose = { editing = null }) }
    if (editingRoutine) RoutineEditor(state.routine, onClose = { editingRoutine = false })
    pendingToggle?.let { a ->
        CodeDialog(state.settings, "This alarm is coming up soon, so turning it off needs your emergency code.",
            onDismiss = { pendingToggle = null }, onOk = { setEnabled(a.id, false); pendingToggle = null })
    }
}

private fun setEnabled(id: String, on: Boolean) =
    Store.update { s -> s.copy(alarms = s.alarms.map { if (it.id == id) it.copy(enabled = on) else it }) }

@Composable
private fun NextAlarmHeader(state: AppState) {
    val now = nowZ()
    val next = state.alarms.mapNotNull { a -> a.nextTrigger(now) }.minOrNull()
    Column(Modifier.padding(vertical = 16.dp)) {
        Text("Alarms", style = MaterialTheme.typography.headlineMedium)
        if (next != null) {
            val d = Duration.between(now, next)
            Text("Next one in ${d.toHours()}h ${d.toMinutes() % 60}m", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (state.alarms.isEmpty()) {
            Text("Tap + to add one. Set up tags and protection in Settings first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun stepName(step: Step, state: AppState): String = when (step) {
    is Step.Scan -> "Scan " + (state.tags.find { it.id == step.tagId }?.name ?: "(deleted tag)")
    is Step.Lights -> "Lights on ${step.seconds}s"
    is Step.Med -> "Take " + (state.meds.find { it.id == step.medId }?.name ?: "(deleted med)")
    is Step.Type -> "Type a code"
}

private fun stepsSummary(alarm: Alarm, state: AppState): String =
    if (alarm.steps.isEmpty()) "No steps: you'll type a code" else alarm.steps.joinToString(" → ") { stepName(it, state) }

@Composable
private fun AlarmEditor(original: Alarm, state: AppState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val isNew = state.alarms.none { it.id == original.id }
    val locked = !isNew && alarmLocked(state, original, nowZ())
    var alarm by remember { mutableStateOf(original) }
    var pickTime by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var needCode by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun save() {
        Store.update { s -> s.copy(alarms = s.alarms.filterNot { it.id == alarm.id } + alarm.copy(enabled = true)) }
        onClose()
    }
    fun delete() {
        Store.update { s -> s.copy(alarms = s.alarms.filterNot { it.id == alarm.id }) }
        onClose()
    }
    fun guarded(action: () -> Unit) = if (locked) needCode = action else action()

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
                    Text(if (isNew) "New alarm" else "Edit alarm", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { guarded(::save) }) { Text("Save") }
                }
                if (locked) {
                    Text(
                        "This alarm is within ${state.settings.guardLeadMin / 60}h of ringing, so saving or deleting changes needs your emergency code.",
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                TextButton(onClick = { pickTime = true }) { Text(fmtMinute(alarm.minuteOfDay), fontSize = 56.sp) }
                DayPicker(alarm.days) { alarm = alarm.copy(days = it) }
                Text(if (alarm.days.isEmpty()) "No days picked: rings once" else daysSummary(alarm.days), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(alarm.label, { alarm = alarm.copy(label = it) }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                SectionTitle("Wake-up steps (in order)")
                if (alarm.steps.isEmpty()) Text("No steps yet. Without any, you'll have to type a random code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                alarm.steps.forEachIndexed { i, step ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${stepName(step, state)}", Modifier.weight(1f))
                        IconButton(onClick = { alarm = alarm.copy(steps = alarm.steps.move(i, -1)) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Up") }
                        IconButton(onClick = { alarm = alarm.copy(steps = alarm.steps.move(i, 1)) }, enabled = i < alarm.steps.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Down") }
                        IconButton(onClick = { alarm = alarm.copy(steps = alarm.steps.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Default.Close, "Remove") }
                    }
                }
                Box {
                    OutlinedButton(onClick = { addMenu = true }) { Text("Add step") }
                    DropdownMenu(addMenu, onDismissRequest = { addMenu = false }) {
                        state.tags.forEach { tag ->
                            DropdownMenuItem(text = { Text("Scan ${tag.name}") }, onClick = { alarm = alarm.copy(steps = alarm.steps + Step.Scan(tag.id)); addMenu = false })
                        }
                        if (hasLightSensor(ctx)) {
                            DropdownMenuItem(text = { Text("Lights on for 30 seconds") }, onClick = { alarm = alarm.copy(steps = alarm.steps + Step.Lights()); addMenu = false })
                        }
                        state.meds.filter { m -> alarm.steps.none { it is Step.Med && it.medId == m.id } }.forEach { med ->
                            DropdownMenuItem(text = { Text("Take ${med.name}") }, onClick = { alarm = alarm.copy(steps = alarm.steps + Step.Med(med.id)); addMenu = false })
                        }
                        if (state.tags.isEmpty()) DropdownMenuItem(text = { Text("Add NFC cards or barcodes in Settings → Tags") }, onClick = { addMenu = false }, enabled = false)
                    }
                }

                if (state.routine.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Start morning routine after", Modifier.weight(1f))
                        Switch(alarm.routine, onCheckedChange = { alarm = alarm.copy(routine = it) })
                    }
                }

                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        if (state.session != null) {
                            ctx.toast("An alarm is already in progress")
                        } else {
                            onClose()
                            Wake.start(ctx, alarm, test = true)
                        }
                    }) { Text("Test now") }
                    if (!isNew) OutlinedButton(onClick = { guarded(::delete) }) { Text("Delete") }
                }
                Text(
                    "Test runs ring for real (with a 1-minute still-up check) but don't count toward your streak.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
    if (pickTime) TimePickerDialog(alarm.minuteOfDay, onDismiss = { pickTime = false }) { alarm = alarm.copy(minuteOfDay = it); pickTime = false }
    needCode?.let { action ->
        CodeDialog(state.settings, "This alarm is coming up soon. Enter your emergency code to change it.",
            onDismiss = { needCode = null }, onOk = { needCode = null; action() })
    }
}

private fun <T> List<T>.move(i: Int, by: Int): List<T> {
    val j = i + by
    if (j !in indices) return this
    return toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
}
