package com.blakeriding.upright

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Home-screen widget: today's doses with a one-tap "Take", and the next alarm. */
class MedWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Store.init(context)
        provideContent {
            val state by Store.state.collectAsState()
            Content(context, state)
        }
    }

    companion object {
        suspend fun refresh(context: Context) {
            try {
                MedWidget().updateAll(context)
            } catch (e: Exception) {
                android.util.Log.w("Upright", "Widget update failed", e)
            }
        }
    }
}

class MedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MedWidget()
}

private val BG = Color(0xFF15131F)
private val TEXT = ColorProvider(Color(0xFFECE8F6))
private val MUTED = ColorProvider(Color(0xFF8A85A0))
private val AMBER = ColorProvider(Color(0xFFFFB547))
private val GREEN = ColorProvider(Color(0xFF8BD5B4))
private val RED = ColorProvider(Color(0xFFFF8A80))

@Composable
private fun Content(context: Context, state: AppState) {
    val now = nowMs()
    val fmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    fun time(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone()).format(fmt)
    val slots = allSlotsOn(state.meds, dateOf(now, zone()), zone())
    val states = slots.map { slotState(it, state.doses, now) }
    val taken = states.count { it == SlotState.TAKEN || it == SlotState.LATE }
    val meds = state.meds.associateBy { it.id }
    val open = actionStartActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 1))

    Column(GlanceModifier.fillMaxSize().background(BG).cornerRadius(20.dp).padding(14.dp)) {
        Row(GlanceModifier.fillMaxWidth().clickable(open), verticalAlignment = Alignment.CenterVertically) {
            Text("Meds today", style = TextStyle(color = TEXT, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.width(8.dp))
            if (slots.isNotEmpty()) Text("$taken/${slots.size}", style = TextStyle(color = if (taken == slots.size) GREEN else AMBER, fontSize = 16.sp))
        }
        state.armed?.let { Text("Next alarm ${time(it.at)}", style = TextStyle(color = MUTED, fontSize = 12.sp)) }
        Spacer(GlanceModifier.height(8.dp))
        if (slots.isEmpty()) Text("Nothing scheduled today", style = TextStyle(color = MUTED, fontSize = 13.sp))
        slots.zip(states).take(5).forEach { (slot, st) ->
            val med = meds[slot.medId] ?: return@forEach
            Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(med.name, style = TextStyle(color = TEXT, fontSize = 14.sp), maxLines = 1)
                    Text(time(slot.at), style = TextStyle(color = MUTED, fontSize = 12.sp))
                }
                when (st) {
                    SlotState.DUE, SlotState.UPCOMING -> Button(
                        "Take",
                        onClick = actionSendBroadcast(
                            Intent(context, MedActionReceiver::class.java).setAction(MedActionReceiver.ACTION_TAKEN)
                                .putExtra(MedActionReceiver.EXTRA_MED, slot.medId).putExtra(MedActionReceiver.EXTRA_AT, slot.at)
                                // Unique data so each button gets its own PendingIntent.
                                .setData(android.net.Uri.parse("upright://dose/${slot.key}")),
                        ),
                    )
                    SlotState.TAKEN -> Text("✓ taken", style = TextStyle(color = GREEN, fontSize = 13.sp))
                    SlotState.LATE -> Text("✓ late", style = TextStyle(color = AMBER, fontSize = 13.sp))
                    SlotState.SKIPPED -> Text("skipped", style = TextStyle(color = RED, fontSize = 13.sp))
                    SlotState.MISSED -> Text("missed", style = TextStyle(color = RED, fontSize = 13.sp))
                }
            }
        }
        if (slots.size > 5) Text("+${slots.size - 5} more", style = TextStyle(color = MUTED, fontSize = 12.sp), modifier = GlanceModifier.clickable(open))
    }
}
