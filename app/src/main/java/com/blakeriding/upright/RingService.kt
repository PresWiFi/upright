package com.blakeriding.upright

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays the alarm while a wake-up is ringing. Volume climbs to max and is re-applied every second,
 * so the volume keys can't turn it down. It stops only when the session leaves the RINGING phase.
 */
class RingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var savedVolume = -1
    private var savedFilter = -1
    private var startedAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        ServiceCompat.startForeground(
            this, Notifs.ID_RING, Notifs.ringNotification(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0,
        )
        if (Store.state.value.session?.phase != Phase.RINGING) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (startedAt == 0L) begin()
        // Works when we're allowed to start activities from the background (e.g. guard service on);
        // otherwise the full-screen notification opens the ringing screen.
        try {
            startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("Upright", "Couldn't open ring screen directly", e)
        }
        return START_STICKY
    }

    private fun begin() {
        startedAt = nowMs()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Upright:ring").apply { setReferenceCounted(false) }
        overrideDnd()
        val am = getSystemService(AudioManager::class.java)
        savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        startSound()
        startVibration()
        scope.launch {
            while (isActive) {
                if (Store.state.value.session?.phase != Phase.RINGING) {
                    stopSelf()
                    break
                }
                applyVolume(am)
                // Renewed every tick so the CPU stays awake for as long as it rings, but never leaks.
                wakeLock?.acquire(MINUTE_MS)
                delay(1000)
            }
        }
    }

    private fun applyVolume(am: AudioManager) {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val elapsed = nowMs() - startedAt
        // 40% -> 100% over the first minute. Quieter while you're actively working on a step.
        var fraction = (0.4 + 0.6 * (elapsed / 60_000.0)).coerceAtMost(1.0)
        if (nowMs() < workingUntil) fraction = 0.25
        val target = (max * fraction).toInt().coerceAtLeast(1)
        if (am.getStreamVolume(AudioManager.STREAM_ALARM) != target) am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 800)
    }

    private fun startSound() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val candidates = listOfNotNull<Uri>(
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (uri in candidates) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(this@RingService, uri)
                    isLooping = true
                    prepare()
                    start()
                }
                return
            } catch (e: Exception) {
                Log.w("Upright", "Can't play $uri", e)
                player?.release()
                player = null
            }
        }
        // No playable ringtone (e.g. before first unlock, when media files aren't readable): beep instead.
        tone = try { ToneGenerator(AudioManager.STREAM_ALARM, 100) } catch (_: Exception) { null }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v = ContextCompat.getSystemService(this, Vibrator::class.java) ?: return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0), attrs)
    }

    /** If Do Not Disturb would mute alarms, switch it off while ringing and put it back after. */
    private fun overrideDnd() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        val filter = nm.currentInterruptionFilter
        if (filter != NotificationManager.INTERRUPTION_FILTER_ALL) {
            savedFilter = filter
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }

    @android.annotation.SuppressLint("WrongConstant") // savedFilter came from currentInterruptionFilter.
    override fun onDestroy() {
        scope.cancel()
        player?.run { stop(); release() }
        player = null
        tone?.release()
        tone = null
        ContextCompat.getSystemService(this, Vibrator::class.java)?.cancel()
        if (savedVolume >= 0) getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
        if (savedFilter >= 0) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(savedFilter)
        }
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        /** Set by the ringing screen while you're mid-step, to drop the volume. */
        @Volatile var workingUntil = 0L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RingService::class.java))
        }
    }
}
