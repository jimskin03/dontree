package org.cryptgregresearch.dontree.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.cryptgregresearch.dontree.core.*
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The default service is LRCLIB. Custom services implement its v1 /search response. */
class OnlineLyrics {
    suspend fun find(song: Song, config: LyricsServiceConfig): Lyrics? {
        config.validate()
        check(song.title.isNotBlank() && song.artist != "Unknown artist") {
            "Song title or artist is missing. Set up song recognition in Settings or import an LRC file."
        }
        val query = mapOf("track_name" to song.title, "artist_name" to song.artist).entries
            .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val response = request("${config.baseUrl}/search?$query", config.apiKey)
        return LyricsMatching.choose(song, parse(response, song.durationMs,
            if (config.endpoint.isBlank()) "LRCLIB" else "Custom lyrics service"))
    }

    internal fun parse(response: String, durationMs: Long, provider: String): List<LyricsCandidate> {
        val array = JSONArray(response)
        return (0 until minOf(array.length(), 100)).mapNotNull { index ->
            val item = array.getJSONObject(index)
            val title = item.optString("trackName")
            val artist = item.optString("artistName")
            val instrumental = item.optBoolean("instrumental")
            val synced = item.optString("syncedLyrics").takeUnless { it == "null" }.orEmpty()
            val plain = item.optString("plainLyrics").takeUnless { it == "null" }.orEmpty()
            val timed = if (synced.isNotBlank()) runCatching { LrcParser.parse(synced, durationMs) }.getOrNull() else null
            val lyrics = timed?.copy(source = "$provider · synced lyrics") ?: Lyrics(
                plain.lineSequence().map(String::trim).filter(String::isNotEmpty).take(2000)
                    .map { LyricLine(-1, -1, it) }.toList(),
                source = "$provider · lyrics without timing", instrumental = instrumental,
            )
            if (title.isBlank() || artist.isBlank()) null
            else LyricsCandidate(title, artist, (item.optDouble("duration", 0.0) * 1000).toLong(), lyrics)
        }
    }

    private suspend fun request(url: String, apiKey: String): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                // Credentials stay on the configured origin, including when a service redirects.
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "Dontree/0.1 (https://github.com/jimskin03/dontree)")
                if (apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
                check(connection.responseCode == 200) {
                    when (connection.responseCode) {
                        401, 403 -> "Lyrics service rejected access. Check your service URL and API key."
                        429 -> "Lyrics service is busy. Please try again later."
                        else -> "Lyrics search failed (HTTP ${connection.responseCode}). Try again or use the local fallback."
                    }
                }
                val response = connection.inputStream.use { it.readLimited(2_000_000).toString(Charsets.UTF_8) }
                if (continuation.isActive) continuation.resume(response)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally { connection.disconnect() }
        }
    }
}
