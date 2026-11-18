package si.davidmuhic.karaoke.lyrics

/** The song currently reported by the media session. */
data class Track(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
) {
    val durationSec: Int get() = ((durationMs + 500) / 1000).toInt()

    /** Identity used for caching and for detecting song changes. */
    val key: String get() = "$artist|$title|$durationSec"
}

/** One timestamped lyric line. */
data class LyricLine(val timeMs: Long, val text: String)

/** Everything the overlay can display for the current song. */
sealed class Lyrics {
    object NothingPlaying : Lyrics()
    object Loading : Lyrics()
    object NotFound : Lyrics()
    object Instrumental : Lyrics()
    data class Synced(val lines: List<LyricLine>) : Lyrics()
    data class Plain(val lines: List<String>) : Lyrics()
    data class Error(val message: String) : Lyrics()
}
