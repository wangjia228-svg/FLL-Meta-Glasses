package com.fll.archaeologyform.stream

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class PresentationQueue(
    private val bufferDelayMs: Long = 100L,
    private val maxQueueSize: Int = 15,
    private val onFrameReady: (PresentationFrame) -> Unit,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    data class PresentationFrame(val bitmap: Bitmap, val presentationTimeUs: Long) : Comparable<PresentationFrame> {
        override fun compareTo(other: PresentationFrame) = presentationTimeUs.compareTo(other.presentationTimeUs)
    }

    private val frameQueue = PriorityBlockingQueue<PresentationFrame>(maxQueueSize + 1)
    private val queueLock = Any()
    private var presentationThread: HandlerThread? = null
    private var presentationHandler: Handler? = null
    private val running = AtomicBoolean(false)
    private val baseWallTimeMs = AtomicLong(-1L)
    private val basePresentationTimeUs = AtomicLong(-1L)

    private val presentationRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val presented = tryPresentNextFrame()
            presentationHandler?.postDelayed(this, if (presented) 5L else 1L)
        }
    }

    fun start() {
        if (running.getAndSet(true)) return
        presentationThread = HandlerThread("PresentationThread", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        presentationHandler = presentationThread?.looper?.let { Handler(it) }
        baseWallTimeMs.set(-1L)
        basePresentationTimeUs.set(-1L)
        presentationHandler?.post(presentationRunnable)
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        presentationHandler?.removeCallbacksAndMessages(null)
        presentationThread?.quit()
        presentationThread = null
        presentationHandler = null
        synchronized(queueLock) {
            while (true) { frameQueue.poll()?.bitmap?.recycle() ?: break }
        }
    }

    fun enqueue(bitmap: Bitmap, presentationTimeUs: Long) {
        if (!running.get()) return
        val cloned = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        val frame = PresentationFrame(cloned, presentationTimeUs)
        val dropped: PresentationFrame?
        synchronized(queueLock) {
            dropped = if (frameQueue.size >= maxQueueSize) frameQueue.poll() else null
            frameQueue.offer(frame)
        }
        dropped?.bitmap?.recycle()
    }

    private fun tryPresentNextFrame(): Boolean {
        val frame: PresentationFrame
        val now = clock()
        synchronized(queueLock) {
            frame = frameQueue.peek() ?: return false
            if (baseWallTimeMs.get() < 0) {
                baseWallTimeMs.set(now + bufferDelayMs)
                basePresentationTimeUs.set(frame.presentationTimeUs)
            }
            val elapsed = now - baseWallTimeMs.get()
            val targetMs = (frame.presentationTimeUs - basePresentationTimeUs.get()) / 1000
            val drift = elapsed - targetMs
            if (kotlin.math.abs(drift) > 2000L) {
                baseWallTimeMs.set(now + bufferDelayMs)
                basePresentationTimeUs.set(frame.presentationTimeUs)
                return false
            }
            if (elapsed < targetMs) return false
            frameQueue.poll()
        }
        try { onFrameReady(frame) } catch (e: Exception) { Log.e("PresentationQueue", "Error", e) }
        return true
    }
}
