package com.example.vocalremover

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.vocalremover.databinding.ActivityMainBinding
import com.example.vocalremover.spike.CaptureSpikeService
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CaptureSpike"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var vocalRemover: VocalRemover
    private lateinit var audioPlayer: AudioPlayer

    private var isUserSeeking = false

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

    // ── Spike: cattura audio di sistema ──────────────────────────────────────
    private var pendingSpikeTargetUid: Int = -1

    private val requestRecordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchSpikeMediaProjectionConsent()
        else Toast.makeText(this, "Permesso microfono necessario per lo spike", Toast.LENGTH_SHORT).show()
    }

    private val spikeMediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "spikeMediaProjectionLauncher: resultCode=${result.resultCode}, data=${result.data}, targetUid=$pendingSpikeTargetUid")
        if (result.resultCode == Activity.RESULT_OK && result.data != null && pendingSpikeTargetUid >= 0) {
            val serviceIntent = Intent(this, CaptureSpikeService::class.java).apply {
                putExtra(CaptureSpikeService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureSpikeService.EXTRA_RESULT_DATA, result.data)
                putExtra(CaptureSpikeService.EXTRA_TARGET_UID, pendingSpikeTargetUid)
            }
            Log.d(TAG, "Avvio CaptureSpikeService con uid=$pendingSpikeTargetUid")
            startForegroundService(serviceIntent)
            binding.tvSpikeOutcome.text = "Cattura avviata, attendi ~10s..."
            binding.root.postDelayed({
                Log.d(TAG, "Esito finale letto in UI: ${CaptureSpikeService.lastOutcome}")
                binding.tvSpikeOutcome.text = CaptureSpikeService.lastOutcome
            }, 11_000L)
        } else {
            Log.w(TAG, "Consenso MediaProjection negato o dati mancanti")
            Toast.makeText(this, "Consenso alla cattura negato", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vocalRemover = VocalRemover(this)
        audioPlayer = AudioPlayer(this)

        setupUi()
        setupPlayerCallbacks()
        setPlayerEnabled(false)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            binding.btnSpikeCapture.isEnabled = false
            binding.tvSpikeOutcome.text = "Spike non disponibile: richiede Android 10 (API 29) o superiore"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioPlayer.release()
        vocalRemover.close()
    }

    // ── UI setup ─────────────────────────────────────────────────────────────
    private fun setupUi() {
        binding.btnPickFile.setOnClickListener { requestStoragePermissionAndPick() }

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

        binding.btnSpikeCapture.setOnClickListener { startSpikeCapture() }
    }

    private fun setupPlayerCallbacks() {
        audioPlayer.onProgress = { pct ->
            runOnUiThread {
                binding.progressBar.progress = pct
                binding.tvStatus.text = when {
                    pct < 5  -> "Caricamento..."
                    pct < 20 -> "Analisi spettrale..."
                    pct < 85 -> "Rimozione voci: $pct%"
                    pct < 100 -> "Ricostruzione audio..."
                    else -> "Pronto!"
                }
            }
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
        }

        audioPlayer.onError = { msg ->
            binding.tvStatus.text = "Errore: $msg"
            binding.progressBar.visibility = android.view.View.GONE
            Toast.makeText(this, "Errore: $msg", Toast.LENGTH_LONG).show()
        }
    }

    // ── Elaborazione ─────────────────────────────────────────────────────────
    private fun processAudio(uri: Uri) {
        setPlayerEnabled(false)
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvStatus.text = "Avvio elaborazione..."

        // Mostra nome file
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)
                binding.tvFileName.text = name
            }
        }

        lifecycleScope.launch {
            audioPlayer.loadAndProcess(uri, vocalRemover)
        }
    }

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

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startSpikeCapture() {
        val packageName = binding.etSpikePackageName.text.toString().trim()
        Log.d(TAG, "startSpikeCapture: pulsante premuto, packageName='$packageName'")
        if (packageName.isEmpty()) {
            Toast.makeText(this, "Inserisci un nome pacchetto", Toast.LENGTH_SHORT).show()
            return
        }
        val uid = try {
            packageManager.getApplicationInfo(packageName, ApplicationInfo.FLAG_INSTALLED).uid
        } catch (e: Exception) {
            Log.e(TAG, "Impossibile risolvere il pacchetto '$packageName'", e)
            Toast.makeText(this, "App '$packageName' non trovata: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        Log.d(TAG, "UID risolto per '$packageName' = $uid")
        pendingSpikeTargetUid = uid

        val recordAudioGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "Permesso RECORD_AUDIO già concesso: $recordAudioGranted")

        if (recordAudioGranted) launchSpikeMediaProjectionConsent()
        else requestRecordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun launchSpikeMediaProjectionConsent() {
        Log.d(TAG, "Richiesta consenso MediaProjection all'utente")
        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Da Android 14 (API 34) si può forzare la modalità "Schermo intero" nel
        // dialogo di consenso, evitando che l'utente possa scegliere "Un'unica app"
        // (modalità che, verificato con lo spike, non produce una cattura audio
        // funzionante su questi device).
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.d(TAG, "Forzo consenso 'Schermo intero' via MediaProjectionConfig (API ${Build.VERSION.SDK_INT})")
            projectionManager.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            Log.w(TAG, "API < 34: impossibile forzare 'Schermo intero', l'utente potrebbe scegliere 'Un'unica app' (non funzionante)")
            projectionManager.createScreenCaptureIntent()
        }
        spikeMediaProjectionLauncher.launch(captureIntent)
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
