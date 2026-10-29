package com.blakeriding.upright

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

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
            json.decodeFromString(AppState.serializer(), String(f.readFully()))
        } catch (_: java.io.FileNotFoundException) {
            AppState()
        } catch (e: Exception) {
            // Keep the unreadable file for inspection rather than overwriting it silently.
            Log.e("Upright", "State file unreadable, starting fresh", e)
            f.baseFile.copyTo(File(de.filesDir, "state.broken-${System.currentTimeMillis()}.json"), overwrite = true)
            AppState()
        }
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
