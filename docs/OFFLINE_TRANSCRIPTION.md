# Set up offline transcription in Dontree

Download and import a Whisper model once to generate Thai lyrics on your phone. After the import, you can transcribe songs without an internet connection.

## 1. Download a model

Choose one file from the official whisper.cpp model repository. Start with **Tiny**. Choose **Base** if Tiny frequently misses words and you have more space and time for processing.

| Model | Download size | File |
| --- | ---: | --- |
| Tiny (recommended) | 78 MB | [Download `ggml-tiny.bin`](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin?download=true) |
| Base | 148 MB | [Download `ggml-base.bin`](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin?download=true) |

On your phone, the download normally goes to **Downloads**. You can also download the file on a computer and copy it to your phone's **Download** folder over USB. Wait for the download or copy to finish before importing it.

Use the exact `.bin` file from the table. Files ending in `.en.bin` support English only and cannot transcribe Thai.

## 2. Import the file in Dontree

1. From **My music**, open **Settings** and find **Offline transcription**. You can also tap **Set up offline transcription** in the lyrics agent.
2. Tap **Import model**. If you already have a model, tap **Replace model**.
3. In the Android file picker, open **Downloads** and select `ggml-tiny.bin` or `ggml-base.bin`.
4. Wait for **Whisper model ready**. Dontree makes its own copy, so you can delete the original from Downloads once the model is ready.

## 3. Transcribe a song

1. Play an MP3 or OGG file and tap the mini player to open the full player.
2. Tap **Lyrics agent**, then **Attach lyrics agent**. If you already imported timed lyrics for this song, tap **Run agent**.
3. Keep Dontree open while it works. Processing can take several minutes, and the agent supports tracks up to 20 minutes. Tap **Detach agent** to cancel.

Generated words and timing can be imperfect, especially when vocals are hard to hear over the music. You can also use **Import a timed LRC file** if you have one.

## If you get stuck

| What you see | What to do |
| --- | --- |
| The file is missing from the picker | Check your browser's download list. Once the download completes, open the file picker menu and select **Downloads** or browse to the folder where you saved it. |
| Dontree says the model is too small, unreadable, or unsupported | Download the complete multilingual `.bin` file from the table and select that file directly. |
| You run out of storage during import | Free enough space for a second copy of the model. Once **Whisper model ready** appears, you can delete the downloaded copy. |
| Transcription takes a long time | Use Tiny and turn off **English meaning** in Settings if you only need Thai lyrics. English meaning adds a second processing pass. |
