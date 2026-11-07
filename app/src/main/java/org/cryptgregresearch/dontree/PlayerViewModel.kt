package org.cryptgregresearch.dontree

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.cryptgregresearch.dontree.agent.*
import org.cryptgregresearch.dontree.core.*
import org.cryptgregresearch.dontree.data.*
import org.cryptgregresearch.dontree.playback.*

data class AgentState(val busy: Boolean = false, val progress: String = "", val fraction: Float = 0f, val lyrics: Lyrics? = null, val error: String? = null)

@UnstableApi
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DontreeApp
    val songs = app.music.songs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val preferences = app.settings.values.stateIn(viewModelScope, SharingStarted.Eagerly, Preferences())
    val player = PlayerConnection(app, viewModelScope)
    val model = ModelStore(app)
    private val decoder = AudioDecoder(app)
    private val agent = LocalLyricsAgent(app.music, model, decoder)
    private val identifier = OnlineMetadata(app.music, decoder)
    private val lyricsSearch = OnlineLyrics()
    val agentState = MutableStateFlow(AgentState())
    val modelReady = MutableStateFlow(model.ready)
    val importing = MutableStateFlow(false)
    val scanning = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val metadata = MutableStateFlow<MetadataRow?>(null)
    val identifying = MutableStateFlow(false)
    var pendingLyricsTarget: Song? = null
    private var agentJob: Job? = null
    private var agentSongId: String? = null
    private val agentUi = Any()
    private var importJob: Job? = null
    private var identifyJob: Job? = null
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            combine(player.state.map { it.id }.distinctUntilChanged(), songs) { id, library ->
                id to library.firstOrNull { it.id.toString() == id }
            }.distinctUntilChangedBy { (id, song) -> id to song?.revision }.collectLatest { (_, song) ->
                // Keep an analysis that is already running. Cancelling here used to drop it when
                // playback moved on, which is while the first pass was still sitting at 0%.
                val sameRunningSong = synchronized(agentUi) { agentSongId != null && agentSongId == song?.id?.toString() && agentJob?.isActive == true }
                if (sameRunningSong) return@collectLatest
                metadata.value = null
                identifyJob?.cancel(); identifying.value = false
                val cached = song?.let { app.music.dao.lyrics("import:${it.revision}")
                    ?: app.music.dao.lyrics("resolved:${it.revision}") ?: app.music.dao.lyrics("generated:${it.revision}") }
                synchronized(agentUi) {
                    if (agentSongId != null && agentSongId == song?.id?.toString() && agentJob?.isActive == true) return@collectLatest
                    agentState.value = if (cached != null) AgentState(lyrics = LyricsJson.decode(cached.json)) else AgentState()
                }
            }
        }
    }
    fun currentSong(): Song? = songs.value.firstOrNull { it.id.toString() == player.state.value.id }
    fun refresh() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            scanning.value = true
            try { app.music.scan() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "Could not scan music" }
            finally { scanning.value = false }
        }
    }
    fun startAgent() {
        val options = preferences.value
        if (!options.lyricsOnline) {
            viewModelScope.launch { app.settings.lyricsOnline(true) }
        }
        runLyrics(options.copy(lyricsOnline = true), onlineSearch = true)
    }
    fun onLyricsOpen() {
        viewModelScope.launch {
            val song = currentSong() ?: return@launch
            if (agentState.value.busy || agentState.value.lyrics != null) return@launch
            val cached = app.music.dao.lyrics("import:${song.revision}") ?: app.music.dao.lyrics("resolved:${song.revision}")
            if (currentSong()?.revision != song.revision || agentState.value.busy) return@launch
            if (cached != null) agentState.value = AgentState(lyrics = LyricsJson.decode(cached.json))
            else if (preferences.value.lyricsOnline) startAgent()
        }
    }
    fun localFallback() { runLyrics(preferences.value, onlineSearch = false) }
    fun syncLyrics() {
        val reference = agentState.value.lyrics ?: return
        runLyrics(preferences.value, onlineSearch = false, reference = reference)
    }
    private fun runLyrics(options: Preferences, onlineSearch: Boolean, reference: Lyrics? = null) {
        if (agentState.value.busy || importing.value) return
        val song = currentSong() ?: return
        val previous = agentJob
        agent.onDetach()
        previous?.cancel()
        synchronized(agentUi) {
            agentSongId = song.id.toString()
            agentState.value = AgentState(busy = true, progress = "Preparing lyrics…", lyrics = reference)
        }
        agentJob = viewModelScope.launch {
            previous?.join()
            try {
                val report: (AgentUpdate) -> Unit = { update ->
                    synchronized(agentUi) {
                        if (agentSongId == song.id.toString() && player.state.value.id == song.id.toString()) {
                            agentState.value = AgentState(busy = true, progress = update.progress, fraction = update.fraction, lyrics = update.lyrics)
                        }
                    }
                }
                val session = PlaybackSession(song, options.translation)
                val lyrics = when {
                    reference != null -> agent.align(session, reference, report)
                    !onlineSearch -> agent.onAttach(session, report)
                    else -> LyricsWorkflow(
                        identify = { original ->
                            val cached = app.music.dao.metadata(app.music.hash(original.uri))
                            val match = cached ?: if (options.online && options.acoustIdKey.isNotBlank()) {
                                try { identifier.identify(original, options.acoustIdKey) }
                                catch (error: CancellationException) { throw error }
                                catch (error: Exception) {
                                    if (original.artist == "Unknown artist") throw error
                                    null
                                }
                            } else null
                            if (player.state.value.id == song.id.toString()) metadata.value = match
                            original.copy(title = match?.title ?: original.title, artist = match?.artist ?: original.artist)
                        },
                        search = { matched ->
                            val found = lyricsSearch.find(matched, LyricsServiceConfig(options.lyricsEndpoint, options.lyricsApiKey))
                            if (found != null) {
                                val decorated = withContext(Dispatchers.Default) {
                                    val language = ThaiLanguagePack()
                                    found.copy(lines = found.lines.map { it.copy(romanization = language.romanize(it.text)) })
                                }
                                app.music.dao.saveLyrics(LyricsRow("resolved:${song.revision}", LyricsJson.encode(decorated)))
                                report(AgentUpdate("Lyrics found", 0f, decorated))
                                decorated
                            } else null
                        },
                        align = { matched, found -> agent.align(PlaybackSession(matched, false), found, report) },
                        transcribe = { agent.onAttach(session, report) },
                        hasModel = { model.ready },
                    ).resolve(song) { stage ->
                        synchronized(agentUi) {
                            if (agentSongId == song.id.toString() && player.state.value.id == song.id.toString()) {
                                agentState.value = agentState.value.copy(busy = true, progress = stage)
                            }
                        }
                    }
                }
                ensureActive()
                app.music.dao.saveLyrics(LyricsRow("resolved:${song.revision}", LyricsJson.encode(lyrics)))
                synchronized(agentUi) {
                    if (agentSongId == song.id.toString() && player.state.value.id == song.id.toString()) agentState.value = AgentState(lyrics = lyrics)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                synchronized(agentUi) {
                    if (agentSongId == song.id.toString() && player.state.value.id == song.id.toString()) {
                        agentState.value = AgentState(lyrics = agentState.value.lyrics, error = e.message ?: "Lyrics lookup failed. Try again or use the local fallback.")
                    }
                }
            }
        }
    }
    fun stopAgent() {
        synchronized(agentUi) { agentSongId = null }
        agent.onDetach(); agentJob?.cancel()
        agentState.value = agentState.value.copy(busy = false, progress = "", fraction = 0f)
    }
    fun importModel(uri: Uri) {
        if (importJob?.isActive == true) return
        stopAgent()
        importing.value = true
        importJob = viewModelScope.launch {
            try { agentJob?.join(); model.import(uri); modelReady.value = model.ready; message.value = "Whisper model ready. All lyrics processing stays on this device." }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "Model import failed" }
            finally { importing.value = false }
        }
    }
    fun importLyrics(uri: Uri, song: Song) {
        stopAgent()
        viewModelScope.launch {
            try {
                val lyrics = withContext(Dispatchers.IO) {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readLimited(2_000_000) } ?: error("Cannot read lyrics file")
                    LrcParser.parse(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"), song.durationMs)
                }
                app.music.dao.saveLyrics(LyricsRow("import:${song.revision}", LyricsJson.encode(lyrics)))
                if (player.state.value.id == song.id.toString()) agentState.value = AgentState(lyrics = lyrics)
                message.value = "Lyrics imported for ${song.title}"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "Lyrics import failed" }
        }
    }
    fun identify() {
        val song = currentSong() ?: return
        if (!preferences.value.online) { message.value = "Enable online metadata in Settings first"; return }
        if (identifying.value) return
        identifying.value = true
        identifyJob = viewModelScope.launch {
            try {
                val result = identifier.identify(song, preferences.value.acoustIdKey)
                ensureActive()
                if (player.state.value.id == song.id.toString()) { metadata.value = result; message.value = "Recording found: ${result.title} — ${result.artist}" }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "No recording found" }
            finally { identifying.value = false }
        }
    }
    fun favorite(id: Long) { viewModelScope.launch { app.settings.favorite(id) } }
    fun online(value: Boolean) {
        if (!value) { identifyJob?.cancel(); metadata.value = null; stopAgent() }
        viewModelScope.launch { app.settings.online(value) }
    }
    fun apiKey(value: String) { viewModelScope.launch { app.settings.apiKey(value) } }
    fun lyricsOnline(value: Boolean) {
        if (!value) stopAgent()
        viewModelScope.launch { app.settings.lyricsOnline(value) }
    }
    fun lyricsService(endpoint: String, key: String) {
        viewModelScope.launch {
            try {
                LyricsServiceConfig(endpoint, key).validate()
                stopAgent()
                app.settings.lyricsService(endpoint, key)
                message.value = "Lyrics service saved"
            } catch (error: IllegalArgumentException) { message.value = error.message }
        }
    }
    fun romanization(value: Boolean) { viewModelScope.launch { app.settings.romanization(value) } }
    fun translation(value: Boolean) { viewModelScope.launch { app.settings.translation(value) } }
    fun clearLyrics() {
        stopAgent()
        viewModelScope.launch { agentJob?.join(); app.music.dao.clearLyrics(); agentState.value = AgentState(); message.value = "Saved lyrics cleared" }
    }
    override fun onCleared() { agent.onDetach(); player.close(); super.onCleared() }
}
