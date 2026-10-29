package com.blakeriding.upright

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object Notifs {
    private const val CH_RING = "ring"
    private const val CH_STILL_UP = "still_up"
    private const val CH_MEDS = "meds"
    private const val CH_REFILL = "refill"

    const val ID_RING = 1
    private const val ID_STILL_UP = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            // RingService plays the sound itself, so this channel stays silent.
            NotificationChannel(CH_RING, "Alarm ringing", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
            },
            NotificationChannel(CH_STILL_UP, "Still up? check", NotificationManager.IMPORTANCE_HIGH).apply { setBypassDnd(true) },
            NotificationChannel(CH_MEDS, "Med reminders", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_REFILL, "Refill warnings", NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    private fun ringScreen(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 1,
            Intent(context, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun mainScreen(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    fun ringNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CH_RING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Time to get up")
            .setContentText("Finish your wake-up steps to stop the alarm")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(ringScreen(context))
            .setFullScreenIntent(ringScreen(context), true)
            .build()

    @SuppressLint("MissingPermission") // Checked by canNotify.
    fun postStillUp(context: Context) {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, CH_STILL_UP)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Still up?")
            .setContentText("Tap within a minute or the alarm starts again")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(ringScreen(context))
            .setFullScreenIntent(ringScreen(context), true)
            .setTimeoutAfter(Store.state.value.settings.stillUpWindowSec * 1000L + 5000)
            .addAction(0, "I'm up", PendingIntent.getBroadcast(
                context, 3, Intent(context, AlarmReceiver::class.java).setAction(Scheduler.ACTION_IM_UP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_STILL_UP, n)
    }

    fun cancelStillUp(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID_STILL_UP)

    private fun medNotifId(slot: Slot) = 1000 + (slot.key.hashCode() and 0xFFFFFF)

    fun cancelMed(context: Context, slot: Slot) =
        context.getSystemService(NotificationManager::class.java).cancel(medNotifId(slot))

    private fun medAction(context: Context, action: String, slot: Slot, label: String): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(
            context, (slot.key + action).hashCode(),
            Intent(context, MedActionReceiver::class.java).setAction(action)
                .putExtra(MedActionReceiver.EXTRA_MED, slot.medId).putExtra(MedActionReceiver.EXTRA_AT, slot.at),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action(0, label, pi)
    }

    /** Alerts for every due dose (re-alerting until answered), and clears ones already logged. */
    @SuppressLint("MissingPermission")
    fun postMedAlerts(context: Context) {
        if (!canNotify(context)) return
        val st = Store.state.value
        val now = nowMs()
        val nm = context.getSystemService(NotificationManager::class.java)
        val alerts = alertSlots(st.meds, st.doses, st.snoozes, now, zone())
        val fmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        for (slot in alerts) {
            val med = st.meds.find { it.id == slot.medId } ?: continue
            val due = Instant.ofEpochMilli(slot.at).atZone(zone()).format(fmt)
            val title = if (med.dose.isBlank()) med.name else "${med.name} ${med.dose}"
            val n = NotificationCompat.Builder(context, CH_MEDS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Take $title")
                .setContentText("Due $due")
                .setSubText(if (med.needsRefill()) "Running low" else null)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setContentIntent(mainScreen(context))
                .addAction(medAction(context, MedActionReceiver.ACTION_TAKEN, slot, "Taken"))
                .addAction(medAction(context, MedActionReceiver.ACTION_SNOOZE, slot, "Snooze 15m"))
                .addAction(medAction(context, MedActionReceiver.ACTION_SKIP, slot, "Skip"))
                .build()
            nm.notify(medNotifId(slot), n)
        }
        // Clear reminders for doses that were logged somewhere else (app, alarm) or became missed.
        val stillDue = dueSlots(st.meds, st.doses, now, zone()).map { medNotifId(it) }.toSet()
        nm.activeNotifications.filter { it.id >= 1000 && it.id !in stillDue }.forEach { nm.cancel(it.id) }
    }

    @SuppressLint("MissingPermission")
    fun maybeRefillWarning(context: Context, medId: String) {
        if (!canNotify(context)) return
        val med = Store.state.value.meds.find { it.id == medId } ?: return
        if (!med.needsRefill()) return
        val days = med.daysLeft()?.toInt() ?: return
        val n = NotificationCompat.Builder(context, CH_REFILL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Refill ${med.name} soon")
            .setContentText("About $days day${if (days == 1) "" else "s"} left")
            .setContentIntent(mainScreen(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(500 + (medId.hashCode() and 0xFF), n)
    }
}
