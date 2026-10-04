package com.example.vocalremover

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.Filter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.vocalremover.capture.CaptureTerminalKind
import com.example.vocalremover.capture.CaptureTerminalResult
import com.example.vocalremover.capture.CaptureTerminalResultStore
import com.example.vocalremover.capture.CaptureTerminalState
import com.example.vocalremover.capture.CaptureProcessingLease
import com.example.vocalremover.capture.RecordingFileNaming
import com.example.vocalremover.capture.RecordingSaver
import com.example.vocalremover.capture.SystemAudioCaptureService
import com.example.vocalremover.capture.WavFileReader
import com.example.vocalremover.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val STATE_PENDING_CAPTURE_TARGET_PACKAGE =
            "pending_capture_target_package"
        private const val PREFS_CAPTURE_PACKAGE_PICKER = "capture_package_picker"
        private const val KEY_RECENT_CAPTURE_PACKAGES = "recent_capture_packages"
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }

    private enum class CaptureUiState {
        IDLE,
        AWAITING_CONSENT,
        RECORDING,
        STOPPING,
        PROCESSING
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var vocalRemover: VocalRemover
    private lateinit var audioPlayer: AudioPlayer

    private var isUserSeeking = false
    private var captureUiState = CaptureUiState.IDLE
    private var captureReceiverRegistered = false
    private var pendingCaptureTargetPackageName: String? = null
    private val captureWorkOwnerId = UUID.randomUUID().toString()
    private var processingCaptureResultId: String? = null
    private var processingCaptureOutputPath: String? = null
    private var captureProcessingJob: Job? = null
    private var discardCaptureWorkOnCancellation = false
    // Nome con cui salvare automaticamente il risultato del brano scelto da file;
    // null per i flussi di cattura, che hanno un proprio salvataggio.
    private var pendingAutoSaveName: String? = null

    // ── Launcher file picker ─────────────────────────────────────────────────
    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processAudio(it) }
    }

    // ── Launcher permessi ────────────────────────────────────────────────────
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pickAudioLauncher.launch("audio/*")
        else Toast.makeText(this, "Permesso storage necessario", Toast.LENGTH_SHORT).show()
    }

    private val requestRecordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestNotificationPermissionThenMediaProjection()
        } else {
            clearPendingCaptureTarget()
            setCaptureUiState(
                CaptureUiState.IDLE,
                "Permesso microfono necessario per registrare l'audio di sistema."
            )
            Toast.makeText(this, "Permesso microfono non concesso", Toast.LENGTH_LONG).show()
        }
    }

    private val requestPostNotificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestMediaProjectionConsent()
        } else {
            clearPendingCaptureTarget()
            setCaptureUiState(
                CaptureUiState.IDLE,
                "Permesso notifiche necessario per avviare la registrazione."
            )
            Toast.makeText(
                this,
                "Permesso notifiche non concesso: registrazione non avviata",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val targetUid = pendingCaptureTargetUid()
        if (result.resultCode != RESULT_OK || result.data == null || targetUid == null) {
            clearPendingCaptureTarget()
            setCaptureUiState(
                CaptureUiState.IDLE,
                "Consenso per la cattura audio non concesso."
            )
            return@registerForActivityResult
        }

        try {
            setCaptureUiState(
                CaptureUiState.RECORDING,
                "Registrazione in corso. Tocca “Ferma e elabora” al termine."
            )
            ContextCompat.startForegroundService(
                this,
                Intent(this, SystemAudioCaptureService::class.java).apply {
                    putExtra(SystemAudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(SystemAudioCaptureService.EXTRA_RESULT_DATA, result.data)
                    putExtra(SystemAudioCaptureService.EXTRA_TARGET_UID, targetUid)
                }
            )
            clearPendingCaptureTarget()
        } catch (error: Exception) {
            clearPendingCaptureTarget()
            showCaptureFailure(
                error.message ?: "Impossibile avviare il servizio di cattura."
            )
        }
    }

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            consumeTerminalCaptureResult()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vocalRemover = VocalRemover(this)
        lifecycleScope.launch(Dispatchers.Default) {
            runCatching { vocalRemover.warmUp() }
                .onFailure { Log.w(TAG, "Preparazione modello fallita", it) }
        }
        audioPlayer = AudioPlayer(this)

        setupUi()
        setupPlayerCallbacks()
        setPlayerEnabled(false)

        pendingCaptureTargetPackageName =
            savedInstanceState?.getString(STATE_PENDING_CAPTURE_TARGET_PACKAGE)
        pendingCaptureTargetPackageName?.let {
            binding.etCapturePackageName.setText(it)
            setCaptureUiState(
                CaptureUiState.AWAITING_CONSENT,
                "Riprendi la richiesta di consenso per la cattura audio."
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (!captureReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                captureReceiver,
                IntentFilter().apply {
                    addAction(SystemAudioCaptureService.ACTION_CAPTURE_BLOCKED)
                    addAction(SystemAudioCaptureService.ACTION_CAPTURE_STOPPED)
                    addAction(SystemAudioCaptureService.ACTION_CAPTURE_ERROR)
                    addAction(SystemAudioCaptureService.ACTION_CAPTURE_PROCESSING_AVAILABLE)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            captureReceiverRegistered = true
        }
        consumeTerminalCaptureResult()
    }

    override fun onStop() {
        audioPlayer.cancelProcessing()
        if (captureReceiverRegistered) {
            runCatching {
                unregisterReceiver(captureReceiver)
            }
            captureReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingCaptureTargetPackageName?.let {
            outState.putString(STATE_PENDING_CAPTURE_TARGET_PACKAGE, it)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        audioPlayer.cancelProcessing()
        val resultId = processingCaptureResultId
        val processingJob = captureProcessingJob
        val closeVocalRemoverAfterJob = processingJob?.isActive == true
        if (resultId != null) {
            if (closeVocalRemoverAfterJob) {
                discardCaptureWorkOnCancellation = isFinishing
                processingJob.cancel()
                processingJob.invokeOnCompletion { vocalRemover.close() }
            } else if (isFinishing) {
                discardCaptureWork(resultId, showFailure = false)
            } else {
                releaseCaptureWork(resultId)
            }
        }
        audioPlayer.release()
        if (!closeVocalRemoverAfterJob) vocalRemover.close()
        super.onDestroy()
    }

    // ── UI setup ─────────────────────────────────────────────────────────────
    private fun setupUi() {
        setupCapturePackageSuggestions()

        binding.btnPickFile.setOnClickListener { requestStoragePermissionAndPick() }
        binding.btnStartCapture.setOnClickListener { startCapture() }
        binding.btnStopCapture.setOnClickListener { stopCapture() }

        binding.btnPlayPause.setOnClickListener {
            if (audioPlayer.isPlaying) {
                audioPlayer.pause()
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            } else {
                audioPlayer.play()
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            }
        }

        binding.btnStop.setOnClickListener {
            audioPlayer.stop()
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            binding.seekBar.progress = 0
            binding.tvCurrentTime.text = formatMs(0)
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) { isUserSeeking = true }
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.tvCurrentTime.text = formatMs(progress)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                isUserSeeking = false
                audioPlayer.seekTo(sb.progress)
            }
        })

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setCaptureUiState(
                CaptureUiState.IDLE,
                "La cattura audio richiede Android 10 o versioni successive."
            )
        } else {
            setCaptureUiState(
                CaptureUiState.IDLE,
                captureInstructions()
            )
        }
    }

    private fun setupCapturePackageSuggestions() {
        val recentPackages = readRecentCapturePackages()
        val candidatePackages = readCaptureCandidatePackagesFromManifest()

        // Solo i pacchetti dichiarati in <queries> nel manifest sono risolvibili
        // da PackageManager su Android 11+; per gli altri getApplicationInfo
        // lancia NameNotFoundException anche se installati.
        val candidates = candidatePackages
            .mapNotNull { packageName ->
                val appInfo = try {
                    packageManager.getApplicationInfo(packageName, ApplicationInfo.FLAG_INSTALLED)
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
                appInfo?.takeIf { it.enabled }?.let { packageName to it }
            }
            .associate { (packageName, appInfo) ->
                val label = packageManager.getApplicationLabel(appInfo)?.toString()?.trim()
                val displayText = when {
                    label.isNullOrBlank() -> packageName
                    packageName == label -> packageName
                    else -> "$label ($packageName)"
                }
                packageName to displayText
            }

        val orderedSuggestions = recentPackages
            .filter { it in candidates }
            .plus(candidates.keys.filter { it !in recentPackages }.sortedBy { candidates[it]?.lowercase() ?: it.lowercase() })
            .distinct()
            .mapNotNull { candidates[it] }

        val autoCompleteView = binding.etCapturePackageName as AutoCompleteTextView
        val adapter = ContainsFilterAdapter(this, orderedSuggestions, autoCompleteView)
        autoCompleteView.threshold = 0
        autoCompleteView.setAdapter(adapter)
        autoCompleteView.setOnItemClickListener { _, _, position, _ ->
            val selectedText = adapter.getItem(position)?.toString() ?: return@setOnItemClickListener
            val packageName = selectedText.substringAfterLast("(").trimEnd(')')
            if (packageName.isNotBlank()) {
                autoCompleteView.setText(packageName)
                autoCompleteView.setSelection(packageName.length)
                rememberCapturePackage(packageName)
            }
        }
        autoCompleteView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showCapturePackageDropdown(autoCompleteView)
        }
        autoCompleteView.setOnClickListener { showCapturePackageDropdown(autoCompleteView) }

    }

    private fun showCapturePackageDropdown(autoCompleteView: AutoCompleteTextView) {
        if (!canShowCaptureDropdown(
                isAttachedToWindow = autoCompleteView.isAttachedToWindow,
                hasWindowToken = autoCompleteView.windowToken != null,
                isActivityFinishing = isFinishing,
                isActivityDestroyed = isDestroyed
            )
        ) {
            return
        }
        autoCompleteView.showDropDown()
    }

    /**
     * Legge i pacchetti dichiarati in <queries><package android:name="..."/></queries>
     * direttamente dal proprio AndroidManifest.xml compilato: è l'unica fonte
     * di verità, non esiste una lista duplicata in Kotlin. Se il parsing
     * dovesse fallire, ritorna vuoto e il picker resta senza suggerimenti
     * (l'utente può comunque scrivere il package a mano).
     */
    private fun readCaptureCandidatePackagesFromManifest(): List<String> {
        var parser: android.content.res.XmlResourceParser? = null
        return try {
            parser = assets.openXmlResourceParser("AndroidManifest.xml")
            val packages = mutableListOf<String>()
            var insideQueries = false
            var eventType = parser.eventType
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                        "queries" -> insideQueries = true
                        "package" -> if (insideQueries) {
                            parser.getAttributeValue(ANDROID_NAMESPACE, "name")?.let(packages::add)
                        }
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> if (parser.name == "queries") {
                        insideQueries = false
                    }
                }
                eventType = parser.next()
            }
            packages
        } catch (_: Exception) {
            emptyList()
        } finally {
            parser?.close()
        }
    }

    private fun readRecentCapturePackages(): List<String> {
        val prefs = getSharedPreferences(PREFS_CAPTURE_PACKAGE_PICKER, MODE_PRIVATE)
        return prefs.getString(KEY_RECENT_CAPTURE_PACKAGES, "")
            ?.split('|')
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: emptyList()
    }

    private fun rememberCapturePackage(packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) return

        val prefs = getSharedPreferences(PREFS_CAPTURE_PACKAGE_PICKER, MODE_PRIVATE)
        val recent = readRecentCapturePackages()
            .filter { it != normalized }
            .toMutableList()
        recent.add(0, normalized)
        prefs.edit().putString(KEY_RECENT_CAPTURE_PACKAGES, recent.take(10).joinToString("|")).apply()
    }

    private fun setupPlayerCallbacks() {
        audioPlayer.onProgress = { pct ->
            runOnUiThread { showProcessingProgress(pct) }
        }

        audioPlayer.onPlaybackPositionChanged = { currentMs, totalMs ->
            if (!isUserSeeking) {
                binding.seekBar.max = totalMs
                binding.seekBar.progress = currentMs
                binding.tvCurrentTime.text = formatMs(currentMs)
                binding.tvTotalTime.text = formatMs(totalMs)
            }
        }

        audioPlayer.onReady = {
            binding.progressBar.visibility = android.view.View.GONE
            binding.seekBar.max = audioPlayer.durationMs
            binding.tvTotalTime.text = formatMs(audioPlayer.durationMs)
            setPlayerEnabled(true)
            Toast.makeText(this, "Elaborazione completata!", Toast.LENGTH_SHORT).show()
            val autoSaveName = pendingAutoSaveName
            pendingAutoSaveName = null
            val processed = audioPlayer.processedStereo
            if (autoSaveName != null && processed != null) {
                autoSaveProcessedFile(processed, autoSaveName)
            }
        }

        audioPlayer.onError = { msg ->
            pendingAutoSaveName = null
            binding.tvStatus.text = "Errore: $msg"
            binding.progressBar.visibility = android.view.View.GONE
            Toast.makeText(this, "Errore: $msg", Toast.LENGTH_LONG).show()
        }
    }

    // ── Elaborazione ─────────────────────────────────────────────────────────
    private fun processAudio(uri: Uri) {
        Log.i(TAG, "Richiesta caricamento brano: uri=$uri")
        setPlayerEnabled(false)
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvStatus.text = "Avvio elaborazione..."

        // Mostra nome file
        var sourceName: String? = null
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)
                sourceName = name
                binding.tvFileName.text = name
                Log.i(TAG, "Brano selezionato: name=$name")
            }
        }
        pendingAutoSaveName = RecordingFileNaming.instrumentalName(sourceName)

        lifecycleScope.launch {
            audioPlayer.loadAndProcess(uri, vocalRemover)
        }
    }

    private fun autoSaveProcessedFile(instrumental: StereoPcm, displayName: String) {
        binding.tvStatus.text = "Salvataggio brano elaborato..."
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RecordingSaver.saveProcessedStereoAsWav(this@MainActivity, instrumental, displayName)
                }
                binding.tvStatus.text = "Salvato in Music/VocalRemover/$displayName.wav"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Salvataggio automatico brano elaborato fallito", error)
                val msg = error.message ?: "Impossibile salvare il brano elaborato."
                binding.tvStatus.text = "Errore salvataggio: $msg"
                Toast.makeText(this@MainActivity, "Errore salvataggio: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Cattura e salvataggio registrazione ───────────────────────────────────
    private fun startCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setCaptureUiState(
                CaptureUiState.IDLE,
                "La cattura audio richiede Android 10 o versioni successive."
            )
            return
        }

        val packageName = binding.etCapturePackageName.text.toString().trim()
        if (packageName.isEmpty()) {
            binding.tvCaptureStatus.text = "Inserisci il nome del pacchetto dell'app sorgente."
            return
        }

        if (resolveTargetUid(packageName) == null) {
            binding.tvCaptureStatus.text =
                "App sorgente non trovata. Verifica il nome del pacchetto."
            return
        }

        rememberCapturePackage(packageName)
        pendingCaptureTargetPackageName = packageName
        setCaptureUiState(
            CaptureUiState.AWAITING_CONSENT,
            "Conferma il consenso dopo aver avviato la riproduzione nell'app sorgente."
        )

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermissionThenMediaProjection()
        } else {
            requestRecordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestNotificationPermissionThenMediaProjection() {
        if (pendingCaptureTargetPackageName == null) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPostNotificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestMediaProjectionConsent()
        }
    }

    private fun requestMediaProjectionConsent() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || pendingCaptureTargetPackageName == null) {
            return
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjectionLauncher.launch(createMediaProjectionIntent(projectionManager))
    }

    private fun stopCapture() {
        setCaptureUiState(CaptureUiState.STOPPING, "Arresto della registrazione in corso…")
        try {
            val stoppedService = startService(
                Intent(this, SystemAudioCaptureService::class.java)
                    .setAction(SystemAudioCaptureService.ACTION_STOP)
            )
            if (stoppedService == null) {
                setCaptureUiState(
                    CaptureUiState.RECORDING,
                    "Il servizio di cattura non è disponibile. Riprova a fermare la registrazione."
                )
            }
        } catch (error: Exception) {
            setCaptureUiState(
                CaptureUiState.RECORDING,
                error.message ?: "Impossibile fermare la registrazione."
            )
        }
    }

    private fun consumeTerminalCaptureResult() {
        val resultStore = CaptureTerminalResultStore(this)
        resultStore.acquireStoppedResult(captureWorkOwnerId)?.let { lease ->
            handleCaptureStopped(lease, resultStore)
            return
        }

        val result = resultStore.claim() ?: return
        when (result.kind) {
            CaptureTerminalKind.BLOCKED -> {
                showCaptureFailure(
                    "La sorgente non consente la cattura dell'audio. Non è stato creato alcun file."
                )
                resultStore.clearClaim(result.id)
            }

            CaptureTerminalKind.ERROR -> {
                showCaptureFailure(result.errorMessage ?: "Errore durante la cattura audio.")
                resultStore.clearClaim(result.id)
            }

            CaptureTerminalKind.STOPPED -> Unit
        }
    }

    private fun handleCaptureStopped(
        lease: CaptureProcessingLease,
        resultStore: CaptureTerminalResultStore
    ) {
        val result = lease.result
        if (processingCaptureResultId != null) {
            resultStore.releaseStoppedResult(result.id, captureWorkOwnerId)
            return
        }

        processingCaptureResultId = result.id
        processingCaptureOutputPath = result.outputPath
        discardCaptureWorkOnCancellation = false

        when (lease.state) {
            CaptureTerminalState.SAVED -> restoreSavedCapture(result)
            CaptureTerminalState.PENDING,
            CaptureTerminalState.PROCESSING -> processStoppedCapture(result)
        }
    }

    private fun processStoppedCapture(result: CaptureTerminalResult) {
        pendingAutoSaveName = null
        val temporaryWav = captureFileIfSafe(result.outputPath)
        if (temporaryWav == null) {
            discardCaptureWork(
                result.id,
                "Il file temporaneo della registrazione non è disponibile."
            )
            return
        }

        setCaptureUiState(
            CaptureUiState.PROCESSING,
            "Registrazione terminata. Elaborazione in corso…"
        )
        Log.i(
            TAG,
            "Avvio elaborazione registrazione catturata: resultId=${result.id} file=${temporaryWav.absolutePath} bytes=${temporaryWav.length()}"
        )
        setPlayerEnabled(false)
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvStatus.text = "Avvio elaborazione..."

        captureProcessingJob = lifecycleScope.launch {
            try {
                val instrumental = withContext(Dispatchers.IO) {
                    val stereoPcm = WavFileReader.readStereoPcm(temporaryWav)
                    Log.i(TAG, "Registrazione caricata: frames=${stereoPcm.size}")
                    runCatching {
                        RecordingSaver.saveStereoCaptureAsWav(
                            this@MainActivity,
                            stereoPcm,
                            RecordingFileNaming.rawCaptureName(RecordingFileNaming.defaultName())
                        )
                    }
                    vocalRemover.removeVocals(stereoPcm) { progress ->
                        if (!isDestroyed) {
                            runOnUiThread {
                                if (!isDestroyed) showProcessingProgress(progress)
                            }
                        }
                    }
                }
                ensureActive()
                captureProcessingJob = null
                Log.i(TAG, "Elaborazione registrazione completata: resultId=${result.id}")
                showRecordingNameDialog(instrumental, result.id)
            } catch (cancelled: CancellationException) {
                handleCaptureWorkCancellation(result.id)
                throw cancelled
            } catch (error: Exception) {
                discardCaptureWork(
                    result.id,
                    error.message ?: "Impossibile elaborare la registrazione."
                )
            }
        }
    }

    private fun captureFileIfSafe(outputPath: String?): File? {
        if (outputPath.isNullOrBlank()) return null
        val cacheDirectory = runCatching { cacheDir.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(outputPath).canonicalFile }.getOrNull() ?: return null
        return file.takeIf {
            it.parentFile == cacheDirectory && it.isFile && it.canRead() && it.length() > 44L
        }
    }

    private fun deleteCaptureFileIfAppCacheFile(outputPath: String?): Boolean {
        val cacheDirectory = runCatching { cacheDir.canonicalFile }.getOrNull() ?: return false
        val file = runCatching { outputPath?.let(::File)?.canonicalFile }.getOrNull() ?: return false
        if (file?.parentFile != cacheDirectory) return false
        return !file.exists() || file.delete()
    }

    private fun showRecordingNameDialog(instrumental: StereoPcm, resultId: String) {
        val nameInput = EditText(this).apply {
            setText(RecordingFileNaming.defaultName())
            selectAll()
            hint = "Nome registrazione"
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Salva registrazione")
            .setMessage("Scegli il nome del file elaborato.")
            .setView(nameInput)
            .setCancelable(false)
            .setPositiveButton("Salva", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                saveCapturedAudio(
                    instrumental,
                    RecordingFileNaming.sanitize(nameInput.text.toString()),
                    resultId
                )
            }
        }
        dialog.show()
    }

    private fun saveCapturedAudio(
        instrumental: StereoPcm,
        displayName: String,
        resultId: String
    ) {
        if (processingCaptureResultId != resultId) return
        binding.tvStatus.text = "Salvataggio registrazione..."
        Log.i(
            TAG,
            "Avvio salvataggio registrazione elaborata: resultId=$resultId name=$displayName frames=${instrumental.size}"
        )
        captureProcessingJob = lifecycleScope.launch {
            try {
                val monoPcm = withContext(Dispatchers.IO) {
                    val savedUri = RecordingSaver.saveProcessedStereoAsWav(
                        this@MainActivity,
                        instrumental,
                        displayName
                    )
                    check(
                        CaptureTerminalResultStore(this@MainActivity).markSaved(
                            resultId,
                            captureWorkOwnerId,
                            savedUri.toString()
                        )
                    ) { "Impossibile registrare il salvataggio della cattura" }
                    instrumental
                }
                ensureActive()
                binding.tvFileName.text = "$displayName.wav"
                audioPlayer.loadProcessedStereo(monoPcm)
                ensureActive()
                Log.i(TAG, "Salvataggio registrazione completato: resultId=$resultId")
                completeSavedCaptureHandoff(resultId)
            } catch (cancelled: CancellationException) {
                handleCaptureWorkCancellation(resultId)
                throw cancelled
            } catch (error: Exception) {
                discardCaptureWork(
                    resultId,
                    error.message ?: "Impossibile salvare la registrazione."
                )
            }
        }
    }

    private fun restoreSavedCapture(result: CaptureTerminalResult) {
        pendingAutoSaveName = null
        val savedUri = result.savedOutputUri
            ?.takeIf { Uri.parse(it).scheme == "content" }
            ?.let(Uri::parse)
        if (savedUri == null) {
            discardCaptureWork(result.id, "Il file elaborato salvato non è disponibile.")
            return
        }

        setCaptureUiState(CaptureUiState.PROCESSING, "Ripristino della registrazione salvata…")
        setPlayerEnabled(false)
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvStatus.text = "Ripristino riproduzione..."

        captureProcessingJob = lifecycleScope.launch {
            try {
                audioPlayer.loadSavedRecording(savedUri)
                ensureActive()
                completeSavedCaptureHandoff(result.id)
            } catch (cancelled: CancellationException) {
                handleCaptureWorkCancellation(result.id)
                throw cancelled
            } catch (error: Exception) {
                discardCaptureWork(
                    result.id,
                    error.message ?: "Impossibile ripristinare la registrazione salvata."
                )
            }
        }
    }

    private fun completeSavedCaptureHandoff(resultId: String) {
        if (!deleteCaptureFileIfAppCacheFile(processingCaptureOutputPath)) {
            releaseCaptureWork(resultId, notify = false)
            showCaptureProcessingFailure(
                "Impossibile eliminare il file temporaneo della registrazione."
            )
            return
        }
        if (!CaptureTerminalResultStore(this).completeSavedHandoff(resultId, captureWorkOwnerId)) {
            releaseCaptureWork(resultId, notify = false)
            showCaptureProcessingFailure(
                "Impossibile completare il salvataggio della registrazione."
            )
            return
        }

        processingCaptureResultId = null
        processingCaptureOutputPath = null
        captureProcessingJob = null
        setCaptureUiState(CaptureUiState.IDLE, "Registrazione salvata.")
    }

    private fun handleCaptureWorkCancellation(resultId: String) {
        if (discardCaptureWorkOnCancellation) {
            discardCaptureWork(resultId, showFailure = false)
        } else {
            releaseCaptureWork(resultId)
        }
    }

    private fun discardCaptureWork(
        resultId: String,
        message: String = "",
        showFailure: Boolean = true
    ) {
        if (!deleteCaptureFileIfAppCacheFile(processingCaptureOutputPath)) {
            releaseCaptureWork(resultId, notify = false)
            if (showFailure) {
                showCaptureProcessingFailure(
                    "$message Impossibile eliminare il file temporaneo della registrazione."
                )
            }
            return
        }
        CaptureTerminalResultStore(this).discardStoppedResult(resultId, captureWorkOwnerId)
        processingCaptureResultId = null
        processingCaptureOutputPath = null
        captureProcessingJob = null
        if (showFailure) showCaptureProcessingFailure(message)
    }

    private fun releaseCaptureWork(resultId: String, notify: Boolean = true) {
        if (
            CaptureTerminalResultStore(this).releaseStoppedResult(
                resultId,
                captureWorkOwnerId
            )
        ) {
            processingCaptureResultId = null
            processingCaptureOutputPath = null
            captureProcessingJob = null
            if (notify) {
                sendBroadcast(
                    Intent(SystemAudioCaptureService.ACTION_CAPTURE_PROCESSING_AVAILABLE)
                        .setPackage(packageName)
                )
            }
        }
    }

    private fun pendingCaptureTargetUid(): Int? {
        val packageName = pendingCaptureTargetPackageName ?: return null
        return resolveTargetUid(packageName)
    }

    private fun resolveTargetUid(packageName: String): Int? =
        try {
            packageManager.getApplicationInfo(packageName, ApplicationInfo.FLAG_INSTALLED).uid
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun clearPendingCaptureTarget() {
        pendingCaptureTargetPackageName = null
    }

    private fun showCaptureFailure(message: String) {
        clearPendingCaptureTarget()
        setCaptureUiState(CaptureUiState.IDLE, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showCaptureProcessingFailure(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = "Errore: $message"
        setPlayerEnabled(audioPlayer.isReady)
        showCaptureFailure(message)
    }

    private fun setCaptureUiState(state: CaptureUiState, status: String? = null) {
        captureUiState = state
        status?.let { binding.tvCaptureStatus.text = it }
        renderCaptureControls()
    }

    private fun renderCaptureControls() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            binding.etCapturePackageName.isEnabled = false
            binding.btnStartCapture.isEnabled = false
            binding.btnStartCapture.visibility = View.VISIBLE
            binding.btnStopCapture.visibility = View.GONE
            return
        }

        when (captureUiState) {
            CaptureUiState.IDLE -> {
                binding.etCapturePackageName.isEnabled = true
                binding.btnStartCapture.visibility = View.VISIBLE
                binding.btnStartCapture.isEnabled = true
                binding.btnStopCapture.visibility = View.GONE
                binding.btnPickFile.isEnabled = true
            }

            CaptureUiState.AWAITING_CONSENT -> {
                binding.etCapturePackageName.isEnabled = false
                binding.btnStartCapture.visibility = View.VISIBLE
                binding.btnStartCapture.isEnabled = false
                binding.btnStopCapture.visibility = View.GONE
                binding.btnPickFile.isEnabled = false
            }

            CaptureUiState.RECORDING -> {
                binding.etCapturePackageName.isEnabled = false
                binding.btnStartCapture.visibility = View.GONE
                binding.btnStopCapture.visibility = View.VISIBLE
                binding.btnStopCapture.isEnabled = true
                binding.btnPickFile.isEnabled = false
            }

            CaptureUiState.STOPPING -> {
                binding.etCapturePackageName.isEnabled = false
                binding.btnStartCapture.visibility = View.GONE
                binding.btnStopCapture.visibility = View.VISIBLE
                binding.btnStopCapture.isEnabled = false
                binding.btnPickFile.isEnabled = false
            }

            CaptureUiState.PROCESSING -> {
                binding.etCapturePackageName.isEnabled = false
                binding.btnStartCapture.visibility = View.VISIBLE
                binding.btnStartCapture.isEnabled = false
                binding.btnStopCapture.visibility = View.GONE
                binding.btnPickFile.isEnabled = false
            }
        }
    }

    private fun captureInstructions(): String =
        "Avvia la riproduzione nell'app sorgente, poi torna qui e registra." +
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
                " Nel consenso scegli Schermo intero."
            } else {
                ""
            }

    private fun showProcessingProgress(progress: Int) {
        binding.progressBar.progress = progress
        binding.tvStatus.text = when {
            progress < 5 -> "Caricamento..."
            progress < 20 -> "Analisi spettrale..."
            progress < 85 -> "Rimozione voci: $progress%"
            progress < 100 -> "Ricostruzione audio..."
            else -> "Pronto!"
        }
    }

    private fun createMediaProjectionIntent(manager: MediaProjectionManager): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            createFullScreenMediaProjectionIntent(manager)
        } else {
            manager.createScreenCaptureIntent()
        }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun createFullScreenMediaProjectionIntent(
        manager: MediaProjectionManager
    ): Intent = manager.createScreenCaptureIntent(
        MediaProjectionConfig.createConfigForDefaultDisplay()
    )

    // ── Permessi ─────────────────────────────────────────────────────────────
    private fun requestStoragePermissionAndPick() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED ->
                pickAudioLauncher.launch("audio/*")
            else ->
                requestPermissionLauncher.launch(permission)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun setPlayerEnabled(enabled: Boolean) {
        binding.btnPlayPause.isEnabled = enabled
        binding.btnStop.isEnabled = enabled
        binding.seekBar.isEnabled = enabled
    }

    private fun formatMs(ms: Int): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms.toLong())
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms.toLong()) % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}

/**
 * ArrayAdapter con filtro "contains" (case-insensitive) invece del default
 * "startsWith" per parola. Mantiene sempre l'elenco completo originale, così
 * il menu a tendina può essere riaperto e rifiltrato ripetutamente (il
 * ArrayAdapter standard perde il riferimento alla lista completa dopo la
 * prima selezione/filtraggio).
 */
private class ContainsFilterAdapter(
    context: Context,
    private val allItems: List<String>,
    private val autoCompleteView: AutoCompleteTextView
) : ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line, allItems.toMutableList()) {

    private val containsFilter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val query = constraint?.toString()?.trim().orEmpty()
            val filtered = if (query.isEmpty()) {
                allItems
            } else {
                allItems.filter { it.contains(query, ignoreCase = true) }
            }
            return FilterResults().apply {
                values = filtered
                count = filtered.size
            }
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            @Suppress("UNCHECKED_CAST")
            val filtered = results?.values as? List<String> ?: emptyList()
            clear()
            addAll(filtered)
            notifyDataSetChanged()
            if (filtered.isNotEmpty() && autoCompleteView.hasFocus()) {
                autoCompleteView.post { autoCompleteView.showDropDown() }
            } else {
                autoCompleteView.dismissDropDown()
            }
        }
    }

    override fun getFilter(): Filter = containsFilter
}
