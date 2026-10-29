package com.blakeriding.upright

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class LogicTest {
    private val zone = ZoneId.of("America/New_York")
    private val rnd = Random(1)

    // Wednesday 2026-10-28
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone)
    private fun ms(day: Int, h: Int, m: Int = 0) = at(day, h, m).toInstant().toEpochMilli()

    // ---------- Alarms ----------

    @Test fun nextTriggerLaterToday() {
        val a = Alarm("a", 7 * 60)
        assertEquals(at(28, 7), a.nextTrigger(at(28, 6)))
    }

    @Test fun nextTriggerTomorrowWhenPassed() {
        val a = Alarm("a", 7 * 60)
        assertEquals(at(29, 7), a.nextTrigger(at(28, 7)))
    }

    @Test fun nextTriggerSkipsDays() {
        val a = Alarm("a", 7 * 60, days = setOf(1)) // Mondays
        assertEquals(ZonedDateTime.of(2026, 11, 2, 7, 0, 0, 0, zone), a.nextTrigger(at(28, 8)))
    }

    @Test fun disabledHasNoTrigger() {
        assertNull(Alarm("a", 7 * 60, enabled = false).nextTrigger(at(28, 6)))
    }

    @Test fun dstFallBackKeepsWallClockTime() {
        // US DST ends 2026-11-01. A 7:00 alarm should still ring at 7:00 local.
        val t = Alarm("a", 7 * 60).nextTrigger(ZonedDateTime.of(2026, 10, 31, 8, 0, 0, 0, zone))!!
        assertEquals(LocalDate.of(2026, 11, 1), t.toLocalDate())
        assertEquals(7, t.hour)
    }

    @Test fun dstGapMovesForward() {
        // 2:30 doesn't exist on 2027-03-14 in New York; it should still ring (at 3:30).
        val t = Alarm("a", 2 * 60 + 30).nextTrigger(ZonedDateTime.of(2027, 3, 14, 0, 0, 0, 0, zone))!!
        assertEquals(LocalDate.of(2027, 3, 14), t.toLocalDate())
        assertEquals(3, t.hour)
    }

    @Test fun nextAlarmPicksEarliest() {
        val alarms = listOf(Alarm("late", 9 * 60), Alarm("early", 6 * 60))
        assertEquals("early", nextAlarm(alarms, at(28, 5))!!.first.id)
        assertEquals("late", nextAlarm(alarms, at(28, 7))!!.first.id)
    }

    @Test fun guardOnlyWithinLeadAndWhenOn() {
        val st = AppState(alarms = listOf(Alarm("a", 7 * 60)), settings = Settings(protectionOn = true, guardLeadMin = 120))
        assertFalse(guardActive(st, at(28, 4, 59)))
        assertTrue(guardActive(st, at(28, 5, 0)))
        assertFalse(guardActive(st.copy(settings = st.settings.copy(protectionOn = false)), at(28, 6)))
        assertTrue(alarmLocked(st, st.alarms[0], at(28, 6)))
    }

    @Test fun guardStaysOnDuringSession() {
        val st = AppState(
            alarms = listOf(Alarm("a", 7 * 60)),
            session = Session("a", ms(28, 7), listOf(Step.Lights())),
            settings = Settings(protectionOn = true),
        )
        // 7:30 is long before the next alarm (tomorrow), but the session is still going.
        assertTrue(guardActive(st, at(28, 7, 30)))
        assertTrue(alarmLocked(st, st.alarms[0], at(28, 7, 30)))
    }

    // ---------- Session ----------

    @Test fun sessionWalksStepsThenWaitsThenEnds() {
        val alarm = Alarm("a", 7 * 60, steps = listOf(Step.Scan("t"), Step.Lights()))
        val settings = Settings(stillUpDelayMin = 10, stillUpWindowSec = 60)
        var s = startSession(alarm, ms(28, 7), test = false, rnd)
        assertEquals(Step.Scan("t"), s.currentStep())
        s = s.completeStep(settings, ms(28, 7, 1))!!
        assertEquals(Step.Lights(), s.currentStep())
        s = s.completeStep(settings, ms(28, 7, 2))!!
        assertEquals(Phase.WAITING, s.phase)
        assertEquals(ms(28, 7, 12), s.promptAt)
        assertNull(s.currentStep())
        s = s.toPrompt(settings, ms(28, 7, 12))
        assertEquals(Phase.PROMPT, s.phase)
        assertEquals(ms(28, 7, 13), s.deadline)
    }

    @Test fun noStillUpEndsRightAway() {
        val alarm = Alarm("a", 7 * 60, steps = listOf(Step.Lights()))
        val s = startSession(alarm, 0, false, rnd)
        assertNull(s.completeStep(Settings(stillUpEnabled = false), 1))
    }

    @Test fun testRunHasShortStillUp() {
        val s = startSession(Alarm("a", 0, steps = listOf(Step.Lights())), 0, test = true, rnd).completeStep(Settings(), 0)!!
        assertEquals(MINUTE_MS, s.promptAt)
    }

    @Test fun emptyStepsFallBackToTyping() {
        val s = startSession(Alarm("a", 7 * 60), 0, false, rnd)
        assertTrue(s.steps.single() is Step.Type)
    }

    @Test fun reRingSkipsMedsAlreadyDone() {
        val alarm = Alarm("a", 7 * 60, steps = listOf(Step.Med("m"), Step.Scan("t")))
        val s = startSession(alarm, 0, false, rnd).copy(phase = Phase.PROMPT, medsDone = setOf("m"))
        val r = s.reRing(alarm, rnd)
        assertEquals(Phase.RINGING, r.phase)
        assertEquals(listOf<Step>(Step.Scan("t")), r.steps)
        assertEquals(1, r.rerings)
        assertEquals(0, r.stepIndex)
    }

    @Test fun reRingWithOnlyMedsFallsBackToTyping() {
        val alarm = Alarm("a", 7 * 60, steps = listOf(Step.Med("m")))
        val r = startSession(alarm, 0, false, rnd).copy(medsDone = setOf("m")).reRing(alarm, rnd)
        assertTrue(r.steps.single() is Step.Type)
    }

    @Test fun swapForTypingReplacesOnlyCurrent() {
        val alarm = Alarm("a", 7 * 60, steps = listOf(Step.Lights(), Step.Scan("t")))
        val s = startSession(alarm, 0, false, rnd).copy(stepIndex = 1).swapForTyping(rnd)
        assertEquals(Step.Lights(), s.steps[0])
        assertEquals(24, (s.steps[1] as Step.Type).phrase.length)
    }

    @Test fun endSessionLogsWake() {
        val st = AppState(session = Session("a", 10, listOf(Step.Lights()), rerings = 2)).endSession(WakeOutcome.DONE, 20)
        assertNull(st.session)
        assertEquals(WakeEvent("a", 10, 20, 2, WakeOutcome.DONE), st.wakes.single())
    }

    @Test fun wakeStreakStopsAtEmergencyAndIgnoresTests() {
        val w = listOf(
            WakeEvent("a", 1, 1, 0, WakeOutcome.DONE),
            WakeEvent("a", 2, 2, 0, WakeOutcome.EMERGENCY),
            WakeEvent("a", 3, 3, 1, WakeOutcome.DONE),
            WakeEvent("a", 4, 4, 0, WakeOutcome.EMERGENCY, test = true),
            WakeEvent("a", 5, 5, 0, WakeOutcome.DONE),
        )
        assertEquals(2, wakeStreak(w))
    }

    // ---------- Emergency code ----------

    @Test fun codeCheckIgnoresCaseAndDashes() {
        val code = generateCode(java.util.Random(3))
        assertTrue(Regex("[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}").matches(code))
        val settings = Settings(codeHash = hashEmergencyCode(code, "salt"), codeSalt = "salt")
        assertTrue(settings.checkCode(code.lowercase().replace("-", " ")))
        assertFalse(settings.checkCode("AAAA-AAAA-AAAA"))
        assertFalse(Settings().checkCode(code))
    }

    // ---------- Meds ----------

    private val med = Med("m", "Vitamin D", "1000 IU", times = listOf(8 * 60, 20 * 60), pillCount = 10.0, createdAt = ms(20, 0))

    @Test fun slotStates() {
        val slot = Slot("m", ms(28, 8))
        assertEquals(SlotState.UPCOMING, slotState(slot, emptyList(), ms(28, 7)))
        assertEquals(SlotState.DUE, slotState(slot, emptyList(), ms(28, 9)))
        assertEquals(SlotState.MISSED, slotState(slot, emptyList(), ms(28, 14)))
        assertEquals(SlotState.TAKEN, slotState(slot, listOf(DoseEvent("m", slot.at, ms(28, 8, 30), DoseStatus.TAKEN)), ms(28, 9)))
        assertEquals(SlotState.LATE, slotState(slot, listOf(DoseEvent("m", slot.at, ms(28, 10), DoseStatus.TAKEN)), ms(28, 11)))
        assertEquals(SlotState.SKIPPED, slotState(slot, listOf(DoseEvent("m", slot.at, ms(28, 8), DoseStatus.SKIPPED)), ms(28, 9)))
    }

    @Test fun slotsRespectDaysAndCreation() {
        val weekly = med.copy(days = setOf(3)) // Wednesday
        assertEquals(2, weekly.slotsOn(LocalDate.of(2026, 10, 28), zone).size)
        assertEquals(0, weekly.slotsOn(LocalDate.of(2026, 10, 29), zone).size)
        val newMed = med.copy(createdAt = ms(28, 12))
        assertEquals(listOf(ms(28, 20)), newMed.slotsOn(LocalDate.of(2026, 10, 28), zone).map { it.at })
        assertEquals(0, med.copy(active = false).slotsOn(LocalDate.of(2026, 10, 28), zone).size)
    }

    @Test fun logDoseAdjustsPillsAndUndoRefunds() {
        val slot = Slot("m", ms(28, 8))
        var st = AppState(meds = listOf(med), snoozes = mapOf(slot.key to 1L))
        st = st.logDose(slot, DoseStatus.TAKEN, ms(28, 8, 5))
        assertEquals(9.0, st.meds[0].pillCount!!, 0.0)
        assertTrue(st.snoozes.isEmpty())
        // Re-logging the same slot as taken doesn't double count.
        st = st.logDose(slot, DoseStatus.TAKEN, ms(28, 8, 6))
        assertEquals(9.0, st.meds[0].pillCount!!, 0.0)
        assertEquals(1, st.doses.size)
        // Switching to skipped gives the pill back.
        st = st.logDose(slot, DoseStatus.SKIPPED, ms(28, 8, 7))
        assertEquals(10.0, st.meds[0].pillCount!!, 0.0)
        st = st.logDose(slot, DoseStatus.TAKEN, ms(28, 8, 8)).undoDose(slot)
        assertEquals(10.0, st.meds[0].pillCount!!, 0.0)
        assertTrue(st.doses.isEmpty())
    }

    @Test fun pillCountNeverNegative() {
        val st = AppState(meds = listOf(med.copy(pillCount = 0.5))).logDose(Slot("m", 1), DoseStatus.TAKEN, 1)
        assertEquals(0.0, st.meds[0].pillCount!!, 0.0)
    }

    @Test fun refillMath() {
        // 2 a day, 10 left -> 5 days, warn at 7.
        assertEquals(5.0, med.daysLeft()!!, 0.001)
        assertTrue(med.needsRefill())
        assertFalse(med.copy(pillCount = 30.0).needsRefill())
        assertNull(med.copy(pillCount = null).daysLeft())
        // Weekly: 1 per week, 2 left -> 14 days.
        assertEquals(14.0, med.copy(times = listOf(480), days = setOf(1), pillCount = 2.0).daysLeft()!!, 0.001)
    }

    @Test fun remindersUpcomingNagAndSnooze() {
        val meds = listOf(med)
        // Before 8:00: next reminder is the 8:00 dose.
        assertEquals(ms(28, 8), nextReminderAt(meds, emptyList(), emptyMap(), ms(28, 7), zone, 10))
        // 8:05 and not logged: nag 10 minutes later.
        assertEquals(ms(28, 8, 15), nextReminderAt(meds, emptyList(), emptyMap(), ms(28, 8, 5), zone, 10))
        assertEquals(listOf(Slot("m", ms(28, 8))), alertSlots(meds, emptyList(), emptyMap(), ms(28, 8, 5), zone))
        // Snoozed until 8:20: no alert now, reminder at 8:20.
        val snooze = mapOf(Slot("m", ms(28, 8)).key to ms(28, 8, 20))
        assertTrue(alertSlots(meds, emptyList(), snooze, ms(28, 8, 5), zone).isEmpty())
        assertEquals(ms(28, 8, 20), nextReminderAt(meds, emptyList(), snooze, ms(28, 8, 5), zone, 10))
        // Logged: next is the 20:00 dose.
        val taken = listOf(DoseEvent("m", ms(28, 8), ms(28, 8, 5), DoseStatus.TAKEN))
        assertEquals(ms(28, 20), nextReminderAt(meds, taken, emptyMap(), ms(28, 8, 6), zone, 10))
        // Past the 3h nag limit: quiet until the next dose.
        assertTrue(alertSlots(meds, emptyList(), emptyMap(), ms(28, 11, 30), zone).isEmpty())
        assertEquals(ms(28, 20), nextReminderAt(meds, emptyList(), emptyMap(), ms(28, 11, 30), zone, 10))
    }

    @Test fun nagNeverOvershootsLimit() {
        // At 10:55 a 10-min nag would land past the 11:00 limit; it should stop at the limit.
        assertEquals(ms(28, 11), nextReminderAt(listOf(med), emptyList(), emptyMap(), ms(28, 10, 55), zone, 10))
    }

    @Test fun lateNightDoseStillDueAfterMidnight() {
        val night = med.copy(times = listOf(23 * 60))
        assertEquals(listOf(Slot("m", ms(28, 23))), dueSlots(listOf(night), emptyList(), ms(29, 0, 30), zone))
    }

    @Test fun nearestOpenSlotForMorningStep() {
        assertEquals(Slot("m", ms(28, 8)), nearestOpenSlot(med, emptyList(), ms(28, 7), zone))
        val taken = listOf(DoseEvent("m", ms(28, 8), ms(28, 8), DoseStatus.TAKEN))
        assertEquals(Slot("m", ms(28, 20)), nearestOpenSlot(med, taken, ms(28, 9), zone))
        // Not scheduled today at all: log as an extra dose at "now".
        assertEquals(Slot("m", ms(29, 7)), nearestOpenSlot(med.copy(days = setOf(3)), emptyList(), ms(29, 7), zone))
    }

    @Test fun medStreakCountsFullDays() {
        val m = med.copy(times = listOf(8 * 60), createdAt = ms(25, 0))
        fun took(day: Int) = DoseEvent("m", ms(day, 8), ms(day, 8, 5), DoseStatus.TAKEN)
        val doses = listOf(took(25), took(26), took(27))
        // Today (28th) at 7:00, dose not due yet: doesn't break the streak.
        assertEquals(3, medStreak(listOf(m), doses, ms(28, 7), zone))
        // Today taken: counts.
        assertEquals(4, medStreak(listOf(m), doses + took(28), ms(28, 9), zone))
        // Today missed: breaks.
        assertEquals(0, medStreak(listOf(m), doses, ms(28, 15), zone))
        // A gap breaks it.
        assertEquals(1, medStreak(listOf(m), listOf(took(25), took(27)), ms(28, 7), zone))
        // Skipped breaks it too.
        val skipped = DoseEvent("m", ms(26, 8), ms(26, 8), DoseStatus.SKIPPED)
        assertEquals(1, medStreak(listOf(m), listOf(took(25), skipped, took(27)), ms(28, 7), zone))
    }

    // ---------- Export + storage ----------

    @Test fun exportIncludesMissedDosesAndWakes() {
        val m = med.copy(times = listOf(8 * 60), createdAt = ms(27, 0), name = "Vit, D")
        val st = AppState(
            meds = listOf(m),
            doses = listOf(DoseEvent("m", ms(28, 8), ms(28, 8, 10), DoseStatus.TAKEN)),
            wakes = listOf(WakeEvent("a", ms(28, 7), ms(28, 7, 4), 1, WakeOutcome.DONE)),
            alarms = listOf(Alarm("a", 7 * 60, label = "Work")),
        )
        val lines = exportCsv(st, ms(28, 12), zone).trim().lines()
        assertEquals("kind,date,scheduled,logged,item,status,note", lines[0])
        assertEquals("dose,2026-10-27,08:00,,\"Vit, D 1000 IU\",missed,", lines[1])
        assertEquals("wake,2026-10-28,07:00,07:04,Work,done (re-rang 1x),", lines[2])
        assertEquals("dose,2026-10-28,08:00,08:10,\"Vit, D 1000 IU\",taken,", lines[3])
    }

    @Test fun stateRoundTripsThroughJson() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val st = AppState(
            alarms = listOf(Alarm("a", 420, steps = listOf(Step.Scan("t"), Step.Lights(20), Step.Med("m"), Step.Type("ABC")))),
            tags = listOf(Tag("t", "Bathroom", TagKind.NFC, "04AB")),
            meds = listOf(med),
            session = Session("a", 1, listOf(Step.Lights()), medsDone = setOf("m")),
            snoozes = mapOf("m@1" to 2L),
            armed = Armed("a", 3),
        )
        val back = json.decodeFromString(AppState.serializer(), json.encodeToString(AppState.serializer(), st))
        assertEquals(st, back)
        assertNotNull(json.decodeFromString(AppState.serializer(), """{"future":1}"""))
    }
}
