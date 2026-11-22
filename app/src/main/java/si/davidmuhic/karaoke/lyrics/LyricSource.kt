package si.davidmuhic.karaoke.lyrics

/** Raw lyrics as returned by one source, before parsing. */
data class FoundLyrics(
    val source: String,
    val synced: String = "",
    val plain: String = "",
    val instrumental: Boolean = false,
) {
    val hasSynced: Boolean get() = synced.isNotBlank()
}

/** A lyrics provider. Implementations block, so call them from a background thread. */
interface LyricSource {
    val name: String

    /**
     * Returns lyrics for [track], or null when the source has none.
     * [variants] are (artist, title) spellings to try, most specific first.
     * Throws on network errors so a miss isn't cached while offline.
     */
    fun find(track: Track, variants: List<Pair<String, String>>): FoundLyrics?
}
