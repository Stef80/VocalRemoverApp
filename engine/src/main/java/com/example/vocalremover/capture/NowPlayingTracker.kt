package com.example.vocalremover.capture

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.vocalremover.lyrics.LYRICS_LOG_TAG

/** Metadata of a media session, without Android types. */
data class SessionMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val mediaId: String?,
)

/** An observable media session (a `MediaController` in production). */
internal interface SessionHandle {
    val packageName: String
    fun metadata(): SessionMetadata?
    fun playback(): PlaybackSnapshot?
    fun observe(listener: SessionListener)
    fun stopObserving()
}

internal interface SessionListener {
    fun onMetadata(metadata: SessionMetadata?)
    fun onPlayback(playback: PlaybackSnapshot?)
}

/** List of active sessions; may throw SecurityException without notification access. */
internal interface SessionSource {
    fun activeSessions(): List<SessionHandle>
    fun observeSessions(onChange: (List<SessionHandle>) -> Unit)
    fun stopObservingSessions()
}

/**
 * During a capture, follows the media session of the recorded app: which track is playing,
 * when it changes, the last playback state. Without notification access it does nothing and
 * [timeline] returns null: the capture goes on without metadata.
 *
 * Callbacks arrive on the main thread, reads come from the capture thread.
 */
class NowPlayingTracker internal constructor(
    private val source: SessionSource,
    private val packages: Set<String>,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {

    companion object {
        private const val TAG = LYRICS_LOG_TAG
        private const val MAX_PLAYBACK_STATES = 200

        fun create(context: Context, targetUid: Int): NowPlayingTracker = NowPlayingTracker(
            MediaSessionSource(context.applicationContext),
            context.packageManager.getPackagesForUid(targetUid)?.toSet().orEmpty(),
        )
    }

    private var session: SessionHandle? = null
    private var accessDenied = false
    private var lastSeenPackages: List<String>? = null
    /** Stays set after [stop]: the track is read once the capture is over. */
    private var sourcePackage: String? = null
    /** "Playing" states received: used to estimate the position of each segment. */
    private val playing = mutableListOf<PlaybackSnapshot>()
    /** Tracks seen, with the moment they appeared. */
    private val history = mutableListOf<Pair<Long, SessionMetadata>>()

    private val listener = object : SessionListener {
        override fun onMetadata(metadata: SessionMetadata?) = record(metadata)
        override fun onPlayback(playback: PlaybackSnapshot?) = recordPlayback(playback)
    }

    @Synchronized
    fun start() {
        try {
            // Some apps (e.g. YouTube) create the session only after the capture starts.
            source.observeSessions { sessions ->
                Log.i(TAG, "Active sessions changed: ${sessions.map { it.packageName }}")
                attach(sessions)
            }
            val sessions = source.activeSessions()
            Log.i(TAG, "Looking for the session of $packages among ${sessions.map { it.packageName }}")
            lastSeenPackages = sessions.map { it.packageName }
            attach(sessions)
        } catch (e: SecurityException) {
            accessDenied = true
            Log.i(TAG, "Notification access not granted: capture without metadata")
        }
    }

    /** True once the session of the captured app has been found. */
    @Synchronized
    fun isAttached(): Boolean = sourcePackage != null

    /**
     * Reads the active sessions again. Some apps (e.g. YouTube) make the session active only
     * when the audio starts and the change notification does not always arrive: the service calls this
     * on the first audio and then at intervals until the session is attached.
     */
    @Synchronized
    fun retryAttach() {
        if (session != null || accessDenied) return
        try {
            val sessions = source.activeSessions()
            val seen = sessions.map { it.packageName }
            if (seen != lastSeenPackages) {
                Log.i(TAG, "New attempt, active sessions: $seen")
                lastSeenPackages = seen
            }
            attach(sessions)
        } catch (e: SecurityException) {
            accessDenied = true
            Log.i(TAG, "Notification access revoked: capture without metadata")
        }
    }

    @Synchronized
    fun stop() {
        session?.stopObserving()
        session = null
        runCatching { source.stopObservingSessions() }
    }

    /**
     * Segments of the capture relative to the first frame of the file (see [TrackTimelineBuilder]).
     * Stays readable after [stop]: the service asks for it once the capture is over.
     */
    @Synchronized
    fun timeline(firstFrameElapsedMs: Long, positionsReliable: Boolean): CaptureTrackTimeline? {
        val packageName = sourcePackage ?: return null
        return TrackTimelineBuilder.build(packageName, history.toList(), playing.toList(), firstFrameElapsedMs, positionsReliable)
    }

    @Synchronized
    private fun attach(sessions: List<SessionHandle>) {
        if (session != null) return
        val found = sessions.firstOrNull { it.packageName in packages } ?: return
        Log.i(TAG, "Session attached: ${found.packageName}")
        try {
            session = found
            sourcePackage = found.packageName
            record(found.metadata())
            recordPlayback(found.playback())
            found.observe(listener)
        } catch (e: SecurityException) {
            Log.i(TAG, "Session no longer readable: capture without metadata")
            session = null
            sourcePackage = null
            history.clear()
            playing.clear()
        }
    }

    @Synchronized
    private fun recordPlayback(playback: PlaybackSnapshot?) {
        if (playback == null || !playback.playing) return
        if (playing.size >= MAX_PLAYBACK_STATES) playing.removeAt(0)
        playing += playback
    }

    /**
     * A new segment starts when title, artist or duration change: on YouTube the
     * ad has the video title but its own duration. Other updates (artwork,
     * album, missing duration) complete the current segment.
     */
    @Synchronized
    private fun record(metadata: SessionMetadata?) {
        // Incomplete metadata (e.g. title before artist): wait for the complete version.
        if (metadata == null || metadata.title.isNullOrBlank() || metadata.artist.isNullOrBlank()) return
        val last = history.lastOrNull()
        if (last != null && isSameSegment(last.second, metadata)) {
            history[history.lastIndex] =
                last.first to metadata.copy(durationMs = metadata.durationMs ?: last.second.durationMs)
            return
        }
        history += clock() to metadata
        Log.i(TAG, "New segment: ${metadata.artist} - ${metadata.title} (duration ${metadata.durationMs} ms)")
    }

    private fun isSameSegment(current: SessionMetadata, update: SessionMetadata): Boolean =
        current.title == update.title &&
            current.artist == update.artist &&
            (update.durationMs == null || current.durationMs == null || update.durationMs == current.durationMs)
}
