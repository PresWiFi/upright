package com.blakeriding.upright

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ISO day numbers: 1 = Monday ... 7 = Sunday. */
val ALL_DAYS: Set<Int> = (1..7).toSet()

@Serializable
enum class TagKind { NFC, BARCODE }

/** Something physical to scan: an NFC card/sticker (value = UID hex) or a barcode/QR (value = raw text). */
@Serializable
data class Tag(val id: String, val name: String, val kind: TagKind, val value: String)

/** One thing you must do to stop the alarm. */
@Serializable
sealed interface Step {
    @Serializable @SerialName("scan")
    data class Scan(val tagId: String) : Step

    /** Light sensor must read bright for [seconds] in a row. */
    @Serializable @SerialName("lights")
    data class Lights(val seconds: Int = 30) : Step

    /** Scan the med's bottle (if it has a tag), then confirm Taken or Skip. */
    @Serializable @SerialName("med")
    data class Med(val medId: String) : Step

    /** Type a random code. Used as a fallback when another step can't be done. */
    @Serializable @SerialName("type")
    data class Type(val phrase: String) : Step
}

@Serializable
data class Alarm(
    val id: String,
    val minuteOfDay: Int,
    /** Empty = rings once, then switches itself off. */
    val days: Set<Int> = ALL_DAYS,
    val enabled: Boolean = true,
    val label: String = "",
    val steps: List<Step> = emptyList(),
)

@Serializable
data class Med(
    val id: String,
    val name: String,
    val dose: String = "",
    /** Dose times as minutes after midnight. */
    val times: List<Int> = listOf(8 * 60),
    val days: Set<Int> = ALL_DAYS,
    val pillsPerDose: Double = 1.0,
    /** Null = not counting pills. */
    val pillCount: Double? = null,
    val refillWarnDays: Int = 7,
    val bottleTagId: String? = null,
    val active: Boolean = true,
    val createdAt: Long = 0,
)

@Serializable
enum class DoseStatus { TAKEN, SKIPPED }

/** One logged dose. A dose slot is identified by (medId, scheduledAt). */
@Serializable
data class DoseEvent(
    val medId: String,
    val scheduledAt: Long,
    val at: Long,
    val status: DoseStatus,
    val note: String = "",
)

@Serializable
enum class Phase {
    /** Ringing until every step is done. */
    RINGING,
    /** Steps done, waiting to ask "still up?". */
    WAITING,
    /** Asking "still up?"; no answer by [Session.deadline] means it rings again. */
    PROMPT,
}

@Serializable
data class Session(
    val alarmId: String,
    val firedAt: Long,
    val steps: List<Step>,
    val phase: Phase = Phase.RINGING,
    val stepIndex: Int = 0,
    val promptAt: Long = 0,
    val deadline: Long = 0,
    val rerings: Int = 0,
    /** Meds already handled this morning, so a re-ring doesn't ask again. */
    val medsDone: Set<String> = emptySet(),
    val test: Boolean = false,
)

@Serializable
enum class WakeOutcome { DONE, EMERGENCY }

@Serializable
data class WakeEvent(
    val alarmId: String,
    val firedAt: Long,
    val doneAt: Long,
    val rerings: Int,
    val outcome: WakeOutcome,
    val test: Boolean = false,
)

@Serializable
data class Settings(
    val stillUpEnabled: Boolean = true,
    val stillUpDelayMin: Int = 10,
    val stillUpWindowSec: Int = 60,
    /** Light sensor reading (lux) that counts as "lights on". */
    val lightsLux: Float = 60f,
    /** Protection kicks in this long before each alarm and lasts until it's done. */
    val guardLeadMin: Int = 120,
    val protectionOn: Boolean = false,
    val codeHash: String? = null,
    val codeSalt: String? = null,
    val nagMin: Int = 10,
)

/** The alarm that's currently scheduled with Android, so a reboot can tell if it was missed. */
@Serializable
data class Armed(val alarmId: String, val at: Long)

@Serializable
data class AppState(
    val alarms: List<Alarm> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val meds: List<Med> = emptyList(),
    val doses: List<DoseEvent> = emptyList(),
    val wakes: List<WakeEvent> = emptyList(),
    val session: Session? = null,
    val settings: Settings = Settings(),
    /** Dose slot key -> snoozed until (epoch ms). */
    val snoozes: Map<String, Long> = emptyMap(),
    val armed: Armed? = null,
)
