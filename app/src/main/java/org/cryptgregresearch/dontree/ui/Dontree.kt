package org.cryptgregresearch.dontree.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import org.cryptgregresearch.dontree.AgentState
import org.cryptgregresearch.dontree.BuildConfig
import org.cryptgregresearch.dontree.PlayerViewModel
import org.cryptgregresearch.dontree.core.*
import org.cryptgregresearch.dontree.data.Preferences
import org.cryptgregresearch.dontree.playback.PlayerState

private val Purple = Color(0xFFB6A0FF)
private val Mint = Color(0xFF90E0C4)
private val Background = Color(0xFF101116)
private val Panel = Color(0xFF1C1D26)
private val Muted = Color(0xFFA8A5B7)
private const val TinyModelUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin?download=true"

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun Dontree(vm: PlayerViewModel, permission: Boolean, onPermission: () -> Unit, onAppSettings: () -> Unit, onModelImport: () -> Unit, onLyricsImport: () -> Unit) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val playback by vm.player.state.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val agent by vm.agentState.collectAsStateWithLifecycle()
    val modelReady by vm.modelReady.collectAsStateWithLifecycle()
    val modelImporting by vm.importing.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("library") }
    var settingsBackScreen by rememberSaveable { mutableStateOf("library") }
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(lyricsOpen, playback.id) { if (lyricsOpen) vm.onLyricsOpen() }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    LaunchedEffect(playback.error) { playback.error?.let { snackbar.showSnackbar(it) } }
    BackHandler(screen != "library") {
        screen = when (screen) {
            "queue" -> "player"
            "settings" -> settingsBackScreen
            else -> "library"
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Purple, secondary = Mint, background = Background, surface = Background, surfaceVariant = Panel, onSurfaceVariant = Muted)) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (screen == "library") Column(Modifier.navigationBarsPadding()) {
                    if (playback.id != null) MiniPlayer(playback, { screen = "player" }, vm.player::toggle, vm.player::next)
                    NavigationBar(containerColor = Background, windowInsets = WindowInsets(0)) {
                        NavigationBarItem(true, {}, { Icon(Icons.Rounded.LibraryMusic, null) }, label = { Text("My music") })
                        NavigationBarItem(false, {
                            settingsBackScreen = "library"
                            screen = "settings"
                        }, { Icon(Icons.Rounded.Settings, null) }, label = { Text("Settings") })
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    "library" -> Library(songs, preferences, playback, scanning, permission, onPermission, onAppSettings,
                        vm::refresh, { list, i -> vm.player.play(list, i) }, vm::favorite)
                    "player" -> NowPlaying(vm, playback, preferences, agent, { screen = "library" }, { screen = "queue" }, { lyricsOpen = true }, onLyricsImport)
                    "queue" -> Queue(playback, { screen = "player" }, vm.player::queuePlay, vm.player::remove, vm.player::move)
                    "settings" -> SettingsScreen(vm, preferences, modelReady, modelImporting, { screen = settingsBackScreen }, onModelImport)
                }
            }
        }
        if (lyricsOpen) ModalBottomSheet(onDismissRequest = { lyricsOpen = false }, containerColor = Background) {
            LyricsPanel(
                agent = agent,
                player = playback,
                prefs = preferences,
                modelReady = modelReady,
                modelImporting = modelImporting,
                setupModel = {
                    lyricsOpen = false
                    settingsBackScreen = "player"
                    screen = "settings"
                },
                start = vm::startAgent,
                localFallback = vm::localFallback,
                syncLyrics = vm::syncLyrics,
                stop = vm::stopAgent,
                importLyrics = onLyricsImport,
                seek = vm.player::seek,
            )
        }
    }
}

@Composable
private fun Library(songs: List<Song>, prefs: Preferences, player: PlayerState, scanning: Boolean, permission: Boolean,
                    requestPermission: () -> Unit, appSettings: () -> Unit, refresh: () -> Unit,
                    play: (List<Song>, Int) -> Unit, favorite: (Long) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var favorites by rememberSaveable { mutableStateOf(false) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    val filtered = remember(songs, query, favorites, prefs.favorites) {
        songs.filter { (query.isBlank() || "${it.title} ${it.artist} ${it.album}".contains(query, true)) && (!favorites || it.id.toString() in prefs.favorites) }
    }
    val grouped = remember(filtered, tab) { filtered.groupBy { if (tab == 1) it.album else it.artist }.toSortedMap() }
    val visible = if (tab == 0 || group == null) filtered else grouped[group].orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Column(Modifier.padding(horizontal = 24.dp).padding(top = 28.dp, bottom = 16.dp)) {
                Text("YOUR EVERYDAY SOUNDTRACK", color = Mint, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("My music", fontSize = 38.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Action(Icons.Rounded.Refresh, "Rescan music", refresh, enabled = permission && !scanning)
                }
                Text(if (scanning) "Finding your music…" else "${songs.size} tracks · stored on your device", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Find a song, artist or album") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp))
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("Tracks", "Albums", "Artists").forEachIndexed { i, title ->
                        FilterChip(tab == i, { tab = i; group = null }, { Text(title) })
                    }
                    IconToggleButton(favorites, { favorites = it }) { Icon(if (favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorites only", tint = if (favorites) Purple else Muted) }
                }
            }
        }
        if (!permission) item {
            EmptyState(Icons.Rounded.FolderOpen, "Your music belongs here", "Allow audio access to find MP3 and OGG files on this device.", "Find my music", requestPermission)
            TextButton(appSettings, Modifier.fillMaxWidth()) { Text("Open app permissions") }
        } else if (filtered.isEmpty()) item {
            EmptyState(Icons.Rounded.MusicNote, if (query.isNotEmpty() || favorites) "No matching tracks" else "A little quiet in here",
                if (query.isNotEmpty() || favorites) "Try another search or turn off the favorites filter." else "Add MP3 or OGG files to your Music folder, then rescan.", "Rescan music", refresh)
        } else if (tab != 0 && group == null) {
            items(grouped.keys.toList(), key = { it }) { name ->
                val tracks = grouped.getValue(name)
                ListItem(
                    headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${tracks.size} tracks") },
                    leadingContent = { Cover("content://media/external/audio/albumart/${tracks.first().albumId}", Modifier.size(58.dp)) },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    modifier = Modifier.clickable { group = name }.padding(horizontal = 8.dp), colors = ListItemDefaults.colors(containerColor = Background))
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (group != null) Action(Icons.AutoMirrored.Rounded.ArrowBack, "All groups", { group = null })
                    Text(group ?: "All tracks", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton({ play(visible, 0) }, enabled = player.connected) { Icon(Icons.Rounded.PlayArrow, null); Text("Play all") }
                }
            }
            itemsIndexed(visible, key = { _, song -> song.id }) { index, song ->
                SongRow(song, song.id.toString() == player.id, song.id.toString() in prefs.favorites, { play(visible, index) }, { favorite(song.id) }, player.connected)
            }
        }
    }
}

@Composable
private fun SongRow(song: Song, active: Boolean, favorite: Boolean, play: () -> Unit, toggleFavorite: () -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = play).padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover("content://media/external/audio/albumart/${song.albumId}", Modifier.size(56.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(song.title, color = if (active) Purple else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp)); Text(song.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(time(song.durationMs), color = Muted, fontSize = 11.sp)
        Action(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (favorite) "Remove favorite" else "Add favorite", toggleFavorite, tint = if (favorite) Purple else Muted)
    }
}

@Composable
private fun Cover(uri: String?, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Color(0xFF302C48), Color(0xFF1F3537)))), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = Purple.copy(alpha = 0.7f), modifier = Modifier.fillMaxSize(0.4f))
        if (uri != null) AsyncImage(uri, "Album cover", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun MiniPlayer(player: PlayerState, open: () -> Unit, toggle: () -> Unit, next: () -> Unit) {
    Surface(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clickable(onClick = open), color = Panel, shape = RoundedCornerShape(18.dp)) {
        Column {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(player.art, Modifier.size(46.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(player.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(player.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 11.sp)
                }
                Action(if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.playing) "Pause" else "Play", toggle)
                Action(Icons.Rounded.SkipNext, "Next track", next)
            }
            LinearProgressIndicator(progress = { if (player.duration > 0) (player.position.toFloat() / player.duration).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = Purple, trackColor = Panel)
        }
    }
}

@UnstableApi
@Composable
private fun NowPlaying(vm: PlayerViewModel, player: PlayerState, preferences: Preferences, agent: AgentState, back: () -> Unit, queue: () -> Unit, lyrics: () -> Unit, importLyrics: () -> Unit) {
    val metadata by vm.metadata.collectAsStateWithLifecycle()
    val identifying by vm.identifying.collectAsStateWithLifecycle()
    var seek by remember(player.id) { mutableStateOf<Float?>(null) }
    val favorite = player.id in preferences.favorites
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Action(Icons.Rounded.KeyboardArrowDown, "Back to library", back)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NOW PLAYING", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                    Text("Your music, closer", fontSize = 12.sp)
                }
                Action(Icons.AutoMirrored.Rounded.QueueMusic, "Open queue", queue)
            }
            Spacer(Modifier.height(28.dp))
            Cover(if (preferences.online) metadata?.coverUrl ?: player.art else player.art, Modifier.fillMaxWidth().aspectRatio(1f).sizeIn(maxWidth = 420.dp, maxHeight = 420.dp))
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(metadata?.title ?: player.title.ifBlank { "Choose a track" }, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(5.dp)); Text(metadata?.artist ?: player.artist, color = Muted, fontSize = 15.sp)
                }
                Action(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Toggle favorite", { player.id?.toLongOrNull()?.let(vm::favorite) }, tint = if (favorite) Purple else Muted)
            }
            Spacer(Modifier.height(22.dp))
            Slider(seek ?: player.position.toFloat().coerceIn(0f, player.duration.coerceAtLeast(1).toFloat()), { seek = it },
                onValueChangeFinished = { seek?.let { vm.player.seek(it.toLong()) }; seek = null },
                valueRange = 0f..player.duration.coerceAtLeast(1).toFloat(), enabled = player.duration > 0)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(time(seek?.toLong() ?: player.position), color = Muted, fontSize = 12.sp)
                Text(time(player.duration), color = Muted, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Action(Icons.Rounded.Shuffle, "Toggle shuffle", vm.player::shuffle, tint = if (player.shuffle) Purple else Muted)
                Action(Icons.Rounded.SkipPrevious, "Previous track", vm.player::previous)
                FilledIconButton(vm.player::toggle, Modifier.size(76.dp), enabled = player.id != null) {
                    Icon(if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.playing) "Pause" else "Play", Modifier.size(38.dp))
                }
                Action(Icons.Rounded.SkipNext, "Next track", vm.player::next)
                Action(if (player.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat: ${listOf("off", "one", "all")[player.repeat]}", vm.player::repeat, tint = if (player.repeat != Player.REPEAT_MODE_OFF) Purple else Muted)
            }
            Surface(onClick = lyrics, color = Panel, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Mint)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text("Lyrics agent", fontWeight = FontWeight.SemiBold)
                        Text(if (agent.busy) agent.progress else "Find lyrics · sync with your song", color = Muted, fontSize = 12.sp)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Muted)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(importLyrics, enabled = player.id != null) { Text("Import LRC") }
                TextButton(vm::identify, enabled = player.id != null && !identifying) { Text(if (identifying) "Identifying…" else "Identify song") }
            }
            Text("${if (preferences.online) "Online metadata enabled" else "Offline by default"} · Local lyrics processing", color = Muted, fontSize = 10.sp)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Queue(player: PlayerState, back: () -> Unit, play: (Int) -> Unit, remove: (Int) -> Unit, move: (Int, Int) -> Unit) {
    Column {
        Header("Up next", back)
        Text("${player.queue.size} tracks${if (player.shuffle) " · shuffle is on" else ""}", color = Muted, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn {
            itemsIndexed(player.queue) { index, item ->
                Row(Modifier.fillMaxWidth().clickable { play(index) }.padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (index == player.index) "♪" else (index + 1).toString(), color = if (index == player.index) Purple else Muted, modifier = Modifier.width(32.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = if (index == player.index) Purple else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Action(Icons.Rounded.KeyboardArrowUp, "Move ${item.title} up", { move(index, index - 1) }, enabled = index > 0)
                    Action(Icons.Rounded.Close, "Remove ${item.title}", { remove(index) })
                }
            }
        }
    }
}

@Composable
private fun LyricsPanel(
    agent: AgentState,
    player: PlayerState,
    prefs: Preferences,
    modelReady: Boolean,
    modelImporting: Boolean,
    setupModel: () -> Unit,
    start: () -> Unit,
    localFallback: () -> Unit,
    syncLyrics: () -> Unit,
    stop: () -> Unit,
    importLyrics: () -> Unit,
    seek: (Long) -> Unit,
) {
    val lines = agent.lyrics?.lines.orEmpty()
    val active = Timeline.activeIndex(lines, player.position)
    val list = rememberLazyListState()
    val beat = agent.lyrics?.beats?.let { beats ->
        val point = beats.binarySearch(player.position).let { if (it >= 0) it else -it - 2 }
        point >= 0 && player.position - beats[point] in 0..140
    } ?: false
    val pulse by animateFloatAsState(if (beat && player.playing) 1.16f else 1f, label = "beat")
    LaunchedEffect(active) { if (active >= 0 && !list.isScrollInProgress) list.animateScrollToItem(active) }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Mint, modifier = Modifier.scale(pulse))
            Text("Lyrics agent", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Text("LYRICS", color = Mint, fontSize = 9.sp, letterSpacing = 1.sp)
        }
        Text(agent.lyrics?.source ?: "Match the song, then find its lyrics", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 10.dp))
        agent.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        if (agent.busy) {
            if (agent.fraction > 0f) LinearProgressIndicator(progress = { agent.fraction }, modifier = Modifier.fillMaxWidth(), color = Purple, trackColor = Panel)
            else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Purple, trackColor = Panel)
            Text(agent.progress, color = Muted, modifier = Modifier.padding(vertical = 16.dp))
        }
        if (lines.isEmpty() && agent.busy) {
            Text("Finding lyrics or syncing them with this recording. Keep Dontree open.", color = Muted)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(stop) { Text("Detach agent") }
        } else if (lines.isEmpty()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                Text(if (agent.lyrics?.instrumental == true) "Instrumental recording" else "Find this song's lyrics", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        modelImporting -> "Wait for the model import to finish in Settings."
                        agent.lyrics?.instrumental == true -> "The lyrics service marks this recording as instrumental. You can search again if this is a different version."
                        !prefs.lyricsOnline -> "Find existing lyrics online using the song title and artist. Song recognition also sends a fingerprint if enabled in Settings."
                        else -> "Existing lyrics come first. A local model adds timing to untimed lyrics and can transcribe the song if no lyrics are found."
                    },
                    color = Muted,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = start,
                    enabled = !modelImporting && player.id != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(when {
                        modelImporting -> "Importing model…"
                        else -> "Find lyrics online"
                    })
                }
                TextButton(importLyrics, modifier = Modifier.fillMaxWidth()) { Text("Import a timed LRC file") }
                if (modelReady) {
                    TextButton(localFallback, enabled = !modelImporting, modifier = Modifier.fillMaxWidth()) { Text("Use local transcription fallback") }
                } else {
                    TextButton(setupModel, modifier = Modifier.fillMaxWidth()) { Text("Set up local sync and fallback") }
                }
            }
        } else {
            LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 16.dp)) {
                itemsIndexed(lines) { index, line ->
                    val color by animateColorAsState(if (index == active || line.startMs < 0) Color.White else Muted.copy(alpha = 0.65f), label = "lyric")
                    Column(Modifier.fillMaxWidth().clickable(enabled = line.startMs >= 0 && line.endMs > line.startMs) { seek(line.startMs) }.padding(vertical = 16.dp)) {
                        Text(line.text, color = color, fontSize = if (index == active) 27.sp else 23.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
                        if (prefs.romanization && line.romanization.isNotEmpty()) Text(line.romanization, color = if (index == active) Purple else Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                        if (prefs.translation && line.translation.isNotEmpty()) Text(line.translation, color = if (index == active) Mint else Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
            if (agent.busy) {
                OutlinedButton(stop, modifier = Modifier.padding(bottom = 16.dp)) { Text("Detach agent") }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(start, enabled = !modelImporting) { Text(if (prefs.lyricsOnline) "Find lyrics again" else "Find lyrics online") }
                    TextButton(importLyrics) { Text("Replace with LRC") }
                }
                if (agent.lyrics?.fullySynced == false) {
                    TextButton(if (modelReady) syncLyrics else setupModel, enabled = !modelImporting) {
                        Text(if (modelReady) "Sync with local model" else "Set up local sync")
                    }
                }
                Text("Tap a timed line to seek. Untimed lines stay visible. Local timing is approximate.", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 16.dp))
            }
        }
    }
}

@UnstableApi
@Composable
private fun SettingsScreen(
    vm: PlayerViewModel,
    prefs: Preferences,
    modelReady: Boolean,
    modelImporting: Boolean,
    back: () -> Unit,
    importModel: () -> Unit,
) {
    var key by remember(prefs.acoustIdKey) { mutableStateOf(prefs.acoustIdKey) }
    var lyricsEndpoint by remember(prefs.lyricsEndpoint) { mutableStateOf(prefs.lyricsEndpoint) }
    var lyricsApiKey by remember(prefs.lyricsApiKey) { mutableStateOf(prefs.lyricsApiKey) }
    var confirmClear by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Header("Settings", back) }
        item {
            Column(Modifier.padding(24.dp)) {
                SectionLabel("LYRICS SEARCH")
                ToggleRow("Find lyrics online", "Searches when you open Lyrics agent. Sends the song title and artist to LRCLIB or your custom service. Saved lyrics are available offline.", prefs.lyricsOnline, vm::lyricsOnline)
                Text("LRCLIB works without an API key. To use your own service, enter an HTTPS API base URL that supports LRCLIB's search format.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(lyricsEndpoint, { lyricsEndpoint = it }, label = { Text("Custom lyrics API URL (optional)") },
                    placeholder = { Text("https://example.com/api") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (lyricsEndpoint.isNotBlank()) {
                    OutlinedTextField(lyricsApiKey, { lyricsApiKey = it }, label = { Text("Lyrics API key (optional)") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("The key is sent as a Bearer token to this service.", color = Muted, fontSize = 12.sp)
                }
                TextButton({ vm.lyricsService(lyricsEndpoint, if (lyricsEndpoint.isBlank()) "" else lyricsApiKey) }) { Text("Save lyrics service") }
                Spacer(Modifier.height(20.dp)); SectionLabel("LOCAL SYNC AND FALLBACK")
                Text("Offline transcription", fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                OfflineModelSetup(modelReady, modelImporting, importModel) {
                    vm.message.value = "Could not open a browser. Open the Dontree GitHub README to download ggml-tiny.bin."
                }
                ToggleRow("Thai pronunciation", "Rule-based phonetic spelling; approximate, not certified RTGS.", prefs.romanization, vm::romanization)
                ToggleRow("English meaning", "Adds English meaning during local fallback transcription. Takes more time.", prefs.translation, vm::translation)
                Spacer(Modifier.height(20.dp)); SectionLabel("OPTIONAL ONLINE METADATA")
                ToggleRow("Identify songs online", "Matches the recording before lyrics search when a key is saved, or when you tap Identify song. Sends a fingerprint and duration to AcoustID and requests cover art.", prefs.online, vm::online)
                if (prefs.online) {
                    OutlinedTextField(key, { key = it }, label = { Text("AcoustID application key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton({ vm.apiKey(key); vm.message.value = "Application key saved" }) { Text("Save key") }
                }
                Spacer(Modifier.height(20.dp)); SectionLabel("STORAGE")
                OutlinedButton({ confirmClear = true }) { Text("Clear saved lyrics") }
                Text("Clears generated and imported lyrics. Your music and model stay on this device.", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(32.dp)); HorizontalDivider(color = Panel); Spacer(Modifier.height(24.dp))
                Text("Dontree", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Cryptgreg Research · ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 13.sp)
                Text("MP3 & OGG · Android 10+", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Text("Powered by Android Media3, whisper.cpp and Chromaprint. See the repository's third-party notices and model licenses.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
                TextButton({ licenses = true }) { Text("Native library licenses") }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear all saved lyrics?") },
        text = { Text("Imported LRC files will need to be imported again. Generated lyrics can be recreated with your local model.") },
        confirmButton = { TextButton({ confirmClear = false; vm.clearLyrics() }) { Text("Clear lyrics") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } })
    if (licenses) {
        val notices = remember { listOf("whisper", "chromaprint", "kissfft").joinToString("\n\n") { name ->
            context.assets.open("licenses/$name.txt").bufferedReader().use { it.readText() }
        } }
        AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Native library licenses") },
            text = { Text(notices, fontSize = 11.sp, modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton({ licenses = false }) { Text("Close") } })
    }
}

@Composable
private fun OfflineModelSetup(ready: Boolean, importing: Boolean, importModel: () -> Unit, onBrowserUnavailable: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val importLabel = if (ready) "Replace model" else "Import model"
    Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("1. Download the model", fontWeight = FontWeight.SemiBold)
                Text("Open the download in your browser and save ggml-tiny.bin to Downloads. This model supports Thai.", color = Muted, fontSize = 14.sp)
                OutlinedButton(
                    onClick = {
                        try {
                            uriHandler.openUri(TinyModelUrl)
                        } catch (_: IllegalArgumentException) {
                            onBrowserUnavailable()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Download Tiny model (78 MB)") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("2. Import the file", fontWeight = FontWeight.SemiBold)
                Text("Return here, tap $importLabel, and choose the .bin file from Downloads. Files ending in .en.bin are English-only.", color = Muted, fontSize = 14.sp)
                Button(importModel, enabled = !importing, modifier = Modifier.fillMaxWidth()) { Text(importLabel) }
                if (importing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Importing and checking the model…", color = Muted, fontSize = 14.sp)
                } else if (ready) {
                    Text("Whisper model ready", color = Mint, fontWeight = FontWeight.SemiBold)
                    Text("You can delete the original file from Downloads to free space.", color = Muted, fontSize = 14.sp)
                } else {
                    Text("Wait for ‘Whisper model ready’ before continuing.", color = Muted, fontSize = 14.sp)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("3. Find lyrics, then sync", fontWeight = FontWeight.SemiBold)
                Text("Open Lyrics agent and tap Find lyrics online. Existing timestamps are used directly. The model helps sync untimed lyrics, or transcribes only when lyrics cannot be found.", color = Muted, fontSize = 14.sp)
                Text("Use local transcription fallback in the lyrics panel to work completely offline.", color = Muted, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, toggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 5.dp))
        }
        Switch(checked, toggle)
    }
}
@Composable private fun SectionLabel(text: String) { Text(text, color = Mint, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp)) }
@Composable private fun Header(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Action(Icons.AutoMirrored.Rounded.ArrowBack, "Back", back)
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
    }
}
@Composable private fun Action(icon: ImageVector, description: String, click: () -> Unit, enabled: Boolean = true, tint: Color = Color.White) {
    IconButton(click, enabled = enabled) { Icon(icon, description, tint = if (enabled) tint else Muted.copy(alpha = 0.35f)) }
}
@Composable private fun EmptyState(icon: ImageVector, title: String, text: String, action: String, click: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp).background(Panel, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(42.dp), tint = Purple) }
        Spacer(Modifier.height(24.dp)); Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp)); Text(text, color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp)); Button(click) { Text(action) }
    }
}
private fun time(ms: Long): String { val seconds = ms.coerceAtLeast(0) / 1000; return "%d:%02d".format(seconds / 60, seconds % 60) }
