package org.cryptgregresearch.dontree

import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.cryptgregresearch.dontree.agent.AudioDecoder
import org.cryptgregresearch.dontree.agent.NativeAudio
import org.cryptgregresearch.dontree.core.*
import org.cryptgregresearch.dontree.data.*
import org.cryptgregresearch.dontree.playback.PlaybackService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sin

@UnstableApi
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun fixture(name: String): File = File(context.cacheDir, name).also { file ->
        instrumentation.context.assets.open(name).use { source -> file.outputStream().use(source::copyTo) }
    }

    @Test fun appLaunchesAndMediaSessionPlaysMp3() {
        val file = fixture("sine.mp3")
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            assertEquals(Lifecycle.State.RESUMED, activity.state)
            val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            val controller = future.get(20, TimeUnit.SECONDS)
            val ended = CountDownLatch(1)
            var failure: PlaybackException? = null
            try {
                instrumentation.runOnMainSync {
                    controller.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended.countDown() }
                        override fun onPlayerError(error: PlaybackException) { failure = error; ended.countDown() }
                    })
                    controller.repeatMode = Player.REPEAT_MODE_OFF
                    controller.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    controller.prepare(); controller.play()
                }
                assertTrue("Playback did not finish", ended.await(20, TimeUnit.SECONDS))
                assertNull("Playback failed: $failure", failure)
            } finally {
                instrumentation.runOnMainSync { controller.stop(); controller.clearMediaItems(); controller.release() }
            }
        }
    }

    @Test fun decoderReadsBothRequiredFormats() = runBlocking {
        for (name in listOf("sine.mp3", "sine.ogg")) {
            val chunks = AudioDecoder(context).chunks(Uri.fromFile(fixture(name)).toString()).toList()
            val count = chunks.sumOf { it.samples.size }
            assertTrue("Unexpected duration for $name: $count samples", count in 30_000..36_000)
            assertEquals(0L, chunks.first().startMs)
            assertTrue("Decoded $name is silent", chunks.any { chunk -> chunk.samples.any { kotlin.math.abs(it) > 0.01f } })
        }
    }

    @Test fun nativeFingerprintLibraryLoadsAndProcessesAudio() {
        val pcm = FloatArray(20 * 16000) { i -> (sin(2 * Math.PI * (220 + i / 16000 * 20) * i / 16000) * 0.4).toFloat() }
        assertTrue(NativeAudio.fingerprint(pcm).isNotBlank())
    }

    @Test fun roomRoundTripsLyricsAndReplacesLibraryAtomically() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).build()
        try {
            val lyrics = Lyrics(listOf(LyricLine(100, 900, "เพลง", "phleng", "song", listOf(Word(100, 900, "เพลง")))), listOf(100), "Test")
            db.music().saveLyrics(LyricsRow("test", LyricsJson.encode(lyrics)))
            assertEquals(lyrics, LyricsJson.decode(db.music().lyrics("test")!!.json))
            db.music().replaceSongs(listOf(SongRow(1, "content://test/1", "Song", "Artist", "Album", 1000, 1, "song.mp3", 1, 10)))
            assertEquals(1, db.music().songs().first().size)
            db.music().replaceSongs(emptyList())
            assertTrue(db.music().songs().first().isEmpty())
            assertNotNull(db.music().lyrics("test"))
        } finally { db.close() }
    }
}
