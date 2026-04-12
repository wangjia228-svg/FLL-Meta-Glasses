package com.fll.archaeologyform

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Base class that gives any activity two hands-free behaviours when
 * hands_free_mode pref is on:
 *   1. Announces [screenName] via TTS on resume.
 *   2. Listens in the background for "hey marp home / back / go back" and
 *      calls finish() if heard.
 *
 * Activities with their own active SpeechRecognizer (e.g. MainActivity) should
 * NOT extend this — call [isHomeCommand] in their own onResults callbacks instead.
 */
abstract class HandsFreeActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    /** Spoken when arriving at this screen in hands-free mode, e.g. "Records". */
    protected abstract val screenName: String

    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("marp_prefs", Context.MODE_PRIVATE) }
    private val handsFreeMode get() = prefs.getBoolean("hands_free_mode", false)
    private var ttsReady = false
    private var isListening = false
    private var savedVolume = -1
    private var isFirstListen = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onResume() {
        super.onResume()
        isListening = false
        isFirstListen = true  // reset so the ready-beep fires once on each screen visit
        if (handsFreeMode) {
            if (ttsReady) {
                speak(screenName) { handler.postDelayed({ startBackgroundListening() }, 300) }
            } else {
                handler.postDelayed({ startBackgroundListening() }, 800)
            }
        }
    }

    private fun playReadyBeep() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            handler.postDelayed({ toneGen.release() }, 300)
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        stopListening()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setSpeechRate(1.1f)
            ttsReady = true
        }
    }

    private fun muteRecognitionBeep() {
        savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    }
    private fun restoreVolume() {
        if (savedVolume >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
            savedVolume = -1
        }
    }

    private fun startBackgroundListening() {
        if (isListening || !handsFreeMode) return
        isListening = true

        muteRecognitionBeep()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3_600_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_600_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_600_000L)
        }

        var homeDetected = false

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            // Detect home command the instant the words are spoken (Alexa-style).
            override fun onPartialResults(partialResults: Bundle?) {
                if (homeDetected) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase() ?: return
                if (isHomeCommand(partial)) {
                    homeDetected = true
                    isListening = false
                    try { speechRecognizer.stopListening() } catch (_: Exception) {}
                    speak("Going home.") { handler.postDelayed({ finish() }, 800) }
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                if (homeDetected) return
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                val text = texts?.firstOrNull()?.lowercase() ?: ""
                val topScore = scores?.firstOrNull() ?: 1f
                if (topScore >= 0.5f && isHomeCommand(text)) {
                    speak("Going home.") { handler.postDelayed({ finish() }, 800) }
                } else {
                    handler.post { startBackgroundListening() }
                }
            }

            override fun onError(error: Int) {
                isListening = false
                if (!homeDetected) handler.post { startBackgroundListening() }
            }

            // Mic is open — restore volume, then beep once so the user knows to talk.
            override fun onReadyForSpeech(params: Bundle?) {
                restoreVolume()
                if (isFirstListen) {
                    isFirstListen = false
                    playReadyBeep()
                }
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            restoreVolume()
            isListening = false
            handler.postDelayed({ startBackgroundListening() }, 500)
        }
    }

    private fun stopListening() {
        if (isListening) {
            try { speechRecognizer.stopListening() } catch (e: Exception) { /* ignore */ }
            isListening = false
        }
    }

    private fun speak(text: String, onComplete: (() -> Unit)? = null) {
        val uid = "utt_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        if (onComplete != null) {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { handler.post { onComplete() } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { handler.post { onComplete() } }
            })
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, uid)
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        speechRecognizer.destroy()
    }

    companion object {
        /**
         * Returns true if [text] contains the hey-marp wake word followed by a
         * home/back navigation intent. Call this from activities that manage their
         * own SpeechRecognizer (e.g. MainActivity) to check each result.
         */
        fun isHomeCommand(text: String): Boolean {
            val hasMarp = text.contains("marp") || text.contains("hey mar") ||
                          text.contains("hey mark") || text.contains("mars")
            val hasHome = text.contains("home") || text.contains("back") ||
                          text.contains("go back") || text.contains("exit")
            return hasMarp && hasHome
        }
    }
}
