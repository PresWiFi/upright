package com.blakeriding.upright.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blakeriding.upright.AppState
import com.blakeriding.upright.SlotState
import com.blakeriding.upright.WakeOutcome
import com.blakeriding.upright.allSlotsOn
import com.blakeriding.upright.dateOf
import com.blakeriding.upright.exportCsv
import com.blakeriding.upright.medStreak
import com.blakeriding.upright.nowMs
import com.blakeriding.upright.slotState
import com.blakeriding.upright.toast
import com.blakeriding.upright.reportLines
import com.blakeriding.upright.wakeStreak
import com.blakeriding.upright.weekStart
import com.blakeriding.upright.weekStats
import com.blakeriding.upright.zone
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(state: AppState) {
    val ctx = LocalContext.current
    val now = nowMs()
    val today = dateOf(now, zone())
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(exportCsv(state, nowMs(), zone()).toByteArray()) }
            ctx.toast("Exported")
        } catch (e: Exception) {
            ctx.toast("Export failed: ${e.message}")
        }
    }
    val days = (0 until 30).map { today.minusDays(it.toLong()) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { Text("History", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 16.dp)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StreakCard("Mornings in a row", wakeStreak(state.wakes), Modifier.weight(1f))
                StreakCard("Days all meds taken", medStreak(state.meds, state.doses, now, zone()), Modifier.weight(1f))
            }
        }
        item {
            val start = weekStart(today)
            val thisWeek = weekStats(state, start, now, zone())
            val lastWeek = weekStats(state, start.minusWeeks(1), now, zone())
            SectionTitle("This week")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    reportLines(thisWeek, lastWeek).forEach { Text("• $it", Modifier.padding(vertical = 2.dp)) }
                }
            }
            if (lastWeek.wakes > 0 || lastWeek.medTotal > 0) {
                SectionTitle("Last week")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        reportLines(lastWeek, weekStats(state, start.minusWeeks(2), now, zone())).forEach {
                            Text("• $it", Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item { SectionTitle("Last 30 days") }
        items(days, key = { it.toEpochDay() }) { day -> DayRow(day, state, now) }
        item {
            OutlinedButton(onClick = { export.launch("upright-${today}.csv") }, modifier = Modifier.padding(vertical = 24.dp)) { Text("Export CSV") }
        }
    }
}

@Composable
private fun StreakCard(label: String, value: Int, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value.toString(), fontSize = 40.sp, fontWeight = FontWeight.Light)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val dayFmt = DateTimeFormatter.ofPattern("EEE MMM d")

@Composable
private fun DayRow(day: LocalDate, state: AppState, now: Long) {
    val wakes = state.wakes.filter { !it.test && dateOf(it.firedAt, zone()) == day }
    val slots = allSlotsOn(state.meds, day, zone()).map { slotState(it, state.doses, now) }
    val taken = slots.count { it == SlotState.TAKEN || it == SlotState.LATE }
    val bad = slots.count { it == SlotState.MISSED || it == SlotState.SKIPPED }
    if (wakes.isEmpty() && slots.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(day.format(dayFmt), Modifier.weight(1f))
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            wakes.forEach { w ->
                val text = when (w.outcome) {
                    WakeOutcome.DONE -> "Up at ${fmtTime(w.doneAt)}" + if (w.rerings > 0) " (re-rang ${w.rerings}x)" else ""
                    WakeOutcome.EMERGENCY -> "Emergency stop"
                }
                Text(text, color = if (w.outcome == WakeOutcome.DONE && w.rerings == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
            }
            if (slots.isNotEmpty()) {
                Text(
                    "Meds $taken/${slots.size}" + if (bad > 0) " · $bad missed/skipped" else "",
                    color = when {
                        bad > 0 -> MaterialTheme.colorScheme.error
                        taken == slots.size -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
