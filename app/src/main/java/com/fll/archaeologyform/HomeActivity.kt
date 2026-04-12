package com.fll.archaeologyform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fll.archaeologyform.databinding.ActivityHomeBinding
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class HomeActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var audioManager: AudioManager

    private val prefs by lazy { getSharedPreferences("marp_prefs", Context.MODE_PRIVATE) }
    private var handsFreeMode
        get() = prefs.getBoolean("hands_free_mode", false)
        set(value) { prefs.edit().putBoolean("hands_free_mode", value).apply() }

    private var ttsReady = false
    private var isListening = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        handsFreeMode = false  // always start hands-free OFF
        checkPermissions()
        setupUI()
        observeGlassesConnection()
    }

    override fun onResume() {
        super.onResume()
        isListening = false
        updateHandsFreeUI()
        if (handsFreeMode) {
            if (ttsReady) {
                speak("Home screen.") { handler.postDelayed({ listenForWakeWord() }, 200) }
            } else {
                handler.postDelayed({ listenForWakeWord() }, 600)
            }
        }
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

    private fun checkPermissions() {
        val readMediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
            readMediaPermission
        )
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        }
    }

    private fun setupUI() {
        binding.cardVoiceForm.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        binding.cardViewRecords.setOnClickListener {
            startActivity(Intent(this, RecordsActivity::class.java))
        }
        binding.cardCustomForm.setOnClickListener {
            startActivity(Intent(this, CustomFormActivity::class.java))
        }
        binding.cardGlassesStream.setOnClickListener {
            startActivity(Intent(this, GlassesStreamActivity::class.java))
        }
        binding.btnConnectGlasses.setOnClickListener {
            connectGlasses()
        }

        binding.cardSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnHandsFreeToggle.setOnClickListener {
            handsFreeMode = !handsFreeMode
            updateHandsFreeUI()
            if (handsFreeMode) {
                isFirstWakeWordListen = true  // reset so the ready-beep fires once
                speak("Home screen.") { handler.postDelayed({ listenForWakeWord() }, 200) }
            } else {
                stopListening()
            }
        }
    }

    private fun updateHandsFreeUI() {
        if (handsFreeMode) {
            binding.btnHandsFreeToggle.text = "\uD83C\uDF99 ON"
            binding.btnHandsFreeToggle.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#22AA22"))
            binding.tvHandsFreeBar.text = "Say 'Hey MARP'..."
            binding.tvHandsFreeBar.visibility = View.VISIBLE
        } else {
            binding.btnHandsFreeToggle.text = "\uD83C\uDF99 OFF"
            binding.btnHandsFreeToggle.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#555555"))
            binding.tvHandsFreeBar.visibility = View.GONE
        }
    }

    // True only for the very first listen session — plays a single ready-beep so the
    // user knows the mic just opened. Silent on every subsequent auto-restart.
    private var isFirstWakeWordListen = true

    private fun playReadyBeep() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            handler.postDelayed({ toneGen.release() }, 300)
        } catch (_: Exception) {}
    }

    // Mute the system start-of-recognition beep so restarts are silent.
    private var savedVolume = -1
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

    private fun containsWakeWord(text: String) =
        text.contains("marp") || text.contains("hey mar") ||
        text.contains("hey mark") || text.contains("mars")

    // Phase 1: passively wait for "Hey MARP" — Alexa-style via partial results so
    // detection fires the instant the words are spoken, not after a silence timeout.
    private fun listenForWakeWord() {
        if (isListening || !handsFreeMode) return
        isListening = true
        binding.tvHandsFreeBar.text = "Say 'Hey MARP'..."

        muteRecognitionBeep()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Ask the OS to keep the session open as long as possible.
            // When it eventually times out, onError fires and we restart immediately.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3_600_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_600_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_600_000L)
        }

        var wakeWordDetected = false

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            // Fires while the user is still speaking — gives instant "Hey MARP" detection.
            override fun onPartialResults(partialResults: Bundle?) {
                if (wakeWordDetected) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase() ?: return
                if (containsWakeWord(partial)) {
                    wakeWordDetected = true
                    isListening = false
                    try { speechRecognizer.stopListening() } catch (_: Exception) {}
                    binding.tvHandsFreeBar.text = "Listening..."
                    handler.post { listenForNavCommand() }
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                if (wakeWordDetected) return
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                val topText = texts?.firstOrNull()?.lowercase() ?: ""
                val topScore = scores?.firstOrNull() ?: 1f
                if (topScore >= 0.55f && containsWakeWord(topText)) {
                    binding.tvHandsFreeBar.text = "Listening..."
                    handler.post { listenForNavCommand() }
                } else {
                    handler.post { listenForWakeWord() }
                }
            }

            override fun onError(error: Int) {
                isListening = false
                if (!wakeWordDetected) handler.post { listenForWakeWord() }
            }

            // Mic is now open — restore volume, then beep once so the user knows to talk.
            override fun onReadyForSpeech(params: Bundle?) {
                restoreVolume()
                if (isFirstWakeWordListen) {
                    isFirstWakeWordListen = false
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
            handler.post { listenForWakeWord() }
        }
    }

    // Phase 2: listen for a navigation command after wake word is detected
    private fun listenForNavCommand() {
        if (isListening || !handsFreeMode) return
        isListening = true
        binding.tvHandsFreeBar.text = "Listening..."

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                isListening = false
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                // Use top result; require confidence ≥ 0.5 if scores are available
                val text = texts?.firstOrNull()?.lowercase() ?: ""
                val topScore = scores?.firstOrNull() ?: 1f
                if (topScore < 0.5f) {
                    handler.post { listenForWakeWord() }
                    return
                }
                when {
                    text.contains("field") || text.contains("new record") ||
                    text.contains("voice form") || text.contains("start record") ->
                        startActivity(Intent(this@HomeActivity, MainActivity::class.java))

                    text.contains("view") || text.contains("saved") ||
                    text.contains("history") || text.contains("records") ->
                        startActivity(Intent(this@HomeActivity, RecordsActivity::class.java))

                    text.contains("custom") || text.contains("template") ||
                    text.contains("import") ->
                        startActivity(Intent(this@HomeActivity, CustomFormActivity::class.java))

                    text.contains("stream") || text.contains("camera") ||
                    text.contains("glasses stream") || text.contains("live") ->
                        startActivity(Intent(this@HomeActivity, GlassesStreamActivity::class.java))

                    text.contains("setting") ->
                        startActivity(Intent(this@HomeActivity, SettingsActivity::class.java))

                    text.contains("connect") || text.contains("register") ->
                        connectGlasses()

                    else ->
                        handler.post { listenForWakeWord() }
                }
            }

            override fun onError(error: Int) {
                isListening = false
                handler.post { listenForWakeWord() }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            handler.post { listenForWakeWord() }
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
                override fun onError(utteranceId: String?) { handler.post { onComplete() } }
            })
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, uid)
    }

    private fun connectGlasses() {
        Wearables.startRegistration(this)
    }

    private fun observeGlassesConnection() {
        lifecycleScope.launch {
            try {
                Wearables.registrationState.collectLatest { state ->
                    when (state) {
                        is RegistrationState.Registered -> {
                            binding.tvGlassesIcon.text = "\uD83D\uDFE2"
                            binding.tvGlassesStatus.text = "Meta Glasses connected"
                            binding.btnConnectGlasses.visibility = View.GONE
                        }
                        else -> {
                            binding.tvGlassesIcon.text = "⚫"
                            binding.tvGlassesStatus.text = "Meta Glasses not connected"
                            binding.btnConnectGlasses.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeActivity", "Observe error", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        speechRecognizer.destroy()
    }
}
