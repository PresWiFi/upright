package com.blakeriding.upright

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.random.Random

/**
 * All app data in one JSON file. It lives in device-protected storage so the alarm can read it
 * (and ring) right after a reboot, before the phone is unlocked.
 */
object Store {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var file: AtomicFile? = null
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val de = context.applicationContext.createDeviceProtectedStorageContext()
        val f = AtomicFile(File(de.filesDir, "state.json"))
        file = f
        _state.value = try {
            decode(String(f.readFully()))
        } catch (_: java.io.FileNotFoundException) {
            AppState()
        } catch (e: Exception) {
            // Keep the unreadable file for inspection rather than overwriting it silently.
            Log.e("Upright", "State file unreadable, starting fresh", e)
            f.baseFile.copyTo(File(de.filesDir, "state.broken-${System.currentTimeMillis()}.json"), overwrite = true)
            AppState()
        }
    }

    fun decode(text: String): AppState =
        json.decodeFromJsonElement(AppState.serializer(), dropMedSteps(json.parseToJsonElement(text)))

    /**
     * Med steps were removed along with med tracking. Drop them from saved alarms; in a wake-up that's in progress,
     * swap them for typing so the step numbering stays right.
     */
    private fun dropMedSteps(root: JsonElement): JsonElement {
        if (root !is JsonObject) return root
        fun isMed(step: JsonElement) = (step as? JsonObject)?.get("type")?.jsonPrimitive?.content == "med"
        fun steps(o: JsonObject) = o["steps"] as? JsonArray ?: JsonArray(emptyList())
        val out = root.toMutableMap()
        (root["alarms"] as? JsonArray)?.let { alarms ->
            out["alarms"] = JsonArray(alarms.map { a ->
                if (a is JsonObject) JsonObject(a + ("steps" to JsonArray(steps(a).filterNot(::isMed)))) else a
            })
        }
        (root["session"] as? JsonObject)?.let { s ->
            val typing = JsonObject(mapOf("type" to JsonPrimitive("type"), "phrase" to JsonPrimitive(randomPhrase(Random.Default))))
            out["session"] = JsonObject(s + ("steps" to JsonArray(steps(s).map { if (isMed(it)) typing else it })))
        }
        return JsonObject(out)
    }

    @Synchronized
    fun update(change: (AppState) -> AppState): AppState {
        val old = _state.value
        val new = change(old)
        if (new != old) {
            save(new)
            _state.value = new
        }
        return new
    }

    private fun save(state: AppState) {
        val f = checkNotNull(file) { "Store.init not called" }
        val out = f.startWrite()
        try {
            out.write(json.encodeToString(AppState.serializer(), state).toByteArray())
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }
}
