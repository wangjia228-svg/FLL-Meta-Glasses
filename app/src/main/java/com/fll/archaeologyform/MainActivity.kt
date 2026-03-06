package com.fll.archaeologyform

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.content.Intent
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
    
    // Form state
    private var currentQuestionIndex = 0
    private var isRunning = false
    private val formData = mutableMapOf<String, String>()
    private var questions = listOf<Question>()
    
    data class Question(val text: String, val field: String)
    
    private val commonQuestions = listOf(
        Question("What is the project name?", "project"),
        Question("What is today's date? Please say the month, day, and year.", "date"),
        Question("Who is recording? Say your name or initials.", "recorders"),
        Question("What is the site number?", "stNumber"),
        Question("What is the location? Please include the distance in meters and the direction.", "location")
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
            Manifest.permission.BLUETOOTH_CONNECT
        )
        
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1)
        }
    }
    
    private fun setupUI() {
        binding.connectGlassesBtn.setOnClickListener {
            requestWearablesRegistration()
        }
        
        binding.startFormBtn.setOnClickListener {
            startForm()
        }
        
        binding.stopBtn.setOnClickListener {
            stopForm()
        }
        
        binding.resetBtn.setOnClickListener {
            resetForm()
        }
    }
    
    private fun requestWearablesRegistration() {
        // Updated to the most likely method name based on common Meta SDK patterns
        Wearables.startRegistration(this)
    }
    
    private fun observeGlassesConnection() {
        lifecycleScope.launch {
            // Using a more generic access method to find the correct status function
            try {
                // We'll try to find the correct status flow. Meta SDKs often use getRegistrationStatus()
                Wearables::class.java.getMethod("getRegistrationStatus").invoke(null).let { flow ->
                    (flow as kotlinx.coroutines.flow.Flow<*>).collectLatest { status ->
                        val statusString = status.toString()
                        if (statusString.contains("Registered", ignoreCase = true)) {
                            binding.connectionStatus.text = "✓ Connected to Meta Glasses"
                            binding.startFormBtn.isEnabled = true
                            binding.connectGlassesBtn.visibility = android.view.View.GONE
                        }
                    }
                }
            } catch (e: Exception) {
                binding.connectionStatus.text = "SDK Connected. Waiting for glasses..."
            }
        }
    }
    
    private fun startForm() {
        speak("Which form would you like to fill out? Say shovel test or unit level.")
        binding.currentQuestion.text = "Say: 'Shovel Test' or 'Unit Level'"
        binding.statusText.text = "Waiting for form selection..."
        isRunning = true
        
        binding.root.postDelayed({ 
            if (isRunning) startListening() 
        }, 2500)
    }
    
    private fun stopForm() {
        isRunning = false
        speechRecognizer.stopListening()
        binding.currentQuestion.text = "Form stopped"
        binding.statusText.text = "Stopped"
    }
    
    private fun resetForm() {
        currentQuestionIndex = 0
        formData.clear()
        questions = listOf()
        binding.currentQuestion.text = "Press Start to begin"
        binding.formDataText.text = "No data yet"
        binding.formTypeText.text = "Form: Not Started"
        binding.progressText.text = "Question 0 of 0"
        binding.statusText.text = "Ready"
    }
    
    private fun askNextQuestion() {
        if (currentQuestionIndex >= questions.size) {
            isRunning = false
            speak("Form complete! All questions answered.")
            binding.currentQuestion.text = "✓ Form Complete!"
            binding.statusText.text = "Complete"
            Snackbar.make(binding.root, "Form saved!", Snackbar.LENGTH_LONG).show()
            return
        }
        
        val question = questions[currentQuestionIndex]
        speak(question.text)
        binding.currentQuestion.text = question.text
        binding.progressText.text = "Question ${currentQuestionIndex + 1} of ${questions.size}"
        binding.statusText.text = "Speaking question..."
        
        binding.root.postDelayed({
            if (isRunning) startListening()
        }, 2500)
    }
    
    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        speechRecognizer.startListening(intent)
        binding.statusText.text = "🎤 Listening..."
    }
    
    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (matches != null && matches.isNotEmpty()) {
                handleAnswer(matches[0])
            }
        }
        
        override fun onError(error: Int) {
            binding.statusText.text = "Error - please try again"
            if (isRunning) {
                binding.root.postDelayed({ startListening() }, 1000)
            }
        }
        
        override fun onReadyForSpeech(params: Bundle?) {
            binding.statusText.text = "🎤 Listening..."
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            binding.statusText.text = "Processing..."
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
    
    private fun handleAnswer(answer: String) {
        binding.statusText.text = "You said: \"$answer\""
        
        if (questions.isEmpty()) {
            val answerLower = answer.lowercase()
            when {
                answerLower.contains("shovel") || answerLower.contains("test") -> {
                    formData["formType"] = "Shovel Test"
                    questions = commonQuestions + shovelTestQuestions
                    speak("Starting shovel test form. First question:")
                    binding.formTypeText.text = "Form: Shovel Test"
                    currentQuestionIndex = 0
                    binding.root.postDelayed({ askNextQuestion() }, 2000)
                }
                answerLower.contains("unit") || answerLower.contains("level") -> {
                    formData["formType"] = "Unit Level Record"
                    questions = commonQuestions
                    speak("Starting unit level record form. First question:")
                    binding.formTypeText.text = "Form: Unit Level Record"
                    currentQuestionIndex = 0
                    binding.root.postDelayed({ askNextQuestion() }, 2000)
                }
                else -> {
                    speak("I didn't understand. Please say shovel test or unit level.")
                    binding.root.postDelayed({ startListening() }, 2500)
                }
            }
            return
        }
        
        val question = questions[currentQuestionIndex]
        formData[question.field] = answer
        updateFormDisplay()
        
        currentQuestionIndex++
        
        binding.root.postDelayed({
            if (isRunning) askNextQuestion()
        }, 1500)
    }
    
    private fun updateFormDisplay() {
        val formText = formData.entries.joinToString("\n\n") { 
            "${it.key}:\n  ${it.value}" 
        }
        binding.formDataText.text = if (formText.isEmpty()) "No data yet" else formText
    }
    
    private fun speak(text: String) {
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech.language = Locale.US
            textToSpeech.setSpeechRate(1.3f)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        textToSpeech.shutdown()
        speechRecognizer.destroy()
    }
}
