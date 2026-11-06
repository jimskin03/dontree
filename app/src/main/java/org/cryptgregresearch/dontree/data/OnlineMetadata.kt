package org.cryptgregresearch.dontree.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.cryptgregresearch.dontree.agent.AudioDecoder
import org.cryptgregresearch.dontree.agent.NativeAudio
import org.cryptgregresearch.dontree.core.Song
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Deliberately separate from PlayerAgent. Called only by the user's explicit lookup action. */
interface SongIdentifier { suspend fun identify(song: Song, clientKey: String): MetadataRow }

class OnlineMetadata(private val repository: MusicRepository, private val decoder: AudioDecoder) : SongIdentifier {
    override suspend fun identify(song: Song, clientKey: String): MetadataRow = withContext(Dispatchers.IO) {
        require(clientKey.isNotBlank()) { "Enter an AcoustID application key in Settings" }
        val hash = repository.hash(song.uri)
        repository.dao.metadata(hash)?.let { return@withContext it }
        val decoded = decoder.chunks(song.uri).first().samples
        val sample = decoded.copyOf(minOf(decoded.size, 16000 * 20))
        val fingerprint = NativeAudio.fingerprint(sample)
        val payload = mapOf("client" to clientKey, "duration" to (song.durationMs / 1000).toString(), "fingerprint" to fingerprint, "meta" to "recordings releases", "format" to "json")
            .entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        currentCoroutineContext().ensureActive()
        val response = request("https://api.acoustid.org/v2/lookup", payload)
        check(response.optString("status") == "ok") { "AcoustID could not complete the lookup. Check your application key." }
        val results = response.optJSONArray("results") ?: error("No matching recording found")
        val candidates = (0 until results.length()).map { results.getJSONObject(it) }
            .filter { it.optDouble("score") >= 0.7 && (it.optJSONArray("recordings")?.length() ?: 0) > 0 }
        val match = candidates.maxByOrNull { it.optDouble("score") } ?: error("No confident match found; your existing tags were kept")
        val recording = match.getJSONArray("recordings").getJSONObject(0)
        val artists = recording.optJSONArray("artists")
        val artist = if (artists == null) song.artist else (0 until artists.length()).joinToString(", ") { artists.getJSONObject(it).getString("name") }
        val release = recording.optJSONArray("releases")?.optJSONObject(0)?.optString("id")?.takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) }
        val row = MetadataRow(hash, recording.optString("title", song.title), artist, recording.getString("id"),
            release?.let { "https://coverartarchive.org/release/$it/front-500" })
        repository.dao.saveMetadata(row)
        row
    }

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")
    private fun request(url: String, body: String): JSONObject {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setRequestProperty("User-Agent", "Dontree/0.1 (https://github.com/jimskin03/dontree)")
            connection.outputStream.use { it.write(body.toByteArray()) }
            check(connection.responseCode == 200) { "Metadata service returned HTTP ${connection.responseCode}; please try later" }
            val bytes = connection.inputStream.use { it.readLimited(2_000_000) }
            JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}
