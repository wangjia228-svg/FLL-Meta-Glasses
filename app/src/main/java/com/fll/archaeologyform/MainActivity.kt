package com.fll.archaeologyform

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.meta.wearable.dat.core.Wearables
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.*
import com.fll.archaeologyform.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    
    private var currentQuestionIndex = 0
    private var isRunning = false
    private val formData = mutableMapOf<String, String>()
    private var questions = listOf<Question>()
    
    data class Question(val text: String, val field: String)
    
    private val commonQuestions = listOf(
        Question("What is the project name?", "project"),
        Question("What is today's date?", "date"),
        Question("Who is recording?", "recorders"),
        Question("What is the site number?", "stNumber"),
        Question("What is the location?", "location")
    )
    
    private val shovelTestQuestions = listOf(
        Question("Describe the surrounding vegetation.", "surroundingVegetation"),
        Question("For Stratum 1, what is the depth range?", "stratum1Depth"),
        Question("What materials were recovered in Stratum 1?", "stratum1Materials"),
        Question("What is the Munsell color for Stratum 1?", "stratum1Munsell"),
        Question("Any additional notes?", "notes")
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        textToSpeech = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(createRecognitionListener())
        
        checkPermissions()
        setupUI()
        observeGlassesConnection()
    }
    
    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1)
        }
    }
    
    private fun getWearablesInstance(): Any? {
        return try {
            // First try to get the Kotlin 'INSTANCE' field
            Wearables::class.java.getField("INSTANCE").get(null)
        } catch (e: Exception) {
            try {
                // Fallback: try the 'getInstance' method found in logs
                val method = Wearables::class.java.methods.find { it.name.contains("getInstance") }
                method?.invoke(null)
            } catch (e2: Exception) {
                Log.e("MainActivity", "Could not get Wearables instance", e2)
                null
            }
        }
    }
    
    private fun setupUI() {
        binding.startFormBtn.isEnabled = true
        binding.connectGlassesBtn.setOnClickListener {
            val instance = getWearablesInstance()
            if (instance == null) {
                binding.connectionStatus.text = "Error: Wearables not initialized"
                return@setOnClickListener
            }
            
            try {
                // The logs show the method is 'startRegistration'
                val registerMethod = instance.javaClass.methods.find { 
                    it.name == "startRegistration" && it.parameterCount == 1 
                }
                registerMethod?.invoke(instance, this) ?: throw Exception("startRegistration method not found")
            } catch (e: Exception) {
                binding.connectionStatus.text = "Connect Error: ${e.message}"
                Log.e("MainActivity", "Registration error", e)
            }
        }
        binding.startFormBtn.setOnClickListener { if (isRunning) stopForm() else startForm() }
        binding.stopBtn.setOnClickListener { stopForm() }
        binding.resetBtn.setOnClickListener { resetForm() }
    }
    
    private fun observeGlassesConnection() {
        lifecycleScope.launch {
            val instance = getWearablesInstance() ?: return@launch
            
            try {
                val statusMethod = instance.javaClass.methods.find { 
                    it.name == "getRegistrationState" && it.parameterCount == 0 
                }
                
                // Observe the registration state flow
                (statusMethod?.invoke(instance) as? kotlinx.coroutines.flow.Flow<*>)?.collectLatest { status ->
                    val statusName = status.toString()
                    Log.d("GlassesDebug", "Status: $statusName")
                    
                    if (statusName.contains("Registered", ignoreCase = true) || statusName.contains("LOW", ignoreCase = true)) {
                        binding.connectionStatus.text = "✓ Meta Glasses Linked"
                        binding.connectionStatus.setTextColor(android.graphics.Color.parseColor("#27ae60"))
                        binding.connectGlassesBtn.visibility = android.view.View.GONE
                    } else {
                        binding.connectionStatus.text = "Status: $statusName"
                        binding.connectionStatus.setTextColor(android.graphics.Color.RED)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Observe error", e)
            }
        }
    }
    
    private fun startForm() {
        isRunning = true
        binding.startFormBtn.text = "Pause Form"
        binding.statusText.text = "Starting..."
        speak("Which form would you like to fill out? Say shovel test or unit level.")
        binding.currentQuestion.text = "Say: 'Shovel Test' or 'Unit Level'"
        binding.root.postDelayed({ if (isRunning) startListening() }, 3000)
    }
    
    private fun stopForm() {
        isRunning = false
        speechRecognizer.stopListening()
        binding.startFormBtn.text = "Start Form"
        binding.statusText.text = "Stopped"
    }
    
    private fun resetForm() {
        currentQuestionIndex = 0
        formData.clear()
        questions = emptyList()
        isRunning = false
        binding.startFormBtn.text = "Start Form"
        binding.currentQuestion.text = "Press Start to begin"
        binding.formDataText.text = "No data yet"
        binding.formTypeText.text = "Form: Not Started"
        binding.progressText.text = "Question 0 of 0"
        binding.statusText.text = "Ready"
    }
    
    private fun askNextQuestion() {
        if (!isRunning) return
        if (currentQuestionIndex >= questions.size) {
            isRunning = false
            speak("Form complete!")
            binding.currentQuestion.text = "✓ Complete!"
            return
        }
        val question = questions[currentQuestionIndex]
        speak(question.text)
        binding.currentQuestion.text = question.text
        binding.progressText.text = "Question ${currentQuestionIndex + 1} of ${questions.size}"
        binding.root.postDelayed({ if (isRunning) startListening() }, 3000)
    }
    
    private fun startListening() {
        if (!isRunning) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        try {
            speechRecognizer.startListening(intent)
            binding.statusText.text = "🎤 Listening..."
        } catch (e: Exception) {
            binding.root.postDelayed({ startListening() }, 2000)
        }
    }
    
    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) handleAnswer(matches[0])
            else if (isRunning) binding.root.postDelayed({ startListening() }, 2000)
        }
        override fun onError(error: Int) { if (isRunning) binding.root.postDelayed({ startListening() }, 2000) }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
    
    private fun handleAnswer(answer: String) {
        if (!isRunning) return
        binding.statusText.text = "You said: \"$answer\""
        if (questions.isEmpty()) {
            val answerLower = answer.lowercase()
            when {
                answerLower.contains("shovel") || answerLower.contains("test") -> {
                    questions = commonQuestions + shovelTestQuestions
                    binding.formTypeText.text = "Form: Shovel Test"
                    speak("Starting shovel test.")
                    currentQuestionIndex = 0
                    binding.root.postDelayed({ askNextQuestion() }, 2000)
                }
                answerLower.contains("unit") || answerLower.contains("level") -> {
                    questions = commonQuestions
                    binding.formTypeText.text = "Form: Unit Level Record"
                    speak("Starting unit level.")
                    currentQuestionIndex = 0
                    binding.root.postDelayed({ askNextQuestion() }, 2000)
                }
                else -> binding.root.postDelayed({ startListening() }, 3000)
            }
            return
        }
        formData[questions[currentQuestionIndex].field] = answer
        updateFormDisplay()
        currentQuestionIndex++
        binding.root.postDelayed({ if (isRunning) askNextQuestion() }, 1500)
    }
    
    private fun updateFormDisplay() {
        binding.formDataText.text = formData.entries.joinToString("\n\n") { "${it.key.uppercase()}:\n ${it.value}" }
    }
    
    private fun speak(text: String) {
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "MainActivityTTS")
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech.language = Locale.US
            textToSpeech.setSpeechRate(1.1f)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        textToSpeech.shutdown()
        speechRecognizer.destroy()
    }
}
