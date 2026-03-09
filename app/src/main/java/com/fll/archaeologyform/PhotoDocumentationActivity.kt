package com.fll.archaeologyform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.fll.archaeologyform.databinding.ActivityPhotoDocumentationBinding
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class PhotoDocumentationActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityPhotoDocumentationBinding
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var locationManager: LocationManager

    private val prefs by lazy { getSharedPreferences("marp_prefs", Context.MODE_PRIVATE) }
    private var handsFreeMode = false

    private var photoUri: Uri? = null
    private var photoFile: File? = null
    private var photoTimestamp: String = ""
    private var currentLocation: Location? = null
    private val responses = mutableMapOf<String, String>()
    private var currentQuestionIndex = 0
    private var isListening = false
    private var ttsReady = false

    private val handler = Handler(Looper.getMainLooper())

    private val questions = listOf(
        Pair("depth", "What is the depth of this finding in centimeters?"),
        Pair("stratum", "Describe the stratum or soil layer, including color and composition."),
        Pair("notes", "Any additional notes or observations about this finding?")
    )

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentLocation = location
            updateLocationDisplay()
        }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) onPhotoTaken()
        else if (handsFreeMode) {
            // Retry voice command if camera was cancelled
            handler.postDelayed({ listenForPhotoCommand() }, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoDocumentationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        startLocationUpdates()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnTakePhoto.setOnClickListener { takePhoto() }
        binding.btnRetryListen.setOnClickListener {
            binding.btnRetryListen.visibility = View.GONE
            startListening()
        }
        binding.btnSave.setOnClickListener { saveRecord() }
        binding.btnHandsFreeToggle.setOnClickListener { toggleHandsFreeMode() }
    }

    override fun onResume() {
        super.onResume()
        val globalHandsFree = prefs.getBoolean("hands_free_mode", false)
        if (globalHandsFree != handsFreeMode) {
            handsFreeMode = globalHandsFree
            applyHandsFreeUI()
            if (handsFreeMode && photoUri == null) {
                handler.postDelayed({
                    if (ttsReady) {
                        speak("Photo documentation hands-free mode. Say 'take photo' to open camera.") {
                            handler.post { listenForPhotoCommand() }
                        }
                    } else {
                        handler.postDelayed({ listenForPhotoCommand() }, 2000)
                    }
                }, 500)
            }
        }
    }

    private fun toggleHandsFreeMode() {
        handsFreeMode = !handsFreeMode
        prefs.edit().putBoolean("hands_free_mode", handsFreeMode).apply()
        applyHandsFreeUI()

        if (handsFreeMode) {
            speak("Hands-free mode on. Say 'take photo' to open the camera.") {
                if (photoUri == null) handler.post { listenForPhotoCommand() }
            }
        } else {
            speak("Hands-free mode off.")
        }
    }

    private fun applyHandsFreeUI() {
        if (handsFreeMode) {
            binding.btnHandsFreeToggle.text = "\uD83C\uDF99 ON"
            binding.btnHandsFreeToggle.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#22AA22"))
            binding.tvHandsFreeBar.visibility = View.VISIBLE
            binding.btnTakePhoto.visibility = View.GONE
            binding.btnSave.visibility = View.GONE
            binding.btnRetryListen.visibility = View.GONE
        } else {
            binding.btnHandsFreeToggle.text = "\uD83C\uDF99 OFF"
            binding.btnHandsFreeToggle.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#555555"))
            binding.tvHandsFreeBar.visibility = View.GONE
            binding.btnTakePhoto.visibility = View.VISIBLE
        }
    }

    // ── Voice-triggered photo command ─────────────────────────────────────────

    private fun listenForPhotoCommand() {
        if (isListening) return
        isListening = true

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                isListening = false
                val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.joinToString(" ")?.lowercase() ?: ""
                if (candidates.contains("take") || candidates.contains("photo") ||
                    candidates.contains("capture") || candidates.contains("picture") ||
                    candidates.contains("snap") || candidates.contains("shoot")
                ) {
                    speak("Opening camera.") { handler.post { takePhoto() } }
                } else {
                    handler.postDelayed({ listenForPhotoCommand() }, 600)
                }
            }

            override fun onError(error: Int) {
                isListening = false
                handler.postDelayed({ listenForPhotoCommand() }, 1200)
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
            handler.postDelayed({ listenForPhotoCommand() }, 1500)
        }
    }

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
                Log.w("PhotoDoc", "Location error: ${e.message}")
            }
        }
    }

    private fun takePhoto() {
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "archaeology")
        if (!dir.exists()) dir.mkdirs()

        photoTimestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        photoFile = File(dir, "ARCH_$photoTimestamp.jpg")
        photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile!!)
        takePictureLauncher.launch(photoUri)
    }

    private fun onPhotoTaken() {
        photoFile?.let {
            val bmp = BitmapFactory.decodeFile(it.absolutePath)
            if (bmp != null) binding.ivPhoto.setImageBitmap(bmp)
        }

        val display = SimpleDateFormat("MMMM d, yyyy  h:mm a", Locale.getDefault()).format(Date())
        binding.tvDateTime.text = "Date/Time: $display"
        updateLocationDisplay()

        binding.cardMetadata.visibility = View.VISIBLE
        binding.cardVoiceQuestions.visibility = View.VISIBLE

        val delay = if (handsFreeMode) {
            speak("Photo captured. Starting voice documentation.")
            1800L
        } else 1500L

        handler.postDelayed({
            if (ttsReady) startVoiceQuestions()
            else handler.postDelayed({ startVoiceQuestions() }, 1500)
        }, delay)
    }

    private fun startVoiceQuestions() {
        currentQuestionIndex = 0
        responses.clear()
        askNextQuestion()
    }

    private fun askNextQuestion() {
        if (currentQuestionIndex >= questions.size) {
            onVoiceComplete()
            return
        }
        val (_, questionText) = questions[currentQuestionIndex]
        val num = currentQuestionIndex + 1
        binding.tvCurrentQuestion.text = "Q$num of ${questions.size}: $questionText"
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

            override fun onReadyForSpeech(params: Bundle?) {
                binding.tvVoiceStatus.text = "Listening..."
            }
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
        updateNotesDisplay()
        binding.cardNotes.visibility = View.VISIBLE

        speak("Got it.") {
            currentQuestionIndex++
            handler.postDelayed({ askNextQuestion() }, 300)
        }
    }

    private fun updateNotesDisplay() {
        binding.tvFieldNotes.text = responses.entries.joinToString("\n\n") {
            "${it.key.uppercase()}:\n  ${it.value}"
        }
    }

    private fun onVoiceComplete() {
        binding.tvCurrentQuestion.text = "Voice documentation complete!"
        binding.tvVoiceStatus.text = "All questions answered"

        if (handsFreeMode) {
            speak("All questions answered. Say 'save' to save, or 'cancel' to discard.") {
                handler.post { listenForSaveCommand() }
            }
        } else {
            binding.btnSave.visibility = View.VISIBLE
            speak("Documentation complete. Press Save to store this record.")
        }
    }

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

    private fun saveRecord() {
        val datetime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val json = JSONObject().apply {
            put("type", "photo_documentation")
            put("datetime", datetime)
            put("photo_file", photoFile?.absolutePath ?: "")
            put("latitude", currentLocation?.latitude?.toString() ?: "")
            put("longitude", currentLocation?.longitude?.toString() ?: "")
            responses.forEach { (k, v) -> put(k, v) }
        }

        val dir = File(filesDir, "records")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "photo_$photoTimestamp.json").writeText(json.toString(2))

        if (!handsFreeMode) {
            Toast.makeText(this, "Record saved!", Toast.LENGTH_SHORT).show()
            binding.btnSave.text = "Saved!"
            binding.btnSave.isEnabled = false
        }
    }

    private fun updateLocationDisplay() {
        val loc = currentLocation
        binding.tvLocation.text = if (loc != null)
            "GPS: ${"%.6f".format(loc.latitude)}, ${"%.6f".format(loc.longitude)}"
        else
            "Location: GPS not available"
    }

    private fun speak(text: String, onComplete: (() -> Unit)? = null) {
        val uid = "utt_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
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

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setSpeechRate(1.1f)
            ttsReady = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        speechRecognizer.destroy()
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                locationManager.removeUpdates(locationListener)
            }
        } catch (e: Exception) { /* ignore */ }
    }
}
