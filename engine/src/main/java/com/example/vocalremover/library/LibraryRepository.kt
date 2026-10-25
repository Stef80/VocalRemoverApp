package com.example.vocalremover.library

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.vocalremover.capture.RecordingFileNaming
import com.example.vocalremover.capture.RecordingSaver

/** Brano elaborato presente in Music/VocalRemover. */
data class LibraryTrack(val uri: Uri, val title: String, val durationMs: Long)

/** Esito di una rinomina o di un'eliminazione. */
sealed interface LibraryEditResult {
    data object Done : LibraryEditResult

    /**
     * File creato da un'installazione precedente dell'app: serve il consenso dell'utente
     * (finestra di sistema da lanciare con `intentSender`), poi si riprova la stessa azione.
     */
    data class NeedsConsent(val intentSender: IntentSender) : LibraryEditResult
}

/** Elenco dei brani elaborati salvati da `RecordingSaver.saveProcessedStereoAsWav`. */
interface LibraryRepository {

    /** Bloccante: va chiamata fuori dal main thread. Più recenti per primi. */
    fun load(): List<LibraryTrack>

    /** Bloccante. Cancella il file dal telefono; le eccezioni diverse dal consenso si propagano. */
    fun delete(uri: Uri): LibraryEditResult

    /** Bloccante. Il file diventa `<titolo>_strumentale.wav`; l'Uri resta lo stesso. */
    fun rename(uri: Uri, title: String): LibraryEditResult

    companion object {
        fun create(context: Context): LibraryRepository =
            MediaStoreLibraryRepository(context.applicationContext)
    }
}

/**
 * Legge da MediaStore i WAV in `Music/VocalRemover/` (le registrazioni grezze in `raw/` restano
 * fuori). Da Android 10 l'app vede senza permessi i file creati da lei; sotto API 29
 * `RELATIVE_PATH` non esiste e il salvataggio stesso non funziona, quindi la libreria è vuota.
 */
internal class MediaStoreLibraryRepository(private val context: Context) : LibraryRepository {

    override fun load(): List<LibraryTrack> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND " +
            "${MediaStore.Audio.Media.IS_PENDING} = 0 AND " +
            "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
        // MediaStore salva il percorso con la barra finale.
        val arguments = arrayOf("${RecordingSaver.BASE_RELATIVE_PATH}/", "%.wav")
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        return context.contentResolver
            .query(collection, projection, selection, arguments, sortOrder)
            ?.use { LibraryCursorReader.read(it, collection) }
            ?: emptyList()
    }

    override fun delete(uri: Uri): LibraryEditResult = withConsent {
        context.contentResolver.delete(uri, null, null)
    }

    override fun rename(uri: Uri, title: String): LibraryEditResult = withConsent {
        val values = ContentValues().apply {
            put(
                MediaStore.Audio.Media.DISPLAY_NAME,
                "${RecordingFileNaming.instrumentalNameForTitle(title)}.wav",
            )
        }
        context.contentResolver.update(uri, values, null, null)
    }

    /**
     * Da Android 10 MediaStore rifiuta di toccare i file di un'altra installazione con una
     * `RecoverableSecurityException`, che contiene la finestra con cui l'utente può consentirlo.
     */
    private inline fun withConsent(action: () -> Unit): LibraryEditResult {
        try {
            action()
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                return LibraryEditResult.NeedsConsent(e.userAction.actionIntent.intentSender)
            }
            throw e
        }
        return LibraryEditResult.Done
    }
}

/** Converte le righe di MediaStore nell'ordine del cursore. Non chiude il cursore. */
internal object LibraryCursorReader {

    fun read(cursor: Cursor, collection: Uri): List<LibraryTrack> {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        val tracks = ArrayList<LibraryTrack>(cursor.count)
        while (cursor.moveToNext()) {
            val duration = if (cursor.isNull(durationColumn)) null else cursor.getLong(durationColumn)
            val size = if (cursor.isNull(sizeColumn)) null else cursor.getLong(sizeColumn)
            tracks += LibraryTrack(
                uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                title = RecordingFileNaming.titleFromLibraryName(cursor.getString(nameColumn).orEmpty()),
                durationMs = LibraryDuration.resolve(duration, size),
            )
        }
        return tracks
    }
}
