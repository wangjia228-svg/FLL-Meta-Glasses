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
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import androidx.lifecycle.lifecycleScope
import com.fll.archaeologyform.databinding.ActivityMainBinding
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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

    private var currentLocation: Location? = null

    private val responses = mutableMapOf<String, String>()
    private var currentQuestionIndex = 0
    private var isListening = false
    private var ttsReady = false
    private var recordTimestamp = ""
    private var photoTaken = false
    private var photoInProgress = false
    private var isRegistered = false

    private var photoFile: File? = null
    private var streamSession: com.meta.wearable.dat.camera.StreamSession? = null
    private var streamStateJob: Job? = null
    private var videoStreamJob: Job? = null
    private var currentStreamState: StreamSessionState? = null
    private var streamReadyForPhoto = false

    private var scoConnected = false
    private var scoReceiver: android.content.BroadcastReceiver? = null
    private var pendingSpeechStart: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val listenForPhotoRunnable = Runnable { listenForPhotoCommand() }

    // Camera permission via SDK
    private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private val permissionMutex = Mutex()
    private val permissionsResultLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            result.onSuccess { status ->
                permissionContinuation?.resume(status)
                permissionContinuation = null
            }
            result.onFailure {
                permissionContinuation?.resume(PermissionStatus.Denied)
                permissionContinuation = null
            }
        }

    // Location permission
    private val locationPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startLocationUpdates()
        }

    private val questions = listOf(
        Pair("artifact",  "What artifact was found?"),
        Pair("depth",     "At what depth was it found?"),
        Pair("elevation", "What is the elevation?"),
        Pair("condition", "What is its condition?"),
        Pair("notes",     "Any additional notes?")
    )

    private data class CustomField(val label: String, val description: String, val type: String)
    private var customFields: List<CustomField>? = null
    private var photoForTemplateQuestion = false
    private var questionsStarted = false
    private var questionsComplete = false
    private var recordName = ""

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
        customFields = loadCustomTemplate()

        startLocationUpdates()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSaveRecord.setOnClickListener { showNameDialogThenSave() }
        binding.btnRetryListen.setOnClickListener {
            binding.btnRetryListen.visibility = View.GONE
            startListening()
        }
        binding.btnHandsFreeToggle.setOnClickListener { toggleHandsFreeMode() }
        binding.btnTakePhoto.setOnClickListener { takePhoto() }
        binding.btnConnect.setOnClickListener {
            binding.tvConnectStatus.text = "Opening Meta AI to register..."
            binding.btnConnect.isEnabled = false
            Wearables.startRegistration(this)
        }

        val noPhotoTemplate = customFields != null && customFields!!.none { it.type == "photo" }
        if (noPhotoTemplate) {
            // Custom template with no photo questions: hide glasses UI, start when TTS ready
            binding.tvConnectLabel.visibility = View.GONE
            binding.cardConnect.visibility = View.GONE
        } else {
            observeRegistrationState()
        }
        applyHandsFreeUI()
    }

    private fun toggleHandsFreeMode() {
        handsFreeMode = !handsFreeMode
        prefs.edit().putBoolean("hands_free_mode", handsFreeMode).apply()
        applyHandsFreeUI()
        if (handsFreeMode) {
            speak("Hands-free mode on.")
        } else {
            // Stop all pending listen loops and timers immediately
            handler.removeCallbacks(listenForPhotoRunnable)
            if (isListening) {
                speechRecognizer.stopListening()
                isListening = false
            }
            tts.stop()
        }
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

    private fun loadCustomTemplate(): List<CustomField>? {
        val json = prefs.getString("custom_template_json", null) ?: return null
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                CustomField(obj.getString("label"), obj.optString("description"), obj.getString("type"))
            }.takeIf { it.isNotEmpty() }
        } catch (e: Exception) { null }
    }

    private fun observeRegistrationState() {
        lifecycleScope.launch {
            Wearables.registrationState.collect { state ->
                when (state) {
                    is RegistrationState.Registered -> {
                        if (!isRegistered) {
                            isRegistered = true
                            binding.tvConnectLabel.visibility = View.GONE
                            binding.cardConnect.visibility = View.GONE
                            val hasPhotoQ = customFields?.any { it.type == "photo" } == true
                            if (customFields != null && !hasPhotoQ) {
                                // Custom template with no photos — skip camera entirely
                                startQuestionsFlow()
                            } else {
                                // Default flow, or custom template that has photo questions
                                setupCameraPermission()
                            }
                        }
                    }
                    else -> {
                        isRegistered = false
                        binding.tvConnectLabel.visibility = View.VISIBLE
                        binding.cardConnect.visibility = View.VISIBLE
                        binding.tvConnectStatus.text = "Connect your Meta glasses to continue."
                        binding.btnConnect.isEnabled = true
                        binding.tvPhotoStepLabel.visibility = View.GONE
                        binding.tvPhotoStatus.visibility = View.GONE
                        binding.btnTakePhoto.visibility = View.GONE
                    }
                }
            }
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
                Log.w("MARP", "Location error: ${e.message}")
            }
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun updateLocationDisplay() {
        val loc = currentLocation
        binding.tvLocation.text = if (loc != null)
            "GPS: ${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}"
        else
            "GPS: Acquiring location..."
    }

    private suspend fun requestWearablesPermission(permission: Permission): PermissionStatus =
        permissionMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                permissionContinuation = continuation
                continuation.invokeOnCancellation { permissionContinuation = null }
                permissionsResultLauncher.launch(permission)
            }
        }

    private fun setupCameraPermission() {
        lifecycleScope.launch {
            val status = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull()
            if (status == PermissionStatus.Granted) {
                showPhotoStep()
            } else {
                binding.tvPhotoStepLabel.visibility = View.VISIBLE
                binding.tvPhotoStatus.visibility = View.VISIBLE
                binding.tvPhotoStatus.text = "Requesting camera permission..."
                val granted = requestWearablesPermission(Permission.CAMERA)
                if (granted == PermissionStatus.Granted) {
                    showPhotoStep()
                } else {
                    binding.tvPhotoStatus.text = "Camera permission denied. Tap to retry."
                    binding.btnTakePhoto.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showPhotoStep() {
        if (customFields != null) {
            // Template mode: go straight to questions; stream starts lazily per photo question
            startQuestionsFlow()
            return
        }
        binding.tvPhotoStepLabel.visibility = View.VISIBLE
        binding.tvPhotoStatus.visibility = View.VISIBLE
        binding.btnTakePhoto.text = "Take Photo via Glasses Camera"
        binding.btnTakePhoto.visibility = View.VISIBLE
        binding.btnTakePhoto.isEnabled = false
        startStreamForPhoto()
    }

    private fun startStreamForPhoto() {
        if (streamSession != null) return

        binding.tvPhotoStatus.text = "Starting camera..."
        currentStreamState = null

        try {
            val session = Wearables.startStreamSession(
                this,
                AutoDeviceSelector(),
                StreamConfiguration(VideoQuality.MEDIUM, 24)
            )
            streamSession = session

            videoStreamJob = lifecycleScope.launch(Dispatchers.Default) {
                session.videoStream.collect { }
            }

            // Fallback: enable button after 2.5s even if STREAMING state isn't confirmed
            handler.postDelayed({
                if (streamSession != null && !photoTaken && customFields == null) {
                    streamReadyForPhoto = true
                    binding.tvPhotoStatus.text = "Camera ready"
                    if (!tts.isSpeaking) {
                        binding.btnTakePhoto.isEnabled = true
                        if (handsFreeMode && !isListening) handler.postDelayed(listenForPhotoRunnable, 300)
                    }
                }
            }, 2500)

            streamStateJob = lifecycleScope.launch {
                session.state.collect { state ->
                    currentStreamState = state
                    if (state == StreamSessionState.STREAMING && customFields == null) {
                        streamReadyForPhoto = true
                        binding.tvPhotoStatus.text = "Camera ready"
                        if (!tts.isSpeaking) {
                            binding.btnTakePhoto.isEnabled = true
                            if (handsFreeMode && !isListening) handler.postDelayed(listenForPhotoRunnable, 300)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MARP", "Stream error", e)
            binding.tvPhotoStatus.text = "Camera connection failed"
        }
    }

    private fun startStreamForTemplatePhoto() {
        binding.btnTakePhoto.isEnabled = false

        // Reuse the existing session if it's already streaming
        if (streamSession != null && currentStreamState == StreamSessionState.STREAMING) {
            binding.tvPhotoStatus.text = "Camera ready — tap to capture"
            binding.btnTakePhoto.isEnabled = true
            if (handsFreeMode) handler.postDelayed({ listenForPhotoCommand() }, 300)
            return
        }

        binding.tvPhotoStatus.text = "Starting camera..."
        currentStreamState = null

        // Cancel any orphaned coroutines from a prior session before starting a new one
        streamStateJob?.cancel()
        videoStreamJob?.cancel()
        streamStateJob = null
        videoStreamJob = null

        try {
            val session = Wearables.startStreamSession(
                this, AutoDeviceSelector(), StreamConfiguration(VideoQuality.MEDIUM, 24)
            )
            streamSession = session
            videoStreamJob = lifecycleScope.launch(Dispatchers.Default) {
                session.videoStream.collect { }
            }

            // Fallback: enable button after 2.5s in case STREAMING state is never confirmed
            handler.postDelayed({
                if (streamSession != null) {
                    binding.btnTakePhoto.isEnabled = true
                    binding.tvPhotoStatus.text = "Camera ready — tap to capture"
                    if (handsFreeMode) handler.postDelayed({ listenForPhotoCommand() }, 300)
                }
            }, 2500)

            // Start voice listener as soon as stream confirms STREAMING (before fallback fires)
            streamStateJob = lifecycleScope.launch {
                session.state.collect { state ->
                    currentStreamState = state
                    if (state == StreamSessionState.STREAMING) {
                        binding.tvPhotoStatus.text = "Camera ready — tap to capture"
                        binding.btnTakePhoto.isEnabled = true
                        if (handsFreeMode) handler.postDelayed({ listenForPhotoCommand() }, 300)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MARP", "Stream error", e)
            binding.tvPhotoStatus.text = "Camera failed — try again"
            binding.btnTakePhoto.isEnabled = true
        }
    }

    private fun takePhoto() {
        val session = streamSession ?: return
        if (photoTaken || photoInProgress) return

        photoInProgress = true
        binding.btnTakePhoto.isEnabled = false
        binding.tvPhotoStatus.text = "Capturing..."

        lifecycleScope.launch {
            session.capturePhoto()
                .onSuccess { photoData ->
                    val bmp: android.graphics.Bitmap? = when (photoData) {
                        is PhotoData.Bitmap -> photoData.bitmap
                        is PhotoData.HEIC -> withContext(Dispatchers.IO) {
                            val buffer = photoData.data.duplicate()
                            buffer.rewind()
                            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                        else -> null
                    }

                    if (bmp != null) {
                        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                        recordTimestamp = ts
                        val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "archaeology")
                        dir.mkdirs()
                        val file = File(dir, "ARCH_$ts.jpg")

                        withContext(Dispatchers.IO) {
                            FileOutputStream(file).use { out ->
                                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                            }
                        }

                        photoFile = file
                        binding.ivPhoto.setImageBitmap(bmp)
                        binding.ivPhoto.visibility = View.VISIBLE
                        onPhotoTaken()
                    } else {
                        photoInProgress = false
                        binding.btnTakePhoto.isEnabled = true
                        binding.tvPhotoStatus.text = "Photo processing failed"
                    }
                }
                .onFailure { error ->
                    Log.e("MARP", "Capture failed: $error")
                    photoInProgress = false
                    binding.btnTakePhoto.isEnabled = true
                    binding.tvPhotoStatus.text = "Capture failed — restart glasses if it keeps happening"
                }
        }
    }

    private fun closeStream() {
        streamStateJob?.cancel()
        videoStreamJob?.cancel()
        try { streamSession?.close() } catch (_: Exception) {}
        streamSession = null
        currentStreamState = null
    }

    private fun onPhotoTaken() {
        photoInProgress = false
        binding.btnTakePhoto.visibility = View.GONE

        val display = SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault()).format(Date())
        binding.tvDateTime.text = "Date/Time: $display"
        updateLocationDisplay()
        binding.layoutMetadata.visibility = View.VISIBLE

        if (photoForTemplateQuestion) {
            photoForTemplateQuestion = false
            val fields = customFields ?: return
            responses[fields[currentQuestionIndex].label] = photoFile?.absolutePath ?: ""
            binding.cardFieldNotes.visibility = View.VISIBLE
            binding.tvFieldNotes.text = responses.entries.joinToString("\n\n") {
                "${it.key.uppercase()}:\n  ${it.value}"
            }
            currentQuestionIndex++
            // Close stream now if no more photo questions remain — prevents the
            // glasses from announcing "Video is paused" randomly during voice questions
            val remainingHasPhoto = fields.drop(currentQuestionIndex).any { it.type == "photo" }
            if (!remainingHasPhoto) closeStream()
            handler.postDelayed({ speak("Photo captured.") { handler.post { askNextQuestion() } } }, 200)
            return
        }

        // Default flow: photo taken, stream no longer needed — close it now so the
        // glasses don't announce "Video is paused" during voice questions
        closeStream()
        photoTaken = true
        binding.tvPhotoStatus.text = "Photo captured"
        if (handsFreeMode) {
            handler.postDelayed({
                speak("Photo captured. Starting field documentation.") {
                    handler.postDelayed({ startQuestionsFlow() }, 500)
                }
            }, 200)
        } else {
            startQuestionsFlow()
        }
    }

    private fun listenForPhotoCommand() {
        if (isListening || photoTaken || photoInProgress) return
        isListening = true
        binding.tvPhotoStatus.text = "Say 'take photo'..."

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Hold the session open for up to 2 minutes so it doesn't time out
            // while the user is deciding when to take the photo
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 120000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 30000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 20000L)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                isListening = false
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.joinToString(" ")?.lowercase() ?: ""
                when {
                    HandsFreeActivity.isHomeCommand(heard) ->
                        speak("Going home.") { handler.postDelayed({ finish() }, 800) }
                    heard.contains("take") || heard.contains("photo") ||
                    heard.contains("capture") || heard.contains("picture") ||
                    heard.contains("snap") ->
                        handler.postDelayed({ speak("Taking photo.") { handler.post { takePhoto() } } }, 200)
                    else ->
                        if (handsFreeMode) handler.post(listenForPhotoRunnable)
                }
            }
            override fun onError(error: Int) {
                isListening = false
                // Session timed out — restart only if still in hands-free mode
                if (handsFreeMode) handler.post(listenForPhotoRunnable)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        launchRecognition(intent) { if (handsFreeMode) handler.post(listenForPhotoRunnable) }
    }

    private fun startQuestionsFlow() {
        if (questionsStarted) return
        questionsStarted = true
        binding.tvVoiceQuestionsLabel.visibility = View.VISIBLE
        binding.cardVoiceQuestions.visibility = View.VISIBLE
        binding.tvStepIndicator.text = "Voice questions"
        startVoiceQuestions()
    }

    private fun startVoiceQuestions() {
        currentQuestionIndex = 0
        questionsComplete = false
        responses.clear()
        binding.tvFieldNotes.text = ""
        binding.tvStepIndicator.text = "Questions"
        handler.postDelayed({ askNextQuestion() }, 500)
    }

    private fun askNextQuestion() {
        val fields = customFields
        val total = fields?.size ?: questions.size
        if (currentQuestionIndex >= total) {
            onVoiceComplete()
            return
        }
        val num = currentQuestionIndex + 1
        binding.tvProgressText.text = "Question $num of $total"
        binding.tvVoiceStatus.text = "Speaking..."
        binding.btnRetryListen.visibility = View.GONE

        if (fields != null) {
            val field = fields[currentQuestionIndex]
            val typeSpoken = when (field.type) {
                "short"  -> "Response type: short answer"
                "long"   -> "Response type: long answer"
                "number" -> "Response type: number"
                else     -> null
            }
            val spoken = listOfNotNull(
                field.label,
                field.description.takeIf { it.isNotEmpty() },
                typeSpoken
            ).joinToString(". ")
            binding.tvCurrentQuestion.text = field.label
            val typeDisplay = when (field.type) {
                "short"  -> "[Response type: Short answer]"
                "long"   -> "[Response type: Long answer]"
                "number" -> "[Response type: Number]"
                else     -> null  // photo: no type label
            }
            val descParts = listOfNotNull(field.description.takeIf { it.isNotEmpty() }, typeDisplay)
            if (descParts.isNotEmpty()) {
                binding.tvQuestionDescription.text = descParts.joinToString("  ")
                binding.tvQuestionDescription.visibility = View.VISIBLE
            } else {
                binding.tvQuestionDescription.visibility = View.GONE
            }

            when (field.type) {
                "photo" -> speak(spoken) {
                    handler.post {
                        photoForTemplateQuestion = true
                        binding.tvPhotoStepLabel.visibility = View.VISIBLE
                        binding.tvPhotoStatus.visibility = View.VISIBLE
                        binding.btnTakePhoto.text = "Take Photo"
                        binding.btnTakePhoto.visibility = View.VISIBLE
                        binding.btnTakePhoto.isEnabled = false
                        startStreamForTemplatePhoto()
                    }
                }
                else -> speak(spoken) { handler.postDelayed({ startListening() }, 50) }
            }
        } else {
            val (_, questionText) = questions[currentQuestionIndex]
            binding.tvCurrentQuestion.text = questionText
            binding.tvQuestionDescription.visibility = View.GONE
            speak(questionText) { handler.postDelayed({ startListening() }, 50) }
        }
    }

    private fun startListening() {
        if (isListening) return
        isListening = true
        binding.tvVoiceStatus.text = "Listening..."

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Hold the session open as long as Android allows before timing out
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 120000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                isListening = false
                val answer = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""

                if (HandsFreeActivity.isHomeCommand(answer.lowercase())) {
                    speak("Going home.") { handler.postDelayed({ finish() }, 800) }
                    return
                }

                if (answer.isNotEmpty()) handleAnswer(answer)
                else if (!questionsComplete) startListening()
            }

            override fun onError(error: Int) {
                isListening = false
                // ERROR_CLIENT means we called stopListening() ourselves — don't restart
                if (!questionsComplete && error != SpeechRecognizer.ERROR_CLIENT) startListening()
            }

            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() { binding.tvVoiceStatus.text = "Processing..." }
            override fun onReadyForSpeech(params: Bundle?) { binding.tvVoiceStatus.text = "Listening..." }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        launchRecognition(intent) { handler.postDelayed({ startListening() }, 500) }
    }

    private fun handleAnswer(answer: String) {
        // Cancel (not just stop) so audio focus is released before TTS starts, preventing "Got it." from being quiet
        speechRecognizer.cancel()
        isListening = false
        val fields = customFields
        if (fields != null) {
            val field = fields[currentQuestionIndex]
            val storedAnswer = if (field.type == "number") {
                answer.trim()
                    .replace(Regex("^(negative|minus)\\s+", RegexOption.IGNORE_CASE), "-")
                    .replace("\\s".toRegex(), "")
            } else answer
            if (field.type == "number" && storedAnswer.toDoubleOrNull() == null) {
                speak("Please say a number.") { handler.post { startListening() } }
                return
            }
            responses[field.label] = storedAnswer
        } else {
            val (key, _) = questions[currentQuestionIndex]
            responses[key] = answer
        }

        binding.cardFieldNotes.visibility = View.VISIBLE
        binding.tvFieldNotes.text = responses.entries.joinToString("\n\n") {
            "${it.key.uppercase()}:\n  ${it.value}"
        }

        currentQuestionIndex++
        speak("Got it.") { handler.post { askNextQuestion() } }
    }

    private fun onVoiceComplete() {
        questionsComplete = true
        binding.tvCurrentQuestion.text = "All questions answered."
        binding.tvQuestionDescription.visibility = View.GONE
        binding.tvVoiceStatus.text = "Complete"
        binding.tvProgressText.text = "Done"
        binding.tvStepIndicator.text = "Ready to save"
        binding.btnSaveRecord.visibility = View.VISIBLE
        if (handsFreeMode) {
            speak("All questions answered. What would you like to name this record? Say skip to save without a name.") {
                handler.postDelayed({ listenForRecordName() }, 800)
            }
        } else {
            speak("Documentation complete. Press Save when ready.")
        }
    }

    private fun listenForRecordName() {
        if (isListening) return
        isListening = true
        binding.tvVoiceStatus.text = "Say a name or 'skip'..."

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 120000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                isListening = false
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim() ?: ""
                if (HandsFreeActivity.isHomeCommand(heard.lowercase())) {
                    speak("Going home.") { handler.postDelayed({ finish() }, 800) }
                    return
                }
                recordName = if (heard.lowercase() == "skip" || heard.isEmpty()) "" else heard
                val confirmation = if (recordName.isEmpty()) "Saving without a name."
                    else "Saving as $recordName."
                saveRecord()
                speak(confirmation) { handler.postDelayed({ finish() }, 1200) }
            }
            override fun onError(error: Int) {
                isListening = false
                // Silently retry — don't auto-save with no name
                handler.postDelayed({ listenForRecordName() }, 200)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { binding.tvVoiceStatus.text = "Processing..." }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        launchRecognition(intent) { handler.postDelayed({ listenForRecordName() }, 500) }
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
                        speak("Say save to save or cancel to discard.") {
                            handler.postDelayed({ listenForSaveCommand() }, 300)
                        }
                    }
                }
            }
            override fun onError(error: Int) {
                isListening = false
                speak("Say save to save or cancel to discard.") {
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

        launchRecognition(intent) { handler.postDelayed({ listenForSaveCommand() }, 2000) }
    }

    private fun showNameDialogThenSave() {
        val editText = android.widget.EditText(this).apply {
            hint = "Enter a name (optional)"
            setPadding(48, 24, 48, 24)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Name this record")
            .setMessage("Leave blank to save without a name.")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                recordName = editText.text.toString().trim()
                saveRecord()
            }
            .setNegativeButton("Skip") { _, _ ->
                recordName = ""
                saveRecord()
            }
            .show()
    }

    private fun saveRecord() {
        try {
            val datetime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val dir = File(filesDir, "records")
            dir.mkdirs()
            val ts = recordTimestamp.ifEmpty {
                SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            }

            val nameSanitized = recordName
                .replace(Regex("[^a-zA-Z0-9 _\\-]"), "").replace(' ', '_').take(30)
            val fileName = if (nameSanitized.isNotEmpty()) "${nameSanitized}_$ts" else "record_$ts"

            val fields = customFields
            if (fields != null) {
                fun esc(v: String) = if (v.contains(',') || v.contains('"') || v.contains('\n'))
                    "\"${v.replace("\"", "\"\"")}\"" else v
                val headers = (listOf("name", "datetime", "latitude", "longitude") + fields.map { it.label })
                    .joinToString(",") { esc(it) }
                val values = (listOf(
                    recordName,
                    datetime,
                    currentLocation?.latitude?.toString() ?: "",
                    currentLocation?.longitude?.toString() ?: ""
                ) + fields.map { responses[it.label] ?: "" }).joinToString(",") { esc(it) }
                File(dir, "$fileName.csv").writeText("$headers\n$values\n")
            } else {
                val json = JSONObject().apply {
                    put("type", "field_record")
                    put("name", recordName)
                    put("datetime", datetime)
                    put("photo_file", photoFile?.absolutePath ?: "")
                    put("latitude", currentLocation?.latitude?.toString() ?: "")
                    put("longitude", currentLocation?.longitude?.toString() ?: "")
                    responses.forEach { (k, v) -> put(k, v) }
                }
                File(dir, "$fileName.json").writeText(json.toString(2))
            }

            Log.d("MARP", "Record saved: $fileName")
            Toast.makeText(this, "Record saved!", Toast.LENGTH_SHORT).show()
            if (!handsFreeMode) {
                binding.btnSaveRecord.text = "Saved!"
                binding.btnSaveRecord.isEnabled = false
            }
        } catch (e: Exception) {
            Log.e("MARP", "Failed to save record", e)
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun speak(text: String, onComplete: (() -> Unit)? = null) {
        // Always use TTS for custom template; otherwise only in hands-free mode
        if (!handsFreeMode && customFields == null) {
            onComplete?.invoke()
            return
        }
        val phoneAudio = prefs.getBoolean("phone_audio_mode", false)
        val stream = if (phoneAudio) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        if (phoneAudio) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true
        }
val uid = "utt_${System.currentTimeMillis()}"
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, stream) }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == uid) handler.post {
                    if (phoneAudio) {
                        audioManager.isSpeakerphoneOn = false
                        audioManager.mode = AudioManager.MODE_NORMAL
                    }
                    onComplete?.invoke()
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == uid) handler.post {
                    if (phoneAudio) {
                        audioManager.isSpeakerphoneOn = false
                        audioManager.mode = AudioManager.MODE_NORMAL
                    }
                    onComplete?.invoke()
                }
            }
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, uid)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setSpeechRate(1.2f)
            tts.setPitch(1.0f)
            ttsReady = true
            val noPhotoTemplate = customFields != null && customFields!!.none { it.type == "photo" }
            when {
                noPhotoTemplate -> handler.postDelayed({ startQuestionsFlow() }, 300)
                handsFreeMode && isRegistered -> {
                    handler.removeCallbacks(listenForPhotoRunnable)
                    speak("Say take photo to begin.") {
                        if (streamReadyForPhoto) binding.btnTakePhoto.isEnabled = true
                        handler.postDelayed(listenForPhotoRunnable, 300)
                    }
                }
            }
        }
    }

    // ── Bluetooth SCO (glasses mic) ────────────────────────────────────────────

    private fun launchRecognition(intent: Intent, onFail: () -> Unit) {
        val doStart = {
            try {
                speechRecognizer.startListening(intent)
            } catch (e: Exception) {
                isListening = false
                onFail()
            }
        }
        if (scoConnected) {
            doStart()
            return
        }
        pendingSpeechStart = doStart
        if (scoReceiver == null) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: Intent) {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                        scoConnected = true
                        pendingSpeechStart?.invoke()
                        pendingSpeechStart = null
                    } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                        scoConnected = false
                    }
                }
            }
            scoReceiver = receiver
            registerReceiver(receiver, android.content.IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.startBluetoothSco()
        // 2s fallback — if glasses don't support HFP, fall back to phone mic
        handler.postDelayed({
            if (!scoConnected && pendingSpeechStart != null) {
                pendingSpeechStart?.invoke()
                pendingSpeechStart = null
            }
        }, 2000)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        speechRecognizer.cancel()
        closeStream()

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                locationManager.removeUpdates(locationListener)
            }
        } catch (e: Exception) { /* ignore */ }

        scoReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        scoReceiver = null
        try { audioManager.stopBluetoothSco() } catch (_: Exception) {}
        audioManager.mode = AudioManager.MODE_NORMAL

        tts.shutdown()
        speechRecognizer.destroy()
    }
}
