package com.blakeriding.upright

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/**
 * Tells the watch app what the alarm is doing. The watch buzzes while the phone rings, and also
 * keeps its own copy of the next alarm time so it rings even if the phone is dead or out of reach.
 */
object WatchLink {
    const val PATH = "/upright/state"
    const val CAPABILITY = "upright_watch"

    data class Snapshot(
        val ringing: Boolean,
        val hushUntil: Long,
        val firedAt: Long,
        val nextAlarmAt: Long,
        val label: String,
        val lastDoneAt: Long,
    )

    fun snapshot(st: AppState): Snapshot {
        val s = st.session
        val armed = st.armed
        return Snapshot(
            ringing = s?.phase == Phase.RINGING,
            hushUntil = s?.hushUntil ?: 0,
            firedAt = s?.firedAt ?: 0,
            nextAlarmAt = armed?.at ?: 0,
            label = armed?.let { a -> st.alarms.find { it.id == a.alarmId }?.label }.orEmpty(),
            lastDoneAt = st.wakes.lastOrNull()?.doneAt ?: 0,
        )
    }

    fun push(context: Context, snap: Snapshot) {
        val req = PutDataMapRequest.create(PATH).apply {
            dataMap.putBoolean("ringing", snap.ringing)
            dataMap.putLong("hushUntil", snap.hushUntil)
            dataMap.putLong("firedAt", snap.firedAt)
            dataMap.putLong("nextAlarmAt", snap.nextAlarmAt)
            dataMap.putString("label", snap.label)
            dataMap.putLong("lastDoneAt", snap.lastDoneAt)
            dataMap.putLong("sentAt", nowMs())
        }.asPutDataRequest().setUrgent()
        try {
            Wearable.getDataClient(context).putDataItem(req)
                .addOnFailureListener { Log.i("Upright", "No watch link: ${it.message}") }
        } catch (e: Exception) {
            // No Play services / Wear OS on this phone. The alarm works the same without a watch.
            Log.i("Upright", "Watch link unavailable", e)
        }
    }

    /** Calls back with true if a watch with Upright installed is connected right now. */
    fun checkWatch(context: Context, onResult: (Boolean) -> Unit) {
        try {
            Wearable.getCapabilityClient(context).getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener { onResult(it.nodes.isNotEmpty()) }
                .addOnFailureListener { onResult(false) }
        } catch (_: Exception) {
            onResult(false)
        }
    }
}
