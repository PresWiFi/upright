package com.blakeriding.upright.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.blakeriding.upright.ALL_DAYS
import com.blakeriding.upright.Settings
import com.blakeriding.upright.checkCode
import com.blakeriding.upright.zone
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val shortTime = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

fun fmtMinute(minuteOfDay: Int): String = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60).format(shortTime)
fun fmtTime(epoch: Long): String = Instant.ofEpochMilli(epoch).atZone(zone()).format(shortTime)

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun daysSummary(days: Set<Int>): String = when (days) {
    emptySet<Int>() -> "Once"
    ALL_DAYS -> "Every day"
    setOf(1, 2, 3, 4, 5) -> "Weekdays"
    setOf(6, 7) -> "Weekends"
    else -> days.sorted().joinToString(" ") { DAY_NAMES[it - 1] }
}

fun fmtPills(n: Double): String = if (n % 1.0 == 0.0) n.toLong().toString() else "%.1f".format(n)

@Composable
fun DayPicker(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (d in 1..7) {
            FilterChip(
                selected = d in days,
                onClick = { onChange(if (d in days) days - d else days + d) },
                label = { Text(DAY_LETTERS[d - 1]) },
                shape = CircleShape,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(initialMinute: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = initialMinute / 60, initialMinute = initialMinute % 60)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks for the emergency code before a protected change. */
@Composable
fun CodeDialog(settings: Settings, why: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Locked") },
        text = {
            Column {
                Text(why)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    code, { code = it; wrong = false }, singleLine = true, isError = wrong, label = { Text("Emergency code") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                )
                if (wrong) Text("Wrong code", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { if (settings.checkCode(code)) onOk() else wrong = true }) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}
