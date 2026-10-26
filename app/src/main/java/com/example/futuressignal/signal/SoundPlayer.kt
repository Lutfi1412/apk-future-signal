package com.example.futuressignal.signal

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Memutar res/raw/notif.mp3, lalu membacakan keterangan sinyal (text-to-speech).
 * Kalau ada beberapa sinyal bersamaan, diputar bergantian (antrean).
 * stopAll() menghentikan suara yang sedang berjalan dan mengosongkan antrean (dipakai saat suara dimatikan).
 *
 * Suara mengikuti volume MEDIA. Kalau mau mengikuti volume ALARM (tetap bunyi walau mode senyap),
 * ganti USAGE_MEDIA di bawah menjadi USAGE_ALARM.
 */
class SoundPlayer(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var busy = false
    private var token = 0                 // penanda giliran yang sedang diputar
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private var pendingSpeech: String? = null
    private var player: MediaPlayer? = null

    private val soundAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val speechAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    init {
        main.post {
            tts = TextToSpeech(app) { status -> main.post { onTtsInit(status) } }
        }
    }

    private fun onTtsInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            ttsFailed = true
            if (pendingSpeech != null) {
                pendingSpeech = null
                finish()
            }
            return
        }
        t.setAudioAttributes(speechAttrs)
        val r = t.setLanguage(Locale("id", "ID"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.getDefault())     // suara Indonesia belum terpasang -> pakai bahasa bawaan HP
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                main.post { if (utteranceId == "sig-$token") finish() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { if (utteranceId == "sig-$token") finish() }
            }
        })
        ttsReady = true
        pendingSpeech?.let {
            pendingSpeech = null
            speak(it)
        }
    }

    fun play(speech: String) {
        main.post {
            queue.addLast(speech)
            if (!busy) next()
        }
    }

    /** Hentikan bunyi dan suara bicara yang sedang berjalan, dan buang semua yang masih antre. */
    fun stopAll() {
        main.post {
            queue.clear()
            token++                         // batalkan giliran yang sedang berjalan (callback lama jadi diabaikan)
            busy = false
            pendingSpeech = null
            player?.let {
                try {
                    it.stop()
                } catch (e: Exception) {
                }
                it.release()
            }
            player = null
            tts?.stop()
        }
    }

    private fun next() {
        val text = queue.removeFirstOrNull()
        if (text == null) {
            busy = false
            return
        }
        busy = true
        token++
        val my = token
        // Pengaman: kalau satu giliran macet (mis. TTS tidak merespons), lepaskan setelah 25 detik
        main.postDelayed({ if (busy && token == my) finish() }, 25_000L)

        val resId = app.resources.getIdentifier("notif", "raw", app.packageName)
        val mp = if (resId != 0) {
            try {
                MediaPlayer.create(app, resId, soundAttrs, AudioManager.AUDIO_SESSION_ID_GENERATE)
            } catch (e: Exception) {
                null
            }
        } else null

        if (mp == null) {          // notif.mp3 belum ada / gagal dibuka -> langsung bicara saja
            speak(text)
            return
        }
        player = mp
        mp.setOnCompletionListener {
            it.release()
            if (player === it) player = null
            if (token == my) speak(text)
        }
        mp.setOnErrorListener { p, _, _ ->
            p.release()
            if (player === p) player = null
            if (token == my) speak(text)
            true
        }
        try {
            mp.start()
        } catch (e: Exception) {
            mp.release()
            if (player === mp) player = null
            speak(text)
        }
    }

    private fun speak(text: String) {
        if (ttsFailed) {
            finish()
            return
        }
        val t = tts
        if (t == null || !ttsReady) {
            pendingSpeech = text            // TTS belum siap: bicara begitu siap
            return
        }
        val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sig-$token")
        if (r == TextToSpeech.ERROR) finish()
    }

    private fun finish() {
        pendingSpeech = null
        busy = false
        next()
    }
}