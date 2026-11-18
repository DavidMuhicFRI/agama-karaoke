package si.davidmuhic.karaoke.lyrics

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Finds lyrics on LRCLIB (free, no API key) and caches them on disk,
 * so songs heard before work without internet.
 */
class LyricsRepository(context: Context) {

    private val cacheDir = File(context.filesDir, "lyrics").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Loads lyrics on a background thread and delivers the result on the main thread. */
    fun load(track: Track, callback: (Lyrics) -> Unit) {
        executor.execute {
            val result = try {
                resolve(track)
            } catch (e: Exception) {
                Log.w(TAG, "Lyrics lookup failed for ${track.key}", e)
                Lyrics.Error("No connection — lyrics will load once online")
            }
            main.post { callback(result) }
        }
    }

    fun shutdown() = executor.shutdownNow()

    private fun resolve(track: Track): Lyrics {
        val file = File(cacheDir, sha1(track.key) + ".json")
        if (file.exists()) {
            val cached = JSONObject(file.readText())
            val staleMiss = !cached.optBoolean("found") &&
                System.currentTimeMillis() - cached.optLong("ts") > MISS_RETRY_MS
            if (!staleMiss) return toLyrics(cached)
        }
        val fetched = fetch(track)
        file.writeText(fetched.toString())
        return toLyrics(fetched)
    }

    private fun fetch(track: Track): JSONObject {
        val variants = queryVariants(track)
        for ((artist, title) in variants) {
            getExact(artist, title, track.album, track.durationSec)?.let { return normalize(it) }
        }
        for ((artist, title) in variants) {
            search(artist, title, track.durationSec)?.let { return normalize(it) }
        }
        return JSONObject().put("found", false).put("ts", System.currentTimeMillis())
    }

    /** Exact match by signature; LRCLIB tolerates small duration differences. */
    private fun getExact(artist: String, title: String, album: String, durationSec: Int): JSONObject? {
        if (durationSec <= 0) return null
        val params = mutableListOf("artist_name" to artist, "track_name" to title, "duration" to durationSec.toString())
        if (album.isNotBlank()) params += "album_name" to album
        val (code, body) = get("$BASE/api/get?" + query(params))
        return when (code) {
            200 -> JSONObject(body)
            404 -> null
            else -> throw IOException("LRCLIB returned $code")
        }
    }

    /** Fuzzy search; prefers synced lyrics whose duration matches the playing song. */
    private fun search(artist: String, title: String, durationSec: Int): JSONObject? {
        val (code, body) = get("$BASE/api/search?" + query(listOf("track_name" to title, "artist_name" to artist)))
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

    private fun normalize(item: JSONObject): JSONObject = JSONObject()
        .put("found", true)
        .put("instrumental", item.optBoolean("instrumental"))
        .put("synced", stringOrEmpty(item, "syncedLyrics"))
        .put("plain", stringOrEmpty(item, "plainLyrics"))

    private fun toLyrics(o: JSONObject): Lyrics {
        if (!o.optBoolean("found")) return Lyrics.NotFound
        if (o.optBoolean("instrumental")) return Lyrics.Instrumental
        val synced = LrcParser.parse(o.optString("synced"))
        if (synced.isNotEmpty()) return Lyrics.Synced(synced)
        val plain = o.optString("plain")
        if (plain.isNotBlank()) return Lyrics.Plain(plain.lines())
        return Lyrics.NotFound
    }

    private fun get(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to body
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "LyricsRepository"
        private const val BASE = "https://lrclib.net"
        private const val USER_AGENT = "AgamaKaraoke/1.0 (https://github.com/DavidMuhicFRI/agama-karaoke)"
        private const val TIMEOUT_MS = 8_000
        private const val MAX_DURATION_DIFF_SEC = 10.0
        private const val MISS_RETRY_MS = 7L * 24 * 60 * 60 * 1000

        private val bracketNoise = Regex(
            """\s*[(\[][^)\]]*\b(feat|ft|with|remaster|remastered|live|version|edit|mix|mono|stereo|bonus)\b[^)\]]*[)\]]""",
            RegexOption.IGNORE_CASE,
        )
        private val dashNoise = Regex(
            """\s+-\s+.*\b(remaster|remastered|live|version|edit|mix|mono|stereo|single|radio)\b.*$""",
            RegexOption.IGNORE_CASE,
        )
        private val artistSeparators = Regex("""\s*(,|&| feat\.? | ft\.? | x )\s*""", RegexOption.IGNORE_CASE)

        /** Spotify titles like "Song - Remastered 2011" often miss; try cleaned-up forms too. */
        fun queryVariants(track: Track): List<Pair<String, String>> {
            val artists = linkedSetOf(track.artist, track.artist.split(artistSeparators).first())
            val titles = linkedSetOf(track.title, track.title.replace(bracketNoise, "").replace(dashNoise, "").trim())
            return artists.flatMap { a -> titles.map { t -> a.trim() to t.trim() } }
                .filter { it.first.isNotBlank() && it.second.isNotBlank() }
                .distinct()
        }

        private fun stringOrEmpty(o: JSONObject, key: String): String =
            if (o.isNull(key)) "" else o.optString(key)

        private fun query(params: List<Pair<String, String>>): String =
            params.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
