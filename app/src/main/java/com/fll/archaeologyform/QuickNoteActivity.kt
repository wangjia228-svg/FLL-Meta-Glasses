package com.fll.archaeologyform

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fll.archaeologyform.databinding.ActivityQuickNoteBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class QuickNoteActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityQuickNoteBinding
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var audioManager: AudioManager

    private var isListening = false
    private var ttsReady = false
    private val notes = mutableListOf<String>()

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickNoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRecord.setOnClickListener { if (!isListening) startListening() }
        binding.btnSave.setOnClickListener { saveNotes() }
        binding.btnClear.setOnClickListener {
            notes.clear()
            binding.tvNotes.text = ""
            binding.cardNotes.visibility = View.GONE
            binding.btnSave.visibility = View.GONE
            binding.btnClear.visibility = View.GONE
            binding.tvStatus.text = "Press the button below and speak your note."
        }
    }

    private fun startListening() {
        if (isListening) return
        isListening = true
        binding.tvStatus.text = "Listening..."
        binding.btnRecord.text = "Listening..."

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                isListening = false
                binding.btnRecord.text = "Record Note"
                val note = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                if (note.isNotEmpty()) {
                    handleNoteResult(note)
                } else {
                    binding.tvStatus.text = "Didn't catch that, try again."
                }
            }

            override fun onError(error: Int) {
                isListening = false
                binding.btnRecord.text = "Record Note"
                binding.tvStatus.text = "Didn't catch that, try again."
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { binding.tvStatus.text = "Processing..." }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            binding.btnRecord.text = "Record Note"
            binding.tvStatus.text = "Error starting recognition."
        }
    }

    private fun handleNoteResult(note: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        notes.add("[$ts] $note")
        binding.tvNotes.text = notes.joinToString("\n\n")
        binding.cardNotes.visibility = View.VISIBLE
        binding.btnSave.visibility = View.VISIBLE
        binding.btnClear.visibility = View.VISIBLE
        binding.tvStatus.text = "Note added! Tap again to add another."
    }

    private fun saveNotes() {
        if (notes.isEmpty()) return
        val dir = File(filesDir, "records")
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        File(dir, "note_$ts.txt").writeText(notes.joinToString("\n"))
        Toast.makeText(this, "Notes saved!", Toast.LENGTH_SHORT).show()
        binding.btnSave.text = "Saved!"
        binding.btnSave.isEnabled = false
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
    }
}
