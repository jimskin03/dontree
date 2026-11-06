package org.cryptgregresearch.dontree.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.cryptgregresearch.dontree.DontreeApp
import org.cryptgregresearch.dontree.MainActivity
import org.cryptgregresearch.dontree.core.Song
import org.json.JSONArray
import org.json.JSONObject

fun Song.mediaItem(): MediaItem = MediaItem.Builder().setMediaId(id.toString()).setUri(uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
        .setArtworkUri(android.net.Uri.parse("content://media/external/audio/albumart/$albumId")).build()).build()

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var saveJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).build()
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) scheduleSave()
            }
        })
        scope.launch {
            val saved = (application as DontreeApp).settings.queue.first()
            if (saved != null && player.mediaItemCount == 0) runCatching {
                val root = JSONObject(saved); val items = root.getJSONArray("items")
                val restored = (0 until items.length()).map { i ->
                    val item = items.getJSONObject(i)
                    MediaItem.Builder().setMediaId(item.getString("id")).setUri(item.getString("uri"))
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(item.getString("title")).setArtist(item.getString("artist"))
                            .setArtworkUri(item.optString("art").takeIf { it.isNotEmpty() }?.let(android.net.Uri::parse)).build()).build()
                }
                if (restored.isNotEmpty()) {
                    player.setMediaItems(restored, root.optInt("index").coerceIn(restored.indices), root.optLong("position").coerceAtLeast(0))
                    player.repeatMode = root.optInt("repeat").coerceIn(0, 2)
                    player.shuffleModeEnabled = root.optBoolean("shuffle")
                    // Restoring the queue must never start audio unexpectedly.
                    player.prepare()
                }
            }
        }
        scope.launch { while (isActive) { delay(5000); if (player.isPlaying) scheduleSave() } }
    }

    private fun scheduleSave() {
        val player = session?.player ?: return
        val json = JSONObject().put("index", player.currentMediaItemIndex.coerceAtLeast(0)).put("position", player.currentPosition)
            .put("repeat", player.repeatMode).put("shuffle", player.shuffleModeEnabled).put("items", JSONArray().apply {
                repeat(player.mediaItemCount) { i ->
                    val item = player.getMediaItemAt(i)
                    put(JSONObject().put("id", item.mediaId).put("uri", item.localConfiguration?.uri.toString())
                        .put("title", item.mediaMetadata.title?.toString() ?: "Unknown track").put("artist", item.mediaMetadata.artist?.toString() ?: "Unknown artist")
                        .put("art", item.mediaMetadata.artworkUri?.toString() ?: ""))
                }
            }).toString()
        saveJob?.cancel()
        saveJob = scope.launch { (application as DontreeApp).settings.saveQueue(json) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session?.takeIf { controllerInfo.uid == android.os.Process.myUid() || controllerInfo.isTrusted }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (session?.player?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        scope.cancel()
        super.onDestroy()
    }
}
