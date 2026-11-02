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

    @Test fun reRingStartsOverFromFirstStep() {
        val alarm = Alarm("a", 7 * 60, steps = listOf(Step.Lights(), Step.Scan("t")))
        val r = startSession(alarm, 0, false, rnd).copy(phase = Phase.PROMPT, stepIndex = 1).reRing(alarm, rnd, 0)
        assertEquals(Phase.RINGING, r.phase)
        assertEquals(alarm.steps, r.steps)
        assertEquals(1, r.rerings)
        assertEquals(0, r.stepIndex)
    }

    @Test fun deletingATagShrinksScanSteps() {
        val any = Step.ScanAny(listOf("a", "b", "c"))
        assertTrue(any.usesTag("b"))
        assertFalse(Step.Lights().usesTag("b"))
        assertEquals(Step.ScanAny(listOf("a", "c")), any.withoutTag("b"))
        assertEquals(Step.Scan("a"), Step.ScanAny(listOf("a", "b")).withoutTag("b"))
        assertNull(Step.ScanAny(listOf("b")).withoutTag("b"))
        assertNull(Step.Scan("b").withoutTag("b"))
        assertEquals(Step.Scan("a"), Step.Scan("a").withoutTag("b"))
        assertEquals("scan", any.kind)
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

    // ---------- Export + storage ----------

    @Test fun exportListsWakes() {
        val st = AppState(
            wakes = listOf(WakeEvent("a", ms(28, 7), ms(28, 7, 4), 1, WakeOutcome.DONE), WakeEvent("a", ms(27, 7), ms(27, 7, 2), 0, WakeOutcome.DONE)),
            alarms = listOf(Alarm("a", 7 * 60, label = "Work, early")),
        )
        val lines = exportCsv(st, ms(28, 12), zone).trim().lines()
        assertEquals("kind,date,scheduled,logged,item,status,note", lines[0])
        assertEquals("wake,2026-10-27,07:00,07:02,\"Work, early\",done,", lines[1])
        assertEquals("wake,2026-10-28,07:00,07:04,\"Work, early\",done (re-rang 1x),", lines[2])
    }

    @Test fun stateRoundTripsThroughJson() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val st = AppState(
            alarms = listOf(Alarm("a", 420, steps = listOf(Step.Scan("t"), Step.ScanAny(listOf("t", "u")), Step.Lights(20), Step.Type("ABC")))),
            tags = listOf(Tag("t", "Bathroom", TagKind.NFC, "04AB"), Tag("u", "Kitchen", TagKind.BARCODE, "XYZ")),
            session = Session("a", 1, listOf(Step.Lights())),
            armed = Armed("a", 3),
            routine = listOf(RoutineItem("r", "Shower", 10)),
            routineRun = RoutineRun(1, listOf(RoutineItem("r", "Shower", 10))),
            tamper = listOf(TamperEvent(1, TamperKind.HUSH, "45s")),
        )
        val back = json.decodeFromString(AppState.serializer(), json.encodeToString(AppState.serializer(), st))
        assertEquals(st, back)
        assertNotNull(json.decodeFromString(AppState.serializer(), """{"future":1}"""))
    }

    @Test fun oldMedDataLoadsWithoutMedSteps() {
        val old = """{
            "alarms": [{"id": "a", "minuteOfDay": 420, "steps": [{"type": "med", "medId": "m"}, {"type": "scan", "tagId": "t"}]}],
            "meds": [{"id": "m", "name": "Vitamin D"}],
            "doses": [], "snoozes": {"m@1": 2}, "homeSnoozes": [], "homeNet": null,
            "session": {"alarmId": "a", "firedAt": 1, "steps": [{"type": "scan", "tagId": "t"}, {"type": "med", "medId": "m"}], "stepIndex": 1, "medsDone": ["m"]}
        }"""
        val st = Store.decode(old)
        assertEquals(listOf<Step>(Step.Scan("t")), st.alarms.single().steps)
        val steps = st.session!!.steps
        assertEquals(Step.Scan("t"), steps[0])
        assertTrue(steps[1] is Step.Type) // keeps the in-progress step count the same
    }

    // ---------- v0.2: step timing, hush, routine, tamper, weekly report ----------

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
        val st = AppState(
            wakes = wakes,
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
        assertEquals(1, w.routineOnTime)
        assertEquals(2, w.routineItems)
        assertEquals(1, w.attempts)
        val lines = reportLines(w, weekStats(st, start.minusWeeks(1), now, zone))
        assertTrue(lines.first(), lines.first().contains("4 min faster"))
    }
}
