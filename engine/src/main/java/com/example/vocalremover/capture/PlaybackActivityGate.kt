package com.example.vocalremover.capture

import kotlin.math.sqrt

/**
 * Decide quali porzioni del PCM catturato vanno scritte nel file, in base alla
 * presenza di audio dall'app sorgente. Nessuna dipendenza Android: lavora su PCM
 * 16 bit little-endian interleaved e consegna i byte da scrivere a un `sink`.
 *
 * - [State.LISTENING]: la cattura è attiva ma l'app non sta ancora riproducendo.
 *   Non si scrive nulla; si conservano solo gli ultimi `preRollMs` di audio per non
 *   tagliare l'attacco del brano. Se per `listenTimeoutMs` non arriva audio si passa a
 *   [State.TIMED_OUT] (la sorgente non è stata avviata oppure blocca la cattura: con
 *   `AudioPlaybackCaptureConfiguration` i due casi producono lo stesso silenzio).
 * - [State.RECORDING]: il primo blocco non silenzioso avvia la registrazione. Il
 *   silenzio viene trattenuto finché non torna l'audio, così le pause brevi restano
 *   nel brano e quelle finali vengono scartate.
 * - [State.FINISHED]: `trailingSilenceMs` di silenzio continuo chiudono la registrazione.
 *
 * L'analisi avviene su finestre fisse indipendenti dalla dimensione dei blocchi letti,
 * quindi il risultato non dipende da come `AudioRecord` spezza il flusso.
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

    /** True dal primo blocco non silenzioso in poi, anche se la registrazione è già finita. */
    val audioDetected: Boolean
        get() = state == State.RECORDING || state == State.FINISHED

    private val windowBytes =
        sampleRate * WINDOW_MS / 1_000 * channelCount * BYTES_PER_SAMPLE
    private val listenTimeoutWindows = listenTimeoutMs / WINDOW_MS
    private val trailingSilenceWindows = trailingSilenceMs / WINDOW_MS
    private val preRollWindows = preRollMs / WINDOW_MS

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
     * Da chiamare a cattura terminata (stop manuale o automatico): scrive l'eventuale
     * finestra parziale se contiene audio e scarta il silenzio finale trattenuto.
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
    }

    private fun flushPendingSilence(sink: (ByteArray, Int, Int) -> Unit) {
        pendingSilence.forEach { sink(it, 0, it.size) }
        pendingSilence.clear()
    }

    private fun isTerminal(): Boolean = state == State.FINISHED || state == State.TIMED_OUT

    /** RMS sui campioni 16 bit normalizzati in [-1, 1), come [RmsSilenceDetector]. */
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
