package com.example.vocalremover

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.vocalremover.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

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
