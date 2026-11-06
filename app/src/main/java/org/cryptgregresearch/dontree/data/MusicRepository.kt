package org.cryptgregresearch.dontree.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.cryptgregresearch.dontree.core.MusicFiles
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class MusicRepository(private val context: Context, val dao: MusicDao) {
    val songs = dao.songs().map { rows -> rows.map { it.song() } }
    suspend fun scan() = withContext(Dispatchers.IO) {
        val result = mutableListOf<SongRow>()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val columns = arrayOf("_id", "_display_name", "title", "artist", "album", "duration", "album_id", "date_modified", "_size")
        context.contentResolver.query(collection, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                coroutineContext.ensureActive()
                val name = cursor.getString(1) ?: continue
                if (!MusicFiles.supported(name)) continue
                val duration = cursor.getLong(5)
                if (duration <= 0) continue
                val id = cursor.getLong(0)
                result += SongRow(id, ContentUris.withAppendedId(collection, id).toString(),
                    MusicFiles.title(cursor.getString(2), name), MusicFiles.artist(cursor.getString(3)),
                    cursor.getString(4)?.takeUnless { it == "<unknown>" } ?: "Unknown album", duration,
                    cursor.getLong(6), name, cursor.getLong(7), cursor.getLong(8))
            }
        } ?: error("Android could not read the audio library. Please try again.")
        dao.replaceSongs(result)
    }

    suspend fun hash(uri: String): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = stream.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        } ?: error("This audio file is no longer available")
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
