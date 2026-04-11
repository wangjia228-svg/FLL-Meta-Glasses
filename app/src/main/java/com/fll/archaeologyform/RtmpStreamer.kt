package com.fll.archaeologyform

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.pedro.common.ConnectChecker
import com.pedro.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer

/**
 * Encodes Bitmap frames from the Meta glasses to H.264 via MediaCodec (Surface input),
 * pairs them with silent AAC audio, and pushes over RTMP to YouTube.
 * Auto-reconnects on broken pipe / disconnect.
 */
class RtmpStreamer(
    private val width: Int,
    private val height: Int,
    private val onStatus: (String) -> Unit
) : ConnectChecker {

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private val rtmpClient = RtmpClient(this)

    @Volatile private var running   = false
    @Volatile private var connected = false
    @Volatile private var infoSent  = false

    private var rtmpUrl    = ""
    private var ptsUs      = 0L
    private val frameUs    = 1_000_000L / 24L

    private var pendingSps: ByteBuffer? = null
    private var pendingPps: ByteBuffer? = null

    private var videoDrainThread: Thread? = null
    private var audioThread:      Thread? = null
    private var framesQueued = 0

    private val mainHandler    = Handler(Looper.getMainLooper())
    private val reconnectDelay = 3_000L

    // ── Public API ────────────────────────────────────────────────────────────

    fun start(url: String) {
        rtmpUrl    = url
        running    = true
        connected  = false
        infoSent   = false
        ptsUs      = 0L
        framesQueued = 0
        pendingSps = null
        pendingPps = null

        startCodecs()
        rtmpClient.connect(rtmpUrl)
        onStatus("YouTube: Connecting…")
    }

    /** Draw a frame into the encoder surface. Safe to call from any thread. */
    fun encodeFrame(bitmap: Bitmap) {
        if (!running) return
        val surface = encoderSurface ?: return
        try {
            val canvas = surface.lockCanvas(null)
            if (bitmap.width == width && bitmap.height == height) {
                canvas.drawBitmap(bitmap, 0f, 0f, null)
            } else {
                val sx = width.toFloat() / bitmap.width
                val sy = height.toFloat() / bitmap.height
                val m  = Matrix().also { it.setScale(sx, sy) }
                canvas.drawBitmap(bitmap, m, null)
            }
            surface.unlockCanvasAndPost(canvas)
            framesQueued++
            if (framesQueued == 1 || framesQueued % 48 == 0)
                Log.d("RtmpStreamer", "Frames drawn to encoder: $framesQueued")
        } catch (e: Exception) {
            Log.e("RtmpStreamer", "encodeFrame error", e)
        }
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        videoDrainThread?.join(1_000); videoDrainThread = null
        audioThread?.join(1_000);      audioThread      = null
        rtmpClient.disconnect()
        releaseCodecs()
        ptsUs     = 0L
        infoSent  = false
        connected = false
    }

    // ── Codec lifecycle ───────────────────────────────────────────────────────

    private fun startCodecs() {
        // Video: Surface input — no manual NV12 conversion needed
        val vFmt = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        videoCodec = MediaCodec.createEncoderByType("video/avc").also { enc ->
            enc.configure(vFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = enc.createInputSurface()
            enc.start()
        }

        // Audio: silent AAC-LC, 44100 Hz, mono
        val aFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
        }
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(aFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        videoDrainThread = Thread(::drainVideo, "RtmpVideoDrain").also { it.start() }
        audioThread      = Thread(::drainAudio, "RtmpAudioDrain").also { it.start() }
    }

    private fun releaseCodecs() {
        try { encoderSurface?.release(); encoderSurface = null } catch (_: Exception) {}
        try { videoCodec?.stop(); videoCodec?.release(); videoCodec = null } catch (_: Exception) {}
        try { audioCodec?.stop(); audioCodec?.release(); audioCodec = null } catch (_: Exception) {}
    }

    // ── Video drain ───────────────────────────────────────────────────────────

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        var framesSent = 0
        Log.d("RtmpStreamer", "Video drain started")
        while (running) {
            val enc = videoCodec ?: break
            try {
                when (val idx = enc.dequeueOutputBuffer(info, 100)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d("RtmpStreamer", "Video FORMAT CHANGED: ${enc.outputFormat}")
                        val sps = enc.outputFormat.getByteBuffer("csd-0")
                        val pps = enc.outputFormat.getByteBuffer("csd-1")
                        if (sps != null && pps != null) {
                            pendingSps = sps
                            pendingPps = pps
                            if (connected) sendVideoInfo(sps, pps)
                            else Log.d("RtmpStreamer", "SPS/PPS cached — not connected yet")
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> if (idx >= 0) {
                        val buf      = enc.getOutputBuffer(idx)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buf != null && infoSent && !isConfig && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            rtmpClient.sendVideo(buf, info)
                            framesSent++
                            if (framesSent == 1 || framesSent % 30 == 0)
                                Log.d("RtmpStreamer", "Video frames sent: $framesSent")
                        }
                        enc.releaseOutputBuffer(idx, false)
                    }
                }
            } catch (e: Exception) {
                Log.e("RtmpStreamer", "Video drain error", e)
            }
        }
        Log.d("RtmpStreamer", "Video drain ended, frames sent: $framesSent")
    }

    // ── Audio drain (silent AAC) ──────────────────────────────────────────────

    private fun drainAudio() {
        val info     = MediaCodec.BufferInfo()
        val silence  = ByteArray(2048)
        var audioPts = 0L
        val period   = 2048L * 1_000_000L / 2L / 44100L
        var sent = 0
        Log.d("RtmpStreamer", "Audio drain started")

        while (running) {
            val enc = audioCodec ?: break
            try {
                val inIdx = enc.dequeueInputBuffer(100)
                if (inIdx >= 0) {
                    enc.getInputBuffer(inIdx)?.apply { clear(); put(silence) }
                    enc.queueInputBuffer(inIdx, 0, silence.size, audioPts, 0)
                    audioPts += period
                }
                when (val outIdx = enc.dequeueOutputBuffer(info, 100)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d("RtmpStreamer", "Audio FORMAT CHANGED — calling setAudioInfo")
                        rtmpClient.setAudioInfo(44100, false)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> if (outIdx >= 0) {
                        val buf      = enc.getOutputBuffer(outIdx)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buf != null && connected && !isConfig && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            rtmpClient.sendAudio(buf, info)
                            sent++
                            if (sent == 1 || sent % 100 == 0)
                                Log.d("RtmpStreamer", "Audio frames sent: $sent")
                        }
                        enc.releaseOutputBuffer(outIdx, false)
                    }
                }
            } catch (e: Exception) {
                Log.e("RtmpStreamer", "Audio drain error", e)
            }
        }
        Log.d("RtmpStreamer", "Audio drain ended")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sendVideoInfo(sps: ByteBuffer, pps: ByteBuffer) {
        sps.rewind(); pps.rewind()
        rtmpClient.setVideoInfo(sps, pps, null)
        infoSent = true
        Log.d("RtmpStreamer", "SPS/PPS sent → streaming")
        onStatus("YouTube: Streaming")
    }

    private fun reconnect() {
        if (!running) return
        connected = false
        infoSent  = false
        onStatus("YouTube: Reconnecting…")
        mainHandler.postDelayed({
            if (running) {
                Log.d("RtmpStreamer", "Reconnecting…")
                rtmpClient.connect(rtmpUrl)
            }
        }, reconnectDelay)
    }

    // ── ConnectChecker ────────────────────────────────────────────────────────

    override fun onConnectionStarted(url: String) { onStatus("YouTube: Connecting…") }

    override fun onConnectionSuccess() {
        connected = true
        rtmpClient.setAudioInfo(44100, false)
        val sps = pendingSps
        val pps = pendingPps
        if (sps != null && pps != null && !infoSent) sendVideoInfo(sps, pps)
        else onStatus("YouTube: Connected — waiting for video…")
        Log.d("RtmpStreamer", "RTMP connected")
    }

    override fun onConnectionFailed(reason: String) {
        Log.e("RtmpStreamer", "Connection failed: $reason")
        if (running) reconnect() else onStatus("YouTube: Failed — $reason")
    }

    override fun onDisconnect() {
        Log.w("RtmpStreamer", "Disconnected")
        if (running) reconnect() else onStatus("YouTube: Disconnected")
    }

    override fun onAuthError()               { onStatus("YouTube: Auth error") }
    override fun onAuthSuccess()             {}
    override fun onNewBitrate(bitrate: Long) {}
}
