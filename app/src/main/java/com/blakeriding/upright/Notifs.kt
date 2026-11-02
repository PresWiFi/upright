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
            NotificationChannel(CH_ROUTINE, "Morning routine", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CH_ROUTINE_NUDGE, "Morning routine nudges", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_REPORT, "Weekly report", NotificationManager.IMPORTANCE_DEFAULT),
        ))
        // Med tracking was removed: deleting its channels also clears any leftover reminders.
        nm.deleteNotificationChannel("meds")
        nm.deleteNotificationChannel("refill")
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
                context, 5, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 1), PendingIntent.FLAG_IMMUTABLE,
            ))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_REPORT, n)
    }
}
