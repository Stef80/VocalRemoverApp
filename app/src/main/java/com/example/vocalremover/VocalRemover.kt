package com.example.vocalremover

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.ensureActive
import java.io.File
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
        require(left.size == right.size) { "I canali stereo devono avere la stessa lunghezza" }
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
        private const val MODEL_CACHE_FILE = "vocal_remover_fp32.onnx"
        private const val N_FFT = 5120
        private const val HOP_LENGTH = 1024
        private const val DIM_F = 2560
        private const val DIM_T = 256
        private const val OVERLAP = 0.25
        private const val NORMALIZATION_PEAK = 0.9f

        /** Lato blocco per trasposizioni cache-friendly: 32×32 float = 4 KB, sta in L1. */
        private const val TRANSPOSE_BLOCK = 32

        fun copyAssetStreamToFile(input: InputStream, outputFile: File) {
            outputFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }

        /** Picco assoluto su due canali in un'unica passata, senza chiamate di funzione per campione. */
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
     * Sessione creata al primo uso, fuori dal thread UI: con QNN la prima compilazione
     * del grafo HTP richiede decine di secondi e bloccherebbe onCreate (ANR).
     */
    private val loaded: Loaded
        get() = loadedRef.get() ?: synchronized(loadLock) {
            loadedRef.get() ?: run {
                check(!closed) { "VocalRemover già chiuso" }
                val session = createSession(appContext)
                val created = Loaded(
                    session,
                    session.inputNames.iterator().next(),
                    session.outputNames.iterator().next()
                )
                loadedRef.set(created)
                // close() concorrente durante la compilazione: chiude chi arriva per secondo.
                if (closed) {
                    loadedRef.getAndSet(null)?.session?.close()
                    error("VocalRemover chiuso durante la creazione della sessione")
                }
                created
            }
        }

    /** Mappa riutilizzata tra chunk per evitare una HashMap per iterazione. */
    private val inputMap = HashMap<String, OnnxTensor>(1)
    private val outputMap = HashMap<String, OnnxTensor>(1)

    private class ModelWorkspace(env: OrtEnvironment, freqBins: Int, chunkSize: Int) : AutoCloseable {
        private val shape = longArrayOf(1L, 4L, DIM_F.toLong(), DIM_T.toLong())

        // Tensori I/O ONNX su buffer diretti creati una volta sola: ORT legge/scrive direttamente
        // in questa memoria, senza copiare 10 MB (e generare garbage) a ogni chunk.
        val inputData    = FloatArray(4 * DIM_F * DIM_T)
        val inputBuffer: FloatBuffer = directFloatBuffer(inputData.size)
        val inputTensor: OnnxTensor = OnnxTensor.createTensor(env, inputBuffer, shape)
        val outputData   = FloatArray(4 * DIM_F * DIM_T)
        val outputBuffer: FloatBuffer = directFloatBuffer(outputData.size)
        val outputTensor: OnnxTensor = OnnxTensor.createTensor(env, outputBuffer, shape)

        // Uscita STFT forward per L e R (serve tenerle entrambe prima di scrivere su inputData)
        val fwdLRe = FloatArray(DIM_T * DIM_F)
        val fwdLIm = FloatArray(DIM_T * DIM_F)
        val fwdRRe = FloatArray(DIM_T * DIM_F)
        val fwdRIm = FloatArray(DIM_T * DIM_F)

        // Spettri riempiti da ONNX → letti da inverseStereoInto
        val spectrumLRe = FloatArray(DIM_T * freqBins)
        val spectrumLIm = FloatArray(DIM_T * freqBins)
        val spectrumRRe = FloatArray(DIM_T * freqBins)
        val spectrumRIm = FloatArray(DIM_T * freqBins)

        // Output ISTFT riutilizzati: NON allocare mai più nel loop
        val chunkOutL = FloatArray(chunkSize)
        val chunkOutR = FloatArray(chunkSize)

        override fun close() {
            inputTensor.close()
            outputTensor.close()
        }

        private fun directFloatBuffer(size: Int): FloatBuffer =
            ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    /** Prepara la sessione in anticipo; va chiamato da un thread in background. */
    fun warmUp() {
        loaded
    }

    private fun createSession(context: Context): OrtSession {
        val modelFile = File(context.cacheDir, MODEL_CACHE_FILE)
        if (!modelFile.exists()) {
            Log.i(TAG, "Copio il modello ONNX in cache: ${modelFile.absolutePath}")
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
                // Default ORT: XNNPACK misurato più lento su Realme (7,2–8,0 s/chunk contro
                // 5,8–6,2 s) e in crash nativo dentro session.run su OPPO.
                ExecutionBackend.CPU -> Unit
                ExecutionBackend.WEBGPU -> addWebGPU(mapOf(
                    "device_id" to "0",
                    "preferred_layout" to "NHWC",
                    "power_preference" to "high-performance"
                ))
                ExecutionBackend.QNN -> {
                    val libDir = context.applicationInfo.nativeLibraryDir
                    // INFO: ORT registra quanti nodi del grafo vengono assegnati a QNN.
                    setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO)
                    QnnConfig.freeDimensionOverrides.forEach { (name, value) ->
                        setSymbolicDimensionValue(name, value)
                    }
                    addQnn(QnnConfig.providerOptions(libDir))
                }
            }
            Log.d(TAG, "Provider disponibili: ${OrtEnvironment.getAvailableProviders()}")
        }
        val sessionStart = System.currentTimeMillis()
        val session = if (backend == ExecutionBackend.QNN) {
            createQnnSession(context, modelFile, options)
        } else {
            env.createSession(modelFile.absolutePath, options)
        }
        Log.i(TAG, "Sessione ONNX creata in ${System.currentTimeMillis() - sessionStart} ms")
        return session
    }

    /** Carica il grafo HTP compilato se presente, altrimenti compila e lo salva. */
    private fun createQnnSession(context: Context, modelFile: File, options: OrtSession.SessionOptions): OrtSession {
        val dir = context.filesDir
        @Suppress("DEPRECATION")
        val installKey = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val ctxFile = QnnConfig.contextCacheFile(dir, installKey)
        QnnConfig.staleContextCaches(dir.list()?.toList().orEmpty(), ctxFile.name)
            .forEach { File(dir, it).delete() }
        if (ctxFile.exists()) {
            try {
                Log.i(TAG, "QNN: carico il contesto compilato ${ctxFile.name}")
                return env.createSession(ctxFile.absolutePath, options)
            } catch (e: OrtException) {
                Log.w(TAG, "QNN: contesto non valido, ricompilo", e)
                ctxFile.delete()
            }
        }
        Log.i(TAG, "QNN: compilo il grafo HTP (solo al primo avvio, può richiedere minuti)")
        QnnConfig.contextGenerationConfig(ctxFile).forEach { (k, v) -> options.addConfigEntry(k, v) }
        return env.createSession(modelFile.absolutePath, options)
    }

    suspend fun removeVocals(
        signal: StereoPcm,
        onProgress: (Int) -> Unit = {}
    ): StereoPcm {
        var start = 0L
        Log.i(TAG, "Rimozione voce iniziata: frames=${signal.size}")
        onProgress(2)

        // 1. Normalizzazione di picco — una sola passata, niente max()/abs() per campione.
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

        // 2. Chunking + inferenza
        val ctx = coroutineContext
        val chunkSize = HOP_LENGTH * (DIM_T - 1)
        val workspace = ModelWorkspace(env, mdxStft.freqBins, chunkSize)
        var chunksDone = 0
        val estimatedChunks = chunker.chunkCount(signal.size)
        Log.i(TAG, "Chunking MDX: estimatedChunks=$estimatedChunks chunkFrames=${HOP_LENGTH * (DIM_T - 1)}")

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
                    Log.i(TAG, "Rimozione voce avanzamento: chunk=$chunksDone/$estimatedChunks")
                }
                onProgress(2 + minOf(93, (chunksDone * 93) / estimatedChunks))
                Log.i(TAG, "Chunk processing time: ${System.currentTimeMillis() - start} ms")
                result
            }
        }
        Log.i(TAG, "Inferenza totale: ${System.currentTimeMillis() - totalStart} ms per $chunksDone chunk")

        if (normalized) {
            for (i in instrumentalLeft.indices) {
                instrumentalLeft[i] *= peak
                instrumentalRight[i] *= peak
            }
        }

        // 4. Normalizzazione di sicurezza in output (una sola passata)
        val outputPeak = peakOf(instrumentalLeft, instrumentalRight)
        if (outputPeak > NORMALIZATION_PEAK) {
            val outScale = NORMALIZATION_PEAK / outputPeak
            for (i in instrumentalLeft.indices) {
                instrumentalLeft[i] *= outScale
                instrumentalRight[i] *= outScale
            }
        }

        onProgress(100)
        Log.i(TAG, "Rimozione voce completata: frames=${signal.size} outputPeak=$outputPeak")
        return StereoPcm(instrumentalLeft, instrumentalRight)
    }

    /** Esegue STFT -> ONNX -> ISTFT su un singolo chunk stereo. */
    private fun runModelOnChunk(
        chunkL: FloatArray,
        chunkR: FloatArray,
        workspace: ModelWorkspace,
        logSanity: Boolean
    ): Pair<FloatArray, FloatArray> {
        // --- STFT forward (L e R in un'unica FFT complessa per frame) ---
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
        val t2 = System.nanoTime()   // tensore di input pronto per ONNX

        val model = loaded
        inputMap[model.inputName] = workspace.inputTensor
        outputMap[model.outputName] = workspace.outputTensor
        // Output "pinned": ORT scrive direttamente in workspace.outputBuffer.
        model.session.run(inputMap, outputMap).use { }
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
            Log.i(TAG, "ONNX sanity (primo chunk): maxAbs=$maxAbs NaN=$nanCount")
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

        Log.i(TAG, "Fasi: " +
                "STFT=${(t1-t0)/1_000_000}ms " +
                "write=${(t2-t1)/1_000_000}ms " +
                "ONNX.run=${(tRun-t2)/1_000_000}ms " +
                "readOutput=${(tRead-tRun)/1_000_000}ms " +
                "ISTFT=${(tEnd-tRead)/1_000_000}ms")

        // ATTENZIONE: restituiamo gli stessi buffer a ogni chiamata.
        // Sicuro SOLO perché MdxChunker li consuma (overlap-add) prima di richiamarci.
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
     * Scrive un canale [DIM_T, DIM_F] (row-major frame-major) nel layout ONNX
     * [channel][DIM_F][DIM_T] usando trasposizione a blocchi.
     *
     * Lettura src:  data[frame * DIM_F + bin]  (contigua per bin all'interno di un frame)
     * Scrittura dst: dest[channelOffset + bin * DIM_T + frame]  (contigua per frame all'interno di un bin)
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
     * Trasposizione bloccata dal layout ONNX [channel][DIM_F][DIM_T] al layout STFT
     * [frame * freqBins + bin]. Il bin di padding (indice DIM_F) viene azzerato per frame.
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

        // Padding bin DIM_F: un solo valore per frame.
        var frameBase = DIM_F
        var frame = 0
        while (frame < cols) {
            dest[frameBase] = 0f
            frameBase += dstStride
            frame++
        }
    }

    fun close() {
        closed = true
        loadedRef.getAndSet(null)?.session?.close()
    }
}