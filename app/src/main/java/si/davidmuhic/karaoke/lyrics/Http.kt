package si.davidmuhic.karaoke.lyrics

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal blocking HTTP GET for the lyric sources; call only from a background thread. */
object Http {

    private const val TIMEOUT_MS = 8_000

    fun get(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to body
        } finally {
            conn.disconnect()
        }
    }

    fun query(vararg params: Pair<String, String>): String =
        params.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
}
