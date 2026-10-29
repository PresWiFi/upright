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

/** A taken dose counts as on time if logged within this long after it was due. */
const val ON_TIME_MS = HOUR_MS
/** A dose nobody logged becomes "missed" this long after it was due. */
const val MISS_AFTER_MS = 6 * HOUR_MS
/** Reminders re-alert every few minutes for this long, then go quiet. */
const val NAG_LIMIT_MS = 3 * HOUR_MS
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

/** Steps for a (re-)ring. Meds already handled are left out; an empty list falls back to typing. */
fun stepsFor(alarm: Alarm?, medsDone: Set<String>, rnd: Random): List<Step> {
    val steps = alarm?.steps.orEmpty().filterNot { it is Step.Med && it.medId in medsDone }
    return steps.ifEmpty { listOf(Step.Type(randomPhrase(rnd))) }
}

fun startSession(alarm: Alarm, now: Long, test: Boolean, rnd: Random): Session =
    Session(alarmId = alarm.id, firedAt = now, steps = stepsFor(alarm, emptySet(), rnd), test = test, stepStartedAt = now)

val Step.kind: String
    get() = when (this) {
        is Step.Scan -> "scan"
        is Step.Lights -> "lights"
        is Step.Med -> "med"
        is Step.Type -> "type"
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

/** No answer to "still up?": ring again from the first step (minus meds already handled). */
fun Session.reRing(alarm: Alarm?, rnd: Random, now: Long): Session =
    copy(
        phase = Phase.RINGING, stepIndex = 0, steps = stepsFor(alarm, medsDone, rnd), rerings = rerings + 1,
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

// ---------- Meds ----------

data class Slot(val medId: String, val at: Long) {
    val key: String get() = "$medId@$at"
}

enum class SlotState { TAKEN, LATE, SKIPPED, MISSED, DUE, UPCOMING }

fun Med.slotsOn(date: LocalDate, zone: ZoneId): List<Slot> {
    if (!active || date.dayOfWeek.value !in days) return emptyList()
    return times.sorted().map { Slot(id, epochAt(date, it, zone)) }.filter { it.at >= createdAt }
}

fun List<DoseEvent>.eventFor(slot: Slot): DoseEvent? = firstOrNull { it.medId == slot.medId && it.scheduledAt == slot.at }

fun slotState(slot: Slot, doses: List<DoseEvent>, now: Long): SlotState {
    val e = doses.eventFor(slot)
    return when {
        e?.status == DoseStatus.TAKEN -> if (e.at - slot.at <= ON_TIME_MS) SlotState.TAKEN else SlotState.LATE
        e?.status == DoseStatus.SKIPPED -> SlotState.SKIPPED
        now < slot.at -> SlotState.UPCOMING
        now - slot.at < MISS_AFTER_MS -> SlotState.DUE
        else -> SlotState.MISSED
    }
}

/** Every active med's slots on a date. */
fun allSlotsOn(meds: List<Med>, date: LocalDate, zone: ZoneId): List<Slot> =
    meds.flatMap { it.slotsOn(date, zone) }.sortedBy { it.at }

/** Doses due now and not logged (yesterday's late ones included until they count as missed). */
fun dueSlots(meds: List<Med>, doses: List<DoseEvent>, now: Long, zone: ZoneId): List<Slot> {
    val today = dateOf(now, zone)
    return (allSlotsOn(meds, today.minusDays(1), zone) + allSlotsOn(meds, today, zone))
        .filter { slotState(it, doses, now) == SlotState.DUE }
}

/** Reminders nag for [NAG_LIMIT_MS] from when the dose was due, or from when its snooze ended. */
private fun nagStart(slot: Slot, snoozes: Map<String, Long>): Long = maxOf(slot.at, snoozes[slot.key] ?: 0L)

/** Due doses that should alert right now: not snoozed and not past the nag limit. */
fun alertSlots(meds: List<Med>, doses: List<DoseEvent>, snoozes: Map<String, Long>, now: Long, zone: ZoneId): List<Slot> =
    dueSlots(meds, doses, now, zone).filter { (snoozes[it.key] ?: 0L) <= now && now - nagStart(it, snoozes) < NAG_LIMIT_MS }

/** When the reminder alarm should next go off, or null if nothing is coming up. */
fun nextReminderAt(
    meds: List<Med>, doses: List<DoseEvent>, snoozes: Map<String, Long>, now: Long, zone: ZoneId, nagMin: Int,
): Long? {
    val today = dateOf(now, zone)
    val upcoming = (0..2L).flatMap { allSlotsOn(meds, today.plusDays(it), zone) }
        .filter { it.at > now && doses.eventFor(it) == null }
        .minOfOrNull { it.at }
    val nags = dueSlots(meds, doses, now, zone).mapNotNull { slot ->
        val snooze = snoozes[slot.key]
        when {
            snooze != null && snooze > now -> snooze
            now - nagStart(slot, snoozes) < NAG_LIMIT_MS -> minOf(now + nagMin * MINUTE_MS, nagStart(slot, snoozes) + NAG_LIMIT_MS)
            else -> null
        }
    }
    return (listOfNotNull(upcoming) + nags).minOrNull()
}

/** Snooze until you get home, or until the fallback time if home is never detected (before the dose counts as missed). */
fun AppState.snoozeUntilHome(slot: Slot, now: Long): AppState {
    val fallback = minOf(now + 4 * HOUR_MS, slot.at + MISS_AFTER_MS - 15 * MINUTE_MS).coerceAtLeast(now + 15 * MINUTE_MS)
    return copy(snoozes = snoozes + (slot.key to fallback), homeSnoozes = homeSnoozes + slot.key)
}

/** Home Wi-Fi seen: every "until home" snooze ends now. */
fun AppState.arriveHome(now: Long): AppState {
    if (homeSnoozes.isEmpty()) return this
    return copy(snoozes = snoozes + homeSnoozes.associateWith { now }, homeSnoozes = emptySet())
}

/** The dose slot the morning med step should log: the nearest unlogged one today, or "now" as an extra dose. */
fun nearestOpenSlot(med: Med, doses: List<DoseEvent>, now: Long, zone: ZoneId): Slot =
    med.slotsOn(dateOf(now, zone), zone).filter { doses.eventFor(it) == null }.minByOrNull { kotlin.math.abs(it.at - now) }
        ?: Slot(med.id, now)

/** Log (or re-log) a dose and keep the pill count right. */
fun AppState.logDose(slot: Slot, status: DoseStatus, now: Long, note: String = ""): AppState {
    val old = doses.eventFor(slot)
    val delta = when {
        status == DoseStatus.TAKEN && old?.status != DoseStatus.TAKEN -> -1
        status != DoseStatus.TAKEN && old?.status == DoseStatus.TAKEN -> 1
        else -> 0
    }
    return copy(
        doses = doses.filterNot { it === old } + DoseEvent(slot.medId, slot.at, now, status, note),
        meds = meds.map { if (it.id == slot.medId) it.adjustPills(delta) else it },
        snoozes = snoozes - slot.key,
        homeSnoozes = homeSnoozes - slot.key,
    )
}

fun AppState.undoDose(slot: Slot): AppState {
    val old = doses.eventFor(slot) ?: return this
    val delta = if (old.status == DoseStatus.TAKEN) 1 else 0
    return copy(doses = doses.filterNot { it === old }, meds = meds.map { if (it.id == slot.medId) it.adjustPills(delta) else it })
}

private fun Med.adjustPills(sign: Int): Med =
    if (pillCount == null || sign == 0) this else copy(pillCount = (pillCount + sign * pillsPerDose).coerceAtLeast(0.0))

fun Med.dosesPerDay(): Double = times.size * days.size / 7.0

fun Med.daysLeft(): Double? {
    val count = pillCount ?: return null
    val perDay = pillsPerDose * dosesPerDay()
    return if (perDay <= 0) null else count / perDay
}

fun Med.needsRefill(): Boolean = daysLeft()?.let { it <= refillWarnDays } ?: false

/**
 * Days in a row with every scheduled dose taken. Today counts once all its doses are taken;
 * until then it doesn't break the streak. Days with nothing scheduled are skipped over.
 */
fun medStreak(meds: List<Med>, doses: List<DoseEvent>, now: Long, zone: ZoneId): Int {
    val active = meds.filter { it.active }
    if (active.isEmpty()) return 0
    val firstDay = dateOf(active.minOf { it.createdAt }, zone)
    val today = dateOf(now, zone)
    var day = today
    var streak = 0
    while (!day.isBefore(firstDay)) {
        val states = allSlotsOn(active, day, zone).map { slotState(it, doses, now) }
        val allTaken = states.all { it == SlotState.TAKEN || it == SlotState.LATE }
        when {
            states.isEmpty() -> {}
            allTaken -> streak++
            day == today && states.none { it == SlotState.MISSED || it == SlotState.SKIPPED } -> {}
            else -> break
        }
        day = day.minusDays(1)
    }
    return streak
}

// ---------- Export ----------

private fun csv(vararg cells: String): String =
    cells.joinToString(",") { c -> if (c.any { it == ',' || it == '"' || it == '\n' }) "\"" + c.replace("\"", "\"\"") + "\"" else c }

/** One CSV with every dose slot (including missed ones) and every wake-up. */
fun exportCsv(state: AppState, now: Long, zone: ZoneId): String {
    val date = DateTimeFormatter.ISO_LOCAL_DATE
    val time = DateTimeFormatter.ofPattern("HH:mm")
    fun d(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(date)
    fun t(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(time)
    val rows = mutableListOf<Pair<Long, String>>()

    val medsById = state.meds.associateBy { it.id }
    val slots = mutableSetOf<Slot>()
    if (state.meds.isNotEmpty()) {
        var day = dateOf(state.meds.minOf { it.createdAt }, zone)
        val today = dateOf(now, zone)
        while (!day.isAfter(today)) {
            slots += allSlotsOn(state.meds, day, zone).filter { it.at <= now }
            day = day.plusDays(1)
        }
    }
    slots += state.doses.map { Slot(it.medId, it.scheduledAt) }
    for (slot in slots) {
        val med = medsById[slot.medId]
        val e = state.doses.eventFor(slot)
        val name = med?.let { if (it.dose.isBlank()) it.name else "${it.name} ${it.dose}" } ?: "(deleted med)"
        rows += slot.at to csv("dose", d(slot.at), t(slot.at), e?.let { t(it.at) } ?: "", name,
            slotState(slot, state.doses, now).name.lowercase(), e?.note ?: "")
    }
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
    val medTaken: Int,
    val medTotal: Int,
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
    val slots = (0..6L).flatMap { allSlotsOn(state.meds, start.plusDays(it), zone) }.filter { it.at < to }
        .map { slotState(it, state.doses, now) }.filter { it != SlotState.UPCOMING && it != SlotState.DUE }
    val routine = state.routineLogs.filter { !it.test && inWeek(it.startedAt) }.flatMap { it.results }
    return WeekStats(
        wakes = wakes.size,
        avgUpMin = if (ups.isEmpty()) null else ups.average(),
        rerings = wakes.sumOf { it.rerings },
        cleanWakes = wakes.count { it.outcome == WakeOutcome.DONE && it.rerings == 0 },
        hushes = wakes.sumOf { it.hushes },
        emergencies = wakes.count { it.outcome == WakeOutcome.EMERGENCY },
        slowestStep = stepAvg.maxByOrNull { it.value }?.toPair(),
        medTaken = slots.count { it == SlotState.TAKEN || it == SlotState.LATE },
        medTotal = slots.size,
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
    if (cur.medTotal > 0) {
        var line = "Meds: ${pct(cur.medTaken, cur.medTotal)}% taken (${cur.medTaken}/${cur.medTotal})"
        if (prev.medTotal > 0) line += ", last week ${pct(prev.medTaken, prev.medTotal)}%"
        lines += line
    }
    if (cur.routineItems > 0) lines += "Routine: ${pct(cur.routineOnTime, cur.routineItems)}% of items on time"
    if (cur.attempts > 0) lines += "${cur.attempts} attempts to get around the alarm (see Tamper log)"
    if (lines.isEmpty()) lines += "Nothing logged this week yet."
    return lines
}
