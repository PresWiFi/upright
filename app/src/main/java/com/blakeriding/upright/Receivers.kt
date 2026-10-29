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
        Store.update { s ->
            val session = s.session ?: return@update s
            val next = session.completeStep(s.settings, nowMs())
            if (next == null) s.endSession(WakeOutcome.DONE, nowMs()) else s.copy(session = next)
        }
        Notifs.cancelStillUp(context)
    }

    fun confirmUp(context: Context) {
        Store.update { s -> if (s.session?.phase == Phase.PROMPT) s.endSession(WakeOutcome.DONE, nowMs()) else s }
        Notifs.cancelStillUp(context)
    }

    fun emergency(context: Context) {
        Store.update { it.endSession(WakeOutcome.EMERGENCY, nowMs()) }
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
            Store.update { st -> st.copy(session = st.session?.reRing(st.alarms.find { it.id == cur.alarmId }, Random.Default)) }
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
        }
        Notifs.cancelMed(context, slot)
        Scheduler.scheduleMeds(context)
    }

    companion object {
        const val ACTION_TAKEN = "com.blakeriding.upright.TAKEN"
        const val ACTION_SKIP = "com.blakeriding.upright.SKIP"
        const val ACTION_SNOOZE = "com.blakeriding.upright.SNOOZE"
        const val EXTRA_MED = "med"
        const val EXTRA_AT = "at"
        const val SNOOZE_MS = 15 * MINUTE_MS
    }
}
