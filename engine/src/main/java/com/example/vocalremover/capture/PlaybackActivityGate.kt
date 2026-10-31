package com.example.vocalremover.capture

import kotlin.math.sqrt

/**
 * Decides which portions of the captured PCM are written to the file, based on
 * whether audio comes from the source app. No Android dependencies: works on
 * 16-bit little-endian interleaved PCM and hands the bytes to write to a `sink`.
 *
 * - [State.LISTENING]: the capture is active but the app is not playing yet.
 *   Nothing is written; only the last `preRollMs` of audio are kept so as not to
 *   cut the start of the track. If no audio arrives for `listenTimeoutMs` it moves to
 *   [State.TIMED_OUT] (the source was not started or it blocks capture: with
 *   `AudioPlaybackCaptureConfiguration` both cases produce the same silence).
 * - [State.RECORDING]: the first non-silent block starts the recording. Silence
 *   is held back until audio returns, so short pauses stay
 *   in the track and trailing ones are discarded.
 * - [State.FINISHED]: `trailingSilenceMs` of continuous silence close the recording.
 *
 * Analysis runs on fixed windows independent of the size of the blocks read,
 * so the result does not depend on how `AudioRecord` splits the stream.
 */
class PlaybackActivityGate(
    sampleRate: Int,
    channelCount: Int,
    listenTimeoutMs: Int = DEFAULT_LISTEN_TIMEOUT_MS,
    trailingSilenceMs: Int = DEFAULT_TRAILING_SILENCE_MS,
    preRollMs: Int = DEFAULT_PRE_ROLL_MS,
    private val silenceThreshold: Float = RmsSilenceDetector.DEFAULT_THRESHOLD
) {

    companion object {
        const val DEFAULT_LISTEN_TIMEOUT_MS = 60_000
        const val DEFAULT_TRAILING_SILENCE_MS = 4_000
        const val DEFAULT_PRE_ROLL_MS = 300
        private const val WINDOW_MS = 20
        private const val BYTES_PER_SAMPLE = 2
    }

    enum class State { LISTENING, RECORDING, FINISHED, TIMED_OUT }

    var state: State = State.LISTENING
        private set

    /** True from the first non-silent block on, even if the recording has already finished. */
    val audioDetected: Boolean
        get() = state == State.RECORDING || state == State.FINISHED

    /**
     * Index, in frames counted from the first byte passed to [process], of the first frame handed to the
     * `sink` (pre-roll included). Null while listening. Used to timestamp the start of the file.
     */
    var firstWrittenFrame: Long? = null
        private set

    private val windowBytes =
        sampleRate * WINDOW_MS / 1_000 * channelCount * BYTES_PER_SAMPLE
    private val listenTimeoutWindows = listenTimeoutMs / WINDOW_MS
    private val trailingSilenceWindows = trailingSilenceMs / WINDOW_MS
    private val preRollWindows = preRollMs / WINDOW_MS

    private val windowFrames = sampleRate * WINDOW_MS / 1_000
    private var windowsAnalyzed = 0L

    private val window = ByteArray(windowBytes)
    private var windowFill = 0
    private var silentWindowsHeard = 0
    private val preRoll = ArrayDeque<ByteArray>()
    private val pendingSilence = ArrayDeque<ByteArray>()

    fun process(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        sink: (ByteArray, Int, Int) -> Unit
    ): State {
        var position = offset
        val end = offset + length
        while (position < end && !isTerminal()) {
            val copied = minOf(windowBytes - windowFill, end - position)
            System.arraycopy(buffer, position, window, windowFill, copied)
            windowFill += copied
            position += copied
            if (windowFill == windowBytes) {
                onWindow(window.copyOf(), sink)
                windowFill = 0
            }
        }
        return state
    }

    /**
     * To be called once the capture is over (manual or automatic stop): writes any
     * partial window if it contains audio and discards the held trailing silence.
     */
    fun finish(sink: (ByteArray, Int, Int) -> Unit) {
        if (state == State.RECORDING && windowFill > 0) {
            val partial = window.copyOf(windowFill)
            if (!isSilent(partial, windowFill)) {
                flushPendingSilence(sink)
                sink(partial, 0, partial.size)
            }
        }
        windowFill = 0
        pendingSilence.clear()
        preRoll.clear()
    }

    private fun onWindow(current: ByteArray, sink: (ByteArray, Int, Int) -> Unit) {
        val silent = isSilent(current, current.size)
        when (state) {
            State.LISTENING -> if (silent) {
                if (preRollWindows > 0) {
                    preRoll.addLast(current)
                    if (preRoll.size > preRollWindows) preRoll.removeFirst()
                }
                silentWindowsHeard++
                if (silentWindowsHeard >= listenTimeoutWindows) {
                    preRoll.clear()
                    state = State.TIMED_OUT
                }
            } else {
                firstWrittenFrame = (windowsAnalyzed - preRoll.size) * windowFrames
                preRoll.forEach { sink(it, 0, it.size) }
                preRoll.clear()
                sink(current, 0, current.size)
                state = State.RECORDING
            }

            State.RECORDING -> if (silent) {
                pendingSilence.addLast(current)
                if (pendingSilence.size >= trailingSilenceWindows) {
                    pendingSilence.clear()
                    state = State.FINISHED
                }
            } else {
                flushPendingSilence(sink)
                sink(current, 0, current.size)
            }

            State.FINISHED, State.TIMED_OUT -> Unit
        }
        windowsAnalyzed++
    }

    private fun flushPendingSilence(sink: (ByteArray, Int, Int) -> Unit) {
        pendingSilence.forEach { sink(it, 0, it.size) }
        pendingSilence.clear()
    }

    private fun isTerminal(): Boolean = state == State.FINISHED || state == State.TIMED_OUT

    /** RMS over 16-bit samples normalized to [-1, 1), like [RmsSilenceDetector]. */
    private fun isSilent(bytes: ByteArray, length: Int): Boolean {
        val sampleCount = length / BYTES_PER_SAMPLE
        if (sampleCount == 0) return true
        var sumSquares = 0.0
        for (index in 0 until sampleCount) {
            val byteIndex = index * BYTES_PER_SAMPLE
            val value = ((bytes[byteIndex + 1].toInt() shl 8) or
                (bytes[byteIndex].toInt() and 0xFF)).toShort() / 32_768.0
            sumSquares += value * value
        }
        return sqrt(sumSquares / sampleCount) < silenceThreshold
    }
}
