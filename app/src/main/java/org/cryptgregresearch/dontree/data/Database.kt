package org.cryptgregresearch.dontree.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.cryptgregresearch.dontree.core.*
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "songs")
data class SongRow(
    @PrimaryKey val id: Long, val uri: String, val title: String, val artist: String,
    val album: String, val durationMs: Long, val albumId: Long, val filename: String,
    val modified: Long, val size: Long,
) {
    fun song() = Song(id, uri, title, artist, album, durationMs, albumId, filename, modified, size)
}

@Entity(tableName = "lyrics")
data class LyricsRow(@PrimaryKey val key: String, val json: String)

@Entity(tableName = "metadata")
data class MetadataRow(@PrimaryKey val hash: String, val title: String, val artist: String, val recordingId: String, val coverUrl: String?)

@Dao
interface MusicDao {
    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE") fun songs(): Flow<List<SongRow>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSongs(songs: List<SongRow>)
    @Query("DELETE FROM songs") suspend fun clearSongs()
    @Transaction suspend fun replaceSongs(songs: List<SongRow>) { clearSongs(); insertSongs(songs) }
    @Query("SELECT * FROM lyrics WHERE `key` = :key") suspend fun lyrics(key: String): LyricsRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveLyrics(row: LyricsRow)
    @Query("DELETE FROM lyrics") suspend fun clearLyrics()
    @Query("SELECT * FROM metadata WHERE hash = :hash") suspend fun metadata(hash: String): MetadataRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveMetadata(row: MetadataRow)
}

@Database(entities = [SongRow::class, LyricsRow::class, MetadataRow::class], version = 1, exportSchema = true)
abstract class MusicDatabase : RoomDatabase() { abstract fun music(): MusicDao }

object LyricsJson {
    fun encode(lyrics: Lyrics): String = JSONObject().put("source", lyrics.source).put("instrumental", lyrics.instrumental)
        .put("beats", JSONArray(lyrics.beats)).put("lines", JSONArray().apply {
            lyrics.lines.forEach { line -> put(JSONObject().put("start", line.startMs).put("end", line.endMs)
                .put("text", line.text).put("romanization", line.romanization).put("translation", line.translation)
                .put("words", JSONArray().apply { line.words.forEach { put(JSONObject().put("start", it.startMs).put("end", it.endMs).put("text", it.text)) } })) }
        }).toString()

    fun decode(json: String): Lyrics {
        val root = JSONObject(json)
        val lines = root.getJSONArray("lines")
        val beats = root.optJSONArray("beats") ?: JSONArray()
        return Lyrics((0 until lines.length()).map { i ->
            val line = lines.getJSONObject(i)
            val words = line.optJSONArray("words") ?: JSONArray()
            LyricLine(line.getLong("start"), line.getLong("end"), line.getString("text"),
                line.optString("romanization"), line.optString("translation"), (0 until words.length()).map { j ->
                    val word = words.getJSONObject(j); Word(word.getLong("start"), word.getLong("end"), word.getString("text"))
                })
        }, (0 until beats.length()).map { beats.getLong(it) }, root.getString("source"), root.optBoolean("instrumental"))
    }
}
