package com.fll.archaeologyform

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fll.archaeologyform.databinding.ActivityGlassesStreamBinding
import com.fll.archaeologyform.stream.PresentationQueue
import com.fll.archaeologyform.stream.YuvToBitmapConverter
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGlassesStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnStartStream.setOnClickListener { startStream() }
        binding.btnStopStream.setOnClickListener { stopStream() }
    }

    private fun startStream() {
        binding.btnStartStream.isEnabled = false
        binding.btnStopStream.isEnabled = true
        binding.tvStreamStatus.text = "Starting…"
        frameCount = 0

        val queue = PresentationQueue(
            bufferDelayMs = 100L,
            maxQueueSize = 15,
            onFrameReady = { frame ->
                runOnUiThread {
                    binding.ivStream.setImageBitmap(frame.bitmap)
                    frameCount++
                    binding.tvFrameCount.text = "Frames: $frameCount"
                }
            }
        )
        presentationQueue = queue
        queue.start()

        val session = Wearables.startStreamSession(
            context = this,
            deviceSelector = AutoDeviceSelector(),
            streamConfiguration = StreamConfiguration(
                videoQuality = VideoQuality.MEDIUM,
                frameRate = 24,
            ),
        )
        streamSession = session

        videoJob = lifecycleScope.launch {
            session.videoStream.collect { frame ->
                val bitmap = YuvToBitmapConverter.convert(
                    frame.buffer,
                    frame.width,
                    frame.height,
                )
                if (bitmap != null) {
                    queue.enqueue(bitmap, frame.presentationTimeUs)
                }
            }
        }

        stateJob = lifecycleScope.launch {
            session.state.collect { state ->
                runOnUiThread { updateStatus(state) }
                if (state == StreamSessionState.STOPPED || state == StreamSessionState.CLOSED) {
                    stopStream()
                }
            }
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
        }
    }

    private fun updateStatus(state: StreamSessionState) {
        binding.tvStreamStatus.text = when (state) {
            StreamSessionState.STARTING   -> "Starting…"
            StreamSessionState.STARTED    -> "Connecting…"
            StreamSessionState.STREAMING  -> "Live"
            StreamSessionState.STOPPING   -> "Stopping…"
            StreamSessionState.STOPPED    -> "Stopped"
            StreamSessionState.CLOSED     -> "Closed"
            else                          -> state.name
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStream()
    }
}
