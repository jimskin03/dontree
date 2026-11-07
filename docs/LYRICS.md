# Find and sync lyrics

## Find existing lyrics first

1. Play an MP3 or OGG file, open the full player and tap **Lyrics agent**.
2. Tap **Find lyrics online**. This enables lyrics lookup, which sends the track title and artist to your lyrics service when you open the panel for an uncached song.
3. Read the lyrics as soon as they arrive. Existing timestamps work immediately. Untimed lines remain readable while an installed local model helps match them to the song.

LRCLIB is the default service and needs no API key. Dontree matches title, artist and recording duration before accepting a result. A different recording, cover or live version may have different timing. Use **Find lyrics again** to repeat a lookup or **Replace with LRC** to import your own timed lyrics.

If the title or artist is missing, enable **Settings → Identify songs online**, enter your AcoustID application key and save it. Dontree then fingerprints the recording before searching for lyrics. If recognition is unavailable but the file has title and artist tags, it uses those tags.

## Local timing and fallback

An installed model helps sync lyrics that arrive without timestamps. The fetched words remain the source text. Unmatched lines stay visible without a seek action. Tap **Sync with local model** to retry timing later. Local timing is approximate.

If the service finds no matching lyrics, Dontree uses local transcription when a model is installed. Connection and authentication errors are shown so you can retry. **Use local transcription fallback** also lets you transcribe manually without a network connection. Sung vocals can be difficult for the model to recognize. [Set up a local model](OFFLINE_TRANSCRIPTION.md).

Synced lyrics work without a local model. Plain lyrics also remain readable without one. English meaning currently applies to local fallback transcription.

## Your own lyrics service

In **Settings → Lyrics search**, enter a **Custom lyrics API URL** and, if required, its **Lyrics API key**. Tap **Save lyrics service**. Leave the URL empty to use LRCLIB again.

The custom service must implement LRCLIB's v1 search response. An example base URL is `https://lyrics.example.com/api`. Dontree calls `GET /api/search?track_name=...&artist_name=...` and sends a configured key as `Authorization: Bearer <key>` to that service. Requests use HTTPS and do not follow redirects. Provider-specific keys require a compatible service or adapter.

The response is a JSON array of records with these fields.

| Field | Value |
| --- | --- |
| `trackName` | Matched track title |
| `artistName` | Matched artist name |
| `duration` | Recording duration in seconds |
| `syncedLyrics` | LRC text, or `null` |
| `plainLyrics` | Plain lyric text, or `null` |
| `instrumental` | `true` for recordings without lyrics |

See the [official LRCLIB API documentation](https://lrclib.net/docs) for the response format. The built-in LRCLIB client uses anonymous access. Custom keys are stored in private app preferences and sent only to the configured service.

## Offline access

Found lyrics are cached for the audio file revision and can be reopened offline. Imported LRC files have priority when reopening a track. **Settings → Clear saved lyrics** removes imported, found and generated lyrics. To stop future web lookups, disable **Find lyrics online** in Settings.
