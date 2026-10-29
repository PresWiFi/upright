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
        s = s.completeStep(settings, ms(28, 7, 1))
        assertEquals(Step.Lights(), s.currentStep())
        s = s.completeStep(settings, ms(28, 7, 2))
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
        assertEquals(Phase.DONE, s.completeStep(Settings(stillUpEnabled = false), 1).phase)
    }

    @Test fun testRunHasShortStillUp() {
        val s = startSession(Alarm("a", 0, steps = listOf(Step.Lights())), 0, test = true, rnd).completeStep(Settings(), 0)
        assertEquals(MINUTE_MS, s.promptAt)
    }

    @Test fun emptyStepsFallBackToTyping() {
        val s = startSession(Alarm("a", 7 * 60), 0, false, rnd)
        assertTrue(s.steps.single() is Step.Type)
    }

    @Test fun reRingSkipsMedsAlreadyDone() {
        val alarm = Alarm("a", 7 * 60, steps = listOf(Step.Med("m"), Step.Scan("t")))
        val s = startSession(alarm, 0, false, rnd).copy(phase = Phase.PROMPT, medsDone = setOf("m"))
        val r = s.reRing(alarm, rnd, 0)
        assertEquals(Phase.RINGING, r.phase)
        assertEquals(listOf<Step>(Step.Scan("t")), r.steps)
        assertEquals(1, r.rerings)
        assertEquals(0, r.stepIndex)
    }

    @Test fun reRingWithOnlyMedsFallsBackToTyping() {
        val alarm = Alarm("a", 7 * 60, steps = listOf(Step.Med("m")))
        val r = startSession(alarm, 0, false, rnd).copy(medsDone = setOf("m")).reRing(alarm, rnd, 0)
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
            homeSnoozes = setOf("m@1"),
            homeNet = HomeNet("192.168.1.1", "192.168.1.0/24", listOf("192.168.1.1"), "lan", "2601:0a:1b"),
            armed = Armed("a", 3),
            routine = listOf(RoutineItem("r", "Shower", 10)),
            routineRun = RoutineRun(1, listOf(RoutineItem("r", "Shower", 10))),
            tamper = listOf(TamperEvent(1, TamperKind.HUSH, "45s")),
        )
        val back = json.decodeFromString(AppState.serializer(), json.encodeToString(AppState.serializer(), st))
        assertEquals(st, back)
        assertNotNull(json.decodeFromString(AppState.serializer(), """{"future":1}"""))
    }

    // ---------- v0.2: step timing, hush, routine, home snooze, tamper, weekly report ----------

    @Test fun stepTimesAndUpAtAreRecorded() {
        val alarm = Alarm("a", 7 * 60, steps = listOf(Step.Scan("t"), Step.Lights()))
        var s = startSession(alarm, ms(28, 7), false, rnd)
        s = s.completeStep(Settings(), ms(28, 7, 3))
        s = s.completeStep(Settings(), ms(28, 7, 4))
        assertEquals(listOf(StepTime("scan", 3 * MINUTE_MS), StepTime("lights", MINUTE_MS)), s.stepTimes)
        assertEquals(ms(28, 7, 4), s.upAt)
        // A re-ring keeps the first "up" time.
        s = s.toPrompt(Settings(), ms(28, 7, 14)).reRing(alarm, rnd, ms(28, 7, 15))
        s = s.completeStep(Settings(), ms(28, 7, 16)).completeStep(Settings(), ms(28, 7, 17))
        assertEquals(ms(28, 7, 4), s.upAt)
        val w = AppState(session = s.copy(phase = Phase.PROMPT)).endSession(WakeOutcome.DONE, ms(28, 7, 30)).wakes.single()
        assertEquals(4, w.stepTimes.size)
        assertEquals(ms(28, 7, 4), w.upAt)
    }

    @Test fun hushIsLimitedAndTimed() {
        val settings = Settings(hushSec = 45, hushMax = 2)
        var s = startSession(Alarm("a", 0, steps = listOf(Step.Lights())), 0, false, rnd)
        s = s.hush(settings, 1000)!!
        assertTrue(s.isHushed(45_000))
        assertFalse(s.isHushed(46_000))
        assertNull(s.hush(settings, 2000)) // already hushed
        s = s.hush(settings, 50_000)!!
        assertNull(s.hush(settings, 200_000)) // used up
        assertEquals(2, s.hushesUsed)
        assertNull(s.copy(phase = Phase.WAITING, hushesUsed = 0).hush(settings, 0))
    }

    @Test fun routineRunsAndLogs() {
        val items = listOf(RoutineItem("1", "Shower", 10), RoutineItem("2", "Dress", 5))
        var st = AppState(routineRun = startRoutine(items, ms(28, 7), false))
        val run = st.routineRun!!
        assertEquals(ms(28, 7, 10), run.itemDeadline())
        assertEquals(ms(28, 7, 15), run.finishBy())
        assertEquals(ms(28, 7, 10), run.nextNudgeAt(ms(28, 7, 2)))
        assertEquals(ms(28, 7, 17), run.nextNudgeAt(ms(28, 7, 12)))
        assertNull(run.nextNudgeAt(ms(28, 8, 10)))
        st = st.routineNext(ms(28, 7, 12), skipped = false)
        assertEquals("Dress", st.routineRun!!.current()!!.name)
        st = st.routineNext(ms(28, 7, 14), skipped = true)
        assertNull(st.routineRun)
        val log = st.routineLogs.single()
        assertEquals(listOf(RoutineResult("Shower", 10, 12 * MINUTE_MS), RoutineResult("Dress", 5, 2 * MINUTE_MS, skipped = true)), log.results)
        assertNull(startRoutine(emptyList(), 0, false))
    }

    @Test fun homeSnoozeEndsOnArrivalAndRestartsNag() {
        val meds = listOf(med)
        val slot = Slot("m", ms(28, 8))
        var st = AppState(meds = meds).snoozeUntilHome(slot, ms(28, 8, 5))
        assertEquals(ms(28, 12, 5), st.snoozes[slot.key]) // fallback 4h later
        assertTrue(alertSlots(meds, emptyList(), st.snoozes, ms(28, 11), zone).isEmpty())
        st = st.arriveHome(ms(28, 11))
        assertTrue(st.homeSnoozes.isEmpty())
        // Past the usual 3h nag limit from 8:00, but the nag clock restarted at arrival.
        assertEquals(listOf(slot), alertSlots(meds, emptyList(), st.snoozes, ms(28, 11, 1), zone))
        assertEquals(ms(28, 11, 11), nextReminderAt(meds, emptyList(), st.snoozes, ms(28, 11, 1), zone, 10))
        // Logging the dose clears it.
        assertTrue(st.snoozeUntilHome(slot, 0).logDose(slot, DoseStatus.TAKEN, ms(28, 11)).homeSnoozes.isEmpty())
    }

    @Test fun homeSnoozeFallbackBeforeMissed() {
        val slot = Slot("m", ms(28, 8))
        val st = AppState(meds = listOf(med)).snoozeUntilHome(slot, ms(28, 12))
        assertEquals(ms(28, 13, 45), st.snoozes[slot.key])
    }

    @Test fun tamperLogIsCapped() {
        var st = AppState()
        repeat(310) { st = st.logTamper(TamperKind.BLOCKED_SCREEN, it.toLong()) }
        assertEquals(300, st.tamper.size)
        assertEquals(10L, st.tamper.first().at)
        assertTrue(TamperKind.BLOCKED_SCREEN.isAttempt)
        assertFalse(TamperKind.HUSH.isAttempt)
    }

    @Test fun weeklyStats() {
        val start = LocalDate.of(2026, 10, 26) // Monday
        assertEquals(start, weekStart(LocalDate.of(2026, 11, 1)))
        val wakes = listOf(
            WakeEvent("a", ms(26, 7), ms(26, 7, 20), 0, WakeOutcome.DONE, upAt = ms(26, 7, 4), stepTimes = listOf(StepTime("scan", 120_000), StepTime("lights", 40_000)), hushes = 1),
            WakeEvent("a", ms(27, 7), ms(27, 7, 30), 1, WakeOutcome.DONE, upAt = ms(27, 7, 8), stepTimes = listOf(StepTime("scan", 240_000))),
            WakeEvent("a", ms(28, 7), ms(28, 7, 1), 0, WakeOutcome.EMERGENCY, test = true),
            WakeEvent("a", ms(20, 7), ms(20, 7, 20), 0, WakeOutcome.DONE, upAt = ms(20, 7, 10)),
        )
        val m = med.copy(times = listOf(8 * 60))
        val doses = listOf(DoseEvent("m", ms(26, 8), ms(26, 8), DoseStatus.TAKEN), DoseEvent("m", ms(27, 8), ms(27, 8), DoseStatus.SKIPPED))
        val st = AppState(
            meds = listOf(m), doses = doses, wakes = wakes,
            routineLogs = listOf(RoutineLog(ms(26, 7, 5), ms(26, 7, 30), listOf(RoutineResult("Shower", 10, 9 * MINUTE_MS), RoutineResult("Dress", 5, 8 * MINUTE_MS)))),
            tamper = listOf(TamperEvent(ms(27, 7, 2), TamperKind.BLOCKED_SCREEN), TamperEvent(ms(27, 7, 3), TamperKind.HUSH)),
        )
        val now = ms(28, 12)
        val w = weekStats(st, start, now, zone)
        assertEquals(2, w.wakes)
        assertEquals(6.0, w.avgUpMin!!, 0.001)
        assertEquals(1, w.rerings)
        assertEquals(1, w.cleanWakes)
        assertEquals(1, w.hushes)
        assertEquals("scan" to 180_000L, w.slowestStep)
        assertEquals(1, w.medTaken)
        assertEquals(2, w.medTotal) // 26 taken, 27 skipped; 28 is still due at noon, so not counted yet
        assertEquals(1, w.routineOnTime)
        assertEquals(2, w.routineItems)
        assertEquals(1, w.attempts)
        val lines = reportLines(w, weekStats(st, start.minusWeeks(1), now, zone))
        assertTrue(lines.first(), lines.first().contains("4 min faster"))
    }
}
