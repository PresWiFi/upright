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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.blakeriding.upright.AppState
import com.blakeriding.upright.DoseStatus
import com.blakeriding.upright.HomeWifi
import com.blakeriding.upright.snoozeUntilHome
import com.blakeriding.upright.toast
import com.blakeriding.upright.Med
import com.blakeriding.upright.Notifs
import com.blakeriding.upright.Slot
import com.blakeriding.upright.SlotState
import com.blakeriding.upright.Step
import com.blakeriding.upright.Store
import com.blakeriding.upright.allSlotsOn
import com.blakeriding.upright.dateOf
import com.blakeriding.upright.daysLeft
import com.blakeriding.upright.logDose
import com.blakeriding.upright.needsRefill
import com.blakeriding.upright.newId
import com.blakeriding.upright.nowMs
import com.blakeriding.upright.slotState
import com.blakeriding.upright.undoDose
import com.blakeriding.upright.zone
import kotlinx.coroutines.flow.SharedFlow

@Composable
fun MedsScreen(state: AppState, nfc: SharedFlow<String>) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<Med?>(null) }
    var refilling by remember { mutableStateOf<Med?>(null) }
    val now = nowMs()
    val today = allSlotsOn(state.meds, dateOf(now, zone()), zone())
    val medsById = state.meds.associateBy { it.id }

    fun log(slot: Slot, status: DoseStatus) {
        Store.update { it.logDose(slot, status, nowMs(), if (status == DoseStatus.SKIPPED) "Skipped in app" else "") }
        Notifs.cancelMed(ctx, slot)
        if (status == DoseStatus.TAKEN) Notifs.maybeRefillWarning(ctx, slot.medId)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item { Text("Meds", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 16.dp)) }
            if (today.isNotEmpty()) {
                item { SectionTitle("Today") }
                items(today, key = { it.key }) { slot ->
                    val med = medsById.getValue(slot.medId)
                    val st = slotState(slot, state.doses, now)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${fmtTime(slot.at)}  ${med.name} ${med.dose}".trim())
                            val waitingHome = slot.key in state.homeSnoozes
                            Text(
                                if (waitingHome && st == SlotState.DUE) "Reminding you when you get home" else stateLabel(st),
                                style = MaterialTheme.typography.bodySmall, color = stateColor(st),
                            )
                        }
                        when (st) {
                            SlotState.DUE, SlotState.UPCOMING, SlotState.MISSED -> {
                                TextButton(onClick = { log(slot, DoseStatus.TAKEN) }) { Text("Taken") }
                                if (st == SlotState.DUE && state.homeNet != null && slot.key !in state.homeSnoozes) {
                                    TextButton(onClick = {
                                        if (HomeWifi.atHome(ctx)) {
                                            ctx.toast("You're home already")
                                        } else {
                                            Store.update { it.snoozeUntilHome(slot, nowMs()) }
                                            Notifs.cancelMed(ctx, slot)
                                            HomeWifi.sync(ctx)
                                        }
                                    }) { Text("At home") }
                                }
                                TextButton(onClick = { log(slot, DoseStatus.SKIPPED) }) { Text("Skip") }
                            }
                            else -> TextButton(onClick = { Store.update { it.undoDose(slot) } }) { Text("Undo") }
                        }
                    }
                }
            }
            item { SectionTitle("Your meds") }
            if (state.meds.isEmpty()) item { Text("Tap + to add a med.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.meds, key = { it.id }) { med ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { editing = med }) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${med.name} ${med.dose}".trim(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (!med.active) Text("Paused", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${med.times.sorted().joinToString(", ") { fmtMinute(it) }} · ${daysSummary(med.days)}")
                        med.pillCount?.let { count ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (med.needsRefill()) Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 4.dp))
                                val days = med.daysLeft()?.let { " (~${it.toInt()} days)" } ?: ""
                                Text("${fmtPills(count)} left$days", color = if (med.needsRefill()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                TextButton(onClick = { refilling = med }) { Text("Refill") }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
        FloatingActionButton(
            onClick = { editing = Med(newId(), "", createdAt = nowMs()) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, "Add med") }
    }
    editing?.let { MedEditor(it, state, nfc, onClose = { editing = null }) }
    refilling?.let { med -> RefillDialog(med, onClose = { refilling = null }) }
}

private fun stateLabel(s: SlotState) = when (s) {
    SlotState.TAKEN -> "Taken"
    SlotState.LATE -> "Taken late"
    SlotState.SKIPPED -> "Skipped"
    SlotState.MISSED -> "Missed"
    SlotState.DUE -> "Due now"
    SlotState.UPCOMING -> "Upcoming"
}

@Composable
fun stateColor(s: SlotState) = when (s) {
    SlotState.TAKEN -> MaterialTheme.colorScheme.tertiary
    SlotState.LATE, SlotState.DUE -> MaterialTheme.colorScheme.primary
    SlotState.MISSED, SlotState.SKIPPED -> MaterialTheme.colorScheme.error
    SlotState.UPCOMING -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun RefillDialog(med: Med, onClose: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Refill ${med.name}") },
        text = {
            OutlinedTextField(amount, { amount = it }, label = { Text("Pills added") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        },
        confirmButton = {
            TextButton(onClick = {
                val n = amount.toDoubleOrNull()
                if (n != null && n > 0) Store.update { s -> s.copy(meds = s.meds.map { if (it.id == med.id) it.copy(pillCount = (it.pillCount ?: 0.0) + n) else it }) }
                onClose()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun MedEditor(original: Med, state: AppState, nfc: SharedFlow<String>, onClose: () -> Unit) {
    val isNew = state.meds.none { it.id == original.id }
    var med by remember { mutableStateOf(original) }
    var countText by remember { mutableStateOf(original.pillCount?.let { fmtPills(it) } ?: "") }
    var perDoseText by remember { mutableStateOf(fmtPills(original.pillsPerDose)) }
    var warnText by remember { mutableStateOf(original.refillWarnDays.toString()) }
    var tracking by remember { mutableStateOf(original.pillCount != null) }
    var pickTimeFor by remember { mutableIntStateOf(-2) } // -2 closed, -1 new time, else index
    var tagMenu by remember { mutableStateOf(false) }
    var addingTag by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val usedByAlarm = state.alarms.any { a -> a.steps.any { it is Step.Med && it.medId == med.id } }

    fun save() {
        val final = med.copy(
            name = med.name.trim(), dose = med.dose.trim(),
            pillCount = if (tracking) countText.toDoubleOrNull() ?: 0.0 else null,
            pillsPerDose = perDoseText.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0,
            refillWarnDays = warnText.toIntOrNull()?.coerceIn(0, 90) ?: 7,
        )
        Store.update { s -> s.copy(meds = if (isNew) s.meds + final else s.meds.map { if (it.id == final.id) final else it }) }
        onClose()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
                    Text(if (isNew) "New med" else "Edit med", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = ::save, enabled = med.name.isNotBlank() && med.times.isNotEmpty()) { Text("Save") }
                }
                OutlinedTextField(med.name, { med = med.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(med.dose, { med = med.copy(dose = it) }, label = { Text("Dose (e.g. 20 mg)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                SectionTitle("When")
                med.times.sorted().forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { pickTimeFor = med.times.indexOf(t) }) { Text(fmtMinute(t)) }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { med = med.copy(times = med.times - t) }) { Icon(Icons.Default.Close, "Remove time") }
                    }
                }
                OutlinedButton(onClick = { pickTimeFor = -1 }) { Text("Add time") }
                Spacer(Modifier.height(8.dp))
                DayPicker(med.days) { med = med.copy(days = it) }

                SectionTitle("Pill count")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Track pills and warn before running out", Modifier.weight(1f))
                    Switch(tracking, { tracking = it })
                }
                if (tracking) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(countText, { countText = it }, label = { Text("Pills left") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(perDoseText, { perDoseText = it }, label = { Text("Per dose") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(warnText, { warnText = it }, label = { Text("Warn at (days)") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }

                SectionTitle("Bottle tag")
                Text("Scanned during the morning alarm to prove you're holding the bottle.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    val tagName = med.bottleTagId?.let { id -> state.tags.find { it.id == id }?.name } ?: "None"
                    OutlinedButton(onClick = { tagMenu = true }) { Text(tagName) }
                    DropdownMenu(tagMenu, onDismissRequest = { tagMenu = false }) {
                        DropdownMenuItem(text = { Text("None") }, onClick = { med = med.copy(bottleTagId = null); tagMenu = false })
                        state.tags.forEach { tag ->
                            DropdownMenuItem(text = { Text(tag.name) }, onClick = { med = med.copy(bottleTagId = tag.id); tagMenu = false })
                        }
                        DropdownMenuItem(text = { Text("Scan a new one…") }, onClick = { tagMenu = false; addingTag = true })
                    }
                }

                SectionTitle("Other")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Active (reminders on)", Modifier.weight(1f))
                    Switch(med.active, { med = med.copy(active = it) })
                }
                if (!isNew) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete med") }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
    if (pickTimeFor != -2) {
        val idx = pickTimeFor
        TimePickerDialog(if (idx >= 0) med.times[idx] else 8 * 60, onDismiss = { pickTimeFor = -2 }) { t ->
            val times = if (idx >= 0) med.times.toMutableList().also { it[idx] = t } else med.times + t
            med = med.copy(times = times.distinct())
            pickTimeFor = -2
        }
    }
    if (addingTag) AddTagDialog(nfc, defaultName = "${med.name.ifBlank { "Med" }} bottle", onClose = { addingTag = false }) { tag ->
        med = med.copy(bottleTagId = tag.id)
        addingTag = false
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${med.name}?") },
            text = {
                Text(
                    "Its dose history stays in History and exports." +
                        if (usedByAlarm) " It's also removed from your alarm steps." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Store.update { s ->
                        s.copy(
                            meds = s.meds.filterNot { it.id == med.id },
                            alarms = s.alarms.map { a -> a.copy(steps = a.steps.filterNot { it is Step.Med && it.medId == med.id }) },
                        )
                    }
                    confirmDelete = false
                    onClose()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
