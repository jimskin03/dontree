# Dontree

An Android music player with a Samsung Music-inspired dark interface and an attachable, on-device Thai lyrics agent.

This repository now contains an initial Android implementation. The original [architecture sketch](docs/ARCHITECTURE.md) remains the target; [implementation status](docs/IMPLEMENTATION.md) records the differences and unfinished work explicitly.

## Included

- MP3/OGG library scanning, search, album/artist browsing and favorites.
- Media3 playback, a mini player, full player, seek, shuffle/repeat, editable queue, background playback, notification/lock-screen controls and audio-focus/headphone handling.
- Room library/lyrics/metadata storage and DataStore preferences/queue restoration. Restoring a queue does not automatically start playback.
- Import timed LRC lyrics, tap a line to seek and follow the active lyric during playback.
- A cancellable local Whisper Thai transcription pipeline, optional local English speech translation, phonetic romanization, energy-onset beat pulses and bounded timestamp snapping.
- SHA-256 song/model cache keys for generated lyrics. A user-imported multilingual GGML model is required; model weights are not bundled or automatically downloaded.
- Native Chromaprint fingerprints with explicit, opt-in AcoustID recognition and Cover Art Archive lookup. Local tags and filenames work without a connection.

## Build

Requires JDK 17 (or 21), Android SDK 35, NDK `28.2.13676358`, CMake `3.22.1`, and an initial internet connection for Gradle dependencies and the checksum-pinned native source archives.

Open the root directory in Android Studio, let it install the requested SDK tools, then run `app`. Or set `ANDROID_HOME` and run:

```sh
./gradlew :core:test :app:lintDebug :app:assembleDebug
```

On Windows, use `gradlew.bat`. APK output: `app/build/outputs/apk/debug/app-debug.apk`. Android 10+; ARM64 phones and x86_64 emulators. CI runs these checks and uploads the debug APK; it does not bundle a model or publish a release.

## First use

1. Place MP3/OGG files in the phone's Music folder and allow music access. Tap Rescan if needed.
2. Tap a track. Playback continues when the screen closes. Queue and Settings are available from the player/library.
3. For existing timed lyrics, choose **Import LRC** while the intended song is selected.
4. For generated lyrics, obtain a **multilingual** whisper.cpp GGML tiny/base `.bin` model from the [upstream model collection](https://huggingface.co/ggerganov/whisper.cpp). Do not use `.en` models. Import it in Settings; Dontree checks the format and loads it before replacing the previous model.
5. Open **Lyrics agent → Attach lyrics agent**. Keep Dontree open during analysis; it may take several minutes and is limited to tracks up to 20 minutes. **Detach agent** cancels inference. Playback is independent of this work.
6. Optional: enable online metadata, save an [AcoustID application key](https://acoustid.org/new-application), then tap **Identify song**. A confident match is displayed without rewriting your original file tags.

## Privacy and quality

The lyrics agent has no networking implementation, microphone access, remote model calls or analytics. It reads the selected local file. Transcripts, imported lyrics and models are stored in private app storage; Android backup is disabled. No audio is uploaded. The separate metadata feature sends fingerprints and track duration to AcoustID only when enabled and explicitly invoked; cover retrieval contacts Cover Art Archive and its image hosts.

Whisper can hallucinate or mishear sung Thai. Timing, English meaning and pronunciation are approximations. This version uses Whisper speech translation rather than the planned dedicated text-translation model, and ICU Thai transliteration rather than verified RTGS. Do not interpret generated lyrics as an authoritative lyric source. See [implementation status and testing](docs/IMPLEMENTATION.md) and [third-party notices](docs/THIRD_PARTY.md).
