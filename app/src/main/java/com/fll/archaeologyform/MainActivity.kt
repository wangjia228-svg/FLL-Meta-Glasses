package com.fll.archaeologyform

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fll.archaeologyform.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var locationManager: LocationManager
    private lateinit var audioManager: AudioManager

    private val prefs by lazy { getSharedPreferences("marp_prefs", Context.MODE_PRIVATE) }
    private var handsFreeMode = false

    private var scoStateReceiver: BroadcastReceiver? = null
    private var currentLocation: Location? = null

    private val responses = mutableMapOf<String, String>()
    private var currentQuestionIndex = 0
    private var isListening = false
    private var ttsReady = false
    private var recordTimestamp = ""

    private val handler = Handler(Looper.getMainLooper())

    private val questions = listOf(
        Pair("artifact",  "What artifact was found?"),
        Pair("depth",     "What is the depth of the artifact in centimeters?"),
        Pair("elevation", "What is the elevation at this location?"),
        Pair("condition", "Describe the condition of the artifact."),
        Pair("notes",     "Any additional notes or observations?")
    )

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentLocation = location
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        handsFreeMode = prefs.getBoolean("hands_free_mode", false)

        initBluetoothSco()
        startLocationUpdates()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSaveRecord.setOnClickListener { saveRecord() }
        binding.btnRetryListen.setOnClickListener {
            binding.btnRetryListen.visibility = View.GONE
            startListening()
        }
        binding.btnHandsFreeToggle.setOnClickListener { toggleHandsFreeMode() }

        applyHandsFreeUI()
    }

    // ── Hands-Free Mode ────────────────────────────────────────────────────────

    private fun toggleHandsFreeMode() {
        handsFreeMode = !handsFreeMode
        prefs.edit().putBoolean("hands_free_mode", handsFreeMode).apply()
        applyHandsFreeUI()
        speak(if (handsFreeMode) "Hands-free mode on." else "Hands-free mode off.")
    }

    private fun applyHandsFreeUI() {
        if (handsFreeMode) {
            binding.btnHandsFreeToggle.text = "\uD83C\uDF99 ON"
            binding.btnHandsFreeToggle.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#22AA22"))
            binding.tvHandsFreeBar.visibility = View.VISIBLE
            binding.btnRetryListen.visibility = View.GONE
        } else {
            binding.btnHandsFreeToggle.text = "\uD83C\uDF99 OFF"
            binding.btnHandsFreeToggle.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#555555"))
            binding.tvHandsFreeBar.visibility = View.GONE
        }
    }

    // ── Bluetooth SCO ──────────────────────────────────────────────────────────

    private fun initBluetoothSco() {
        scoStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                Log.d("MARP", "SCO state: $state")
            }
        }
        registerReceiver(scoStateReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
    }

    // ── GPS ────────────────────────────────────────────────────────────────────

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 5000L, 5f, locationListener
                )
                currentLocation =
                    locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (e: Exception) {
                Log.w("MARP", "Location error: ${e.message}")
            }
        }
    }

    private fun updateLocationDisplay() {
        val loc = currentLocation
        binding.tvLocation.text = if (loc != null)
            "GPS: ${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}"
        else
            "GPS: Acquiring location..."
    }

    // ── Questions Flow ────────────────────────────────────────────────────────

    private fun startQuestionsFlow() {
        val display = SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault()).format(Date())
        binding.tvDateTime.text = "Date/Time: $display"
        recordTimestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        updateLocationDisplay()
        binding.layoutMetadata.visibility = View.VISIBLE
        binding.tvVoiceQuestionsLabel.visibility = View.VISIBLE
        binding.cardVoiceQuestions.visibility = View.VISIBLE
        binding.tvStepIndicator.text = "Voice questions"

        if (handsFreeMode) {
            speak("Starting field documentation.") {
                handler.postDelayed({ startVoiceQuestions() }, 400)
            }
        } else {
            startVoiceQuestions()
        }
    }

    // ── Voice questions ────────────────────────────────────────────────────────

    private fun startVoiceQuestions() {
        currentQuestionIndex = 0
        responses.clear()
        binding.tvFieldNotes.text = ""
        binding.tvStepIndicator.text = "Questions"
        askNextQuestion()
    }

    private fun askNextQuestion() {
        if (currentQuestionIndex >= questions.size) {
            onVoiceComplete()
            return
        }
        val (_, questionText) = questions[currentQuestionIndex]
        val num = currentQuestionIndex + 1
        binding.tvCurrentQuestion.text = questionText
        binding.tvProgressText.text = "Question $num of ${questions.size}"
        binding.tvVoiceStatus.text = "Speaking..."
        binding.btnRetryListen.visibility = View.GONE

        speak(questionText) {
            handler.postDelayed({ startListening() }, 400)
        }
    }

    private fun startListening() {
        if (isListening) return
        isListening = true
        binding.tvVoiceStatus.text = "Listening..."

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                isListening = false
                val answer = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                if (answer.isNotEmpty()) handleAnswer(answer)
                else showRetry()
            }

            override fun onError(error: Int) {
                isListening = false
                showRetry()
            }

            override fun onReadyForSpeech(params: Bundle?) { binding.tvVoiceStatus.text = "Listening..." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { binding.tvVoiceStatus.text = "Processing..." }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            showRetry()
        }
    }

    private fun showRetry() {
        if (handsFreeMode) {
            binding.tvVoiceStatus.text = "Retrying..."
            handler.postDelayed({ startListening() }, 1500)
        } else {
            binding.tvVoiceStatus.text = "Didn't catch that"
            binding.btnRetryListen.visibility = View.VISIBLE
        }
    }

    private fun handleAnswer(answer: String) {
        val (field, _) = questions[currentQuestionIndex]
        responses[field] = answer

        binding.cardFieldNotes.visibility = View.VISIBLE
        binding.tvFieldNotes.text = responses.entries.joinToString("\n\n") {
            "${it.key.uppercase()}:\n  ${it.value}"
        }

        speak("Got it.") {
            currentQuestionIndex++
            handler.postDelayed({ askNextQuestion() }, 300)
        }
    }

    private fun onVoiceComplete() {
        binding.tvCurrentQuestion.text = "All questions answered."
        binding.tvVoiceStatus.text = "Complete"
        binding.tvProgressText.text = "Done"
        binding.tvStepIndicator.text = "Ready to save"
        binding.btnSaveRecord.visibility = View.VISIBLE
        if (handsFreeMode) {
            speak("All questions answered. Say 'save' to save, or press the save button.") {
                handler.post { listenForSaveCommand() }
            }
        } else {
            speak("Documentation complete. Press Save when ready.")
        }
    }

    // ── Hands-free: voice save command ────────────────────────────────────────

    private fun listenForSaveCommand() {
        if (isListening) return
        isListening = true
        binding.tvVoiceStatus.text = "Say 'save' or 'cancel'..."

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                isListening = false
                val result = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase() ?: ""
                when {
                    result.contains("save") || result.contains("yes") ||
                    result.contains("confirm") || result.contains("done") -> {
                        saveRecord()
                        speak("Record saved.") { handler.postDelayed({ finish() }, 1500) }
                    }
                    result.contains("cancel") || result.contains("no") ||
                    result.contains("discard") -> {
                        speak("Record discarded.") { handler.postDelayed({ finish() }, 1500) }
                    }
                    else -> {
                        speak("Say 'save' to save or 'cancel' to discard.") {
                            handler.postDelayed({ listenForSaveCommand() }, 300)
                        }
                    }
                }
            }

            override fun onError(error: Int) {
                isListening = false
                speak("Say 'save' to save or 'cancel' to discard.") {
                    handler.postDelayed({ listenForSaveCommand() }, 300)
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { binding.tvVoiceStatus.text = "Processing..." }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            handler.postDelayed({ listenForSaveCommand() }, 2000)
        }
    }

    // ── Save ───────────────────────────────────────────────────────────────────

    private fun saveRecord() {
        val datetime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val json = JSONObject().apply {
            put("type", "field_record")
            put("datetime", datetime)
            put("latitude", currentLocation?.latitude?.toString() ?: "")
            put("longitude", currentLocation?.longitude?.toString() ?: "")
            responses.forEach { (k, v) -> put(k, v) }
        }

        val dir = File(filesDir, "records")
        if (!dir.exists()) dir.mkdirs()
        val ts = recordTimestamp.ifEmpty {
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        }
        File(dir, "record_$ts.json").writeText(json.toString(2))

        if (!handsFreeMode) {
            Toast.makeText(this, "Field record saved!", Toast.LENGTH_SHORT).show()
            binding.btnSaveRecord.text = "Saved!"
            binding.btnSaveRecord.isEnabled = false
        }
    }

    // ── TTS ───────────────────────────────────────────────────────────────────

    private fun speak(text: String, onComplete: (() -> Unit)? = null) {
        val uid = "utt_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
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

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setSpeechRate(1.1f)
            ttsReady = true
            handler.postDelayed({ startQuestionsFlow() }, 500)
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        try {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
            scoStateReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) { /* ignore */ }

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                locationManager.removeUpdates(locationListener)
            }
        } catch (e: Exception) { /* ignore */ }

        tts.shutdown()
        speechRecognizer.destroy()
    }
}
