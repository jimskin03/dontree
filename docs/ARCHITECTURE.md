# Dontree architecture sketch

## Goals
0. Supported audio formats: MP3 and OGG only (required), played natively by Media3.
1. Samsung Music-style local player (library, now-playing, queue, mini player, notification controls).
2. Auto-detect the song title from the audio, then fetch album cover art.
3. An insertable "lyrics agent" the user can switch on during playback. On demand it transcribes the song, shows lyrics synced to the beat, and shows English romanization.
4. Thai first; the language layer is pluggable for later languages.

## Layers

### 1. App / UI (Kotlin, Jetpack Compose, Material 3)
- Screens: Library, Now Playing, Queue, Lyrics overlay, Settings.
- Samsung-like design: big rounded cover art, colour pulled from the cover, bottom mini player, swipe-up Now Playing.
- Now Playing has an "Agent" button that slides in the lyrics panel.

### 2. Playback core (Media3 / ExoPlayer)
- **Supported audio formats: MP3 and OGG (required).** Media3 plays both natively, so no extra decoders are needed. No other formats are in scope for now; the library scan only indexes `.mp3` and `.ogg` files.
- `MediaSessionService` for background play, lock screen and notification controls.
- Exposes a tap on the decoded PCM stream (an `AudioProcessor`) so other modules can listen without a second decode.

### 3. Song identification
- Order of lookup: embedded tags (ID3/Vorbis) -> filename -> audio fingerprint.
- Fingerprint: Chromaprint (AcoustID) computed on-device from a ~15-20 s sample; AcoustID returns a MusicBrainz recording ID.
- Fallback: a pluggable recognition service (e.g. a commercial recognizer) behind one `SongIdentifier` interface.
- Result is cached in a local Room database keyed by file hash.

### 4. Cover art
- MusicBrainz release ID -> Cover Art Archive image; fallbacks: iTunes Search API, Deezer API, embedded art.
- Images cached on disk (Coil) and stored in Room.

### 5. Insertable agent ("Lyrics Agent")
A plug-in module behind a small interface so it can be inserted on demand and removed again:
```
interface PlayerAgent {
  suspend fun onAttach(session: PlaybackSession)
  fun onDetach()
}
```
When attached it runs this pipeline:
1. Lyrics lookup first: LRCLIB / other synced-lyrics sources by title+artist+duration (cheap, accurate).
2. If none found, transcribe:
   - Optional vocal separation (on-device Demucs-style model or server) to improve accuracy.
   - Speech-to-text with word timestamps: Whisper (on-device via whisper.cpp for short use, or a server endpoint for quality). Thai model/language hint set to `th`.
3. Alignment: word/line timestamps from step 1 (LRC) or 2 (Whisper) become a timeline.
4. Beat sync: on-device beat/onset tracking (e.g. librosa-style analysis ported, or a TFLite beat tracker) produces beat times and tempo. Line highlights snap to the nearest beat and the UI pulses on the beat.
5. Romanization (see below).
6. Result is cached per song so the agent only works once.

### 6. Language layer (Thai first)
```
interface LanguagePack {
  val code: String            // "th"
  fun segment(text): List<Word>      // Thai has no spaces between words
  fun romanize(word): String         // English-letter reading
}
```
- Thai pack: word segmentation (PyThaiNLP-style dictionary segmenter; on Android, a ported dictionary + maximal-matching or ICU `BreakIterator` for Thai), romanization via the Royal Thai General System of Transcription (RTGS), with an option for a tone-aware scheme later.
- Later packs (Japanese, Korean, Chinese...) implement the same interface; Whisper language code selects the transcriber.

### 7. Data and services
- Local: Room (songs, cover URLs, lyrics, beat maps, romanization), DataStore (settings).
- Optional backend (small service) for heavy jobs: vocal separation + Whisper large. The app works fully offline with smaller on-device models.
- Privacy: only a short audio fingerprint or a vocal clip leaves the phone, and only when the agent is switched on.

## Data flow
play song -> Media3 -> tags/fingerprint -> MusicBrainz ID -> cover art + metadata -> (user taps Agent) -> lyrics lookup or transcription -> beat map -> romanization -> synced lyrics view.

## Build order
1. Basic player UI + Media3 playback.
2. Tag reading, fingerprint ID, cover art.
3. Agent interface + lyrics lookup + synced display.
4. Whisper transcription fallback (Thai).
5. Beat sync and pulse animation.
6. Thai romanization.
7. Additional language packs.

## Open questions
- Quality bar for on-device vs server transcription of sung Thai.
- Licensing of lyrics sources and recognition APIs.
