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
    private const val CH_ROUTINE = "routine"
    private const val CH_ROUTINE_NUDGE = "routine_nudge"
    private const val CH_REPORT = "report"

    const val ID_RING = 1
    private const val ID_STILL_UP = 2
    private const val ID_ROUTINE = 3
    private const val ID_REPORT = 4

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
            NotificationChannel(CH_ROUTINE, "Morning routine", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CH_ROUTINE_NUDGE, "Morning routine nudges", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_REPORT, "Weekly report", NotificationManager.IMPORTANCE_DEFAULT),
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
                .addAction(
                    // "Until home" only makes sense when home is set up and you're not there.
                    if (st.homeNet != null && !HomeWifi.atHome(context)) medAction(context, MedActionReceiver.ACTION_HOME, slot, "Until home")
                    else medAction(context, MedActionReceiver.ACTION_SKIP, slot, "Skip"),
                )
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

    private fun routineAction(context: Context, action: String, label: String): NotificationCompat.Action =
        NotificationCompat.Action(0, label, PendingIntent.getBroadcast(
            context, action.hashCode(), Intent(context, RoutineActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ))

    /** Ongoing card for the current routine item, with a countdown. [alert] = it ran over: buzz. */
    @SuppressLint("MissingPermission")
    fun postRoutine(context: Context, alert: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val run = Store.state.value.routineRun
        val item = run?.current()
        if (run == null || item == null || !canNotify(context)) {
            nm.cancel(ID_ROUTINE)
            return
        }
        val fmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        val finishBy = Instant.ofEpochMilli(run.finishBy()).atZone(zone()).format(fmt)
        val over = nowMs() > run.itemDeadline()
        val n = NotificationCompat.Builder(context, if (alert) CH_ROUTINE_NUDGE else CH_ROUTINE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (over) "Still on: ${item.name}?" else "Now: ${item.name}")
            .setContentText("${run.index + 1} of ${run.items.size} · on track to finish by $finishBy")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setWhen(run.itemDeadline())
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(mainScreen(context))
            .addAction(routineAction(context, RoutineActionReceiver.ACTION_DONE, "Done"))
            .addAction(routineAction(context, RoutineActionReceiver.ACTION_SKIP, "Skip"))
            .addAction(routineAction(context, RoutineActionReceiver.ACTION_END, "End routine"))
            .build()
        // Cancel first so a nudge alerts again even though the card is already showing.
        if (alert) nm.cancel(ID_ROUTINE)
        nm.notify(ID_ROUTINE, n)
    }

    @SuppressLint("MissingPermission")
    fun postWeeklyReport(context: Context) {
        if (!canNotify(context)) return
        val st = Store.state.value
        val now = nowMs()
        val start = weekStart(dateOf(now, zone()))
        val lines = reportLines(weekStats(st, start, now, zone()), weekStats(st, start.minusWeeks(1), now, zone()))
        val n = NotificationCompat.Builder(context, CH_REPORT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Your week with Upright")
            .setContentText(lines.first())
            .setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach { style.addLine(it) } })
            .setContentIntent(PendingIntent.getActivity(
                context, 5, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 2), PendingIntent.FLAG_IMMUTABLE,
            ))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_REPORT, n)
    }
}
