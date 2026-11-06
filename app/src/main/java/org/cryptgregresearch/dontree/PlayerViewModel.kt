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

data class AgentState(val busy: Boolean = false, val progress: String = "", val lyrics: Lyrics? = null, val error: String? = null)

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
    val agentState = MutableStateFlow(AgentState())
    val modelReady = MutableStateFlow(model.ready)
    val importing = MutableStateFlow(false)
    val scanning = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val metadata = MutableStateFlow<MetadataRow?>(null)
    val identifying = MutableStateFlow(false)
    var pendingLyricsTarget: Song? = null
    private var agentJob: Job? = null
    private var importJob: Job? = null
    private var identifyJob: Job? = null
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            combine(player.state.map { it.id }.distinctUntilChanged(), songs) { id, library ->
                id to library.firstOrNull { it.id.toString() == id }
            }.distinctUntilChangedBy { (id, song) -> id to song?.revision }.collectLatest { (_, song) ->
                stopAgent(); agentState.value = AgentState(); metadata.value = null
                identifyJob?.cancel(); identifying.value = false
                if (song != null) {
                    val cached = app.music.dao.lyrics("import:${song.revision}")
                    if (cached != null && !agentState.value.busy) agentState.value = AgentState(lyrics = LyricsJson.decode(cached.json))
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
        if (agentState.value.busy || importing.value) return
        val song = currentSong() ?: return
        val previous = agentJob
        agentState.value = AgentState(busy = true, progress = "Preparing local analysis…")
        agentJob = viewModelScope.launch {
            previous?.join()
            try {
                val lyrics = agent.onAttach(PlaybackSession(song, preferences.value.translation)) { progress ->
                    if (player.state.value.id == song.id.toString()) agentState.value = agentState.value.copy(progress = progress)
                }
                ensureActive()
                if (player.state.value.id == song.id.toString()) agentState.value = AgentState(lyrics = lyrics)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (player.state.value.id == song.id.toString()) agentState.value = AgentState(error = e.message ?: "Local analysis failed") }
        }
    }
    fun stopAgent() {
        agent.onDetach(); agentJob?.cancel()
        agentState.value = agentState.value.copy(busy = false, progress = "")
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
        if (!value) { identifyJob?.cancel(); metadata.value = null }
        viewModelScope.launch { app.settings.online(value) }
    }
    fun apiKey(value: String) { viewModelScope.launch { app.settings.apiKey(value) } }
    fun romanization(value: Boolean) { viewModelScope.launch { app.settings.romanization(value) } }
    fun translation(value: Boolean) { viewModelScope.launch { app.settings.translation(value) } }
    fun clearLyrics() {
        stopAgent()
        viewModelScope.launch { agentJob?.join(); app.music.dao.clearLyrics(); agentState.value = AgentState(); message.value = "Saved lyrics cleared" }
    }
    override fun onCleared() { agent.onDetach(); player.close(); super.onCleared() }
}
