# Dontree

An Android music player with a Samsung Music-inspired dark interface, song matching, online lyrics lookup and local lyric timing support.

This repository now contains an initial Android implementation. The original [architecture sketch](docs/ARCHITECTURE.md) remains the target; [implementation status](docs/IMPLEMENTATION.md) records the differences and unfinished work explicitly.

## Find lyrics

1. Play a song, open the full player and tap **Lyrics agent → Find lyrics online**. This enables online lyrics lookup for future visits to the panel.
2. Dontree uses saved song matches or the track's title and artist. For audio recognition, enable **Settings → Identify songs online** and save your AcoustID application key.
3. Synced lyrics play immediately. Untimed lyrics remain readable, and an installed local model can add approximate timing. Local transcription is used when no matching lyrics are found, or through **Use local transcription fallback**.

LRCLIB is the default lyrics service and works without a key. You can configure an HTTPS lyrics service and its API key in Settings. See [lyrics lookup and custom services](docs/LYRICS.md).

## Local sync and offline fallback

1. On your phone, download the **multilingual Tiny model** (`ggml-tiny.bin`, 78 MB). [Download from Whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin?download=true)
2. In Dontree, open **Settings → Offline transcription → Import model**. Select the file from **Downloads** and wait for **Whisper model ready**.
3. Open **Lyrics agent**. Use **Find lyrics online** to fetch lyrics first, **Sync with local model** for untimed lyrics, or **Use local transcription fallback** to transcribe offline. Keep Dontree open while the model works.

Once the model is imported, you can transcribe songs offline. For a larger model or help finding the downloaded file, see the [full setup guide](docs/OFFLINE_TRANSCRIPTION.md).

## Included

- MP3/OGG library scanning, search, album/artist browsing and favorites.
- Media3 playback, a mini player, full player, seek, shuffle/repeat, editable queue, background playback, notification/lock-screen controls and audio-focus/headphone handling.
- Room library/lyrics/metadata storage and DataStore preferences/queue restoration. Restoring a queue does not automatically start playback.
- Import timed LRC lyrics, tap a line to seek and follow the active lyric during playback.
- Lyrics lookup that prefers existing synchronized lyrics, preserves untimed lyrics and uses local recognition for timing support or fallback transcription.
- A cancellable local Whisper Thai pipeline, optional English meaning during fallback transcription, phonetic romanization and energy-onset beat pulses.
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
4. For lyrics, follow [Find lyrics](#find-lyrics) above. For offline fallback, see [local model setup](docs/OFFLINE_TRANSCRIPTION.md).
5. To identify a song online, enable online metadata, save an [AcoustID application key](https://acoustid.org/new-application), then tap **Identify song**. A confident match is displayed without rewriting your original file tags.

## Privacy and quality

Online lyrics lookup sends track title and artist to LRCLIB or your configured lyrics service. It is enabled through **Find lyrics online** or Settings. Optional AcoustID recognition sends a fingerprint and duration before lookup. Cover retrieval contacts Cover Art Archive and its image hosts. Audio stays on the phone, and local timing and fallback inference run on the device. Cached lyrics and models use private app storage with Android backup disabled.

Whisper can hallucinate or mishear sung Thai. Timing, English meaning and pronunciation are approximations. This version uses Whisper speech translation rather than the planned dedicated text-translation model, and ICU Thai transliteration rather than verified RTGS. Do not interpret generated lyrics as an authoritative lyric source. See [implementation status and testing](docs/IMPLEMENTATION.md) and [third-party notices](docs/THIRD_PARTY.md).
