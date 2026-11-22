package si.davidmuhic.karaoke.lyrics

import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs

/**
 * NetEase Cloud Music's public (unofficial) web API. Huge catalogue of synced
 * lyrics, including most Western releases — a good fallback when LRCLIB misses.
 */
class NetEaseSource : LyricSource {

    override val name = "NetEase"

    override fun find(track: Track, variants: List<Pair<String, String>>): FoundLyrics? {
        val tried = mutableSetOf<Long>()
        for ((artist, title) in variants) {
            val songId = searchSong(artist, title, track) ?: continue
            if (!tried.add(songId)) continue
            lyricsFor(songId)?.let { return it }
        }
        return null
    }

    /** Best search hit whose title matches, preferring the same artist and a similar duration. */
    private fun searchSong(artist: String, title: String, track: Track): Long? {
        val url = "$BASE/api/search/get?" + Http.query("s" to "$title $artist", "type" to "1", "limit" to "15", "offset" to "0")
        val (code, body) = Http.get(url, HEADERS)
        if (code != 200) throw IOException("NetEase search returned $code")
        val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return null

        val wantedTitle = normalized(title)
        val wantedArtist = normalized(artist)
        var bestId: Long? = null
        var bestScore = Double.MAX_VALUE
        for (i in 0 until songs.length()) {
            val song = songs.getJSONObject(i)
            val songName = normalized(song.optString("name"))
            if (songName.isEmpty() || !(songName.contains(wantedTitle) || wantedTitle.contains(songName))) continue

            val durationDiffSec = if (track.durationMs > 0) abs(song.optLong("duration") - track.durationMs) / 1000.0 else 0.0
            if (durationDiffSec > MAX_DURATION_DIFF_SEC) continue

            val artists = song.optJSONArray("artists")
            val artistMatches = artists != null && (0 until artists.length()).any { j ->
                val a = normalized(artists.getJSONObject(j).optString("name"))
                a.isNotEmpty() && (a.contains(wantedArtist) || wantedArtist.contains(a))
            }
            val score = durationDiffSec + if (artistMatches) 0.0 else ARTIST_MISMATCH_PENALTY
            if (score < bestScore) {
                bestScore = score
                bestId = song.optLong("id")
            }
        }
        // Without an artist match the hit is probably a cover; better to show nothing.
        return if (bestScore < ARTIST_MISMATCH_PENALTY) bestId else null
    }

    private fun lyricsFor(songId: Long): FoundLyrics? {
        val url = "$BASE/api/song/lyric?" + Http.query("id" to songId.toString(), "lv" to "1", "kv" to "1", "tv" to "-1")
        val (code, body) = Http.get(url, HEADERS)
        if (code != 200) throw IOException("NetEase lyric returned $code")
        val json = JSONObject(body)
        if (json.optBoolean("nolyric")) return FoundLyrics(name, instrumental = true)

        val lrc = json.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val lines = lrc.lines()
        if (lines.any { instrumentalMarker.containsMatchIn(it) }) return FoundLyrics(name, instrumental = true)
        val cleaned = lines.filterNot { creditLine.containsMatchIn(it) }.joinToString("\n")
        return if (LrcParser.parse(cleaned).any { it.text.isNotBlank() }) FoundLyrics(name, synced = cleaned) else null
    }

    private companion object {
        const val BASE = "https://music.163.com"
        const val MAX_DURATION_DIFF_SEC = 10.0
        const val ARTIST_MISMATCH_PENALTY = 1000.0
        val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Referer" to "https://music.163.com/",
        )

        /**
         * Credit lines NetEase prepends, e.g. "[00:00.00] 作词 : Conor Mason" or "[00:02.31] 人声录音 : …":
         * a short Chinese label or a known English role, followed by a colon.
         */
        val creditLine = Regex(
            """]\s*([\u3400-\u9FFF]{1,8}|OP|SP|Lyricist|Lyrics by|Lyrics|Composer|Composed by|Arranger|Arranged by|Producer|Produced by|Written by|Mixed by|Mastered by)\s*[:：]""",
            RegexOption.IGNORE_CASE,
        )
        val instrumentalMarker = Regex("纯音乐")

        fun normalized(text: String): String = text.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "")
    }
}
