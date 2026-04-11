package com.fll.archaeologyform

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fll.archaeologyform.databinding.ActivityGlassesStreamBinding
import com.fll.archaeologyform.stream.PresentationQueue
import com.fll.archaeologyform.stream.YuvToBitmapConverter
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class GlassesStreamActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGlassesStreamBinding

    private var streamSession: StreamSession? = null
    private var videoJob: Job? = null
    private var stateJob: Job? = null
    private var presentationQueue: PresentationQueue? = null
    private var frameCount = 0

    // YouTube live streaming
    private var rtmpStreamer: RtmpStreamer? = null
    private var youTubeBroadcastId: String? = null
    private var youTubeStreamId: String? = null
    private var youTubeAccountEmail: String? = null
    private var isYouTubeStreaming = false

    private val youTubeManager by lazy { YouTubeLiveManager(this) }

    private val googleSignInLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            runCatching {
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                val email = account?.email ?: return@runCatching
                youTubeAccountEmail = email
                binding.tvYouTubeStatus.text = "YouTube: Signed in as $email — creating broadcast…"
                lifecycleScope.launch {
                    val result = youTubeManager.createBroadcastAndStream(email)
                    result.onSuccess { creds ->
                        youTubeBroadcastId = creds.broadcastId
                        youTubeStreamId    = creds.streamId
                        startRtmpStream(creds.rtmpUrl)
                    }
                    result.onFailure { e ->
                        binding.tvYouTubeStatus.text = "YouTube: Failed — ${e.message}"
                        binding.btnYouTubeStream.isEnabled = true
                    }
                }
            }.onFailure {
                binding.tvYouTubeStatus.text = "YouTube: Sign-in failed — ${it.message}"
                binding.btnYouTubeStream.isEnabled = true
            }
        } else {
            binding.btnYouTubeStream.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGlassesStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnStartStream.setOnClickListener { startStream() }
        binding.btnStopStream.setOnClickListener { stopStream() }
        binding.btnYouTubeStream.setOnClickListener {
            if (isYouTubeStreaming) stopYouTubeStream() else showYouTubeDialog()
        }
    }

    // ── Glasses preview stream ───────────────────────────────────────────────

    private fun startStream() {
        binding.btnStartStream.isEnabled = false
        binding.btnStopStream.isEnabled = true
        binding.tvStreamStatus.text = "Starting…"
        frameCount = 0

        try {
            val queue = PresentationQueue(
                bufferDelayMs = 100L,
                maxQueueSize = 15,
                onFrameReady = { frame ->
                    runOnUiThread {
                        binding.ivStream.setImageBitmap(frame.bitmap)
                        frameCount++
                        binding.tvFrameCount.text = "Frames: $frameCount"
                    }
                    rtmpStreamer?.encodeFrame(frame.bitmap)
                }
            )
            presentationQueue = queue
            queue.start()

            val session = Wearables.startStreamSession(
                context = this,
                deviceSelector = AutoDeviceSelector(),
                streamConfiguration = StreamConfiguration(VideoQuality.MEDIUM, 24),
            )
            streamSession = session

            videoJob = lifecycleScope.launch {
                session.videoStream.collect { frame ->
                    val bitmap = YuvToBitmapConverter.convert(frame.buffer, frame.width, frame.height)
                    if (bitmap != null) queue.enqueue(bitmap, frame.presentationTimeUs)
                }
            }

            var seenActiveState = false
            stateJob = lifecycleScope.launch {
                session.state.collect { state ->
                    when (state) {
                        StreamSessionState.STARTING,
                        StreamSessionState.STARTED,
                        StreamSessionState.STREAMING,
                        StreamSessionState.STOPPING -> seenActiveState = true
                        StreamSessionState.STOPPED,
                        StreamSessionState.CLOSED -> if (seenActiveState) stopStream()
                        else -> {}
                    }
                    runOnUiThread { updateStatus(state) }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("GlassesStream", "startStream failed", e)
            binding.tvStreamStatus.text = "Error: ${e.message}"
            Toast.makeText(this, "Stream error: ${e.message}", Toast.LENGTH_LONG).show()
            binding.btnStartStream.isEnabled = true
            binding.btnStopStream.isEnabled = false
        }
    }

    private fun stopStream() {
        videoJob?.cancel(); videoJob = null
        stateJob?.cancel(); stateJob = null
        presentationQueue?.stop(); presentationQueue = null
        streamSession?.close(); streamSession = null

        runOnUiThread {
            binding.btnStartStream.isEnabled = true
            binding.btnStopStream.isEnabled = false
            binding.tvStreamStatus.text = "Stopped"
            binding.tvFrameCount.text = "Frames: 0"
            binding.ivStream.setImageDrawable(null)
        }
    }

    private fun updateStatus(state: StreamSessionState) {
        binding.tvStreamStatus.text = when (state) {
            StreamSessionState.STARTING  -> "Starting…"
            StreamSessionState.STARTED   -> "Connecting…"
            StreamSessionState.STREAMING -> "Live"
            StreamSessionState.STOPPING  -> "Stopping…"
            StreamSessionState.STOPPED   -> "Stopped"
            StreamSessionState.CLOSED    -> "Closed"
            else                         -> state.name
        }
    }

    // ── YouTube live streaming ───────────────────────────────────────────────

    private fun showYouTubeDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val keyInput = EditText(this).apply {
            hint = "Stream key (from YouTube Studio)"
        }
        container.addView(keyInput)

        AlertDialog.Builder(this)
            .setTitle("Stream to YouTube")
            .setMessage("Enter your stream key, or sign in with Google to create a broadcast automatically.")
            .setView(container)
            .setPositiveButton("Start with key") { _, _ ->
                val key = keyInput.text.toString().trim()
                if (key.isNotEmpty()) {
                    startRtmpStream("${YouTubeLiveManager.RTMP_BASE}/$key")
                } else {
                    Toast.makeText(this, "Please enter a stream key.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Sign in with Google") { _, _ ->
                startGoogleSignIn()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startGoogleSignIn() {
        binding.btnYouTubeStream.isEnabled = false
        binding.tvYouTubeStatus.text = "YouTube: Signing in…"
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(YouTubeLiveManager.YOUTUBE_SCOPE))
            .build()
        googleSignInLauncher.launch(GoogleSignIn.getClient(this, gso).signInIntent)
    }

    private fun startRtmpStream(rtmpUrl: String) {
        isYouTubeStreaming = true
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.btnYouTubeStream.text = "Stop YouTube Stream"
        binding.tvYouTubeStatus.text = "YouTube: Connecting…"

        // 504×896 matches VideoQuality.MEDIUM from the glasses
        val streamer = RtmpStreamer(432, 768) { status ->
            runOnUiThread { binding.tvYouTubeStatus.text = status }
        }
        rtmpStreamer = streamer
        streamer.start(rtmpUrl)

        // If we created the broadcast via API, offer to go live after 5 s
        val broadcastId = youTubeBroadcastId
        val email = youTubeAccountEmail
        val streamId = youTubeStreamId
        if (broadcastId != null && streamId != null && email != null) {
            lifecycleScope.launch {
                val url = "https://www.youtube.com/watch?v=$broadcastId"
                val success = youTubeManager.waitForStreamThenGoLive(
                    broadcastId, streamId, email
                ) { status -> runOnUiThread { binding.tvYouTubeStatus.text = status } }

                if (!isYouTubeStreaming) return@launch
                runOnUiThread {
                    if (success) {
                        binding.tvYouTubeStatus.text = "▶ LIVE — tap to open"
                    } else {
                        binding.tvYouTubeStatus.text = "⚠ Could not go live — check YouTube Studio"
                    }
                    binding.tvYouTubeStatus.setOnClickListener {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    }
                    binding.btnShareStream.visibility = android.view.View.VISIBLE
                    binding.btnShareStream.setOnClickListener {
                        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, url)
                        }
                        startActivity(android.content.Intent.createChooser(share, "Share stream link"))
                    }
                }
            }
        }
    }

    private fun stopYouTubeStream() {
        isYouTubeStreaming = false
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.btnYouTubeStream.text = "🎬 Stream to YouTube"
        binding.tvYouTubeStatus.text = "YouTube: Stopping…"
        binding.btnYouTubeStream.isEnabled = false

        val streamer = rtmpStreamer
        rtmpStreamer = null
        val broadcastId = youTubeBroadcastId
        val email = youTubeAccountEmail
        youTubeBroadcastId = null
        youTubeStreamId    = null

        lifecycleScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    streamer?.stop()
                    if (broadcastId != null && email != null) {
                        youTubeManager.endBroadcast(broadcastId, email)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("GlassesStream", "stopYouTubeStream error", e)
            } finally {
                binding.tvYouTubeStatus.text = "YouTube: Not streaming"
                binding.tvYouTubeStatus.setOnClickListener(null)
                binding.btnShareStream.visibility = android.view.View.GONE
                binding.btnShareStream.setOnClickListener(null)
                binding.btnYouTubeStream.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopYouTubeStream()
        stopStream()
    }
}
