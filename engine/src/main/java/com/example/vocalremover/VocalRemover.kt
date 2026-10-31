package com.example.vocalremover

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

data class StereoPcm(
    val left: FloatArray,
    val right: FloatArray
) {
    init {
        require(left.size == right.size) { "Stereo channels must have the same length" }
    }

    val size: Int
        get() = left.size
}

class VocalRemover(
    context: Context,
    private val backend: ExecutionBackend = ExecutionBackend.fromId(BuildConfig.EXECUTION_BACKEND)
) {

    private class Loaded(val session: OrtSession, val inputName: String, val outputName: String)

    companion object {
        private const val TAG = "VocalRemover"
        private const val MODEL_ASSET = "vocal_remover_fp32.onnx"
        /** Prefix of the cached copy; the suffix is the install key. */
        private const val MODEL_CACHE_PREFIX = "vocal_remover_fp32"
        private const val N_FFT = 5120
        private const val HOP_LENGTH = 1024
        private const val DIM_F = 2560
        private const val DIM_T = 256
        private const val OVERLAP = 0.25
        private const val NORMALIZATION_PEAK = 0.9f

        /** Block side for cache-friendly transposes: 32×32 floats = 4 KB, fits in L1. */
        private const val TRANSPOSE_BLOCK = 32

        /**
         * Atomic copy: writes to a temporary file and renames it only once the copy is complete,
         * so a process killed halfway does not leave a truncated model in place of the good one.
         */
        fun copyAssetStreamToFile(input: InputStream, outputFile: File) {
            val tmpFile = File(outputFile.path + ".tmp")
            try {
                tmpFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
                if (!tmpFile.renameTo(outputFile)) {
                    throw IOException("Cannot rename ${tmpFile.name} to ${outputFile.name}")
                }
            } finally {
                tmpFile.delete()
            }
        }

        /** Absolute peak over two channels in a single pass, without per-sample function calls. */
        private fun peakOf(l: FloatArray, r: FloatArray): Float {
            var peak = 0f
            for (i in l.indices) {
                val lv = l[i]; val rv = r[i]
                val al = if (lv < 0f) -lv else lv
                val ar = if (rv < 0f) -rv else rv
                if (al > peak) peak = al
                if (ar > peak) peak = ar
            }
            return peak
        }
    }

    private val mdxStft = MdxStftProcessor(nFft = N_FFT, hopLength = HOP_LENGTH, dimF = DIM_F)
    private val chunker = MdxChunker(nFft = N_FFT, hopLength = HOP_LENGTH, dimT = DIM_T, overlap = OVERLAP)
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val appContext: Context = context.applicationContext
    private val loadLock = Any()
    private val loadedRef = AtomicReference<Loaded?>(null)
    @Volatile private var closed = false

    /**
     * Session created on first use, off the UI thread: with QNN the first compilation
     * of the HTP graph takes tens of seconds and would block onCreate (ANR).
     */
    private val loaded: Loaded
        get() = loadedRef.get() ?: synchronized(loadLock) {
            loadedRef.get() ?: run {
                check(!closed) { "VocalRemover already closed" }
                val session = createSession(appContext)
                val created = Loaded(
                    session,
                    session.inputNames.iterator().next(),
                    session.outputNames.iterator().next()
                )
                loadedRef.set(created)
                // Concurrent close() during compilation: whoever comes second closes it,
                // but never under a run already started (another thread that read loadedRef).
                synchronized(runLock) {
                    if (closed) {
                        if (runsInFlight == 0) loadedRef.getAndSet(null)?.session?.close()
                        error("VocalRemover closed while creating the session")
                    }
                }
                created
            }
        }

    /**
     * Coordinates session.run and close(): close() does not free the native session under
     * a running inference (native crash), but lets the last run that finishes close it.
     * This way close() does not block the UI thread for the length of a chunk (seconds on CPU).
     */
    private val runLock = Any()
    private val inferenceMutex = Mutex()
    private var runsInFlight = 0  // guarded by runLock

    private inline fun <T> withSession(block: (Loaded) -> T): T {
        val model = loaded
        synchronized(runLock) {
            check(!closed && loadedRef.get() === model) { "VocalRemover already closed" }
            runsInFlight++
        }
        try {
            return block(model)
        } finally {
            synchronized(runLock) {
                runsInFlight--
                if (closed && runsInFlight == 0) loadedRef.getAndSet(null)?.session?.close()
            }
        }
    }

    /** Map reused across chunks to avoid one HashMap per iteration. */
    private val inputMap = HashMap<String, OnnxTensor>(1)
    private val outputMap = HashMap<String, OnnxTensor>(1)

    private class ModelWorkspace(env: OrtEnvironment, freqBins: Int, chunkSize: Int) : AutoCloseable {
        private val shape = longArrayOf(1L, 4L, DIM_F.toLong(), DIM_T.toLong())

        // ONNX I/O tensors on direct buffers created only once: ORT reads/writes directly
        // in this memory, without copying 10 MB (and creating garbage) on every chunk.
        val inputData    = FloatArray(4 * DIM_F * DIM_T)
        val inputBuffer: FloatBuffer = directFloatBuffer(inputData.size)
        val inputTensor: OnnxTensor = OnnxTensor.createTensor(env, inputBuffer, shape)
        val outputData   = FloatArray(4 * DIM_F * DIM_T)
        val outputBuffer: FloatBuffer = directFloatBuffer(outputData.size)
        val outputTensor: OnnxTensor = OnnxTensor.createTensor(env, outputBuffer, shape)

        // Forward STFT output for L and R (both must be kept before writing to inputData)
        val fwdLRe = FloatArray(DIM_T * DIM_F)
        val fwdLIm = FloatArray(DIM_T * DIM_F)
        val fwdRRe = FloatArray(DIM_T * DIM_F)
        val fwdRIm = FloatArray(DIM_T * DIM_F)

        // Spectra filled by ONNX → read by inverseStereoInto
        val spectrumLRe = FloatArray(DIM_T * freqBins)
        val spectrumLIm = FloatArray(DIM_T * freqBins)
        val spectrumRRe = FloatArray(DIM_T * freqBins)
        val spectrumRIm = FloatArray(DIM_T * freqBins)

        // Reused ISTFT outputs: NEVER allocate again in the loop
        val chunkOutL = FloatArray(chunkSize)
        val chunkOutR = FloatArray(chunkSize)

        override fun close() {
            inputTensor.close()
            outputTensor.close()
        }

        private fun directFloatBuffer(size: Int): FloatBuffer =
            ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    /** Prepares the session ahead of time; must be called from a background thread. */
    fun warmUp() {
        loaded
    }

    private fun installKey(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

    private fun createSession(context: Context): OrtSession {
        // Install key in the name: an app update that changes the model
        // does not reuse the old copy. Copies (and .tmp files) from previous installs are deleted.
        val cacheDir = context.cacheDir
        val modelFile = File(cacheDir, "${MODEL_CACHE_PREFIX}_${installKey(context)}.onnx")
        cacheDir.listFiles { f -> f.name.startsWith(MODEL_CACHE_PREFIX) && f != modelFile }
            ?.forEach { it.delete() }
        if (!modelFile.exists()) {
            Log.i(TAG, "Copying the ONNX model to cache: ${modelFile.absolutePath}")
            context.assets.open(MODEL_ASSET).use { input ->
                copyAssetStreamToFile(input, modelFile)
            }
        }
        Log.i(
            TAG,
            "Execution backend=${backend.id}, modelCache=${modelFile.absolutePath}, modelBytes=${modelFile.length()}"
        )
        val options = OrtSession.SessionOptions().apply {
            when (backend) {
                // ORT default: XNNPACK measured slower on Realme (7.2–8.0 s/chunk vs
                // 5.8–6.2 s) and crashing natively inside session.run on OPPO. Intra-op threads and
                // INT8 quantization measured with no useful gains (see copilot-instructions).
                ExecutionBackend.CPU -> Unit
                ExecutionBackend.WEBGPU -> addWebGPU(mapOf(
                    "device_id" to "0",
                    "preferred_layout" to "NHWC",
                    "power_preference" to "high-performance"
                ))
                ExecutionBackend.QNN -> {
                    val libDir = context.applicationInfo.nativeLibraryDir
                    // INFO: ORT logs how many graph nodes are assigned to QNN.
                    setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO)
                    QnnConfig.freeDimensionOverrides.forEach { (name, value) ->
                        setSymbolicDimensionValue(name, value)
                    }
                    addQnn(QnnConfig.providerOptions(libDir))
                }
            }
            Log.d(TAG, "Available providers: ${OrtEnvironment.getAvailableProviders()}")
        }
        val sessionStart = System.currentTimeMillis()
        val session = if (backend == ExecutionBackend.QNN) {
            createQnnSession(context, modelFile, options)
        } else {
            env.createSession(modelFile.absolutePath, options)
        }
        Log.i(TAG, "ONNX session created in ${System.currentTimeMillis() - sessionStart} ms")
        return session
    }

    /** Loads the compiled HTP graph if present, otherwise compiles and saves it. */
    private fun createQnnSession(context: Context, modelFile: File, options: OrtSession.SessionOptions): OrtSession {
        val dir = context.filesDir
       // @Suppress("DEPRECATION")
        val ctxFile = QnnConfig.contextCacheFile(dir, installKey(context))
        QnnConfig.staleContextCaches(dir.list()?.toList().orEmpty(), ctxFile.name)
            .forEach { File(dir, it).delete() }
        if (ctxFile.exists()) {
            try {
                Log.i(TAG, "QNN: loading the compiled context ${ctxFile.name}")
                return env.createSession(ctxFile.absolutePath, options)
            } catch (e: OrtException) {
                Log.w(TAG, "QNN: invalid context, recompiling", e)
                ctxFile.delete()
            }
        }
        Log.i(TAG, "QNN: compiling the HTP graph (first launch only, may take minutes)")
        QnnConfig.contextGenerationConfig(ctxFile).forEach { (k, v) -> options.addConfigEntry(k, v) }
        return env.createSession(modelFile.absolutePath, options)
    }

    /**
     * One processing run per instance: chunker, STFT and tensor maps share working
     * buffers. Concurrent calls (e.g. file import + capture) are queued; the wait
     * is cancellable. Per-call buffers would double the memory for no gain: the model
     * already saturates the CPU/NPU.
     */
    suspend fun removeVocals(
        signal: StereoPcm,
        onProgress: (Int) -> Unit = {}
    ): StereoPcm = inferenceMutex.withLock { removeVocalsLocked(signal, onProgress) }

    private suspend fun removeVocalsLocked(
        signal: StereoPcm,
        onProgress: (Int) -> Unit
    ): StereoPcm {
        var start = 0L
        Log.i(TAG, "Vocal removal started: frames=${signal.size}")
        onProgress(2)

        // 1. Peak normalization — a single pass, no per-sample max()/abs().
        val peak = peakOf(signal.left, signal.right)
        val normalized = peak > NORMALIZATION_PEAK

        val procLeft: FloatArray
        val procRight: FloatArray
        if (normalized) {
            val scale = NORMALIZATION_PEAK / peak
            val n = signal.size
            val l = FloatArray(n)
            val r = FloatArray(n)
            val srcL = signal.left
            val srcR = signal.right
            for (i in 0 until n) {
                l[i] = srcL[i] * scale
                r[i] = srcR[i] * scale
            }
            procLeft = l
            procRight = r
        } else {
            procLeft = signal.left
            procRight = signal.right
        }

        // 2. Chunking + inference
        val ctx = coroutineContext
        val chunkSize = HOP_LENGTH * (DIM_T - 1)
        val workspace = ModelWorkspace(env, mdxStft.freqBins, chunkSize)
        var chunksDone = 0
        val estimatedChunks = chunker.chunkCount(signal.size)
        Log.i(TAG, "MDX chunking: estimatedChunks=$estimatedChunks chunkFrames=${HOP_LENGTH * (DIM_T - 1)}")

        val instrumentalLeft = FloatArray(signal.size)
        val instrumentalRight = FloatArray(signal.size)
        val totalStart = System.currentTimeMillis()
        workspace.use {
            chunker.processStereoInto(procLeft, procRight, instrumentalLeft, instrumentalRight) { chunkL, chunkR ->
                start = System.currentTimeMillis()
                ctx.ensureActive()
                val result = runModelOnChunk(chunkL, chunkR, workspace, logSanity = chunksDone == 0)
                chunksDone++
                if (chunksDone == 1 || chunksDone % 10 == 0 || chunksDone == estimatedChunks) {
                    Log.i(TAG, "Vocal removal progress: chunk=$chunksDone/$estimatedChunks")
                }
                onProgress(2 + minOf(93, (chunksDone * 93) / estimatedChunks))
                Log.i(TAG, "Chunk processing time: ${System.currentTimeMillis() - start} ms")
                result
            }
        }
        Log.i(TAG, "Total inference: ${System.currentTimeMillis() - totalStart} ms for $chunksDone chunks")

        if (normalized) {
            for (i in instrumentalLeft.indices) {
                instrumentalLeft[i] *= peak
                instrumentalRight[i] *= peak
            }
        }

        // 4. Safety normalization on output (a single pass)
        val outputPeak = peakOf(instrumentalLeft, instrumentalRight)
        if (outputPeak > NORMALIZATION_PEAK) {
            val outScale = NORMALIZATION_PEAK / outputPeak
            for (i in instrumentalLeft.indices) {
                instrumentalLeft[i] *= outScale
                instrumentalRight[i] *= outScale
            }
        }

        onProgress(100)
        Log.i(TAG, "Vocal removal completed: frames=${signal.size} outputPeak=$outputPeak")
        return StereoPcm(instrumentalLeft, instrumentalRight)
    }

    /** Runs STFT -> ONNX -> ISTFT on a single stereo chunk. */
    private fun runModelOnChunk(
        chunkL: FloatArray,
        chunkR: FloatArray,
        workspace: ModelWorkspace,
        logSanity: Boolean
    ): Pair<FloatArray, FloatArray> {
        // --- Forward STFT (L and R in a single complex FFT per frame) ---
        val t0 = System.nanoTime()
        mdxStft.forwardStereoInto(
            chunkL, chunkR,
            workspace.fwdLRe, workspace.fwdLIm, workspace.fwdRRe, workspace.fwdRIm
        )
        val t1 = System.nanoTime()

        zeroFirstBins(workspace.fwdLRe, workspace.fwdLIm, DIM_F, 3)
        zeroFirstBins(workspace.fwdRRe, workspace.fwdRIm, DIM_F, 3)

        writeChannel(workspace.inputData, 0, workspace.fwdLRe)
        writeChannel(workspace.inputData, 1, workspace.fwdLIm)
        writeChannel(workspace.inputData, 2, workspace.fwdRRe)
        writeChannel(workspace.inputData, 3, workspace.fwdRIm)
        workspace.inputBuffer.rewind()
        workspace.inputBuffer.put(workspace.inputData)
        workspace.inputBuffer.rewind()
        val t2 = System.nanoTime()   // input tensor ready for ONNX

        withSession { model ->
            inputMap[model.inputName] = workspace.inputTensor
            outputMap[model.outputName] = workspace.outputTensor
            // "Pinned" output: ORT writes directly into workspace.outputBuffer.
            model.session.run(inputMap, outputMap).use { }
        }
        val tRun = System.nanoTime()

        workspace.outputBuffer.rewind()
        workspace.outputBuffer.get(workspace.outputData)
        if (logSanity) {
            var maxAbs = 0f
            var nanCount = 0
            for (v in workspace.outputData) {
                if (v.isNaN() || v.isInfinite()) nanCount++
                else if (kotlin.math.abs(v) > maxAbs) maxAbs = kotlin.math.abs(v)
            }
            Log.i(TAG, "ONNX sanity (first chunk): maxAbs=$maxAbs NaN=$nanCount")
        }
        val tRead = System.nanoTime()

        readChannelToFullSpectrum(workspace.outputData, 0, workspace.spectrumLRe)
        readChannelToFullSpectrum(workspace.outputData, 1, workspace.spectrumLIm)
        readChannelToFullSpectrum(workspace.outputData, 2, workspace.spectrumRRe)
        readChannelToFullSpectrum(workspace.outputData, 3, workspace.spectrumRIm)
        mdxStft.inverseStereoInto(
            workspace.spectrumLRe, workspace.spectrumLIm,
            workspace.spectrumRRe, workspace.spectrumRIm,
            DIM_T, chunkL.size, workspace.chunkOutL, workspace.chunkOutR
        )
        val tEnd = System.nanoTime()

        Log.i(TAG, "Stages: " +
                "STFT=${(t1-t0)/1_000_000}ms " +
                "write=${(t2-t1)/1_000_000}ms " +
                "ONNX.run=${(tRun-t2)/1_000_000}ms " +
                "readOutput=${(tRead-tRun)/1_000_000}ms " +
                "ISTFT=${(tEnd-tRead)/1_000_000}ms")

        // WARNING: we return the same buffers on every call.
        // Safe ONLY because MdxChunker consumes them (overlap-add) before calling us again.
        return Pair(workspace.chunkOutL, workspace.chunkOutR)
    }

    private fun zeroFirstBins(re: FloatArray, im: FloatArray, dimF: Int, count: Int) {
        val frames = re.size / dimF
        for (bin in 0 until count) {
            var idx = bin
            var f = 0
            while (f < frames) {
                re[idx] = 0f
                im[idx] = 0f
                idx += dimF
                f++
            }
        }
    }

    /**
     * Writes one channel [DIM_T, DIM_F] (row-major, frame-major) into the ONNX layout
     * [channel][DIM_F][DIM_T] using a blocked transpose.
     *
     * Source read:  data[frame * DIM_F + bin]  (contiguous by bin within a frame)
     * Dest write: dest[channelOffset + bin * DIM_T + frame]  (contiguous by frame within a bin)
     */
    private fun writeChannel(dest: FloatArray, channel: Int, data: FloatArray) {
        val channelOffset = channel * DIM_F * DIM_T
        val rows = DIM_T          // frames
        val cols = DIM_F          // bins
        val block = TRANSPOSE_BLOCK

        var i0 = 0
        while (i0 < rows) {
            val iMax = if (i0 + block < rows) i0 + block else rows
            var j0 = 0
            while (j0 < cols) {
                val jMax = if (j0 + block < cols) j0 + block else cols
                var j = j0
                while (j < jMax) {
                    val dstBase = channelOffset + j * rows
                    var i = i0
                    while (i < iMax) {
                        dest[dstBase + i] = data[i * cols + j]
                        i++
                    }
                    j++
                }
                j0 += block
            }
            i0 += block
        }
    }

    /**
     * Blocked transpose from the ONNX layout [channel][DIM_F][DIM_T] to the STFT layout
     * [frame * freqBins + bin]. The padding bin (index DIM_F) is zeroed per frame.
     */
    private fun readChannelToFullSpectrum(src: FloatArray, channel: Int, dest: FloatArray) {
        val channelOffset = channel * DIM_F * DIM_T
        val rows = DIM_F          // bin
        val cols = DIM_T          // frame
        val dstStride = mdxStft.freqBins
        val block = TRANSPOSE_BLOCK

        var i0 = 0
        while (i0 < rows) {
            val iMax = if (i0 + block < rows) i0 + block else rows
            var j0 = 0
            while (j0 < cols) {
                val jMax = if (j0 + block < cols) j0 + block else cols
                var j = j0
                while (j < jMax) {
                    val dstBase = j * dstStride
                    var i = i0
                    while (i < iMax) {
                        dest[dstBase + i] = src[channelOffset + i * cols + j]
                        i++
                    }
                    j++
                }
                j0 += block
            }
            i0 += block
        }

        // Padding bin DIM_F: a single value per frame.
        var frameBase = DIM_F
        var frame = 0
        while (frame < cols) {
            dest[frameBase] = 0f
            frameBase += dstStride
            frame++
        }
    }

    fun close() {
        synchronized(runLock) {
            closed = true
            // With a run in progress, closing is deferred to withSession.
            if (runsInFlight == 0) loadedRef.getAndSet(null)?.session?.close()
        }
    }
}