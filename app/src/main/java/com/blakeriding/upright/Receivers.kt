package com.blakeriding.upright

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.random.Random

/** Starting, advancing and ending a wake-up. Shared by the receivers and the ringing screen. */
object Wake {
    fun start(context: Context, alarm: Alarm, test: Boolean) {
        val started = Store.update { s ->
            // A real alarm takes over from a test run; otherwise one wake-up at a time.
            if (s.session != null && !(s.session.test && !test)) return@update s
            // One-time alarms switch themselves off once they ring.
            val alarms = if (!test && alarm.days.isEmpty()) s.alarms.map { if (it.id == alarm.id) it.copy(enabled = false) else it } else s.alarms
            s.copy(session = startSession(alarm, nowMs(), test, Random.Default), alarms = alarms)
        }
        if (started.session != null) RingService.start(context)
    }

    /** Current step done. Moves to the next step, the still-up wait, or ends the wake-up. */
    fun completeStep(context: Context) {
        val now = nowMs()
        val after = Store.update { s ->
            val session = s.session ?: return@update s
            val next = session.completeStep(s.settings, now)
            var st = s.copy(session = next)
            // First time you're up: start the morning routine.
            if (session.upAt == 0L && next.upAt != 0L && st.routineRun == null) {
                val alarm = s.alarms.find { it.id == session.alarmId }
                if (alarm?.routine != false) st = st.copy(routineRun = startRoutine(s.routine, now, session.test))
            }
            if (next.phase == Phase.DONE) st.endSession(WakeOutcome.DONE, now) else st
        }
        Notifs.cancelStillUp(context)
        if (after.routineRun != null) Notifs.postRoutine(context, alert = false)
    }

    /** The quick hush: silent for a short while, a limited number of times per alarm. */
    fun hush(context: Context): Boolean {
        val now = nowMs()
        var ok = false
        Store.update { s ->
            val hushed = s.session?.hush(s.settings, now) ?: return@update s
            ok = true
            s.copy(session = hushed).logTamper(TamperKind.HUSH, now, "${s.settings.hushSec}s")
        }
        return ok
    }

    fun swapStep() {
        Store.update { s ->
            val session = s.session ?: return@update s
            val step = session.currentStep() ?: return@update s
            s.copy(session = session.swapForTyping(Random.Default)).logTamper(TamperKind.STEP_SWAPPED, nowMs(), step.kind)
        }
    }

    fun confirmUp(context: Context) {
        Store.update { s -> if (s.session?.phase == Phase.PROMPT) s.endSession(WakeOutcome.DONE, nowMs()) else s }
        Notifs.cancelStillUp(context)
    }

    fun emergency(context: Context) {
        Store.update { it.logTamper(TamperKind.EMERGENCY_STOP, nowMs()).endSession(WakeOutcome.EMERGENCY, nowMs()) }
        Notifs.cancelStillUp(context)
    }

    /** Catch up on any still-up timer that came due (also used after a reboot). */
    fun advanceTimers(context: Context) {
        val now = nowMs()
        val s = Store.state.value
        val session = s.session ?: return
        // Small slack: Android may deliver the alarm a moment early.
        if (session.phase == Phase.WAITING && now >= session.promptAt - 2000) {
            Store.update { it.copy(session = it.session?.toPrompt(it.settings, now)) }
            Notifs.postStillUp(context)
        }
        val cur = Store.state.value.session ?: return
        if (cur.phase == Phase.PROMPT && now >= cur.deadline - 1000) {
            Store.update { st -> st.copy(session = st.session?.reRing(st.alarms.find { it.id == cur.alarmId }, Random.Default, now)) }
            Notifs.cancelStillUp(context)
            RingService.start(context)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        when (intent.action) {
            Scheduler.ACTION_FIRE -> {
                val st = Store.state.value
                val alarm = st.armed?.let { a -> st.alarms.find { it.id == a.alarmId } }
                if (alarm != null) Wake.start(context, alarm, test = false)
                Scheduler.scheduleAlarm(context)
            }
            Scheduler.ACTION_PROMPT, Scheduler.ACTION_DEADLINE -> Wake.advanceTimers(context)
            Scheduler.ACTION_MEDS -> Notifs.postMedAlerts(context)
            Scheduler.ACTION_IM_UP -> Wake.confirmUp(context)
            Scheduler.ACTION_ROUTINE -> Routine.nudge(context)
            Scheduler.ACTION_REPORT -> Notifs.postWeeklyReport(context)
        }
        Scheduler.rescheduleAll(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        val session = Store.state.value.session
        // A wake-up was in progress when the phone restarted: pick it right back up.
        if (session?.phase == Phase.RINGING) RingService.start(context) else if (session != null) Wake.advanceTimers(context)
        // Also rings now for an alarm that was due while the phone was off (see Scheduler.scheduleAlarm).
        Scheduler.rescheduleAll(context)
        Notifs.postMedAlerts(context)
        if (Store.state.value.routineRun != null) Notifs.postRoutine(context, alert = false)
        HomeWifi.sync(context)
    }
}

/** Taken / Snooze / Skip buttons on med reminders. */
class MedActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        val medId = intent.getStringExtra(EXTRA_MED) ?: return
        val slot = Slot(medId, intent.getLongExtra(EXTRA_AT, 0))
        val now = nowMs()
        when (intent.action) {
            ACTION_TAKEN -> {
                Store.update { it.logDose(slot, DoseStatus.TAKEN, now) }
                Notifs.maybeRefillWarning(context, medId)
            }
            ACTION_SKIP -> Store.update { it.logDose(slot, DoseStatus.SKIPPED, now, "Skipped from reminder") }
            ACTION_SNOOZE -> Store.update { it.copy(snoozes = it.snoozes + (slot.key to now + SNOOZE_MS)) }
            ACTION_HOME -> Store.update { it.snoozeUntilHome(slot, now) }
        }
        Notifs.cancelMed(context, slot)
        Scheduler.scheduleMeds(context)
        HomeWifi.sync(context)
    }

    companion object {
        const val ACTION_TAKEN = "com.blakeriding.upright.TAKEN"
        const val ACTION_SKIP = "com.blakeriding.upright.SKIP"
        const val ACTION_SNOOZE = "com.blakeriding.upright.SNOOZE"
        const val ACTION_HOME = "com.blakeriding.upright.SNOOZE_HOME"
        const val EXTRA_MED = "med"
        const val EXTRA_AT = "at"
        const val SNOOZE_MS = 15 * MINUTE_MS
    }
}

/** The morning routine: tick items off from the notification, the ring screen or the app. */
object Routine {
    fun next(context: Context, skipped: Boolean) {
        Store.update { it.routineNext(nowMs(), skipped) }
        Notifs.postRoutine(context, alert = false)
    }

    fun end(context: Context) {
        Store.update { it.endRoutine(nowMs()) }
        Notifs.postRoutine(context, alert = false)
    }

    /** The current item ran over its time: nudge, or give up on a routine that's been left alone. */
    fun nudge(context: Context) {
        val run = Store.state.value.routineRun ?: return
        val now = nowMs()
        if (run.nextNudgeAt(now) == null) end(context)
        else if (now >= run.itemDeadline() - 2000) Notifs.postRoutine(context, alert = true)
    }
}

class RoutineActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        when (intent.action) {
            ACTION_DONE -> Routine.next(context, skipped = false)
            ACTION_SKIP -> Routine.next(context, skipped = true)
            ACTION_END -> Routine.end(context)
        }
        Scheduler.rescheduleAll(context)
    }

    companion object {
        const val ACTION_DONE = "com.blakeriding.upright.ROUTINE_DONE"
        const val ACTION_SKIP = "com.blakeriding.upright.ROUTINE_SKIP"
        const val ACTION_END = "com.blakeriding.upright.ROUTINE_END"
    }
}
