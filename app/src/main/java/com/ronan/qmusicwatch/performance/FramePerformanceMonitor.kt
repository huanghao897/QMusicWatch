package com.ronan.qmusicwatch.performance

import android.view.Choreographer
import com.ronan.qmusicwatch.data.AppLog

object FramePerformanceMonitor : Choreographer.FrameCallback {
    @Volatile var section: String = "startup"
    private var running = false
    private var lastFrameNanos = 0L
    private var frames = 0
    private var slowFrames = 0
    private var frozenFrames = 0

    fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        flush()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (lastFrameNanos != 0L) {
            val frameMs = (frameTimeNanos - lastFrameNanos) / 1_000_000
            frames++
            if (frameMs > 32) slowFrames++
            if (frameMs > 700) frozenFrames++
            if (frames >= 120) flush()
        }
        lastFrameNanos = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun flush() {
        if (frames > 0) AppLog.write("PERF", "section=$section frames=$frames slow=$slowFrames frozen=$frozenFrames")
        frames = 0; slowFrames = 0; frozenFrames = 0
    }
}
