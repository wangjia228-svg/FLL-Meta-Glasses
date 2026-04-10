package com.fll.archaeologyform

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
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
    private var scoStateReceiver: BroadcastReceiver? = null

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
        // Only init Bluetooth SCO if hands-free is actually on (avoids audio mode
        // interfering with the Meta glasses camera stream in MainActivity)
        checkPermissions()
        setupUI()
        observeGlassesConnection()
    }

    override fun onResume() {
        super.onResume()
        isListening = false
        updateHandsFreeUI()
        if (handsFreeMode && ttsReady) {
            handler.postDelayed({ announceMenuAndListen() }, 600)
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
            if (handsFreeMode) {
                handler.postDelayed({ announceMenuAndListen() }, 800)
            }
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
        binding.btnHandsFreeToggle.setOnClickListener {
            handsFreeMode = !handsFreeMode
            updateHandsFreeUI()
            if (handsFreeMode) {
                speak("Hands-free mode on. Say 'field record' to start a new record, or 'view records' to see saved records.") {
                    handler.post { listenForNavCommand() }
                }
            } else {
                stopListening()
                speak("Hands-free mode off.")
            }
        }
    }

    private fun updateHandsFreeUI() {
        if (handsFreeMode) {
            binding.btnHandsFreeToggle.text = "\uD83C\uDF99 ON"
            binding.btnHandsFreeToggle.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#22AA22"))
            binding.tvHandsFreeBar.visibility = View.VISIBLE
        } else {
            binding.btnHandsFreeToggle.text = "\uD83C\uDF99 OFF"
            binding.btnHandsFreeToggle.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#555555"))
            binding.tvHandsFreeBar.visibility = View.GONE
        }
    }

    private fun announceMenuAndListen() {
        speak("MARP home. Say 'field record' to start a new record, or 'view records' to see saved records.") {
            handler.post { listenForNavCommand() }
        }
    }

    private fun listenForNavCommand() {
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
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.joinToString(" ")?.lowercase() ?: ""
                when {
                    text.contains("view") || text.contains("saved") || text.contains("history") -> {
                        speak("Opening records.") {
                            startActivity(Intent(this@HomeActivity, RecordsActivity::class.java))
                        }
                    }
                    text.contains("field") || text.contains("new") || text.contains("start") ||
                    text.contains("form") || text.contains("record") -> {
                        speak("Opening field record.") {
                            startActivity(Intent(this@HomeActivity, MainActivity::class.java))
                        }
                    }
                    else -> {
                        if (handsFreeMode) handler.postDelayed({ listenForNavCommand() }, 500)
                    }
                }
            }

            override fun onError(error: Int) {
                isListening = false
                if (handsFreeMode) handler.postDelayed({ listenForNavCommand() }, 1000)
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
            handler.postDelayed({ listenForNavCommand() }, 1500)
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

    private fun initBluetoothSco() {
        scoStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {}
        }
        registerReceiver(scoStateReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
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
        try {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
            scoStateReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) { /* ignore */ }
        tts.shutdown()
        speechRecognizer.destroy()
    }
}
