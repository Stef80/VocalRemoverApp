package com.example.vocalremover.capture

import kotlinx.serialization.Serializable

/** Stretch of a capture during which the session showed the same track (or the same ad). */
@Serializable
data class TrackSegment(
    val title: String,
    val artist: String,
    val album: String?,
    val trackDurationMs: Long?,
    val mediaId: String?,
    /** Ms from the start of the recorded (untrimmed) file. */
    val startMs: Long,
    /** Position in the track at [startMs]; null if it cannot be estimated. */
    val songPositionAtStartMs: Long?,
)

/** Tracks seen during a capture, in order. */
@Serializable
data class CaptureTrackTimeline(
    val sourcePackage: String,
    val segments: List<TrackSegment>,
)

/** Builds the segments from the data collected by the tracker during the capture. */
internal object TrackTimelineBuilder {

    /**
     * A track announced within this interval after the first frame is the recorded one:
     * the callback for a playlist change often arrives after the audio of the new track.
     */
    const val CHANGE_GRACE_MS = 1_500L

    /**
     * @param entries complete metadata with the time (elapsedRealtime) at which they appeared
     * @param playing "playing" states received from the session
     * @param positionsReliable false if the time of the first frame is approximate: no positions
     */
    fun build(
        sourcePackage: String,
        entries: List<Pair<Long, SessionMetadata>>,
        playing: List<PlaybackSnapshot>,
        firstFrameElapsedMs: Long,
        positionsReliable: Boolean,
    ): CaptureTrackTimeline? {
        if (entries.isEmpty()) return null
        val firstIndex = entries.indexOfLast { it.first <= firstFrameElapsedMs + CHANGE_GRACE_MS }.coerceAtLeast(0)
        val segments = (firstIndex until entries.size).map { index ->
            val (seenAt, metadata) = entries[index]
            val isFirst = index == firstIndex
            val startMs = if (isFirst) 0L else (seenAt - firstFrameElapsedMs).coerceAtLeast(0)
            // Playback state received while the segment was the one shown by the session.
            val from = if (isFirst) Long.MIN_VALUE else seenAt
            val until = entries.getOrNull(index + 1)?.first ?: Long.MAX_VALUE
            val state = playing.firstOrNull { it.playing && it.lastPositionUpdateTime in from until until }
            val position = SongPositionEstimator.positionAt(state, firstFrameElapsedMs + startMs)
                // A track that appeared after recording started, with no state, starts from the beginning.
                ?: if (isFirst) null else 0L
            TrackSegment(
                title = metadata.title.orEmpty().trim(),
                artist = metadata.artist.orEmpty().trim(),
                album = metadata.album?.trim()?.ifEmpty { null },
                trackDurationMs = metadata.durationMs,
                mediaId = metadata.mediaId,
                startMs = startMs,
                songPositionAtStartMs = position.takeIf { positionsReliable },
            )
        }
        return CaptureTrackTimeline(sourcePackage, segments)
    }
}

/** Proposed trim and chosen track based on the segments. Pure logic. */
object TrackTimelineResolver {

    /** A change within this time from the start of the file is almost certainly the end of an ad. */
    const val AUTO_TRIM_START_LIMIT_MS = 15_000L

    /** Below this length auto-trim is not worth it: the whole file is kept. */
    const val MIN_AUTO_RANGE_MS = 5_000L

    /**
     * Start and end proposed in the editor.
     * - Start: leading ads are skipped (YouTube announces them with the video title
     *   and a shorter duration), even more than one and of any length, and a track
     *   change within [AUTO_TRIM_START_LIMIT_MS].
     * - End: where a track with a different title or artist starts (e.g. the next video).
     *   A mid-video ad has the same title and does not end the trim.
     */
    fun initialRange(timeline: CaptureTrackTimeline?, durationMs: Long): Pair<Long, Long> {
        val segments = timeline?.segments.orEmpty()
        if (segments.size < 2) return 0L to durationMs
        var main = 0
        while (main + 1 < segments.size && segments[main + 1].startMs < durationMs) {
            val current = segments[main]
            val next = segments[main + 1]
            val earlyChange = !sameTrack(current, next) && next.startMs <= AUTO_TRIM_START_LIMIT_MS
            if (isAdBefore(current, next) || earlyChange) main++ else break
        }
        val start = segments[main].startMs
        val end = segments.drop(main + 1).firstOrNull { !sameTrack(it, segments[main]) }
            ?.startMs?.takeIf { it < durationMs } ?: durationMs
        return if (end - start >= MIN_AUTO_RANGE_MS) start to end else 0L to durationMs
    }

    private fun sameTrack(a: TrackSegment, b: TrackSegment) = a.title == b.title && a.artist == b.artist

    /** YouTube ad: same title as the following video, shorter declared duration. */
    private fun isAdBefore(segment: TrackSegment, next: TrackSegment): Boolean {
        val duration = segment.trackDurationMs ?: return false
        val nextDuration = next.trackDurationMs ?: return false
        return sameTrack(segment, next) && duration < nextDuration
    }

    /** Track covering the longest part of the kept interval, with its position at [trimStartMs]. */
    fun resolve(timeline: CaptureTrackTimeline, trimStartMs: Long, trimEndMs: Long): CapturedTrackInfo? {
        val segments = timeline.segments
        val best = segments.indices.maxByOrNull { index ->
            val segmentEnd = segments.getOrNull(index + 1)?.startMs ?: Long.MAX_VALUE
            minOf(trimEndMs, segmentEnd) - maxOf(trimStartMs, segments[index].startMs)
        } ?: return null
        val segment = segments[best]
        return CapturedTrackInfo(
            title = segment.title,
            artist = segment.artist,
            album = segment.album,
            trackDurationMs = segment.trackDurationMs,
            songOffsetMs = segment.songPositionAtStartMs?.plus(trimStartMs - segment.startMs),
            sourcePackage = timeline.sourcePackage,
            mediaId = segment.mediaId,
        )
    }
}
