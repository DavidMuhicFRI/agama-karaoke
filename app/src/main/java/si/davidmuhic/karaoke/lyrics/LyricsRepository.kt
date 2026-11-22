package si.davidmuhic.karaoke.lyrics

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Finds lyrics by asking each source in turn (LRCLIB first, then NetEase) and caches
 * the result on disk, so songs heard before work without internet.
 */
class LyricsRepository(context: Context) {

    private val sources: List<LyricSource> = listOf(LrclibSource(), NetEaseSource())
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
            if (!isStaleMiss(cached)) return toLyrics(cached)
        }
        val fetched = toJson(fetch(track))
        file.writeText(fetched.toString())
        return toLyrics(fetched)
    }

    /**
     * Returns the first synced (or instrumental) result; plain-text lyrics are kept only
     * as a last resort. Throws if nothing was found and a source failed, so offline misses aren't cached.
     */
    private fun fetch(track: Track): FoundLyrics? {
        val variants = queryVariants(track)
        var fallback: FoundLyrics? = null
        var failure: Exception? = null
        for (source in sources) {
            val found = (
                try {
                    source.find(track, variants)
                } catch (e: Exception) {
                    Log.w(TAG, "${source.name} failed for ${track.key}", e)
                    failure = e
                    null
                }
                ) ?: continue
            if (found.hasSynced || found.instrumental) return found
            if (fallback == null && found.plain.isNotBlank()) fallback = found
        }
        if (fallback == null) failure?.let { throw it }
        return fallback
    }

    /** Misses are retried after a while, and immediately after a new source is added. */
    private fun isStaleMiss(cached: JSONObject): Boolean =
        !cached.optBoolean("found") && (
            cached.optInt("v") < CACHE_VERSION ||
                System.currentTimeMillis() - cached.optLong("ts") > MISS_RETRY_MS
            )

    private fun toJson(found: FoundLyrics?): JSONObject {
        val json = JSONObject().put("v", CACHE_VERSION).put("ts", System.currentTimeMillis())
        if (found == null) return json.put("found", false)
        return json.put("found", true)
            .put("source", found.source)
            .put("instrumental", found.instrumental)
            .put("synced", found.synced)
            .put("plain", found.plain)
    }

    private fun toLyrics(o: JSONObject): Lyrics {
        if (!o.optBoolean("found")) return Lyrics.NotFound
        if (o.optBoolean("instrumental")) return Lyrics.Instrumental
        val source = o.optString("source", "LRCLIB")
        val synced = LrcParser.parse(o.optString("synced"))
        if (synced.isNotEmpty()) return Lyrics.Synced(synced, source)
        val plain = o.optString("plain")
        if (plain.isNotBlank()) return Lyrics.Plain(plain.lines(), source)
        return Lyrics.NotFound
    }

    companion object {
        private const val TAG = "LyricsRepository"
        private const val CACHE_VERSION = 2
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

        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
