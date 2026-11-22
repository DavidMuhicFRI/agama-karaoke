package si.davidmuhic.karaoke.lyrics

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs

/** LRCLIB (lrclib.net): free, open lyrics database with synced lyrics and no API key. */
class LrclibSource : LyricSource {

    override val name = "LRCLIB"

    override fun find(track: Track, variants: List<Pair<String, String>>): FoundLyrics? {
        for ((artist, title) in variants) {
            getExact(artist, title, track.album, track.durationSec)?.let { return toFound(it) }
        }
        for ((artist, title) in variants) {
            search(artist, title, track.durationSec)?.let { return toFound(it) }
        }
        return null
    }

    /** Exact match by signature; LRCLIB tolerates small duration differences. */
    private fun getExact(artist: String, title: String, album: String, durationSec: Int): JSONObject? {
        if (durationSec <= 0) return null
        val params = mutableListOf("artist_name" to artist, "track_name" to title, "duration" to durationSec.toString())
        if (album.isNotBlank()) params += "album_name" to album
        val (code, body) = Http.get("$BASE/api/get?" + Http.query(*params.toTypedArray()), HEADERS)
        return when (code) {
            200 -> JSONObject(body)
            404 -> null
            else -> throw IOException("LRCLIB returned $code")
        }
    }

    /** Fuzzy search; prefers synced lyrics whose duration matches the playing song. */
    private fun search(artist: String, title: String, durationSec: Int): JSONObject? {
        val (code, body) = Http.get("$BASE/api/search?" + Http.query("track_name" to title, "artist_name" to artist), HEADERS)
        if (code != 200) throw IOException("LRCLIB returned $code")
        val results = JSONArray(body)
        var best: JSONObject? = null
        var bestScore = Double.MAX_VALUE
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val diff = if (durationSec > 0) abs(item.optDouble("duration", 0.0) - durationSec) else 0.0
            if (diff > MAX_DURATION_DIFF_SEC) continue
            val score = diff + if (stringOrEmpty(item, "syncedLyrics").isBlank()) 1000 else 0
            if (score < bestScore) {
                best = item
                bestScore = score
            }
        }
        return best
    }

    private fun toFound(item: JSONObject) = FoundLyrics(
        source = name,
        synced = stringOrEmpty(item, "syncedLyrics"),
        plain = stringOrEmpty(item, "plainLyrics"),
        instrumental = item.optBoolean("instrumental"),
    )

    private fun stringOrEmpty(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key)

    private companion object {
        const val BASE = "https://lrclib.net"
        const val MAX_DURATION_DIFF_SEC = 10.0
        val HEADERS = mapOf("User-Agent" to "AgamaKaraoke/1.0 (https://github.com/DavidMuhicFRI/agama-karaoke)")
    }
}
