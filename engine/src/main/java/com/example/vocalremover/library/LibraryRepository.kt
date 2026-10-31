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
import com.example.vocalremover.lyrics.LyricsScheduler
import com.example.vocalremover.lyrics.TrackLyricsRepository
import com.example.vocalremover.lyrics.TrackLyricsStore
import com.example.vocalremover.lyrics.WorkManagerLyricsScheduler

/** Processed track in Music/VocalRemover. */
data class LibraryTrack(val uri: Uri, val title: String, val durationMs: Long)

/** Outcome of a rename or a deletion. */
sealed interface LibraryEditResult {
    data object Done : LibraryEditResult

    /**
     * File created by a previous install of the app: user consent is needed
     * (system dialog to launch with `intentSender`), then the same action is retried.
     */
    data class NeedsConsent(val intentSender: IntentSender) : LibraryEditResult
}

/** List of the processed tracks saved by `RecordingSaver.saveProcessedStereoAsWav`. */
interface LibraryRepository {

    /** Blocking: must be called off the main thread. Most recent first. */
    fun load(): List<LibraryTrack>

    /**
     * Blocking. Deletes the file from the phone, its lyrics and any queued download;
     * exceptions other than consent are propagated.
     */
    fun delete(uri: Uri): LibraryEditResult

    /** Blocking. The file becomes `<title>_strumentale.wav`; the Uri stays the same. */
    fun rename(uri: Uri, title: String): LibraryEditResult

    companion object {
        fun create(context: Context): LibraryRepository =
            MediaStoreLibraryRepository(context.applicationContext)
    }
}

/**
 * Reads from MediaStore the WAVs in `Music/VocalRemover/` (raw recordings in `raw/` stay
 * out). Since Android 10 the app sees the files it created without permissions; below API 29
 * `RELATIVE_PATH` does not exist and saving itself does not work, so the library is empty.
 */
internal class MediaStoreLibraryRepository(
    private val context: Context,
    private val lyricsStore: TrackLyricsStore = TrackLyricsRepository.lyricsStore(context),
    private val lyricsScheduler: LyricsScheduler = WorkManagerLyricsScheduler(context, lyricsStore),
) : LibraryRepository {

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
        // MediaStore stores the path with a trailing slash.
        val arguments = arrayOf("${RecordingSaver.BASE_RELATIVE_PATH}/", "%.wav")
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        return context.contentResolver
            .query(collection, projection, selection, arguments, sortOrder)
            ?.use { LibraryCursorReader.read(it, collection) }
            ?: emptyList()
    }

    override fun delete(uri: Uri): LibraryEditResult = withConsent {
        context.contentResolver.delete(uri, null, null)
        // Only after a successful deletion: with the consent request the track stays.
        lyricsScheduler.cancel(uri.toString())
        lyricsStore.delete(uri.toString())
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
     * Since Android 10 MediaStore refuses to touch files of another install with a
     * `RecoverableSecurityException`, which contains the dialog through which the user can allow it.
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

/** Converts MediaStore rows in cursor order. Does not close the cursor. */
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
