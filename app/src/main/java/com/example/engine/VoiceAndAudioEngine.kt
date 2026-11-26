package com.example.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Base64
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

enum class AudioOutputRoute { AUTO, SPEAKER, HEADSET }

class VoiceAndAudioEngine(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var currentSpanish = Locale.getDefault().language.startsWith("es", ignoreCase = true)
    private var currentRoute = AudioOutputRoute.AUTO

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var recorder: MediaRecorder? = null
    private var currentRecordFile: File? = null
    private var lastPlayedPaTimestamp = 0L

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                applyLocale(currentSpanish)
            }
        }
    }

    fun applyLocale(isSpanish: Boolean) {
        currentSpanish = isSpanish
        if (ttsReady) {
            tts?.language = if (isSpanish) Locale("es", "ES") else Locale.US
        }
    }

    fun setOutputRoute(route: AudioOutputRoute) {
        currentRoute = route
        applyHardwareAudioRoute()
    }

    private fun applyHardwareAudioRoute() {
        val am = audioManager ?: return
        try {
            when (currentRoute) {
                AudioOutputRoute.AUTO -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        am.clearCommunicationDevice()
                    }
                    am.mode = AudioManager.MODE_NORMAL
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = false
                }
                AudioOutputRoute.SPEAKER -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val spk = am.availableCommunicationDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        }
                        if (spk != null) am.setCommunicationDevice(spk)
                    } else {
                        am.mode = AudioManager.MODE_IN_COMMUNICATION
                        @Suppress("DEPRECATION")
                        am.isSpeakerphoneOn = true
                    }
                }
                AudioOutputRoute.HEADSET -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val hs = am.availableCommunicationDevices.firstOrNull { dev ->
                            dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                                dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                                dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                                dev.type == AudioDeviceInfo.TYPE_USB_HEADSET
                        }
                        if (hs != null) am.setCommunicationDevice(hs) else am.clearCommunicationDevice()
                    } else {
                        am.mode = AudioManager.MODE_NORMAL
                        @Suppress("DEPRECATION")
                        am.isSpeakerphoneOn = false
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun requestTransientFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                am.requestAudioFocus(req)
            }
        } catch (_: Exception) {
        }
    }

    fun speak(text: String, flush: Boolean = true) {
        if (!ttsReady || text.isBlank()) return
        applyHardwareAudioRoute()
        requestTransientFocus()
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        tts?.speak(text, mode, params, "mx_${System.currentTimeMillis()}")
    }

    fun playBeep(tone: Int = ToneGenerator.TONE_PROP_BEEP, durationMs: Int = 180) {
        try {
            applyHardwareAudioRoute()
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 88)
            tg.startTone(tone, durationMs)
        } catch (_: Exception) {
        }
    }

    /**
     * Synthesizes a multi-harmonic stadium crowd cheer / applause burst on AudioTrack
     * when the 10-tap Ovación de Grada threshold hits.
     */
    fun playStadiumApplause() {
        Thread {
            try {
                val sampleRate = 22050
                val durationSec = 1.6
                val numSamples = (sampleRate * durationSec).toInt()
                val pcm = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val env = if (t < 0.25) t / 0.25 else (1.0 - (t - 0.25) / 1.35).coerceAtLeast(0.0)
                    val noise = (Random.nextDouble() * 2.0 - 1.0) * 0.55
                    val chord = 0.25 * sin(2 * PI * 330.0 * t) + 0.20 * sin(2 * PI * 440.0 * t)
                    pcm[i] = ((noise + chord) * env * Short.MAX_VALUE * 0.7).toInt().toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
            } catch (_: Exception) {
            }
        }.start()
    }

    fun startPaRecording(): Boolean {
        return try {
            stopPaRecordingRaw()
            val outFile = File(context.cacheDir, "pa_clip.m4a")
            currentRecordFile = outFile
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(48000)
            mr.setAudioSamplingRate(22050)
            mr.setOutputFile(outFile.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stopPaRecordingToBase64(): String? {
        return try {
            stopPaRecordingRaw()
            val f = currentRecordFile ?: return null
            if (!f.exists() || f.length() < 128) return null
            Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun stopPaRecordingRaw() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
    }

    fun playRemotePaClipIfNew(base64Audio: String, timestampMs: Long) {
        if (base64Audio.isBlank() || timestampMs <= lastPlayedPaTimestamp) return
        lastPlayedPaTimestamp = timestampMs
        try {
            val bytes = Base64.decode(base64Audio, Base64.DEFAULT)
            val tmp = File(context.cacheDir, "pa_play_$timestampMs.m4a")
            tmp.writeBytes(bytes)
            val mp = MediaPlayer()
            mp.setDataSource(tmp.absolutePath)
            mp.setOnCompletionListener {
                it.release()
                tmp.delete()
            }
            mp.prepare()
            mp.start()
        } catch (_: Exception) {
        }
    }

    fun release() {
        stopPaRecordingRaw()
        tts?.stop()
        tts?.shutdown()
    }
}
