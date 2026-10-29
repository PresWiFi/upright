package com.blakeriding.upright.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.blakeriding.upright.Routine
import com.blakeriding.upright.RoutineItem
import com.blakeriding.upright.RoutineRun
import com.blakeriding.upright.Store
import com.blakeriding.upright.current
import com.blakeriding.upright.finishBy
import com.blakeriding.upright.itemDeadline
import com.blakeriding.upright.newId
import com.blakeriding.upright.nowMs
import kotlinx.coroutines.delay

/** The routine in progress: what's done, what's now (with time left), what's next. */
@Composable
fun RoutineCard(run: RoutineRun, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(Unit) { while (true) { now = nowMs(); delay(1000) } }
    val left = (run.itemDeadline() - now) / 1000
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Morning routine", style = MaterialTheme.typography.titleMedium)
            Text("On track to finish by ${fmtTime(run.finishBy())}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            run.items.forEachIndexed { i, item ->
                val result = run.results.getOrNull(i)
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        result != null -> Text(
                            (if (result.skipped) "– " else "✓ ") + item.name, Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textDecoration = TextDecoration.LineThrough,
                        )
                        i == run.index -> {
                            Text("▶ " + item.name, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                            Text(
                                if (left >= 0) "%d:%02d left".format(left / 60, left % 60) else "%d min over".format(-left / 60 + 1),
                                color = if (left >= 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                            )
                        }
                        else -> Text("   ${item.name} · ${item.minutes} min", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (run.current() != null) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Routine.next(ctx, skipped = false) }) { Text("Done") }
                    OutlinedButton(onClick = { Routine.next(ctx, skipped = true) }) { Text("Skip") }
                    TextButton(onClick = { Routine.end(ctx) }) { Text("End") }
                }
            }
        }
    }
}

/** Edit the list of routine items and their time budgets. */
@Composable
fun RoutineEditor(items: List<RoutineItem>, onClose: () -> Unit) {
    var list by remember { mutableStateOf(items) }
    var name by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("5") }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
                    Text("Morning routine", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { Store.update { it.copy(routine = list) }; onClose() }) { Text("Save") }
                }
                Text(
                    "Starts as soon as you finish your wake-up steps. Each item gets a time budget; " +
                        "if you go over, you get a nudge every 5 minutes. Total: ${list.sumOf { it.minutes }} min.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
                )
                list.forEachIndexed { i, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${item.name}", Modifier.weight(1f))
                        Text("${item.minutes} min", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { list = list.swap(i, i - 1) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Up") }
                        IconButton(onClick = { list = list.swap(i, i + 1) }, enabled = i < list.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Down") }
                        IconButton(onClick = { list = list.filterIndexed { j, _ -> j != i } }) { Icon(Icons.Default.Close, "Remove") }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it }, label = { Text("Item (e.g. Shower)") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        minutes, { v -> minutes = v.filter { it.isDigit() }.take(3) }, label = { Text("Min") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(80.dp),
                    )
                }
                OutlinedButton(
                    onClick = {
                        list = list + RoutineItem(newId(), name.trim(), minutes.toIntOrNull()?.coerceIn(1, 180) ?: 5)
                        name = ""
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Add item") }
            }
        }
    }
}

private fun <T> List<T>.swap(i: Int, j: Int): List<T> =
    if (j !in indices) this else toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
