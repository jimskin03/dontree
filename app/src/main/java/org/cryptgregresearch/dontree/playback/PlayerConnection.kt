package org.cryptgregresearch.dontree.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.cryptgregresearch.dontree.core.Song

data class QueueItem(val id: String, val title: String, val artist: String)
data class PlayerState(
    val connected: Boolean = false, val id: String? = null, val title: String = "", val artist: String = "",
    val art: String? = null, val playing: Boolean = false, val position: Long = 0, val duration: Long = 0,
    val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueItem> = emptyList(), val index: Int = -1, val error: String? = null,
)

@UnstableApi
class PlayerConnection(context: Context, private val scope: CoroutineScope) : AutoCloseable {
    val state = MutableStateFlow(PlayerState())
    private val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    private var controller: MediaController? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { update() }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) { state.value = state.value.copy(error = "Unable to play this file. It may have moved or use an unsupported codec.") }
    }
    private var ticker: Job? = null
    init {
        future.addListener({
            runCatching { future.get() }.onSuccess { player ->
                controller = player; player.addListener(listener); update()
                ticker = scope.launch { while (isActive) { update(positionOnly = true); delay(100) } }
            }.onFailure { state.value = state.value.copy(error = "Could not connect to playback. Reopen Dontree to retry.") }
        }, ContextCompat.getMainExecutor(context))
    }
    private fun update(positionOnly: Boolean = false) {
        val p = controller ?: return
        if (positionOnly) {
            state.value = state.value.copy(position = p.currentPosition.coerceAtLeast(0), duration = p.duration.coerceAtLeast(0))
            return
        }
        state.value = PlayerState(true, p.currentMediaItem?.mediaId, p.mediaMetadata.title?.toString() ?: "",
            p.mediaMetadata.artist?.toString() ?: "", p.mediaMetadata.artworkUri?.toString(), p.isPlaying,
            p.currentPosition.coerceAtLeast(0), p.duration.coerceAtLeast(0), p.shuffleModeEnabled, p.repeatMode,
            (0 until p.mediaItemCount).map { i -> p.getMediaItemAt(i).let { QueueItem(it.mediaId, it.mediaMetadata.title.toString(), it.mediaMetadata.artist.toString()) } },
            p.currentMediaItemIndex, if (p.playerError != null) state.value.error else null)
    }
    fun play(songs: List<Song>, index: Int) { controller?.run {
        if (songs.isEmpty()) return
        setMediaItems(songs.map { it.mediaItem() }, index.coerceIn(songs.indices), 0); prepare(); play()
    } }
    fun toggle() { controller?.run { if (isPlaying) pause() else { if (playbackState == Player.STATE_ENDED) seekToDefaultPosition(); prepare(); play() } } }
    fun seek(ms: Long) { controller?.seekTo(ms.coerceAtLeast(0)) }
    fun previous() { controller?.seekToPrevious() }
    fun next() { controller?.seekToNext() }
    fun shuffle() { controller?.run { shuffleModeEnabled = !shuffleModeEnabled } }
    fun repeat() { controller?.run { repeatMode = (repeatMode + 1) % 3 } }
    fun queuePlay(index: Int) { controller?.run { if (index in 0 until mediaItemCount) { seekToDefaultPosition(index); play() } } }
    fun remove(index: Int) { controller?.run { if (index in 0 until mediaItemCount) removeMediaItem(index) } }
    fun move(from: Int, to: Int) { controller?.run { if (from in 0 until mediaItemCount && to in 0 until mediaItemCount) moveMediaItem(from, to) } }
    override fun close() { ticker?.cancel(); controller?.removeListener(listener); MediaController.releaseFuture(future); controller = null }
}
