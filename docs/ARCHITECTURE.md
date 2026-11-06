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
**Local-only rule: the agent uses no LLM and makes no cloud calls.** Everything it does runs on the phone, and no audio or text leaves the device.

A plug-in module behind a small interface so it can be inserted on demand and removed again:
```
interface PlayerAgent {
  suspend fun onAttach(session: PlaybackSession)
  fun onDetach()
}
```
When attached it runs this pipeline, all on-device:
1. Transcription: Whisper running locally (whisper.cpp or an equivalent on-device runtime) with the language set to Thai (`th`). It produces text with word-level timestamps. Model size is chosen to fit the phone (e.g. small/base, quantized).
2. Translation: a local Thai-to-English translation model (small offline neural MT model, e.g. an OPUS-MT or NLLB-style model converted for on-device use). It is a dedicated translation model, not an LLM.
3. Romanization: rule-based Royal Thai General System of Transcription (RTGS) applied to the segmented Thai words. No model needed.
4. Beat tracking: on-device signal processing only (onset detection and tempo/beat tracking on the audio, no neural model required). It produces beat times and tempo.
5. Sync: the word timestamps from Whisper are snapped to the nearest beat, and the UI pulses on the beat. Lyrics lines show Thai, romanization and English translation together.
6. Results are cached per song in the local database so the agent only works once per track.

### 7. Data and services
- Local: Room (songs, cover URLs, lyrics, beat maps, romanization), DataStore (settings).
- Privacy: the agent is fully local. Nothing it processes (audio, transcripts, translations) is sent anywhere.

## Data flow
play song -> Media3 -> tags/fingerprint -> MusicBrainz ID -> cover art + metadata -> (user taps Agent) -> local Whisper transcription -> local Thai-to-English translation + RTGS romanization -> on-device beat map -> synced lyrics view.

## Build order
1. Basic player UI + Media3 playback.
2. Tag reading, fingerprint ID, cover art.
3. Agent interface + synced display.
4. Local Whisper transcription (Thai) + local translation model.
5. Beat sync and pulse animation.
6. Thai romanization.
7. Additional language packs.

## Open questions
- Quality of on-device Whisper on sung Thai (model size vs speed and memory).
- Note: song identification and cover art (sections 3 and 4) still look up online services (AcoustID/MusicBrainz, Cover Art Archive). The local-only rule covers the agent. If the whole app must work offline, those need a local alternative.
- Licensing of lyrics sources and recognition APIs.
