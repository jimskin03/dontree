# Implementation status

The architecture sketch describes the target, not a claim that every component is finished. This first implementation uses native Android components and actual local inference; there are no placeholder lyrics or simulated model results.

Issue #4 changes the lyrics entry point to song matching and provider lookup. Existing synchronized lyrics bypass local inference. Plain lyrics are retained and optionally aligned with local recognition. Local transcription handles lookup misses or an explicit offline fallback request. The user can configure a compatible HTTPS lyrics service and Bearer API key. See [lyrics lookup](LYRICS.md) for setup and privacy details.

| Area | Implemented | Remaining / difference from sketch |
| --- | --- | --- |
| Library | MediaStore MP3/OGG-only scan, embedded metadata exposed by Android, filename fallback, album/artist groups, search and favorites | File/tag editing and playlists are outside the sketch |
| Playback | Media3 ExoPlayer + MediaSessionService, controls, audio focus, noisy-output handling, queue restoration | No swipe-up gesture; tap the mini player to expand |
| Recognition | Native Chromaprint, AcoustID application key, confidence threshold, SHA-256 cache, MusicBrainz recording ID returned by AcoustID | Explicit lookup instead of automatic network requests; no commercial fallback provider |
| Cover art | Android album artwork, Cover Art Archive from recognized release | iTunes/Deezer fallbacks and cover-derived palette remain unimplemented |
| Agent lifecycle | PlayerAgent attach/detach contract, cancellation, single inference worker | Runs in the activity ViewModel, not a persistent analysis worker; keep app open. Moving to the next track does not cancel an analysis already running; detach, a new run, import, or leaving the app does |
| Transcription | Pinned whisper.cpp 1.7.6, Thai, segment and experimental token timestamps, multilingual model validation. Temperature fallback is off and each pass is token-capped so music does not re-decode the same window; 15-second slices publish partial lyrics and a real percent | Native speech quality has not been evaluated on a physical Thai music corpus; token times are not reliable word segmentation |
| Translation | Second local Whisper speech-to-English pass, matched by overlapping segment timestamps | Dedicated non-LLM Thai text-to-English MT model remains unimplemented; a translation may span multiple lyric lines |
| Romanization | Pluggable LanguagePack interface; ICU Thai-Latin/Latin-ASCII transliteration | Strict RTGS and Thai word segmentation remain unimplemented |
| Beat synchronization | Measured energy onsets, bounded 90 ms line-start snapping, beat pulse and active-line tracking | Not a production music beat tracker; token-level karaoke highlighting remains unimplemented |
| Audio input | Bounded 30-second MediaExtractor/MediaCodec decode chunks, 16 kHz mono conversion | A separate analysis decode replaces the proposed shared playback PCM tap; analysis currently requires source sample rate >=16 kHz |
| Cache | Room lyrics keyed by song SHA-256, model SHA-256, language pipeline version and translation setting | Imported LRC uses file revision (media ID, size, modified time); changing the model invalidates generated-cache lookup |
| Formats | Native MP3 and OGG playback only | OGG is a container: actual codec playback depends on Android/Media3 support |

## Verification

- Core JUnit tests exercise extension filtering, metadata fallback, LRC offsets/duplicate timestamps, seeking/gaps, bounded beat snapping, and silence/click-track beat detection.
- Local Android Kotlin/Compose source compilation was checked directly against SDK 35 and Android dependencies. This checks source types but does not replace Gradle/KSP/resource packaging.
- Native CMake builds passed for ARM64 and x86_64, including the dynamically linked Chromaprint library. ARM64 ELF load segments have 16 KB alignment.
- The full Gradle build and ADB cannot connect to local services in the restricted development environment. GitHub Actions successfully ran `:core:test :app:lintDebug :app:assembleDebug` for commit `6db3967`. Follow-up commits add emulator smoke tests; consult the latest run for their results.
- Android instrumentation checks app launch and MediaSession MP3 playback, MP3/OGG decoding using synthetic tone fixtures, native Chromaprint loading, and Room/lyrics round trips. CI runs these on an API 35 x86_64 emulator.
- Physical-device playback, notification behavior, import validation, inference speed/quality, and offline packet inspection remain device acceptance tests.

## Device acceptance checklist

1. Fresh install: deny permission, reopen, grant permission, rescan; verify only MP3/OGG tracks appear.
2. Play both formats, seek, shuffle/repeat, edit queue, rotate, background, lock/unlock and remove headphones. Confirm notification and Bluetooth controls.
3. Reopen after process death: queue restored and paused. Deleted/unavailable files should show a recoverable playback error.
4. Import multilingual tiny/base; reject a non-model and an English-only model without losing a valid installed model.
5. In airplane mode, generate Thai lyrics and English meaning; cancel during processing; switch tracks; retry. Check the app does not crash or show the previous track's result.
6. Import LRC including Thai, multiple timestamps, offsets and duplicate bilingual lines. Seek forward/backward and verify the active line.
7. Explicitly enable metadata lookup with a valid application key; handle offline, no match and HTTP errors; confirm the audio file itself is never uploaded.

## Native dependencies

CMake downloads checksum-pinned upstream release archives. For an offline native build, place the exact archives in `.deps/whisper.tar.gz` and `.deps/chromaprint.tar.gz`; their hashes are still checked. No source archive or model is committed to Git.
