package it.klab.ritrova

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.sin

/** Bip "contatore Geiger" sul telefono + suoneria da mandare alle cuffie connesse. */
class Sounder(private val ctx: Context) {
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    fun beep(proximity: Double) {
        // più vicino = bip più acuto e lungo
        val t = if (proximity > 0.75) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP
        tone?.startTone(t, 60)
    }

    fun buzz(ms: Long) {
        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    // ---------- Suoneria per le cuffie ----------
    private var track: AudioTrack? = null
    private var savedVolume = -1

    val isRinging: Boolean get() = track != null

    /**
     * Fa suonare un trillo nelle cuffie Bluetooth connesse (indirizzo MAC = [address]).
     * Ritorna false se non trova un'uscita audio Bluetooth.
     */
    fun ringHeadphones(address: String): Boolean {
        stopRing()
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val btTypes = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= 31) { add(AudioDeviceInfo.TYPE_BLE_HEADSET); add(AudioDeviceInfo.TYPE_BLE_SPEAKER) }
        }
        val bt = outputs.filter { it.type in btTypes }
        val target = bt.firstOrNull { Build.VERSION.SDK_INT >= 28 && it.address.equals(address, true) } ?: bt.firstOrNull()
            ?: return false

        val rate = 44100
        val pcm = buildRingtone(rate)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size / 2, -1)
        t.setPreferredDevice(target)

        savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        runCatching {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        }
        t.play()
        track = t
        return true
    }

    fun stopRing() {
        track?.let { runCatching { it.stop(); it.release() } }
        track = null
        if (savedVolume >= 0) {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0) }
            savedVolume = -1
        }
    }

    /** Trillo acuto (2,5 / 3,5 kHz alternati) + pausa: si sente bene anche da sotto un cuscino. */
    private fun buildRingtone(rate: Int): ShortArray {
        val seconds = 1.2
        val n = (rate * seconds).toInt()
        val out = ShortArray(n * 2)
        for (i in 0 until n) {
            val tSec = i.toDouble() / rate
            val slot = (tSec / 0.1).toInt()          // blocchi da 100 ms
            val on = slot < 8                       // 0.8 s di trillo, 0.4 s di pausa
            val f = if (slot % 2 == 0) 2500.0 else 3500.0
            val v: Short = if (on) (sin(2 * PI * f * tSec) * 0.9 * Short.MAX_VALUE).toInt().toShort() else 0
            out[2 * i] = v; out[2 * i + 1] = v
        }
        return out
    }

    fun release() {
        stopRing()
        tone?.release()
    }
}
