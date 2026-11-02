package com.blakeriding.upright

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

// Pure logic: no Android, no wall clock. Everything takes "now" and a zone so it can be tested.

const val MINUTE_MS = 60_000L
const val HOUR_MS = 60 * MINUTE_MS

/** After a reboot, an alarm missed while the phone was off still rings if it's at most this late. */
const val LATE_RING_MS = 6 * HOUR_MS

private fun timeOf(minuteOfDay: Int): LocalTime = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)

fun epochAt(date: LocalDate, minuteOfDay: Int, zone: ZoneId): Long =
    ZonedDateTime.of(date, timeOf(minuteOfDay), zone).toInstant().toEpochMilli()

fun dateOf(epoch: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate()

// ---------- Alarms ----------

fun Alarm.nextTrigger(now: ZonedDateTime): ZonedDateTime? {
    if (!enabled) return null
    for (i in 0..7L) {
        val date = now.toLocalDate().plusDays(i)
        if (days.isNotEmpty() && date.dayOfWeek.value !in days) continue
        // ZonedDateTime.of moves a time inside a DST gap forward, so the alarm still rings.
        val candidate = ZonedDateTime.of(date, timeOf(minuteOfDay), now.zone)
        if (candidate.isAfter(now)) return candidate
    }
    return null
}

fun nextAlarm(alarms: List<Alarm>, now: ZonedDateTime): Pair<Alarm, ZonedDateTime>? =
    alarms.mapNotNull { a -> a.nextTrigger(now)?.let { a to it } }.minByOrNull { it.second }

/** Protection is on and an alarm is ringing or due within the lead time. */
fun guardActive(state: AppState, now: ZonedDateTime): Boolean =
    state.settings.protectionOn &&
        (state.session != null || state.alarms.any { withinLead(state, it, now) })

/** This alarm can't be changed without the emergency code right now. */
fun alarmLocked(state: AppState, alarm: Alarm, now: ZonedDateTime): Boolean =
    state.settings.protectionOn && (state.session?.alarmId == alarm.id || withinLead(state, alarm, now))

private fun withinLead(state: AppState, alarm: Alarm, now: ZonedDateTime): Boolean {
    val next = alarm.nextTrigger(now) ?: return false
    return next.toInstant().toEpochMilli() - now.toInstant().toEpochMilli() <= state.settings.guardLeadMin * MINUTE_MS
}

// ---------- Wake-up session ----------

private const val CODE_CHARS = "ACDEFGHJKMNPQRTUVWXY34679"

fun randomPhrase(rnd: Random, length: Int = 10): String =
    (1..length).map { CODE_CHARS[rnd.nextInt(CODE_CHARS.length)] }.joinToString("")

/** Steps for a (re-)ring. No steps falls back to typing. */
fun stepsFor(alarm: Alarm?, rnd: Random): List<Step> =
    alarm?.steps.orEmpty().ifEmpty { listOf(Step.Type(randomPhrase(rnd))) }

fun startSession(alarm: Alarm, now: Long, test: Boolean, rnd: Random): Session =
    Session(alarmId = alarm.id, firedAt = now, steps = stepsFor(alarm, rnd), test = test, stepStartedAt = now)

val Step.kind: String
    get() = when (this) {
        is Step.Scan, is Step.ScanAny -> "scan"
        is Step.Lights -> "lights"
        is Step.Type -> "type"
    }

fun Step.usesTag(tagId: String): Boolean = when (this) {
    is Step.Scan -> this.tagId == tagId
    is Step.ScanAny -> tagId in tagIds
    else -> false
}

/** This step with a deleted tag taken out, or null if nothing is left to scan. */
fun Step.withoutTag(tagId: String): Step? = when {
    !usesTag(tagId) -> this
    this is Step.ScanAny -> (tagIds - tagId).let { left -> if (left.size == 1) Step.Scan(left[0]) else left.ifEmpty { null }?.let { Step.ScanAny(it) } }
    else -> null
}

fun Session.currentStep(): Step? = if (phase == Phase.RINGING) steps.getOrNull(stepIndex) else null

/** Swap the current step for a long typing task (for a lost card, broken sensor, etc.). */
fun Session.swapForTyping(rnd: Random): Session =
    copy(steps = steps.toMutableList().also { it[stepIndex] = Step.Type(randomPhrase(rnd, 24)) })

/** Finish the current step: on to the next one, the still-up wait, or [Phase.DONE]. */
fun Session.completeStep(settings: Settings, now: Long): Session {
    if (phase != Phase.RINGING) return this
    val timed = copy(
        stepTimes = stepTimes + StepTime(steps[stepIndex].kind, (now - (if (stepStartedAt > 0) stepStartedAt else firedAt)).coerceAtLeast(0)),
        stepStartedAt = now,
    )
    if (stepIndex + 1 < steps.size) return timed.copy(stepIndex = stepIndex + 1)
    val up = timed.copy(upAt = if (upAt == 0L) now else upAt, hushUntil = 0)
    if (!settings.stillUpEnabled) return up.copy(phase = Phase.DONE)
    val delayMin = if (test) 1 else settings.stillUpDelayMin
    return up.copy(phase = Phase.WAITING, promptAt = now + delayMin * MINUTE_MS)
}

/** Silence the alarm for a short while. Null if it's not ringing or the hushes are used up. */
fun Session.hush(settings: Settings, now: Long): Session? {
    if (phase != Phase.RINGING || hushesUsed >= settings.hushMax || now < hushUntil) return null
    return copy(hushUntil = now + settings.hushSec * 1000L, hushesUsed = hushesUsed + 1)
}

fun Session.isHushed(now: Long): Boolean = phase == Phase.RINGING && now < hushUntil

fun Session.toPrompt(settings: Settings, now: Long): Session =
    copy(phase = Phase.PROMPT, deadline = now + settings.stillUpWindowSec * 1000L)

/** No answer to "still up?": ring again from the first step. */
fun Session.reRing(alarm: Alarm?, rnd: Random, now: Long): Session =
    copy(
        phase = Phase.RINGING, stepIndex = 0, steps = stepsFor(alarm, rnd), rerings = rerings + 1,
        promptAt = 0, deadline = 0, stepStartedAt = now,
    )

fun AppState.endSession(outcome: WakeOutcome, now: Long): AppState {
    val s = session ?: return this
    val event = WakeEvent(s.alarmId, s.firedAt, now, s.rerings, outcome, s.test, s.upAt, s.stepTimes, s.hushesUsed)
    return copy(session = null, wakes = wakes + event)
}

// ---------- Morning routine ----------

/** How often to nudge once the current item is over its time. */
const val ROUTINE_NUDGE_MS = 5 * MINUTE_MS
/** A routine left untouched this long past an item's budget ends by itself. */
const val ROUTINE_ABANDON_MS = HOUR_MS

fun startRoutine(items: List<RoutineItem>, now: Long, test: Boolean): RoutineRun? =
    if (items.isEmpty()) null else RoutineRun(startedAt = now, items = items, test = test)

fun RoutineRun.current(): RoutineItem? = items.getOrNull(index)

fun RoutineRun.itemDeadline(): Long = itemStartedAt + (current()?.minutes ?: 0) * MINUTE_MS

/** When the whole routine should be finished if every item keeps to its budget. */
fun RoutineRun.finishBy(): Long = itemStartedAt + items.drop(index).sumOf { it.minutes } * MINUTE_MS

/** Tick off (or skip) the current item. Ends the routine after the last one. */
fun AppState.routineNext(now: Long, skipped: Boolean): AppState {
    val run = routineRun ?: return this
    val item = run.current() ?: return endRoutine(now)
    val next = run.copy(
        index = run.index + 1, itemStartedAt = now,
        results = run.results + RoutineResult(item.name, item.minutes, now - run.itemStartedAt, skipped),
    )
    return if (next.current() == null) copy(routineRun = next).endRoutine(now) else copy(routineRun = next)
}

fun AppState.endRoutine(now: Long): AppState {
    val run = routineRun ?: return this
    return copy(routineRun = null, routineLogs = (routineLogs + RoutineLog(run.startedAt, now, run.results, run.test)).takeLast(400))
}

/** When to next nudge about the current item, or null if the routine should end (abandoned). */
fun RoutineRun.nextNudgeAt(now: Long): Long? {
    val deadline = itemDeadline()
    return when {
        now < deadline -> deadline
        now - deadline >= ROUTINE_ABANDON_MS -> null
        else -> now + ROUTINE_NUDGE_MS
    }
}

// ---------- Tamper log ----------

fun AppState.logTamper(kind: TamperKind, now: Long, detail: String = ""): AppState =
    copy(tamper = (tamper + TamperEvent(now, kind, detail)).takeLast(300))

/** Things that count as trying to get around the alarm (as opposed to allowed ways out like hush). */
val TamperKind.isAttempt: Boolean
    get() = this !in setOf(TamperKind.HUSH, TamperKind.STEP_SWAPPED, TamperKind.CODE_UNLOCK, TamperKind.KILLED)

/** Mornings in a row finished properly (no emergency skip), newest first. Test runs don't count. */
fun wakeStreak(wakes: List<WakeEvent>): Int =
    wakes.filter { !it.test }.sortedByDescending { it.firedAt }.takeWhile { it.outcome == WakeOutcome.DONE }.size

// ---------- Emergency code ----------

/** 12 characters as XXXX-XXXX-XXXX, from an alphabet without look-alikes (no 0/O, 1/I, 5/S...). */
fun generateCode(rnd: java.util.Random): String =
    (1..12).map { CODE_CHARS[rnd.nextInt(CODE_CHARS.length)] }.chunked(4).joinToString("-") { it.joinToString("") }

fun normalizeCode(input: String): String = input.uppercase().filter { it.isLetterOrDigit() }

fun hashEmergencyCode(code: String, salt: String): String =
    MessageDigest.getInstance("SHA-256").digest((salt + normalizeCode(code)).toByteArray())
        .joinToString("") { "%02x".format(it) }

fun Settings.checkCode(input: String): Boolean {
    val h = codeHash ?: return false
    val salt = codeSalt ?: return false
    return MessageDigest.isEqual(hashEmergencyCode(input, salt).toByteArray(), h.toByteArray())
}

// ---------- Export ----------

private fun csv(vararg cells: String): String =
    cells.joinToString(",") { c -> if (c.any { it == ',' || it == '"' || it == '\n' }) "\"" + c.replace("\"", "\"\"") + "\"" else c }

/** One CSV with every wake-up. */
fun exportCsv(state: AppState, now: Long, zone: ZoneId): String {
    val date = DateTimeFormatter.ISO_LOCAL_DATE
    val time = DateTimeFormatter.ofPattern("HH:mm")
    fun d(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(date)
    fun t(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(time)
    val rows = mutableListOf<Pair<Long, String>>()

    val alarms = state.alarms.associateBy { it.id }
    for (w in state.wakes) {
        val label = alarms[w.alarmId]?.label?.ifBlank { null } ?: "Alarm"
        val outcome = w.outcome.name.lowercase() + (if (w.rerings > 0) " (re-rang ${w.rerings}x)" else "") + (if (w.test) " test" else "")
        rows += w.firedAt to csv("wake", d(w.firedAt), t(w.firedAt), t(w.doneAt), label, outcome, "")
    }
    return (listOf("kind,date,scheduled,logged,item,status,note") + rows.sortedBy { it.first }.map { it.second })
        .joinToString("\n", postfix = "\n")
}

// ---------- Weekly report ----------

data class WeekStats(
    val wakes: Int,
    val avgUpMin: Double?,
    val rerings: Int,
    /** Wake-ups that passed the still-up check first time. */
    val cleanWakes: Int,
    val hushes: Int,
    val emergencies: Int,
    /** Slowest kind of step on average, with its average time. */
    val slowestStep: Pair<String, Long>?,
    val routineOnTime: Int,
    val routineItems: Int,
    val attempts: Int,
)

fun weekStart(date: LocalDate): LocalDate = date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

/** Stats for the 7 days starting [start] (counting only what's already happened by [now]). */
fun weekStats(state: AppState, start: LocalDate, now: Long, zone: ZoneId): WeekStats {
    val from = epochAt(start, 0, zone)
    val to = minOf(epochAt(start.plusDays(7), 0, zone), now)
    fun inWeek(t: Long) = t in from until to
    val wakes = state.wakes.filter { !it.test && inWeek(it.firedAt) }
    val ups = wakes.filter { it.upAt > 0 }.map { (it.upAt - it.firedAt) / 60_000.0 }
    val stepAvg = wakes.flatMap { it.stepTimes }.groupBy { it.kind }.mapValues { (_, v) -> v.map { it.ms }.average().toLong() }
    val routine = state.routineLogs.filter { !it.test && inWeek(it.startedAt) }.flatMap { it.results }
    return WeekStats(
        wakes = wakes.size,
        avgUpMin = if (ups.isEmpty()) null else ups.average(),
        rerings = wakes.sumOf { it.rerings },
        cleanWakes = wakes.count { it.outcome == WakeOutcome.DONE && it.rerings == 0 },
        hushes = wakes.sumOf { it.hushes },
        emergencies = wakes.count { it.outcome == WakeOutcome.EMERGENCY },
        slowestStep = stepAvg.maxByOrNull { it.value }?.toPair(),
        routineOnTime = routine.count { !it.skipped && it.tookMs <= it.budgetMin * MINUTE_MS },
        routineItems = routine.size,
        attempts = state.tamper.count { inWeek(it.at) && it.kind.isAttempt },
    )
}

private fun pct(a: Int, b: Int) = if (b == 0) 0 else (a * 100 + b / 2) / b

/** Plain-language lines for the report card and notification, comparing to the week before. */
fun reportLines(cur: WeekStats, prev: WeekStats): List<String> {
    val lines = mutableListOf<String>()
    if (cur.wakes > 0) {
        val up = cur.avgUpMin?.let { "%.0f".format(it) }
        var line = "${cur.wakes} wake-ups" + (up?.let { ", up in $it min on average" } ?: "")
        if (cur.avgUpMin != null && prev.avgUpMin != null) {
            val d = cur.avgUpMin - prev.avgUpMin
            if (kotlin.math.abs(d) >= 0.5) line += " (" + (if (d < 0) "%.0f min faster" else "%.0f min slower").format(kotlin.math.abs(d)) + " than last week)"
        }
        lines += line
        lines += "${cur.cleanWakes} of ${cur.wakes} passed the still-up check first time" +
            (if (cur.rerings > 0) ", ${cur.rerings} re-rang" else "")
        cur.slowestStep?.let { (kind, ms) -> lines += "Slowest step: $kind (${ms / 1000 / 60}m ${ms / 1000 % 60}s on average)" }
        if (cur.hushes > 0 || cur.emergencies > 0) lines += "${cur.hushes} hushes, ${cur.emergencies} emergency stops"
    }
    if (cur.routineItems > 0) lines += "Routine: ${pct(cur.routineOnTime, cur.routineItems)}% of items on time"
    if (cur.attempts > 0) lines += "${cur.attempts} attempts to get around the alarm (see Tamper log)"
    if (lines.isEmpty()) lines += "Nothing logged this week yet."
    return lines
}
