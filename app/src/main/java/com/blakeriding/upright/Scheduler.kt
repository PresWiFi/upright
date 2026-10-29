package com.blakeriding.upright

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/** Tells Android when to wake us: the next alarm, the "still up?" timers, and med reminders. */
object Scheduler {
    const val ACTION_FIRE = "com.blakeriding.upright.FIRE"
    const val ACTION_PROMPT = "com.blakeriding.upright.PROMPT"
    const val ACTION_DEADLINE = "com.blakeriding.upright.DEADLINE"
    const val ACTION_MEDS = "com.blakeriding.upright.MEDS"
    const val ACTION_IM_UP = "com.blakeriding.upright.IM_UP"
    const val ACTION_ROUTINE = "com.blakeriding.upright.ROUTINE"
    const val ACTION_REPORT = "com.blakeriding.upright.REPORT"

    /** Weekly report goes out Sunday at this time. */
    private const val REPORT_MINUTE = 19 * 60

    fun rescheduleAll(context: Context) {
        scheduleAlarm(context)
        scheduleSession(context)
        scheduleMeds(context)
        scheduleRoutine(context)
        scheduleReport(context)
    }

    private fun pi(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0, Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun showIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    /**
     * setAlarmClock is the most reliable kind of alarm: exact, wakes from Doze, and OEM battery
     * savers leave it alone. Android also shows it as the phone's next alarm.
     */
    private fun alarmClock(context: Context, at: Long, action: String) {
        val am = context.getSystemService(AlarmManager::class.java)
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent(context)), pi(context, action))
        } catch (e: SecurityException) {
            Log.e("Upright", "Exact alarms not allowed; falling back", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(context, action))
        }
    }

    private fun cancel(context: Context, action: String) =
        context.getSystemService(AlarmManager::class.java).cancel(pi(context, action))

    fun scheduleAlarm(context: Context) {
        // The planned alarm time passed but it never rang (phone was off, or this reschedule replaced it
        // a moment before it went off). Ring now rather than lose it.
        val st = Store.state.value
        val armed = st.armed
        val now = nowMs()
        if (armed != null && armed.at <= now && now - armed.at < LATE_RING_MS && st.session == null) {
            st.alarms.find { it.id == armed.alarmId && it.enabled }?.let { Wake.start(context, it, test = false) }
        }
        val next = nextAlarm(Store.state.value.alarms, nowZ())
        if (next == null) {
            cancel(context, ACTION_FIRE)
            Store.update { it.copy(armed = null) }
            return
        }
        val at = next.second.toInstant().toEpochMilli()
        alarmClock(context, at, ACTION_FIRE)
        Store.update { it.copy(armed = Armed(next.first.id, at)) }
    }

    private fun scheduleSession(context: Context) {
        val s = Store.state.value.session
        if (s?.phase == Phase.WAITING) alarmClock(context, s.promptAt, ACTION_PROMPT) else cancel(context, ACTION_PROMPT)
        if (s?.phase == Phase.PROMPT) alarmClock(context, s.deadline, ACTION_DEADLINE) else cancel(context, ACTION_DEADLINE)
    }

    /** Exact when allowed, but not an alarm clock (no alarm icon in the status bar). */
    private fun exactQuiet(context: Context, at: Long, action: String) {
        val am = context.getSystemService(AlarmManager::class.java)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(context, action))
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(context, action))
        }
    }

    private fun scheduleRoutine(context: Context) {
        val at = Store.state.value.routineRun?.nextNudgeAt(nowMs())
        // An abandoned routine (null) still gets one more wake-up so it can be closed.
        if (Store.state.value.routineRun == null) cancel(context, ACTION_ROUTINE) else exactQuiet(context, at ?: nowMs() + 1000, ACTION_ROUTINE)
    }

    private fun scheduleReport(context: Context) {
        if (!Store.state.value.settings.weeklyReport) {
            cancel(context, ACTION_REPORT)
            return
        }
        val now = nowZ()
        var date = now.toLocalDate().with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY))
        if (epochAt(date, REPORT_MINUTE, now.zone) <= now.toInstant().toEpochMilli()) date = date.plusWeeks(1)
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochAt(date, REPORT_MINUTE, now.zone), pi(context, ACTION_REPORT))
    }

    fun scheduleMeds(context: Context) {
        val st = Store.state.value
        val at = nextReminderAt(st.meds, st.doses, st.snoozes, nowMs(), zone(), st.settings.nagMin)
        if (at == null) {
            cancel(context, ACTION_MEDS)
            return
        }
        exactQuiet(context, at, ACTION_MEDS)
    }
}
